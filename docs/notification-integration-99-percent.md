# 알림 시스템 통합으로 응답시간 99% 개선 (5초→50ms)

## 문제

회원가입, 인증, 결제, 대타 요청, 근무 관리, 급여 확정, 지점 초대 등 다양한 시점에 알림을 발송한다. 서비스를 빠르게 확장하는 과정에서 각 서비스(user-service, billing-service, workpay-service, branch-service)가 직접 외부 API(비즈엠, SMTP, FCM)를 호출하는 구조가 되었고, 동일한 발송 로직이 여러 서비스에 중복 산재하게 되었다.

### 동기 호출로 인한 응답 지연

```java
// UserService.java
@Transactional
public User signup(SignupRequest request) {
    User user = userRepository.save(newUser);     // ~50ms
    bizMService.sendSignupAlimtalk(user);         // 동기 호출 (최대 5000ms)
    return user;
}

// BizMService.java
public void sendSignupAlimtalk(User user) {
    restTemplate.postForEntity(bizMUrl, request, String.class);  // 타임아웃: 5초
}
```

```
사용자          user-service              비즈엠 API
  │                  │                        │
  │  회원가입 요청   │                        │
  │─────────────────▶│                        │
  │                  │  DB 저장 (50ms)        │
  │                  │────────┐               │
  │                  │◀───────┘               │
  │                  │  알림톡 발송 요청      │
  │                  │───────────────────────▶│
  │                  │       대기중...        │  ◀── 최대 5초 블로킹
  │                  │◀───────────────────────│
  │  회원가입 완료   │                        │
  │◀─────────────────│                        │
```

회원가입 성공/실패와 무관한 알림 발송이 응답시간을 지배하고 있었다.

| 시나리오 | DB 저장 | 알림톡 API | 총 응답시간 |
| --- | --- | --- | --- |
| 정상 | 50ms | 200ms | 250ms |
| API 느림 | 50ms | 2,000ms | 2,050ms |
| API 타임아웃 | 50ms | 5,000ms | 5,050ms |
| API 장애 | 50ms | 5,000ms + 예외 | 5,050ms+ |

### 발송 실패 시 복구 불가

```java
public void sendSignupAlimtalk(User user) {
    try {
        restTemplate.postForEntity(bizMUrl, request, String.class);
    } catch (Exception e) {
        log.error("알림톡 발송 실패: {}", e.getMessage());
        // 재시도 로직 없음. 사용자는 환영 알림톡을 받지 못함.
    }
}
```

| 실패 원인 | 빈도 | 복구 가능성 | 당시 처리 |
| --- | --- | --- | --- |
| 네트워크 일시 장애 | 가끔 | 재시도 시 높음 | 유실 |
| 비즈엠 API 점검 | 드물게 | 점검 후 가능 | 유실 |
| Rate Limit 초과 | 트래픽 급증 시 | 대기 후 가능 | 유실 |
| 잘못된 전화번호 | 데이터 오류 | 불가 | 유실 (정상) |

비즈니스 영향은 다음과 같다.

- 신규 가입자가 환영 메시지를 받지 못함 → 첫인상 저하
- 인증번호 발송 실패 시 → 본인인증 불가 → 서비스 이용 불가
- 대타 요청 푸시 알림 유실 시 → 동료가 인지 못함 → 대타 공백
- 지점 초대 알림톡 유실 시 → 직원이 초대를 인지 못함 → 온보딩 지연
- 운영팀이 실패 건을 추적할 방법 없음 (로그만 존재)

### 코드 중복으로 인한 유지보수 비용

```
user-service/
├── service/
│   ├── BizMService.java       ── 비즈엠 API 호출 (알림톡)
│   └── EmailService.java      ── SMTP 발송 (이메일)

billing-service/
├── service/mail/
│   └── InvoiceEmailService.java  ── SMTP 발송 (이메일 중복)

workpay-service/
├── service/
│   ├── FcmService.java        ── FCM 직접 호출 (푸시)
│   └── BizMService.java       ── 비즈엠 API 호출 (알림톡 중복)

branch-service/
├── service/
│   └── BizMService.java       ── 비즈엠 API 호출 (알림톡 중복)
```

| 서비스 | 알림톡 (비즈엠) | 이메일 (SMTP) | 푸시 (FCM) | 비고 |
| --- | --- | --- | --- | --- |
| user-service | BizMService.java | EmailService.java | - | 원본 |
| billing-service | - | InvoiceEmailService.java | - | 이메일 중복 |
| workpay-service | BizMService.java | - | FcmService.java | 알림톡 중복 + FCM |
| branch-service | BizMService.java | - | - | 알림톡 중복 |

- 비즈엠 호출 로직이 3곳에 중복 (user-service, workpay-service, branch-service)
- SMTP 발송 로직이 2곳에 중복 (user-service, billing-service)
- FCM 설정/호출이 workpay-service에 단독 존재
- 총 8개 파일이 4개 서비스에 산재

### 알림 채널 확장의 어려움

- 새로운 알림 채널(인앱 알림 등) 추가 시 4개 서비스 모두에 개별 구현 필요
- FCM 토큰 관리, 토픽 구독 등 부가 로직이 비즈니스 서비스에 침투
- Firebase SDK 설정이 workpay-service에 종속되어 다른 서비스에서 푸시를 보내려면 또 중복 발생

---

## 분석

기존 구조의 핵심 문제는 각 서비스가 외부 알림 API를 직접 동기 호출하는 데 있었다.

```
┌─────────────────────────────────────────────────────────────────┐
│                          user-service                           │
│                                                                 │
│  UserService.java                                               │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │  @Transactional                                            │ │
│  │  public User signup(SignupRequest request) {               │ │
│  │      User user = userRepository.save(newUser);             │ │
│  │      bizMService.sendSignupAlimtalk(user);  ◀── 블로킹     │ │
│  │      return user;                                          │ │
│  │  }                                                         │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                 │
│  BizMService.java ── 비즈엠 API 직접 호출 (HTTP)               │
│  EmailService.java ── JavaMailSender 직접 사용                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                       billing-service                           │
│                                                                 │
│  InvoiceEmailService.java ── JavaMailSender 직접 사용           │
│  (user-service의 이메일 발송 로직과 중복 구현)                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                       workpay-service                           │
│                                                                 │
│  FcmService.java ── Firebase SDK 직접 호출                      │
│  (대타 요청, 근무 요청, 급여 확정 시 푸시 알림 직접 발송)       │
│  BizMService.java ── 비즈엠 API 직접 호출 (HTTP)               │
│  (user-service와 동일 로직 복사)                                │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                       branch-service                            │
│                                                                 │
│  BizMService.java ── 비즈엠 API 직접 호출 (HTTP)               │
│  (지점 초대장 알림톡 발송, user-service와 동일 로직 복사)       │
└─────────────────────────────────────────────────────────────────┘
```

개선 목표는 다음 네 가지로 정리했다.

- 외부 API 장애가 핵심 비즈니스 로직에 영향을 주지 않도록 격리
- 4개 서비스에 산재된 알림 발송 로직의 중복 제거 및 중앙화
- 알림 발송 실패에 대한 복구 메커니즘 확보
- 알림 채널 추가/변경 시 영향 범위를 단일 서비스로 한정

### Outbox Pattern 도입 배경

Kafka는 외부 인프라이므로 로컬 DB 트랜잭션과 원자적으로 묶을 수 없다. 이벤트 기반 아키텍처는 서비스 간 결합도를 낮춰주지만, 그만큼 이벤트가 반드시 처리되었는가에 대한 신뢰성 보장이 필요하다.

다음과 같은 상황에서 알림 유실이 발생할 수 있다.

```
[알림 유실 시나리오]

1. 배포 중 유실 (무중단 배포 미적용 시)
   비즈니스 로직 성공 (DB 커밋 완료)
   → Kafka 이벤트 발행 직전 서버 종료 (배포)
   → 이벤트 유실 → 알림 누락

2. Kafka 브로커 일시 장애
   비즈니스 로직 성공 (DB 커밋 완료)
   → Kafka 발행 실패 (브로커 다운)
   → 이벤트 유실 → 알림 누락

3. notification-service 처리 중 장애
   Kafka 이벤트 수신 완료
   → 외부 API(비즈엠/SMTP/FCM) 호출 중 장애
   → 재시도 로직 없으면 알림 누락
```

현재는 Graceful Shutdown을 적용하여 배포 시 진행 중인 요청은 완료 후 종료되지만, Outbox Pattern은 그 이전부터 적용하여 배포 방식에 관계없이 알림 발송을 보장하는 역할을 한다.

Outbox Pattern의 원리는 다음과 같다.

```
┌─────────────────────────────────────────────────────────────────┐
│  @Transactional                                                 │
│                                                                 │
│  1. 알림 이벤트 수신                                            │
│  2. notification_outbox 테이블에 알림 정보 저장 (영속화)        │
│                                                                 │
│  → DB 트랜잭션이 성공하면 outbox 레코드가 반드시 존재           │
│  → 서버가 죽어도, 외부 API가 죽어도, outbox에 기록은 남아있음   │
│  → 스케줄러가 outbox를 폴링하여 미발송 건 재시도               │
└─────────────────────────────────────────────────────────────────┘
```

Kafka 이벤트 발행의 at-most-once를 Outbox로 at-least-once로 보강한 것이다.

---

## 해결

### 1차 개선. 알림 서비스 분리 + Outbox Pattern

```
┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐
│   user-service   │  │ billing-service  │  │ workpay-service  │  │ branch-service   │
│                  │  │                  │  │                  │  │                  │
│  이벤트 발행만   │  │  이벤트 발행만   │  │  이벤트 발행만   │  │  이벤트 발행만   │
│  (발송 로직 제거)│  │  (발송 로직 제거)│  │  (발송 로직 제거)│  │  (발송 로직 제거)│
└────────┬─────────┘  └────────┬─────────┘  └────────┬─────────┘  └────────┬─────────┘
         │                     │                      │                     │
         └─────────────────────┴──────────────────────┴─────────────────────┘
                                       │
                          Kafka Topic: "notification-events"
                                       │
                                       ▼
┌──────────────────────────────────────────────────────────────────────────────────┐
│                            notification-service                                  │
│                                                                                  │
│  NotificationConsumer (Kafka)                                                    │
│  ┌────────────────────────────────────────────────────────────────────────────┐  │
│  │  채널별 라우팅:                                                            │  │
│  │  ├── ALIMTALK → AlimtalkService (비즈엠 API)                              │  │
│  │  ├── EMAIL    → EmailService / InvoiceEmailService (SMTP)                 │  │
│  │  ├── PUSH     → FcmService (Firebase Cloud Messaging)                    │  │
│  │  └── 공통     → InAppNotificationService (DB 저장)                        │  │
│  └────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                  │
│  NotificationOutboxService                                                       │
│  ┌────────────────────────────────────────────────────────────────────────────┐  │
│  │  1. Outbox 테이블에 저장 (영속화) — 같은 트랜잭션으로 발송 보장           │  │
│  │  2. 즉시 발송 시도                                                         │  │
│  │  3. 실패 시 retry_count++ → 스케줄러가 재시도                              │  │
│  └────────────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────────────────────┘
```

### 서비스별 발행 이벤트

user-service (알림톡 + 이메일)

| 이벤트 타입 | 채널 | 설명 |
| --- | --- | --- |
| WELCOME_ALIMTALK | ALIMTALK | 회원가입 환영 |
| VERIFICATION_ALIMTALK | ALIMTALK | 아이디 찾기 인증번호 |
| PASSWORD_RESET_ALIMTALK | ALIMTALK | 비밀번호 재설정 인증번호 |
| VERIFICATION_EMAIL | EMAIL | 아이디 찾기 인증번호 |
| PASSWORD_RESET_EMAIL | EMAIL | 비밀번호 재설정 인증번호 |
| SIGNUP_VERIFICATION_EMAIL | EMAIL | 회원가입 이메일 인증 |

billing-service (이메일)

| 이벤트 타입 | 채널 | 설명 |
| --- | --- | --- |
| INVOICE_EMAIL | EMAIL | 인보이스 발송 |
| PAYMENT_FAILURE_EMAIL | EMAIL | 결제 실패 안내 |

branch-service (알림톡)

| 이벤트 타입 | 채널 | 설명 |
| --- | --- | --- |
| INVITE_ALIMTALK | ALIMTALK | 지점 초대 알림톡 발송 (초대 링크 포함) |

사장님이 직원을 지점에 초대하면 초대 대상자에게 알림톡이 발송되며, 앱 다운로드 링크와 초대 수락 URL이 포함된다.

workpay-service (푸시 알림)

| 이벤트 타입 | 채널 | 대상 | 설명 |
| --- | --- | --- | --- |
| SHIFT_REQUEST_CREATED | PUSH | 브랜치 전체 | 대타 요청 생성 |
| SHIFT_REQUEST_ACCEPTED | PUSH | 브랜치 전체 | 대타 수락 |
| SHIFT_REQUEST_APPROVED | PUSH | 요청자 | 대타 승인 |
| SHIFT_REQUEST_REJECTED | PUSH | 요청자 + 수락자 | 대타 거절 |
| SALARY_CONFIRMED | PUSH | 해당 직원 | 급여 확정 |
| SCHEDULE_REQUEST_CREATED | PUSH | 브랜치 전체 | 근무 요청 생성 |
| SCHEDULE_REQUEST_APPROVED | PUSH | 요청자 | 근무 요청 승인 |
| SCHEDULE_REQUEST_REJECTED | PUSH | 요청자 | 근무 요청 거절 |
| SCHEDULE_ANNOUNCEMENT_CREATED | PUSH | 브랜치 전체 | 근무 공고 생성 |
| SCHEDULE_ANNOUNCEMENT_APPLIED | PUSH | 사장님 | 공고 신청 |
| SCHEDULE_ANNOUNCEMENT_APPROVED | PUSH | 신청자 | 공고 승인 |
| SCHEDULE_ANNOUNCEMENT_REJECTED | PUSH | 신청자 | 공고 거절 |

### 개선된 시퀀스. 회원가입 흐름

```
사용자        user-service                Kafka              notification-service     비즈엠 API
  │               │                         │                        │                    │
  │ 회원가입 요청 │                         │                        │                    │
  │──────────────▶│                         │                        │                    │
  │               │ DB 저장 (50ms)          │                        │                    │
  │               │────────┐                │                        │                    │
  │               │◀───────┘                │                        │                    │
  │ 회원가입 완료 │                         │                        │                    │
  │◀──────────────│                         │                        │                    │
  │               │                         │                        │                    │
  │               │ 이벤트 발행 (비동기)    │                        │                    │
  │               │────────────────────────▶│                        │                    │
  │               │                         │  이벤트 소비           │                    │
  │               │                         │───────────────────────▶│                    │
  │               │                         │                        │ outbox 저장        │
  │               │                         │                        │ → 즉시 발송        │
  │               │                         │                        │───────────────────▶│
  │               │                         │                        │◀───────────────────│
  │               │                         │                        │ status = SENT      │
```

사용자 응답시간은 DB 저장 약 50ms로, 알림 발송과 완전 분리되었다.

### 개선된 시퀀스. 대타 요청 흐름

```
사용자        workpay-service             Kafka              notification-service       FCM
  │               │                         │                        │                    │
  │ 대타 요청     │                         │                        │                    │
  │──────────────▶│                         │                        │                    │
  │               │ 대타 요청 저장          │                        │                    │
  │ 요청 완료     │                         │                        │                    │
  │◀──────────────│                         │                        │                    │
  │               │                         │                        │                    │
  │               │ SHIFT_REQUEST_CREATED   │                        │                    │
  │               │────────────────────────▶│                        │                    │
  │               │                         │───────────────────────▶│                    │
  │               │                         │                        │ 브랜치 전체 푸시   │
  │               │                         │                        │───────────────────▶│
  │               │                         │                        │ 인앱 알림 저장     │
```

### 개선된 시퀀스. 지점 초대 흐름

```
사장님        branch-service              Kafka              notification-service     비즈엠 API
  │               │                         │                        │                    │
  │ 직원 초대     │                         │                        │                    │
  │──────────────▶│                         │                        │                    │
  │               │ 초대 정보 저장          │                        │                    │
  │ 초대 완료     │                         │                        │                    │
  │◀──────────────│                         │                        │                    │
  │               │                         │                        │                    │
  │               │ INVITE_ALIMTALK         │                        │                    │
  │               │────────────────────────▶│                        │                    │
  │               │                         │───────────────────────▶│                    │
  │               │                         │                        │ 초대 알림톡 발송   │
  │               │                         │                        │───────────────────▶│
  │               │                         │                        │ (초대 링크 포함)   │
```

### 물리 테이블

```sql
CREATE TABLE notification_outbox (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    type            VARCHAR(50) NOT NULL,
    payload         TEXT NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count     INT NOT NULL DEFAULT 0,
    created_at      DATETIME NOT NULL,
    updated_at      DATETIME,
    sent_at         DATETIME,
    error_message   TEXT,

    INDEX idx_status_retry (status, retry_count),
    INDEX idx_created_at (created_at)
);
```

### 2차 개선. DLT 및 데이터 관리

1차 개선의 한계는 다음과 같았다.

| 문제 | 설명 |
| --- | --- |
| 무한 재시도 | 영구적 실패 건(잘못된 전화번호 등)이 계속 재시도됨 |
| 데이터 누적 | SENT 상태 데이터가 무한정 쌓임 → 스토리지 낭비 |
| 실패 추적 불가 | 최종 실패 건을 별도로 관리/확인할 방법 없음 |

DLT(Dead Letter Table)를 도입하여 이를 해결했다.

```
┌─────────────────────────────────────────────────────────────┐
│                     notification_outbox                      │
│                                                             │
│  status: PENDING, retry_count: 0                            │
│  ↓                                                          │
│  발송 시도 #1 → 실패 → retry_count: 1                      │
│  ↓                                                          │
│  발송 시도 #2 → 실패 → retry_count: 2                      │
│  ↓                                                          │
│  발송 시도 #3 → 실패 → retry_count: 3                      │
│  ↓                                                          │
│  MAX_RETRY(3) 도달 → DLT로 이동 → outbox에서 삭제          │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      notification_dlt                        │
│                                                             │
│  - 최종 실패 건 보관                                        │
│  - 수동 확인 및 처리 대상                                   │
│  - processed_at, processed_by 필드로 처리 이력 관리         │
└─────────────────────────────────────────────────────────────┘
```

### 스케줄러 구성

| 스케줄러 | 주기 | 역할 |
| --- | --- | --- |
| `retryFailedNotifications()` | 1분 (fixedDelay=60000) | PENDING & retry_count < 3인 건 재발송 |
| `moveExceededToDlt()` | 1분 (fixedDelay=60000) | retry_count >= 3인 건 DLT로 이동, outbox에서 삭제 |
| `cleanupSentNotifications()` | 매일 자정 (cron) | SENT 상태의 전일 이전 데이터 삭제 |

폴링 주기 1분의 판단 근거는 다음과 같다.

- 조회 조건이 `status = 'PENDING' AND retry_count < 3`으로, `idx_status_retry` 복합 인덱스를 타므로 풀스캔이 발생하지 않음
- 정상 운영 시 대부분의 알림은 즉시 발송 성공(SENT)하므로, 실패 건이 없으면 결과 0건의 인덱스 스캔으로 끝남
- 실패 건이 존재하는 비정상 상황에서만 실제 처리가 발생하므로, 평시 부하는 무시할 수 있는 수준

### DLT 물리 테이블

```sql
CREATE TABLE notification_dlt (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    type                VARCHAR(50) NOT NULL,
    payload             TEXT NOT NULL,
    retry_count         INT NOT NULL,
    last_error_message  TEXT,
    original_outbox_id  BIGINT NOT NULL,
    created_at          DATETIME NOT NULL,
    processed_at        DATETIME,
    processed_by        VARCHAR(100),

    INDEX idx_processed (processed_at),
    INDEX idx_created (created_at)
);
```

### 전체 데이터 흐름 (최종)

```
[알림 요청 수신 - Kafka Consumer]
      │
      ▼
notification_outbox 저장 (status: PENDING, retry_count: 0)
      │
      ▼
  즉시 발송 시도
      │
      ├── 성공 ──▶ status: SENT
      │                │
      │                ▼ (자정 스케줄러)
      │           전일 이전 데이터 삭제
      │
      └── 실패 ──▶ retry_count++, error_message 기록
                       │
                       ▼
                 retry_count < 3 ?
                  │            │
                 YES           NO
                  │            │
                  ▼            ▼
            1분 후 재시도    DLT로 이동
            (위 과정 반복)   - DLT 테이블에 저장
                             - outbox에서 삭제
                             - 운영팀 수동 확인 대상
```

### 결제 알림 도입의 비즈니스 근거

| 상황 | 알림 없음 | 알림 있음 |
| --- | --- | --- |
| 결제 완료 | 불안감, CS 문의 | 안심, 신뢰 |
| 결제 실패 | 원인 불명, 이탈 | 원인 파악, 재시도 |
| 반복 결제 실패 | 서비스 해지 | 결제 수단 변경 |

결제는 돈이 오가는 민감한 행위이므로 알림은 신뢰의 문제다.

### 변경 파일 요약

신규 생성 (notification-service)

| 파일 | 용도 |
| --- | --- |
| `kafka/NotificationConsumer.java` | Kafka 이벤트 소비 + 채널별 라우팅 |
| `kafka/KafkaConsumerConfig.java` | Kafka Consumer 설정 |
| `service/NotificationOutboxService.java` | Outbox 핵심 로직 + 스케줄러 |
| `service/AlimtalkService.java` | 비즈엠 API 호출 (중앙화) |
| `service/EmailService.java` | SMTP 발송 (중앙화) |
| `service/InvoiceEmailService.java` | 인보이스 이메일 발송 (중앙화) |
| `service/FcmService.java` | FCM 푸시 발송 (중앙화) |
| `service/InAppNotificationService.java` | 인앱 알림 저장/조회 |
| `domain/NotificationOutbox.java` | Outbox 엔티티 |
| `domain/NotificationDlt.java` | DLT 엔티티 |
| `domain/NotificationType.java` | 알림 타입 enum |
| `domain/NotificationStatus.java` | 상태 enum (PENDING, SENT) |
| `controller/NotificationOutboxController.java` | Outbox 관리 API |
| `controller/InAppNotificationController.java` | 인앱 알림 조회 API (모바일) |
| `controller/WebNotificationController.java` | 웹 알림 조회 API |
| `controller/DeviceTokenController.java` | FCM 토큰 관리 API |
| `scheduler/DeviceTokenCleanupScheduler.java` | 비활성 토큰 정리 (매일 3시) |
| `scheduler/InAppNotificationCleanupScheduler.java` | 90일 지난 알림 정리 (매일 4시) |

수정 (user-service)

| 파일 | 변경 내용 |
| --- | --- |
| `BizMService.java` | 삭제 → Kafka 이벤트 발행으로 전환 |
| `EmailService.java` | 삭제 → Kafka 이벤트 발행으로 전환 |
| `NotificationService.java` (신규) | Kafka 이벤트 발행 래퍼 |

수정 (billing-service)

| 파일 | 변경 내용 |
| --- | --- |
| `InvoiceEmailService.java` | 직접 SMTP 발송 삭제 → Kafka 이벤트 발행으로 전환 |

수정 (workpay-service)

| 파일 | 변경 내용 |
| --- | --- |
| `FcmService.java` | 삭제 → Kafka 이벤트 발행으로 전환 |
| `BizMService.java` | 삭제 → Kafka 이벤트 발행으로 전환 |
| `NotificationEventPublisher.java` (신규) | 12종 푸시 알림 이벤트 발행 |

수정 (branch-service)

| 파일 | 변경 내용 |
| --- | --- |
| `BizMService.java` | 삭제 → Kafka 이벤트 발행으로 전환 |
| `NotificationService.java` (신규) | 초대 알림 이벤트 발행 |

### 확장성 고려

```
현재 구조 (Kafka + Outbox + 폴링)
├── 장점: 안정적, DB 기반 영속화, 서비스 간 느슨한 결합
├── 단점: 실패 건 재시도 시 1분 주기 폴링 (실패 건이 없어도 쿼리 발생)
└── 적합: 현재 트래픽 수준

트래픽 증가 시 고려사항
├── Kafka 파티션 확장 + Consumer 인스턴스 추가
├── 폴링 주기 조정 (1분 → 5분)
├── 배치 처리 크기 제한 (LIMIT 100)
└── Consumer concurrency 조정 (현재 3)
```

---

## 결과

| 지표 | Before | After | 개선율 |
| --- | --- | --- | --- |
| 회원가입 응답시간 (정상) | 250ms | 50ms | 80% 감소 |
| 회원가입 응답시간 (API 5초 장애) | 5,050ms | 50ms | 99% 감소 |
| 결제 응답시간 | 결제 + 이메일 발송 | 결제만 | 이메일 시간 제거 |
| 알림 발송 복구 가능성 | 0% (1회 실패 시 유실) | 최대 3회 재시도 | 복구 가능 |
| 알림 관련 코드 위치 | 4개 서비스, 8개 파일 | 1개 서비스 | 중앙화 |
| 알림 채널 | 3개 (알림톡, 이메일, FCM) | 4개 (+인앱 알림) | 채널 추가 |
| 통합 관리 알림 이벤트 | - | 21종 | 체계화 |
| 알림 연동 서비스 | 4개 (발송 로직 포함) | 4개 (이벤트 발행만) | 결합도 제거 |
| 실패 건 추적 | 불가 (로그만) | DLT 테이블 | 추적 가능 |
| 설정 변경 시 수정/배포 | 최대 4개 서비스 | 1개 서비스 | 75% 감소 |

| 항목 | 효과 |
| --- | --- |
| 장애 격리 | 외부 API(비즈엠, SMTP, FCM) 장애가 핵심 로직에 영향 없음 |
| 발송 보장 | Outbox + 3회 재시도로 일시 장애 및 배포 중 유실 복구 |
| 실패 추적 | DLT로 최종 실패 건 관리 및 수동 처리 가능 |
| 유지보수성 | 알림 로직 변경 시 notification-service 1개만 수정/배포 |
| 확장 용이 | 이벤트 발행만으로 신규 알림 기능 추가 가능 |
| 운영 효율 | 5개 스케줄러로 토큰/알림/outbox 자동 정리 |

| 단계 | 내용 | 효과 |
| --- | --- | --- |
| 1차 | 알림 서비스 분리 + Kafka 이벤트 기반 + Outbox Pattern | 응답 지연 제거, 4개 서비스 코드 중앙화, 4개 채널 통합 |
| 2차 | DLT + 스케줄러 기반 자동 운영 | 무한 재시도 방지, 스토리지 관리, 실패 추적 |

핵심 원칙은 서비스를 빠르게 확장하는 과정에서 누적된 알림 발송 로직의 중복과 결합도를 제거하고, 핵심 비즈니스 로직(회원가입, 결제, 대타 요청, 근무 관리, 지점 초대)과 부가 기능(알림 발송)을 분리하여 외부 API 장애로부터 사용자 경험을 보호하는 것이다.
