package example.billing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 결제 취소 서비스 — Append-Only 원장 + 멱등성
 *
 * 결제 기록은 절대 UPDATE/DELETE하지 않는다.
 * 취소도 원거래를 참조하는 새로운 행으로 기록한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl {

    // ... (의존성 주입 생략)

    @Transactional
    public PaymentResponseDTO cancelPayment(String paymentKey, PaymentCancelRequestDTO request) {
        log.info("결제 취소 요청: paymentKey={}, reason={}", paymentKey, request.getCancelReason());

        // 1. Payment 원장에서 원본 결제 조회 (취소거래 제외)
        Payment originalPayment = paymentRepository.findByPaymentKeyAndOriginalPaymentIdIsNull(paymentKey)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentKey));

        if (originalPayment.isCancelTransaction()) {
            throw new IllegalArgumentException("취소 거래는 취소할 수 없습니다: " + paymentKey);
        }

        // 2. 이미 취소된 결제인지 확인 (멱등성)
        if (paymentRepository.existsByOriginalPaymentId(originalPayment.getId())) {
            log.warn("이미 취소된 결제입니다: paymentKey={}", paymentKey);
            List<Payment> cancelPayments = paymentRepository.findByOriginalPaymentId(originalPayment.getId());
            return PaymentResponseDTO.from(cancelPayments.get(0));
        }

        // 3. 토스 API 결제 취소 호출
        String cancelRequestId = UUID.randomUUID().toString();
        TossCancelRequestDTO tossCancelReq = TossCancelRequestDTO.builder()
                .cancelReason(request.getCancelReason())
                .cancelAmount(request.getCancelAmount())
                .cancelRequestId(cancelRequestId)
                .build();

        try {
            TossPaymentResponseDTO tossResp = tossPaymentsClient
                    .cancelPayment(paymentKey, tossCancelReq)
                    .block();

            // 4. 새로운 취소 거래 row 생성 (append-only)
            BigDecimal cancelAmount = request.getCancelAmount() != null
                    ? BigDecimal.valueOf(request.getCancelAmount()).negate()
                    : originalPayment.getAmount().negate();

            Payment cancelPayment = Payment.builder()
                    .subscriptionId(originalPayment.getSubscriptionId())
                    .originalPaymentId(originalPayment.getId())  // 원거래 참조
                    .paymentKey(tossResp.getPaymentKey())
                    .orderId(originalPayment.getOrderId())
                    .amount(cancelAmount)                        // 음수 금액
                    .status(Payment.PaymentStatus.CANCELED)
                    .cancelReason(request.getCancelReason())
                    .cancelRequestId(cancelRequestId)            // 멱등성 키
                    .build();

            Payment savedCancelPayment = paymentRepository.save(cancelPayment);

            log.info("취소 거래 원장 기록 완료: originalPaymentId={}, cancelPaymentId={}",
                    originalPayment.getId(), savedCancelPayment.getId());

            // 5. 환불 영수증 — 실패해도 취소 기록은 유지
            try {
                invoiceEmailService.sendRefund(savedCancelPayment, subscription);
            } catch (Exception mailErr) {
                log.error("환불 영수증 전송 실패: cancelPaymentId={}", savedCancelPayment.getId(), mailErr);
            }

            return PaymentResponseDTO.from(savedCancelPayment);

        } catch (Exception e) {
            log.error("결제 취소 실패: paymentKey={}", paymentKey, e);
            throw new RuntimeException("결제 취소 실패: " + e.getMessage(), e);
        }
    }
}
