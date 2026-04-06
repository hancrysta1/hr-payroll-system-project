# 토스페이먼츠 결제↔DB 불일치율 30%→0% — 보상 트랜잭션으로 자동 복구

토스페이먼츠 빌링키 기반 구독 결제 시스템의 구조적 취약점을 분석하고, 보상 트랜잭션 + 거래 원장 + 에러 분기 재시도 체계를 구축한 프로젝트다.

- 사용 기술. Java(JDK-21), Spring Boot, JPA, WebClient, TossPayments API, Spring AOP, K6, JUnit 5 + Mockito
- 기여도. 100%

---

## 문제

### 결제 성공 후 DB 저장 실패 시 고객 피해

기존 코드는 `@Transactional` 안에서 토스 결제 API(외부, 롤백 불가)를 호출한 뒤 DB 저장을 수행하는 구조였다.

```java
@Transactional
public SubscriptionResponseDTO create(SubscriptionRequestDTO request) {
    // [트랜잭션 밖] 1단계: 토스 결제 API 호출
    payResp = tossPaymentsClient.payWithBillingKey(...).block();
    // → 100% 성공 가정 (외부 API, 롤백 불가)

    // [트랜잭션 안] 2단계: DB 저장
    Subscription sub = Subscription.builder()...build();
    subscriptionRepository.save(sub);
    return SubscriptionResponseDTO.from(sub);
}
```

결제 완료 후 DB 저장이 실패하면 다음과 같은 상태가 발생한다.

```
토스 결제: 출금됨 (카드에서 돈 빠져나감)
Subscription: 트랜잭션 롤백으로 삭제됨
→ 고객: 돈은 나갔는데 구독은 활성화 안 됨
→ 환불 방법 없음, 결제 이력도 없음
```

이전 MSA 구조의 금액 서비스 개발 경험에서 데이터 정합성 문제로 인한 금액 누락이 심각한 문제임을 확인했으므로, 예측 가능한 장애에 대비할 필요가 있다고 판단했다.

### 거래 이력 부재

기존 `Subscription` 테이블은 `lastPaymentKey`만 저장하여 매 결제 시 마지막 결제 정보만 덮어씌운다.

```
2025-10 → paymentKey_001 (10,000원)
2025-11 → paymentKey_002 (10,000원) ← 덮어씌워짐
2025-12 → paymentKey_003 (10,000원) ← 덮어씌워짐
```

- 구독 결제 히스토리 확인 불가
- 지난 결제 환불 불가능
- 회계 감사 대응 불가

### 결제 실패 시 에러 분기 부재

토스 API는 에러 코드를 반환하는데, 기존 코드는 모든 실패를 동일하게 처리했다.

- 500번대 에러 (일시적 장애). 재시도하면 성공할 수 있음
- 400/403 에러 (카드 한도 초과 등). 고객 조치 필요, 재시도 무의미

에러 분기 없이 모든 실패를 재시도하면 400/403 에러에서 매일 무의미한 재시도가 반복된다.

---

## 분석

### Chaos Engineering 도입 배경

보상 트랜잭션, 에러 분기 로직이 실제 장애 상황에서 정상 동작하는지 검증할 방법이 필요했다. 실제 토스 API로는 다양한 에러 케이스를 재현할 수 없으므로, 의도적으로 장애를 주입해서 시스템이 제대로 복구하는지 검증하는 Chaos Engineering을 도입했다.

성능 목표. 30% 확률로 DB 저장 실패가 일어나는 환경에서 100% 데이터 정합성 유지.

### 시뮬레이션 전략

Spring Profile + AOP로 환경을 분리하여 운영 코드 수정 없이 장애를 주입했다.

```java
@Aspect
@Profile("chaos")
public class ChaosDbFailureAspect {
    @Around("execution(* ...Repository.save(..))")
    public Object injectFailure(...) {
        if (Math.random() < failureRate) throw new DataAccessException();
        return joinPoint.proceed();
    }
}
```

Mock 서버를 구성하여 토스 API는 100% 동작을 가정했다.

```java
// 실제 운영 코드
@Component
@Profile("!chaos")
public class TossPaymentsClient {
    public Mono<TossPaymentResponseDTO> payWithBillingKey(...) {
        return webClient.post()...;  // 실제 토스 API 호출
    }
}

// Chaos 테스트용 Mock
@Component
@Profile("chaos")
public class TossPaymentsClient {
    public Mono<TossPaymentResponseDTO> payWithBillingKey(...) {
        TossPaymentResponseDTO response = new TossPaymentResponseDTO();
        response.setPaymentKey("mock_payment_" + UUID.randomUUID());
        response.setStatus("DONE");
        return Mono.just(response);  // 즉시 성공 반환
    }
}
```

before/after 프로필로 환불 로직을 on/off 제어했다.

```yaml
# application-chaos-before.yml (환불 비활성화)
chaos:
  database:
    failure-rate: 0.3
  auto-refund:
    enabled: false

# application-chaos-after.yml (환불 활성화)
chaos:
  database:
    failure-rate: 0.3
  auto-refund:
    enabled: true
```

프로젝트 구조는 다음과 같다.

```
기존 운영 코드 (@Profile("!chaos"))
├── service/SubscriptionServiceImpl.java
└── client/TossPaymentsClient.java

Chaos 전용 코드 (@Profile("chaos"))
├── chaos/
│   ├── TossPaymentsClient.java       ← Mock 서버
│   ├── ChaosDbFailureAspect.java     ← 장애 주입 AOP
│   └── ChaosSubscriptionService.java ← 자동 환불 포함
└── dto/toss/
    └── TossCancelRequestDTO.java
```

### 단위 테스트 도구 선정

처음에는 실제 토스 API를 호출해서 테스트하려 했으나 문제가 있었다.

- 실제 결제가 발생해서 비용 청구
- 다양한 에러코드 케이스를 의도적으로 발생시킬 방법 없음
- 카드 한도 초과 같은 상황 재현 불가
- 토스 서버 상태에 따라 테스트 결과가 달라짐

이전에 Mock 서버 + 실제 DB 연결로 카오스 통합 테스트를 진행했으나, 환경 영향이 크고 실행 속도가 느려 실패 시 원인 파악이 어려웠다(플래키 테스트). 로직 단위에서 동작을 정확히 검증하는 것이 목적이므로, 외부 의존성을 끊고 빠르게 검증할 수 있는 JUnit 5 + Mockito 단위 테스트를 선택했다.

---

## 해결

### 해결 1. Payment 거래 원장 설계

목표 구조는 다음과 같다.

```
BillingKey (1) ─── (N) Subscription (1) ─── (N) Payment
                                                  ↑
                                              매월 결제 이력
```

한 사용자가 여러 구독을 할 수 있고, 하나의 구독마다 여러 결제 이력을 저장한다.

Payment 테이블 스키마.

```sql
payments (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    subscription_id BIGINT NOT NULL,              -- FK
    payment_key     VARCHAR(100) NOT NULL UNIQUE,  -- 토스 발급 고유키
    order_id        VARCHAR(100) NOT NULL,
    amount          DECIMAL(12,2) NOT NULL,
    status          VARCHAR(20) NOT NULL,          -- DONE, CANCELED, FAILED
    payment_method  VARCHAR(20),                   -- 빌링
    customer_key    VARCHAR(100),
    requested_at    DATETIME,
    approved_at     DATETIME,
    canceled_at     DATETIME,
    cancel_reason   VARCHAR(200),
    cancel_request_id VARCHAR(100),                -- 멱등성 보장
    failure_code    VARCHAR(100),                  -- 에러 코드 (400/403 시)
    failure_message VARCHAR(500),                  -- 에러 메시지
    created_at      DATETIME,
    updated_at      DATETIME
);
```

Payment 엔티티.

```java
@Entity
@Table(name = "payments", indexes = {
    @Index(name = "idx_payment_key", columnList = "paymentKey"),
    @Index(name = "idx_subscription_id", columnList = "subscriptionId")
})
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long subscriptionId;

    @Column(nullable = false, unique = true)
    private String paymentKey;

    private String orderId;
    private String orderName;
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;  // DONE, CANCELED, FAILED

    private String paymentMethod;
    private String customerKey;
    private LocalDateTime requestedAt;
    private LocalDateTime approvedAt;
    private LocalDateTime canceledAt;
    private String cancelReason;
    private String cancelRequestId;
    private String failureCode;
    private String failureMessage;

    // 취소 처리
    public void cancel(String cancelReason, String cancelRequestId) {
        this.status = PaymentStatus.CANCELED;
        this.cancelReason = cancelReason;
        this.cancelRequestId = cancelRequestId;
        this.canceledAt = LocalDateTime.now();
    }
}
```

설계 원칙.

| 원칙 | 구현 | 이유 |
| --- | --- | --- |
| 고유성 | `paymentKey` UNIQUE 제약 | 토스가 발급하는 거래 고유키 기준 관리, 중복 저장 방지 |
| 멱등성 | `cancelRequestId` 기록 | `UUID.randomUUID()`로 생성, 중복 취소 방지 |
| 추적성 | `cancelReason`, `canceledAt`, `failureCode`, `failureMessage` 기록 | 실패/취소 원인과 시점 감사 추적 |
| 무결성 | `status` = DONE/CANCELED/FAILED | 결제 상태 명확, 금액 추적 가능 |

### 해결 2. 구독 생성 시 Payment 원장 기록

```java
@Service
public class SubscriptionServiceImpl implements SubscriptionService {

    @Override
    @Transactional
    public SubscriptionResponseDTO create(SubscriptionRequestDTO request) {
        // 1. 빌링키 조회
        BillingKey billingKey = billingKeyService.getBillingKey(...);

        // 2. 토스 결제 API 호출
        TossPaymentResponseDTO payResp = tossPaymentsClient
            .payWithBillingKey(billingKey.getBillingKey(), tossBillingReq)
            .block();

        // 3. Subscription 저장
        Subscription savedSub = subscriptionRepository.save(sub);

        // 4. Payment 원장에 기록 (신규 추가)
        if (payResp != null) {
            Payment payment = Payment.builder()
                .subscriptionId(savedSub.getId())
                .paymentKey(payResp.getPaymentKey())
                .orderId(payResp.getOrderId())
                .amount(request.getAmount())
                .status(Payment.PaymentStatus.DONE)
                .customerKey(request.getCustomerKey())
                .requestedAt(LocalDateTime.parse(payResp.getRequestedAt()))
                .approvedAt(LocalDateTime.parse(payResp.getApprovedAt()))
                .build();
            paymentRepository.save(payment);
        }

        return SubscriptionResponseDTO.from(savedSub);
    }
}
```

### 해결 3. 결제 취소(환불) 기능 구현

토스 클라이언트 취소 메서드.

```java
public Mono<TossPaymentResponseDTO> cancelPayment(
    String paymentKey,
    TossCancelRequestDTO request) {
    return webClient.post()
        .uri(baseUrl + "/v1/payments/" + paymentKey + "/cancel")
        .header(HttpHeaders.AUTHORIZATION, "Basic " + encodeSecretKey())
        .bodyValue(request)
        .retrieve()
        .bodyToMono(TossPaymentResponseDTO.class);
}
```

취소 요청 DTO.

```java
@Data
@Builder
public class TossCancelRequestDTO {
    private String cancelReason;     // 필수
    private Long cancelAmount;       // 선택 (없으면 전액)
    private String cancelRequestId;  // 멱등성 보장
}
```

PaymentService 취소 로직.

```java
@Override
@Transactional
public PaymentResponseDTO cancelPayment(String paymentKey, PaymentCancelRequestDTO request) {
    // 1. Payment 조회
    Payment payment = paymentRepository.findByPaymentKey(paymentKey)
        .orElseThrow(() -> new IllegalArgumentException("Payment not found"));

    // 이미 취소된 경우 중복 방지
    if (payment.getStatus() == Payment.PaymentStatus.CANCELED) {
        return PaymentResponseDTO.from(payment);
    }

    // 2. 토스 API 결제 취소
    String cancelRequestId = UUID.randomUUID().toString();
    TossCancelRequestDTO tossCancelReq = TossCancelRequestDTO.builder()
        .cancelReason(request.getCancelReason())
        .cancelAmount(request.getCancelAmount())
        .cancelRequestId(cancelRequestId)
        .build();

    tossPaymentsClient.cancelPayment(paymentKey, tossCancelReq).block();

    // 3. Payment 원장에 취소 기록
    payment.cancel(request.getCancelReason(), cancelRequestId);
    paymentRepository.save(payment);

    return PaymentResponseDTO.from(payment);
}
```

API 엔드포인트.

```java
@RestController
@RequestMapping("/api/billing/payments")
public class PaymentController {

    @PostMapping("/{paymentKey}/cancel")
    public ResponseEntity<PaymentResponseDTO> cancelPayment(
        @PathVariable String paymentKey,
        @RequestBody PaymentCancelRequestDTO request);

    @GetMapping("/{paymentKey}")
    public ResponseEntity<PaymentResponseDTO> getPayment(
        @PathVariable String paymentKey);

    @GetMapping("/subscription/{subscriptionId}")
    public ResponseEntity<List<PaymentResponseDTO>> getPaymentsBySubscription(
        @PathVariable Long subscriptionId);
}
```

### 해결 4. DB 저장 실패 시 자동 환불 (보상 트랜잭션)

```java
try {
    // 토스 결제 + DB 저장 (Subscription + Payment)
    // ...
} catch (Exception e) {
    // DB 저장 실패 시 자동 환불
    log.error("DB 저장 실패 billingKey: {}", payResp.getPaymentKey());

    if (autoRefundEnabled) {
        try {
            TossCancelRequestDTO cancelReq = TossCancelRequestDTO.builder()
                .cancelReason("DB 저장 실패로 인한 자동 환불")
                .build();

            tossPaymentsClient.cancelPayment(payResp.getPaymentKey(), cancelReq)
                .block();

            log.info("자동 환불 성공: {}", payResp.getPaymentKey());
        } catch (Exception refundError) {
            log.error("자동 환불 실패, 수동 처리 필요: {}",
                payResp.getPaymentKey(), refundError);
        }
    }
    throw e;
}
```

자동 환불 흐름.

```
토스 결제 성공 (카드 출금)
    ↓
DB 저장 시도 (Subscription + Payment)
    ↓
[성공] → 정상 완료
[실패] → 트랜잭션 롤백 (Subscription, Payment 모두 롤백)
           ↓
         자동 환불 실행:
         tossPaymentsClient.cancelPayment() 호출
           ↓
         [성공] → 토스 결제 취소, 고객 피해 없음
         [실패] → 수동 처리 필요 로그 남김
```

### 해결 5. 에러 코드 분기 + 재시도 로직

토스 API 에러 코드에 따라 재시도 여부를 분기한다.

| 에러 유형 | 예시 | 처리 |
| --- | --- | --- |
| 500번대 (일시적 장애) | `FAILED_INTERNAL_SYSTEM_PROCESSING`, `FAILED_CARD_COMPANY_RESPONSE`, `FAILED_DB_PROCESSING` | `nextBillingDateTime` 유지 → 다음 스케줄에서 자동 재시도 |
| 400/403 (고객 조치 필요) | `REJECT_CARD_PAYMENT` (카드 한도 초과) | `failureCode`/`failureMessage` 저장 → 재시도 안 함, 고객에 알림 |

스케줄러 기반 재시도.

```java
@Scheduled(cron = "0 0 3 * * *")  // 매일 새벽 3시
public void runDailyBilling() {
    LocalDateTime now = LocalDateTime.now();

    // nextBillingDateTime이 "지금 이전/같은" 구독들을 DB에서 조회
    List<Subscription> dueSubs = subscriptionRepository
        .findByStatusAndNextBillingDateTimeLessThanEqual(
            Subscription.Status.ACTIVE, now);

    for (Subscription s : dueSubs) {
        processSingleSubscriptionBilling(s);
    }
}
```

재시도 원리.

- 결제 성공 → `nextBillingDateTime`을 다음 달로 업데이트 → 다음 달까지 재조회 안 됨
- 500 에러 → `nextBillingDateTime` 유지 → 다음날 새벽 3시에 다시 조회됨 (자동 재시도)
- 400/403 에러 → 실패 기록 저장, 재시도 무의미

---

## 결과

### K6 부하테스트 결과

테스트 조건. 200명 사용자 시뮬레이션, 200개 빌링키 사전 발급, 30% 확률로 DB 저장 실패 주입.

K6 검증 흐름.

```jsx
// 1. 구독 생성 + 결제 시도
const createRes = http.post('/subscriptions', {...});
tossPaymentAttempts.add(1);

// 2. DB 저장 확인 (Payment + Subscription 동시 확인)
const verify = http.get(`/subscriptions/verify/${customerKey}`);
const payment = verify.json('payment');
const subscription = verify.json('subscription');

// 3. 불일치 판정
if (payment === null && subscription === null) {
    if (version === 'after') {
        const refundCheck = http.get(`/payments/status/${customerKey}`);
        if (refundCheck.json('status') === 'CANCELED') {
            autoRefundCount.add(1);  // 환불 성공
        }
    } else {
        dataInconsistency.add(1);  // Before: 불일치 발생
    }
}
```

Before 테스트 결과 (환불 로직 없음).

```bash
SPRING_PROFILES_ACTIVE=chaos-before ./gradlew bootRun
k6 run billing-chaos-test.js
```

| 지표 | 값 |
| --- | --- |
| 토스 결제 승인 | 10,212건 |
| DB 구독 저장 성공 | 7,093건 |
| 결제 누락 (돈만 나감) | 3,119건 |
| 환불 처리 | 0건 |
| 데이터 불일치율 | ~30% |

After 테스트 결과 (자동 환불 활성화).

```bash
SPRING_PROFILES_ACTIVE=chaos-after ./gradlew bootRun
k6 run billing-chaos-test.js
```

| 지표 | 값 |
| --- | --- |
| 토스 결제 승인 | 4,014건 |
| 자동 환불(CANCELED) | 1,139건 |
| 최종 유효 결제 | 2,875건 (4,014 - 1,139) |
| DB 구독 저장 성공 | 2,871건 |
| 데이터 불일치율 | ~0% (차이 4건 = 부하 시 Too many connection 에러) |

Before vs After 비교.

| 메트릭 | Before | After | 개선 |
| --- | --- | --- | --- |
| 데이터 불일치율 | ~30% | ~0% | 정합성 100% |
| 결제 누락 건수 | 3,119건 | 0건 | 누락 완전 제거 |
| 자동 환불 | 0건 | 1,139건 | 보상 트랜잭션 동작 |
| 거래 추적 가능 | 불가 (덮어씌워짐) | 가능 (원장 기록) | 감사 대응 가능 |

### 단위 테스트 결과 (JUnit 5 + Mockito)

테스트 클래스 구조.

```java
@ExtendWith(MockitoExtension.class)
@DisplayName("구독 결제 스케줄러 테스트")
class SubscriptionBillingSchedulerTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private BillingKeyService billingKeyService;
    @Mock
    private TossPaymentsClient tossPaymentsClient;
    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private SubscriptionBillingScheduler scheduler;

    private Subscription testSubscription;
    private LocalDateTime originalNextBilling;

    @BeforeEach
    void setUp() {
        // 각 테스트 전 테스트 데이터 초기화
    }
}
```

테스트 1. 500 에러 시 재시도 보장.

```java
@Test
@DisplayName("500번대 에러 발생 시 - nextBillingDateTime 유지되어야 함")
void test_500_error_keeps_nextBillingDateTime() {
    // Given: 500 에러 발생하도록 설정
    when(tossPaymentsClient.payWithBillingKey(anyString(), any()))
        .thenReturn(Mono.error(new RuntimeException("500 FAILED_INTERNAL_SYSTEM_PROCESSING")));

    // When: 결제 시도
    scheduler.processSingleSubscriptionBilling(testSubscription);

    // Then
    assertThat(testSubscription.getNextBillingDateTime()).isEqualTo(originalNextBilling);
    // nextBillingDateTime 유지 → 다음날 새벽 3시에 자동 재시도
    verify(paymentRepository, times(1)).save(any());        // 실패 기록은 저장
    verify(subscriptionRepository, never()).save(any());     // Subscription은 변경 안 함
}
```

`nextBillingDateTime`이 유지되면 다음날 스케줄러가 다시 이 구독을 조회하여 재시도한다.

테스트 2. 400/403 에러 시 실패 정보 저장.

```java
@Test
@DisplayName("400/403 에러 발생 시 - failureCode와 failureMessage 저장")
void test_400_error_saves_detailed_failure_info() {
    // Given: 403 에러 발생
    when(tossPaymentsClient.payWithBillingKey(anyString(), any()))
        .thenReturn(Mono.error(new RuntimeException("403 REJECT_CARD_PAYMENT 카드 한도 초과")));

    // When
    scheduler.processSingleSubscriptionBilling(testSubscription);

    // Then: ArgumentCaptor로 실제 저장된 값 검증
    verify(paymentRepository).save(argThat(payment ->
        payment.getFailureCode().equals("REJECT_CARD_PAYMENT") &&
        payment.getFailureMessage().contains("카드 한도 초과")
    ));
}
```

`failureCode`와 `failureMessage`가 정확히 추출 및 저장되는지 확인했다. `ArgumentCaptor`를 추가 활용하여 실제 인자값 내용까지 검증했다.

테스트 3. 다양한 500 에러 모두 재시도 가능.

```java
@Test
@DisplayName("500 에러 여러 종류 - 모두 재시도 가능")
void test_various_500_errors_are_retryable() {
    String[] errorMessages = {
        "500 FAILED_INTERNAL_SYSTEM_PROCESSING",
        "500 FAILED_CARD_COMPANY_RESPONSE",
        "500 FAILED_DB_PROCESSING"
    };

    for (String errorMsg : errorMessages) {
        reset(tossPaymentsClient, paymentRepository);

        when(tossPaymentsClient.payWithBillingKey(anyString(), any()))
            .thenReturn(Mono.error(new RuntimeException(errorMsg)));

        scheduler.processSingleSubscriptionBilling(testSubscription);

        assertThat(testSubscription.getNextBillingDateTime()).isEqualTo(originalNextBilling);
        verify(paymentRepository, times(1)).save(any(Payment.class));
        verify(subscriptionRepository, never()).save(any(Subscription.class));
    }
}
```

3가지 500 에러 모두 `nextBillingDateTime` 유지, Payment는 실패 기록 저장, Subscription은 변경 없음.

테스트 4. 결제 성공 시 다음 결제일 업데이트.

```java
@Test
@DisplayName("결제 성공 시 - nextBillingDateTime이 1개월 후로 업데이트")
void test_successful_payment_updates_nextBillingDateTime() {
    // Given: 결제 성공 응답
    TossPaymentResponseDTO successResponse = new TossPaymentResponseDTO();
    successResponse.setStatus("DONE");
    successResponse.setPaymentKey("test_payment_key_123");

    when(tossPaymentsClient.payWithBillingKey(anyString(), any()))
        .thenReturn(Mono.just(successResponse));

    // When
    scheduler.processSingleSubscriptionBilling(testSubscription);

    // Then
    assertThat(testSubscription.getNextBillingDateTime()).isAfter(originalNextBilling);
    verify(subscriptionRepository, times(1)).save(testSubscription);  // 다음 결제일 업데이트
    verify(paymentRepository, times(1)).save(any());                  // 결제 성공 기록
}
```

성공 시 `nextBillingDateTime`이 1개월 후로 변경, Subscription과 Payment 모두 저장.

테스트 결과 요약. 모든 테스트 1초 미만 완료, 실제 API 호출 없이 모든 시나리오 검증.

| 테스트 | 검증 내용 | 결과 |
| --- | --- | --- |
| 500 에러 재시도 | `nextBillingDateTime` 유지, 다음날 재시도 | PASS |
| 400/403 실패 기록 | `failureCode`/`failureMessage` 정확히 저장 | PASS |
| 다양한 500 에러 | 3종 모두 재시도 가능으로 처리 | PASS |
| 결제 성공 | `nextBillingDateTime` 1개월 후 업데이트 | PASS |

### 결론

결제 시스템에서 외부 API(토스) 호출 성공 후 내부 DB 저장 실패라는 분산 시스템의 고전적 문제를 발견하고, 다음과 같이 해결했다.

1. Payment 거래 원장. 모든 결제/취소를 개별 기록. `paymentKey` 기준 고유성, `cancelRequestId`로 멱등성 보장
2. 자동 환불 (보상 트랜잭션). DB 저장 실패 시 `cancelPayment()` 호출로 결제 취소. 환불 실패 시 수동 처리 로그
3. 에러 코드 분기 재시도. 500번대는 `nextBillingDateTime` 유지로 자동 재시도, 400/403은 실패 기록 저장 후 재시도 안 함
4. Chaos Engineering 검증. Spring Profile + AOP로 30% DB 장애 주입, K6로 200명 시뮬레이션. Before(불일치 30%) → After(불일치 0%)
5. 단위 테스트. JUnit 5 + Mockito로 재시도/실패 기록/성공 업데이트 로직을 모든 테스트 1초 미만에 검증

---

## Sources

- [[Springboot] 토스페이먼츠 구독 결제 - 거래 내역 추적, 환불 기능 구현하기 (거래 원장)](https://velog.io/@hansjour/%ED%86%A0%EC%8A%A4%ED%8E%98%EC%9D%B4%EB%A8%BC%EC%B8%A0-%EA%B5%AC%EB%8F%85-%EA%B2%B0%EC%A0%9C-%EA%B1%B0%EB%9E%98-%EB%82%B4%EC%97%AD-%EC%A0%80%EC%9E%A5%EC%B6%94%EC%A0%81-%EC%B7%A8%EC%86%8C-%EA%B8%B0%EB%8A%A5-%EA%B5%AC%ED%98%84%ED%95%98%EA%B8%B0)
- [[Springboot] 토스페이먼츠 구독 결제 - 장애 상황 대비하기 1탄 | Chaos Engineering 적용기](https://velog.io/@hansjour/%EC%84%B1%EB%8A%A5-%ED%85%8C%EC%8A%A4%ED%8A%B8)
- [[Springboot] 토스페이먼츠 구독 결제 - 장애 상황 대비하기 2탄 | K6로 '자동 환불' 시나리오 테스트하기](https://velog.io/@hansjour/%ED%86%A0%EC%8A%A4%ED%8E%98%EC%9D%B4%EB%A8%BC%EC%B8%A0-%EA%B5%AC%EB%8F%85-%EA%B2%B0%EC%A0%9C-%EC%9E%A5%EC%95%A0-%EC%83%81%ED%99%A9-%EB%8C%80%EB%B9%84%ED%95%98%EA%B8%B0-2%ED%83%84-K6%EB%A1%9C-%EC%8B%9C%EB%82%98%EB%A6%AC%EC%98%A4-%ED%85%8C%EC%8A%A4%ED%8A%B8%ED%95%98%EA%B8%B0)
- [[Springboot] 토스페이먼츠 구독 결제 - 장애 상황 대비하기 3탄 | 단위 테스트로 구독 결제 재시도 로직 검증하기 (JUnit 5 + Mockito)](https://velog.io/@hansjour/Springboot-%ED%86%A0%EC%8A%A4%ED%8E%98%EC%9D%B4%EB%A8%BC%EC%B8%A0-%EA%B5%AC%EB%8F%85-%EA%B2%B0%EC%A0%9C-%EC%9E%A5%EC%95%A0-%EC%83%81%ED%99%A9-%EB%8C%80%EB%B9%84%ED%95%98%EA%B8%B0-3%ED%83%84-%EB%8B%A8%EC%9C%84-%ED%85%8C%EC%8A%A4%ED%8A%B8%EB%A1%9C-%EA%B5%AC%EB%8F%85-%EA%B2%B0%EC%A0%9C-%EC%9E%AC%EC%8B%9C%EB%8F%84-%EB%A1%9C%EC%A7%81-%EA%B2%80%EC%A6%9D%ED%95%98%EA%B8%B0-JUnit-5-Mockito)
