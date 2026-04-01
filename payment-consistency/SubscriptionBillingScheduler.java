package example.billing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 정기결제 스케줄러 — 보상 트랜잭션 (Compensating Transaction)
 *
 * 매일 새벽 3시에 만료 예정 구독을 자동 결제한다.
 * 결제 API는 DB 트랜잭션 밖이므로, 결제 성공 후 DB 실패 시
 * 자동 환불로 보상한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionBillingScheduler {

    // ... (의존성 주입 생략)

    @Scheduled(cron = "0 0 3 * * *")
    public void processDueSubscriptions() {
        // 만료 예정 구독 목록 조회 후 각각 처리
        // for (Subscription sub : dueSubscriptions) { processBilling(sub); }
    }

    private void processBilling(Subscription subscription) {
        String paymentKey = null;

        try {
            // 1. 토스 결제 API 호출 — 이 시점에 고객 카드에서 돈이 빠져나감
            TossPaymentResponseDTO tossResp = tossPaymentsClient
                    .payWithBillingKey(subscription.getBillingKey(), paymentRequest)
                    .block();
            paymentKey = tossResp.getPaymentKey();

            // 2. DB에 구독 갱신 + 결제 이력 저장
            subscription.renew();
            subscriptionRepository.save(subscription);
            paymentRepository.save(Payment.success(paymentKey, subscription.getAmount()));

        } catch (Exception dbError) {
            // 3. DB 저장 실패 시 자동 환불 처리
            log.error("[Scheduler] DB save failed after payment success! Initiating auto-refund. subId={}, paymentKey={}",
                    subscription.getId(), paymentKey, dbError);

            try {
                PaymentCancelRequestDTO cancelRequest = PaymentCancelRequestDTO.builder()
                        .cancelReason("구독 갱신 DB 저장 실패로 인한 자동 환불")
                        .build();

                paymentService.cancelPayment(paymentKey, cancelRequest);

                log.info("[Scheduler] Auto-refund successful for paymentKey={}", paymentKey);

            } catch (Exception refundError) {
                // 4. 환불도 실패 — CRITICAL, 수동 처리 필요
                log.error("[Scheduler] CRITICAL: Auto-refund failed! paymentKey={}, Manual intervention required!",
                        paymentKey, refundError);
            }

            throw dbError;
        }
    }
}
