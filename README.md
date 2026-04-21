# Manezy & Crewezy — 매장 관리 SaaS + 직원용 앱

> 사장님은 웹(Manezy)에서 매장을 관리하고, 직원은 앱(Crewezy)에서 근무·급여를 확인하는 B2B 서비스.<br>
> 1유저 N지점 — 여러 매장을 운영하는 사장과, 여러 곳에서 일하는 알바생 모두 지원.<br>
> 7개 서비스 분리 · 토스페이먼츠 정기결제 · 카나리 무중단 배포 · 3계층 테스트 자동화 · 실서비스 운영 중.
<br>

<!-- 캡쳐: Manezy 웹 대시보드 + Crewezy 앱 화면 -->

<br>
<img width="745" height="739" alt="image" src="https://github.com/user-attachments/assets/7b6f5978-bdc4-4bdb-bf18-c17871bf3aba" />

<br>
<br>


## 프로젝트 개요

| 항목 | 내용 |
|------|------|
| 기간 | 2024.12 ~ 운영 중 |
| 인원 | 백엔드 2인 (본인), 프론트 1인, 디자인 1인 |
| 서비스 | Manezy(웹, 사장/관리자용) · Crewezy(앱, 직원용) |
| 유저 구조 | 1유저 N지점 — 사장·관리자·직원 3단계 권한 × 지점별 급여 공개 설정 |
| 배포 | AWS EC2 · Docker Compose · GitHub Actions CI/CD · 카나리 무중단 배포 |

<br>

## 기술 스택

| 영역 | 기술 |
|------|------|
| Backend | ![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.x-6DB33F?logo=springboot&logoColor=white) ![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white) ![Spring Cloud](https://img.shields.io/badge/Spring_Cloud-Gateway_/_Eureka-6DB33F?logo=spring&logoColor=white) ![JPA](https://img.shields.io/badge/JPA-Hibernate-59666C?logo=hibernate&logoColor=white) ![OpenFeign](https://img.shields.io/badge/OpenFeign-HTTP_Client-6DB33F?logo=spring&logoColor=white) |
| Database | ![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql&logoColor=white) ![RDS](https://img.shields.io/badge/AWS_RDS-Managed_DB-527FFF?logo=amazonrds&logoColor=white) |
| Storage | ![S3](https://img.shields.io/badge/AWS_S3-Image_Upload-569A31?logo=amazons3&logoColor=white) |
| Cache | ![Caffeine](https://img.shields.io/badge/Caffeine-JVM_Local_Cache-6DB33F) |
| Message Queue | ![RabbitMQ](https://img.shields.io/badge/RabbitMQ-Event_Driven-FF6600?logo=rabbitmq&logoColor=white) |
| Payment | ![Toss Payments](https://img.shields.io/badge/Toss_Payments-Billing_Key-0064FF) |
| Auth | ![JWT](https://img.shields.io/badge/JWT-Refresh_Token_Rotation-000000?logo=jsonwebtokens&logoColor=white) ![OAuth2](https://img.shields.io/badge/OAuth_2.0-Kakao_/_Naver_/_Google_/_Apple-EB5424) |
| AI | ![OpenAI](https://img.shields.io/badge/OpenAI-Schedule_Parsing-412991?logo=openai&logoColor=white) |
| Monitoring | ![Prometheus](https://img.shields.io/badge/Prometheus-Metrics-E6522C?logo=prometheus&logoColor=white) ![Grafana](https://img.shields.io/badge/Grafana-Dashboard-F46800?logo=grafana&logoColor=white) ![Loki](https://img.shields.io/badge/Loki-Log_Aggregation-F46800) |
| CI/CD | ![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-CI/CD-2088FF?logo=githubactions&logoColor=white) ![Docker](https://img.shields.io/badge/Docker_Compose-Container-2496ED?logo=docker&logoColor=white) |
| Docs | ![Swagger](https://img.shields.io/badge/Swagger-API_Docs-85EA2D?logo=swagger&logoColor=black) |
| Test | ![JUnit5](https://img.shields.io/badge/JUnit_5-3_Layer_Testing-25A162?logo=junit5&logoColor=white) ![JaCoCo](https://img.shields.io/badge/JaCoCo-Coverage-C21325) |

<br>

## 서비스 아키텍처

<img width="800" height="2432" alt="image" src="https://github.com/user-attachments/assets/0f8f6473-61b5-4d50-a49c-58f65eaedd34" />


<br>
<br>

## CI/CD · 배포 흐름

<img width="800" height="1186" alt="image" src="https://github.com/user-attachments/assets/83a20310-7ce6-4a70-b4ef-bdc9e55786b7" />


<br>
<br>

## 프로젝트 구조

```
Server/
├── apigateway-service/      # Spring Cloud Gateway — JWT 검증, 헤더 주입, 라우팅
├── discovery-service/       # Eureka — 서비스 디스커버리
├── user-service/            # 회원 — OAuth(카카오/네이버/구글/애플), JWT, 리프레시 토큰 회전
├── branch-service/          # 지점·직원 — 권한 관리, S3 이미지 업로드, Swagger API 문서
├── workpay-service/         # 급여 정산 + 스케줄 — DDD Value Object, 수당/공제, 급여 확정
├── billing-service/         # 구독 결제 — 토스페이먼츠 빌링키, 보상 트랜잭션, 결제 원장
├── notification-service/    # 알림 — FCM 푸시, BizM 알림톡, Email (RabbitMQ 비동기)
├── docs/                    # 설계 문서, 트러블슈팅 기록
├── portfolio-examples/      # 핵심 패턴 코드 예시 (money-vo 등)
├── docker-compose.yml       # 전체 서비스 + 인프라 컨테이너 정의
└── deploy-canary.sh         # 카나리 무중단 배포 스크립트
```

<br>
<br>

## 주요 성과 요약

| 항목 | Before | After |
|------|--------|-------|
| 급여 관련 문의 | "왜 이 금액이냐" 반복 | 0건 — 계산 근거 구조화 + 확정 시점 스냅샷 보존 |
| 스케줄-급여 데이터 누락율 | 3.44% (Kafka/Outbox 분리 구조) | 0% — 도메인 통합 + 단일 트랜잭션 |
| 결제↔DB 불일치율 | ~30% (Chaos 테스트, 30% DB 장애 주입) | 0% — 보상 트랜잭션으로 자동 복구 |
| 급여 API 응답시간 | 3.2초 (N+1 쿼리 ~954회) | 198ms — 배치 전환으로 I/O 97% 절감 |
| 테스트 실행 시간 (급여 엔진 변경 시) | 38초 (단일 모듈 전체 실행) | 6.21초 — 2-모듈 분리로 변경 영향 한정, 84% 단축 |
| 배포 중 다운타임 | 5~10분 | 무중단 — 카나리 배포 + 자동 롤백 |
| 테스트 | 없음 | 3계층 자동 검증 (도메인 단위 · 유저 시나리오 · DTO 호환성) |
| 알림 응답시간 (최악) | 5초 (외부 API 타임아웃) | 50ms — RabbitMQ 비동기 분리 |
| CS 에러 특정 시간 | 20분 | 1분 — Prometheus + Grafana + Loki |

<br>
<br>

## 원본 소스

이 레포는 실서비스 코드에서 핵심 패턴을 발췌한 포트폴리오 요약 프로젝트입니다.
원본 소스코드는 프라이빗 레포(7개 서비스, Java 파일 670개)에서 관리되고 있으며, 면접 시 커밋 로그 및 코드 구조를 화면 공유로 확인하실 수 있습니다.

| 항목 | 링크 |
|------|------|
| 원본 레포 (Private) | [github.com/hancrysta1/Server](https://github.com/hancrysta1/Server) |
| 직원용 앱 | <!-- 앱스토어 링크 --> |
| 사장용 웹 | <!-- 웹 서비스 URL --> |

<br>
<br>

<br>

---

# 1. 정산 시스템 — 오차 0건, 문의 100% 제거

## 문제

급여 타입 3종(시급/일급/월급) × 수당 4종(야간/연장/휴일/주휴) × 공제 5종(3.3% + 4대보험) 조합의 정산 시스템에서 두 가지 문제가 있었음.

### 문제 1. 소수점 오차 위험

<!-- 캡쳐: 아래 코드 -->

```java
// 기본급 계산하는 곳
baseSalary = hourlyWage.multiply(minutes).divide(60, 0, HALF_UP);     // scale=0

// 야간수당 계산하는 다른 곳
nightAllowance = hourlyWage.multiply(rate).divide(60, 2, HALF_UP);    // scale=2

// 합산하면? 10,030원 + 5,015.00원 → 소수점이 섞임
```

BigDecimal 연산이 서비스 레이어 곳곳에 산재. 개발자마다 scale을 다르게 지정하면 합산 시 소수점 불일치 발생.

### 문제 2. "왜 이 금액이냐" 추적 불가

<!-- 캡쳐: 기존 API 응답 (totalSalary 하나만 반환) -->

API가 최종 금액 하나만 반환. 사장님이 "왜 주휴수당이 안 붙었냐"고 물으면 개발자가 6~8개 DB 테이블을 직접 추적해야 했음 (건당 30분+).

---

## 분석

- 소수점 문제의 근본 원인은 BigDecimal을 직접 다루는 구조. scale/RoundingMode를 개발자가 매번 지정해야 하니 실수가 구조적으로 발생할 수밖에 없음.
- 문의 문제의 근본 원인은 계산 과정이 블랙박스. 결과만 저장하고 "왜 이 결과인지"는 어디에도 없음.
- 두 문제 모두 "계산 로직이 서비스에 흩어져 있고, 계산 근거가 구조화되지 않았다"는 같은 원인에서 비롯.

---

## 해결

### 1) DDD Value Object로 금액 연산 캡슐화

<!-- 캡쳐: 아래 코드 -->

```java
// Money — 어디서 만들든 setScale(0, HALF_UP) 강제. 소수점 불일치가 구조적으로 불가능.
public final class Money {
    private final BigDecimal amount;
    private Money(BigDecimal amount) {
        this.amount = amount.setScale(0, RoundingMode.HALF_UP);
    }
}

// 기본급: 시급 × 근무시간
Money baseSalary = hourlyWage.toMoney().multiplyByMinutes(workedMinutes);

// 야간수당: 시급 × 가산율(DB에서 조회) × 야간시간
Money nightAllowance = hourlyWage.toMoney()
        .multiply(rates.nightRate())
        .multiplyByMinutes(nightMinutes);
```

- Money끼리만 연산 가능 — BigDecimal을 직접 다루는 실수가 타입 시스템으로 차단.
- WorkMinutes에 음수 방지, `exceedsDailyLimit()` (연장수당 8시간 초과 판정) 등 비즈니스 규칙 내장.
- 정책(가산율, 수당 ON/OFF)은 7개 DB 테이블로 분리 — 재배포 없이 런타임 변경 가능.
- 실행 가능한 코드 예시. [money-vo/](./money-vo/) — `./gradlew test`로 22개 테스트 확인 가능.

### 2) 계산 근거 구조화 + 확정 시점 스냅샷

기존 API는 `totalSalary: 80000`만 반환. 개선 후에는 각 단계의 근거를 구조화하여 응답에 포함.

<!-- 캡쳐: 개선된 API 응답 전체 -->

시급 결정 근거 — 5단계 우선순위 중 어디서 시급을 가져왔는지 기록.

```json
{
  "wageReason": {
    "wageType": "MONTHLY",
    "resolvedSource": "salary_history",
    "hourlyWage": 10000,
    "monthlySalary": 2090000
  }
}
```

수당 적용/미적용 근거 — 설정이 ON인데 왜 안 붙었는지까지 기록.

```json
{
  "overtimeReason": {
    "settingEnabled": true,
    "applied": false,
    "notAppliedReason": "DAILY_LIMIT_NOT_EXCEEDED",
    "dailyThresholdMinutes": 480,
    "actualDailyMinutes": 420
  }
}
```

사장님이 "왜 주휴수당이 안 붙었냐"고 물으면 → `notAppliedReason: "WEEKLY_HOURS_NOT_MET"` (주 15시간 미달)이 명세에 표시됨. 개발자가 DB를 뒤질 필요 없이 고객이 직접 확인 가능.

공제 근거 — 세금/4대보험 각각의 적용 여부와 금액.

```json
{
  "deductionReason": {
    "tax": { "enabled": true, "rate": 0.033, "baseAmount": 80000, "deductedAmount": 2640 },
    "insurance": {
      "nationalPensionEnabled": true, "nationalPensionAmount": 5000,
      "healthInsuranceEnabled": true, "healthInsuranceAmount": 3000,
      "employmentInsuranceEnabled": false, "employmentInsuranceAmount": 0,
      "industrialAccidentEnabled": false, "industrialAccidentInsuranceAmount": 0
    }
  }
}
```

확정 시점 스냅샷 — 급여 확정 시 `salary_confirmation.reason_snapshot`에 위 근거 전체를 JSON으로 보존. 나중에 사장님이 수당 설정을 바꿔도, 확정 당시 "어떤 기준으로, 왜 이렇게 계산됐는지" 누구나 확인 가능.

---

## 결과

| 지표 | Before | After |
|---|---|---|
| 급여 관련 문의 | 반복 발생 | 0건 |
| 문의 대응 시간 | 건당 30분+ (DB 직접 추적) | 고객이 명세에서 직접 확인 |
| 과거 급여 근거 소급 | 불가 | 확정 시점 스냅샷으로 보존 |
| 계산 오차 위험 | scale 불일치 가능 | 타입 시스템으로 원천 차단 |
| 단위 테스트 | Spring 기동 필수 (수십 초) | POJO 20케이스, 3초 |

> 상세 문서. [급여 정산 시스템 설계](docs/salary-system-dispute-zero.md) · [DDD 리팩토링](docs/ddd-refactoring-value-object.md) · [쿼리 비용 97% 절감](docs/salary-query-cost-97-percent.md)

<br>
<br>

---

# 2. 결제 정합성 — 불일치율 30% → 0%

## 문제

토스페이먼츠 빌링키 정기결제 스케줄러(매일 03:00)에서, 결제 API 호출은 성공했는데 DB 저장이 실패하면 고객은 과금됐는데 구독 기록이 없는 상태가 됨.

<!-- 캡쳐: 아래 코드 (기존 구조) -->

```java
// 기존 — 결제 성공 후 DB 실패 시 복구 수단 없음
tossPaymentsClient.pay(billingKey, amount);   // ← 돈 빠져나감
subscriptionRepository.save(subscription);     // ← 여기서 실패하면?
// → 고객은 과금됐는데 구독 기록 없음
```

K6 부하 테스트 + Chaos Engineering(30% DB 장애 주입)으로 불일치율 ~30% 확인.

---

## 분석

- 결제 API 호출은 DB 트랜잭션 밖. 결제가 성공하면 돈은 이미 빠져나갔고, DB 실패로 롤백해도 돈은 돌아오지 않음.
- 기존에는 결제 상태를 UPDATE로 덮어쓰는 구조 → "원래 얼마를 결제했고, 언제 취소됐고, 왜 취소됐는지" 추적 불가.
- 같은 취소 요청이 중복으로 들어올 수 있는데, 방지 수단이 없었음.

---

## 해결

### 1) 보상 트랜잭션 — 결제 성공 후 DB 실패 시 자동 환불

<!-- 캡쳐: 아래 코드 -->

```java
try {
    tossPaymentsClient.pay(billingKey, amount);   // 결제 성공
    subscriptionRepository.save(subscription);     // DB 저장 시도
} catch (Exception dbError) {
    // DB 실패 → 즉시 토스 결제 취소 API 호출
    paymentService.cancelPayment(paymentKey, "DB 저장 실패로 인한 자동 환불");
    throw dbError;
}
```

- 코드 발췌. [payment-consistency/](./payment-consistency/)

환불마저 실패하면 CRITICAL 로그 남김. (DLQ 기반 자동 대기열은 미구현, 현재 로그 기반 수동 감지)

### 2) Append-Only 결제 원장

<!-- 캡쳐: 아래 테이블 구조 -->

```
payments 테이블 — UPDATE 없이 새 행으로만 기록
  #1  결제  +9,900원  paymentKey=abc  originalPaymentId=NULL     ← 원거래
  #2  취소  -9,900원  paymentKey=abc  originalPaymentId=1        ← 원거래 참조
```

기존 행을 수정하면 이전 상태가 사라짐. 취소도 원거래를 참조하는 별도 행으로 기록하여 "누가, 언제, 왜" 전수 추적 가능.

### 3) 멱등성 + 에러 분기

```java
// 이미 취소된 결제인지 확인 — 중복 취소 방지
if (paymentRepository.existsByOriginalPaymentId(originalPayment.getId())) {
    return existingCancelPayment;  // 기존 결과 그대로 반환
}
```

- `cancelRequestId`(UUID)로 동일 취소 요청 중복 처리 방지.
- 토스 API 에러를 retryable(서버 에러, 7일 재시도) / non-retryable(카드 문제, 즉시 알림)로 분기.

---

## 결과

| 지표 | Before | After |
|---|---|---|
| 결제↔DB 불일치율 | ~30% (Chaos 테스트) | 0% |
| 결제 실패 복구 | 수동 확인 후 환불 | 보상 트랜잭션으로 자동 복구 |
| 결제 이력 추적 | UPDATE 덮어쓰기 | Append-Only 원장으로 전수 추적 |
| 중복 취소 방지 | 없음 | cancelRequestId 멱등성 |

> 상세 문서. [토스페이먼츠 결제 정합성](docs/toss-payments-consistency-30-to-0.md)

<br>
<br>

---

# 3. 무중단 배포 + 테스트 자동화

## 문제

### 문제 1. 배포 중 다운타임

`docker-compose up -d`로 즉시 교체하는 구조 — 배포마다 5~10분 다운타임 발생. 장애 시 수동으로 이전 이미지를 다시 배포해야 했음.

### 문제 2. 테스트 부재로 인한 장애

테스트 없이 배포하다 직원이 앱에서 급여를 조회할 때 사장 전용 권한 체크를 타서 403 에러 발생 — 다운타임을 아무도 눈치채지 못함.

<!-- 캡쳐: 403 에러 발생 상황 또는 CI 없는 배포 흐름 -->

### 문제 3. 서비스 간 DTO 불일치 장애

branch-service에 필드를 추가했는데 workpay-service의 DTO에는 반영 안 됨 → Feign 호출 시 `UnrecognizedPropertyException` → 관련 기능 전체 장애.

<!-- 캡쳐: 실제 장애 에러 로그 -->

---

## 분석

- 배포 문제. 구버전을 내리고 신버전을 올리는 구조라 교체 시간 동안 서비스 불가. 실패 시 롤백도 수동.
- 테스트 문제. 3단계 권한(사장/관리자/직원) × 2채널(웹/앱) × 3가지 설정(visibility 0/1/2) 조합을 수동으로 매번 확인하는 건 현실적으로 불가능.
- DTO 문제. 7개 서비스가 독립 빌드라 컴파일 에러가 안 남. 런타임에서야 발견됨.

---

## 해결

### 1) 카나리 무중단 배포

```
Phase 1  Docker 이미지 확인
Phase 2  새 버전 기동 (기존 포트 + 1000)
Phase 3  Health Check (2분간 10초 간격)
Phase 4  Eureka 등록 확인 (1분 대기)
Phase 5  트래픽 모니터링 (1분, 50:50)
Phase 6  구버전 제거
Phase 7  태그 전환

※ Phase 3~5 실패 시 자동 롤백 — 구버전 계속 실행
```

### 2) 테스트 계층화

| 계층 | 검증 대상 | 예시 |
|------|----------|------|
| 도메인 단위 | 계산 정합성 | Money VO, 수당/공제 계산, 경계값 |
| 시나리오 | 권한 × 설정 조합 | 사장 급여 확정 → 직원 visibility별 조회 |
| 컨트롤러 | API 요청/응답 | MockMvc standaloneSetup |
| Feign 계약 | 서비스 간 DTO 호환성 | Provider DTO → JSON 자동 생성 → Consumer 역직렬화 검증 |

<br>

### 3) CI 파이프라인에서 배포 전 차단
<!-- 캡쳐: CI에서 잡힌 에러 로그 -->
<img width="800" height="657" alt="O Search loot" src="https://github.com/user-attachments/assets/9eeb2bee-e8ad-4e03-8e8c-944e5fc2e341" />
<img width="800" height="642" alt="image" src="https://github.com/user-attachments/assets/a1aba265-539c-43c7-8a4e-dead38919fbb" />


```
PR to main
  → 변경된 서비스 감지 (7개 중 변경분만)
  → Feign DTO 호환성 검증 (변경된 서비스의 API를 호출하는 모든 서비스를 함께 컴파일하여 시그니처 불일치를 머지 전 차단)
  → 빌드 + 테스트 (실패 시 머지 차단)
  → Docker 이미지 빌드
```

알림 서비스 DTO 시그니처를 변경한 PR에서 호출 측 컴파일 에러를 CI가 머지 전 차단. 수정 과정에서 알림 호출이 회원가입/결제 등 핵심 트랜잭션 `@Transactional` 안에 들어가 있어, 외부 의존성(알림) 실패가 본 비즈니스 트랜잭션을 롤백시킬 수 있는 구조임을 발견. 알림 호출 9곳을 try-catch로 격리하여 외부 의존성 실패와 핵심 비즈니스 로직을 분리.

---

## 결과

| 지표 | Before | After |
|---|---|---|
| 배포 중 다운타임 | 5~10분 | 무중단 (카나리, 자동 롤백) |
| 배포 시간 | 13분 31초 | 3분 24초 |
| 테스트 | CI 미연동 (테스트 부재) | 3계층 자동 검증 — 도메인 단위 + 유저 시나리오 + DTO 호환성 |
| 서비스 간 DTO 불일치 | 배포 후 런타임 장애 | PR 시점 자동 감지 |

> 상세 문서. [CI 테스트 자동화](docs/ci-test-3-layer-validation.md) · [테스트 전략](docs/test-strategy-unit-to-scenario.md) · [카나리 무중단 배포](docs/canary-deploy-zero-downtime.md)


<br>
<br>

---

# 기타 트러블슈팅

| 주제 | Before → After | 문서 |
|------|----------------|------|
| 스케줄-급여 데이터 누락 | 3.44% → 0% (도메인 통합 + 단일 트랜잭션) | [도메인 경계 재설정](docs/domain-boundary-data-loss-3-to-0.md) |
| Refresh Token 레이스 컨디션 | 동시 갱신 시 토큰 폐기 충돌 → SELECT FOR UPDATE로 동시 접근 직렬화 | [레이스 컨디션 해결](docs/refresh-token-race-condition.md) |
| 스케줄 수정 동시성 이슈 | 스케줄·급여가 별도 트랜잭션으로 실행되어 부분 롤백 발생 → MANDATORY 전파로 호출자 트랜잭션 참여 강제 | [스케줄 Race Condition 해결](docs/schedule-race-condition-pessimistic-lock.md) |
| 급여 API 응답시간 | 3.2초 → 198ms (N+1 → 배치, I/O 97% 절감) | [쿼리 최적화](docs/query-optimization-94-percent.md) |
| 테스트 실행 시간 (급여 엔진 변경 시) | 38초 → 6.21초 (84% 단축, 2-모듈 분리 + 변경 영향 한정 실행) | [모듈 분리 테스트 최적화](docs/module-separation-test-optimization.md) |
| 알림 응답시간 | 5초 → 50ms (RabbitMQ 비동기 분리, 99% 개선) | [알림 통합](docs/notification-integration-99-percent.md) |
| CS 에러 특정 | 20분 → 1분 (Prometheus + Grafana + Loki) | [모니터링 구축](docs/monitoring-prometheus-grafana-loki.md) |
| OpenAI API | 비용 46% 절감, 60초 → 1.8초 | [AI 최적화](docs/openai-cost-46-percent-reduction.md) |
| OAuth 보안 | Implicit → Authorization Code Flow | [OAuth 전환](docs/oauth-implicit-to-auth-code.md) |
| Kafka 메모리 | 72% 과소비 → RabbitMQ 전환 | [MQ 전환](docs/kafka-to-rabbitmq-memory.md) |

<br>
<br>

---

# 코드 예시

실서비스 코드는 프라이빗 레포에 있으며, 아래는 핵심 패턴을 발췌한 코드입니다.

| 폴더 | 내용 | 실행 |
|------|------|------|
| [money-vo/](./money-vo/) | 급여 도메인 단일 모듈 — VO(Money, WorkMinutes, Percentage, HourlyWage), [engine/](./money-vo/src/main/java/example/salary/engine)(PayrollEngine, AllowanceCalc, NightWorkCalc), [rates/](./money-vo/src/main/java/example/salary/rates)(연도별 요율 레지스트리 + 세율 JSON 로더), 모델·정책 | `./gradlew test` |
| [payroll-adapter-pattern/](./payroll-adapter-pattern/) | 서비스↔엔진 어댑터 (V1 fallback 브랜칭, 도메인→DTO 변환) | 코드 발췌 |
| [payroll-v1v2-integration-test/](./payroll-v1v2-integration-test/) | H2 통합 테스트로 V1↔V2 급여 계산 결과 동등성 검증 | 코드 발췌 |
| [schedule-locking-pattern/](./schedule-locking-pattern/) | JPA PESSIMISTIC_WRITE 락 + 대타 요청 만료 정리 스케줄러 | 코드 발췌 |
| [atomic-token-rotation/](./atomic-token-rotation/) | Refresh Token 검증+회전 원자적 트랜잭션 + 비관적 락 | 코드 발췌 |
| [transaction-boundary/](./transaction-boundary/) | MANDATORY 전파 + 외부 호출 실패 격리 (try-catch) | 코드 발췌 |
| [payment-consistency/](./payment-consistency/) | 보상 트랜잭션 + Append-Only 결제 원장 + 멱등성 | 코드 발췌 |
| [canary-deploy/](./canary-deploy/) | 7단계 카나리 무중단 배포 셸 스크립트 | 코드 원본 |
| [feign-contract-test/](./feign-contract-test/) | 서비스 간 DTO 호환성 자동 검증 | 코드 원본 |
| [query-optimization/](./query-optimization/) | N+1 → 배치 전환 Before/After | 코드 발췌 |
| [github-actions/](./github-actions/) | CI/CD 파이프라인 (변경 감지 → 계약 검증 → 빌드 → 테스트 → 배포) | 구조 요약 |

<br>
<br>

---

# DDD 적용 요약

DDD를 전면 도입한 건 아니고, 계산 정합성이 핵심인 급여 도메인에 필요한 만큼만 적용.

### 적용한 전술적 패턴 (코드 수준)

| 패턴 | 설명 | 이 프로젝트에서 |
|---|---|---|
| Value Object | 값 자체가 의미를 가지는 불변 객체 | `Money.of(10000).multiplyByMinutes(WorkMinutes.of(480))` — 시급 만원 x 8시간 = 8만원. Money끼리만 연산 가능. |
| Domain Model | 비즈니스 로직을 가진 객체 | `DailySalaryCalculation.calculate(근무시간, 야간분, 휴일여부)` — 기본급→수당→세금 파이프라인을 이 객체가 실행. |
| Aggregate | 반드시 같이 변해야 하는 객체 묶음 | 스케줄 수정 시 급여 삭제/재계산이 하나의 트랜잭션. MANDATORY 전파로 강제. |
| Domain Service | 여러 도메인 개념을 조합하는 로직 | `AllowanceCalculator.calculate(시급, 근무시간, 설정, 가산율)` — 야간/연장/휴일 수당 계산. |
| Domain Event | 도메인에서 일어난 사건을 외부에 알림 | `NotificationEventPublisher`로 급여 확정, 대타 요청 등을 RabbitMQ에 발행. 단, 스케줄→급여는 정합성 문제로 같은 트랜잭션으로 전환. |

### 적용한 전략적 패턴 (구조 수준)

| 패턴 | 설명 | 이 프로젝트에서 |
|---|---|---|
| Bounded Context 재설정 | 같은 말을 같은 의미로 쓰는 범위를 다시 긋는 것 | 스케줄과 급여는 같은 맥락 → 별도 서비스가 아니라 하나의 Context로 재설정. |
| Ubiquitous Language | 코드에 도메인 용어를 그대로 쓰는 것 | `exceedsDailyLimit()`, `overtimeMinutes()`, `TAX_RATE_3_3` — 코드가 곧 업무 규칙서. |

### 적용하지 않은 것

| 패턴 | 이유 |
|---|---|
| Event Sourcing | 도메인 이벤트를 RabbitMQ로 발행하고 있지만, 이벤트 저장 + 상태 재구성까지는 불필요. |
| Repository 추상화 | JPA Repository 직접 사용. 헥사고날 구조는 현재 규모에서 오버엔지니어링. |
| Factory | Builder 패턴으로 충분. 객체 생성이 복잡하지 않음. |
