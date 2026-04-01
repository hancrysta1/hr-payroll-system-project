# CI 테스트 자동화 — 테스트 0개→410개, Feign 계약 테스트 3중 방어

## 문제

- 테스트를 로컬에서만 수동 실행하고 있어 배포 전 테스트 누락이 잦았음
- PR 머지 시 테스트 통과 여부를 강제할 방법이 없었음
- 서비스별 테스트 범위가 불균등했고, 일부 서비스는 테스트가 0건이었음
- 서비스 간 Feign Client(HTTP 기반 서비스 간 호출 라이브러리) DTO 불일치를 배포 전에 감지할 수 없었음

---

## 분석

테스트를 CI에 통합하되, 빌드 실패와 테스트 실패를 분리해서 확인할 수 있는 구조가 필요했다. 빌드는 통과했는데 테스트만 실패하면 컴파일 자체는 되지만 로직에 문제가 있다는 뜻이다. 또한 서비스 간 Feign Client DTO 불일치를 런타임이 아닌 PR 시점에 감지하려면 계약 테스트가 필요했다.

---

## 해결

GitHub Actions CI 파이프라인(`action.yml`)에 서비스별 테스트 실행 단계를 추가했음. PR → main 브랜치 시 자동 실행되고, 테스트 실패 시 머지가 차단됨.

### CI 파이프라인 구조

```
PR to main
    │
    ▼
① 변경 감지 — 어떤 서비스가 변경됐는지 감지 (7개 서비스)
    │
    ▼
② DTO 계약 검증 — 모든 PR에서 항상 실행 (어떤 서비스가 바뀌든)
    ├─ generate-contract-fixtures.sh — Provider DTO 소스 → JSON 자동 생성
    ├─ feign-contract-check.sh — Provider/Consumer DTO 필드명 비교 (1초 미만)
    └─ FeignContractTest — 자동 생성 JSON → Consumer DTO 역직렬화 검증 (1초 미만)
    ※ 실패 시 이후 단계 전체 차단
    │
    ▼
③ 빌드 — 변경된 서비스만 빌드 (gradlew clean build -x test)
    │
    ▼
④ 테스트 — 서비스별 테스트 실행
    │
    ▼
⑤ 배포 — Docker 이미지 생성 → EC2 카나리 배포
```

### 서비스별 테스트 현황

| 서비스 | 테스트 파일 | 테스트 메서드 | CI 실행 | 커버리지 |
|---|---|---|---|---|
| workpay-service | 44개 | 358개 | O (14개 패턴 + 계약 테스트) | JaCoCo 적용 |
| branch-service | 5개 | 45개 | O (통합 테스트 + 계약 테스트) | - |
| user-service | 2개 | 7개 | O (통합 테스트) | - |
| billing-service | - | - | X | - |
| notification-service | - | - | X | - |
| apigateway-service | - | - | X (빌드만) | - |
| discovery-service | - | - | X (빌드만) | - |
| 합계 | 51개 | 410개 | 3개 서비스 | |

### workpay-service 테스트 구성 (가장 포괄적)

| 테스트 유형 | 파일 수 | 내용 |
|---|---|---|
| 도메인 단위 테스트 | MoneyTest, HourlyWageTest, DailySalaryCalculationTest 등 | VO/도메인 모델 계산 정합성 |
| 서비스 테스트 | SalaryCalculateServiceTest, AllowanceCalculatorTest 등 | 급여 계산 로직 |
| 컨트롤러 테스트 | SalaryAppControllerTest, SalaryWebControllerTest 등 | API 요청/응답 검증 |
| 통합 테스트 | SalaryEmployeeFlowIntegrationTest, SalaryOwnerFlowIntegrationTest | 직원/사장 시나리오 E2E |
| DTO 검증 테스트 | PayTypeWageValidatorTest | 급여 타입별 교차 필드 검증 |
| Feign 계약 테스트 | FeignContractTest (11개 테스트) | 서비스 간 DTO 역직렬화 호환성 검증 |

### JaCoCo 커버리지 설정

workpay-service에 적용했고, 보고서 형식은 XML + HTML + CSV.

테스트 의미가 없는 코드는 제외 대상으로 설정했음.
- DTO (`*/dto/**`)
- JPA Q클래스 (`*/domain/Q*`)
- Config (`*/config/**`)
- Exception (`*/exception/**`)

## 결과

### 정량 성과

| 지표 | Before | After |
|---|---|---|
| 테스트 실행 | 로컬 수동 | PR 시 자동 (GitHub Actions) |
| 테스트 메서드 수 | 0개 | 408개 |
| 테스트 파일 수 | 0개 | 50개 |
| CI에서 테스트 실행 서비스 | 0개 | 3개 (workpay, user, branch) |
| 도메인 단위 테스트 실행 시간 | Spring 기동 필요 (수십 초) | 3초 (순수 Java) |
| 커버리지 리포트 | 없음 | JaCoCo (workpay-service) |
| 배포 전 테스트 실패 감지 | 불가 (수동 의존) | PR 머지 차단 |
| 서비스 간 DTO 불일치 감지 | 배포 후 런타임 장애 | PR 시점 자동 감지 (계약 테스트) |

---

### 실전 사례 1. 알림 메시지 수정이 급여 확정 장애로 이어질 뻔한 케이스

### CI에서 에러 발생

푸시 알림 개편 중, 알림 메시지에 `[지점명]`을 붙이기 위해 `publishSalaryConfirmed` 메서드에 `branchName` 파라미터를 추가했음. 프로덕션 코드 호출부는 전부 수정했지만, 테스트 코드의 mock verify 호출을 누락한 채 PR을 올렸음.

CI가 다음 에러를 잡았음.

```
> Task :compileTestJava FAILED

SalaryConfirmationServiceTest.java:163: error:
  method publishSalaryConfirmed in class NotificationEventPublisher
  cannot be applied to given types;
    verify(notificationEventPublisher, never())
        .publishSalaryConfirmed(anyLong(), anyLong(), any(), any());
  required: Long, Long, String, LocalDate, LocalDate
  found:    long, long, Object, Object
  reason: actual and formal argument lists differ in length
```

`publishSalaryConfirmed`가 4개 → 5개 파라미터로 바뀌었는데, 테스트의 `verify()`는 옛날 시그니처 그대로였음. CI에 `compileTestJava` 단계가 있었기 때문에 PR 시점에 머지가 차단됐음. 테스트가 없었으면 프로덕션 코드끼리는 시그니처가 맞으므로, 빌드도 통과하고 배포까지 그대로 나갔을 것.

### 코드를 다시 보니 더 큰 문제가 있었음

에러를 수정하면서 프로덕션 코드를 다시 살펴봤음. 급여 확정 서비스 구조가 이랬음.

```java
@Transactional  // ← 전체가 하나의 트랜잭션
public ConfirmationResult confirmBulkSalaries(Long branchId, ...) {

    // 1~8. 급여 확정 핵심 로직 (DB 저장)
    createBulkConfirmations(branchId, startDate, endDate, ...);
    saveBranchBulkConfirmation(branchId, startDate, endDate, null);

    // 9. 알림 발행 — 여기서 예외가 터지면?
    String branchName = branchServiceClient.getBranch(branchId).getName();
    for (Long userId : foundUserIds) {
        notificationEventPublisher.publishSalaryConfirmed(userId, branchId, branchName, ...);
    }

    return ConfirmationResult.success();
}
```

`@Transactional`로 묶여 있어서, 알림 발행 코드(지점명 조회, RabbitMQ publish)에서 예외가 터지면 메서드 전체가 롤백됨. 이미 DB에 저장한 급여 확정 레코드까지 전부 없던 일이 되는 구조였음.

알림만 안 가는 게 아니었음. 같은 패턴이 다른 핵심 기능에도 있었음.

| 메서드 | @Transactional | 알림 실패 시 실제 영향 |
|---|---|---|
| `confirmBulkSalaries` (전체 급여 확정) | O | 확정 레코드 롤백 — 사장님이 급여 확정을 눌러도 반영 안 됨 |
| `confirmIndividualSalary` (개별 급여 확정) | O | 해당 직원 급여 확정 불가 |
| `approveShiftRequest` (대타 승인) | O | 승인 + 스케줄 변경 롤백 — 대타 승인했는데 스케줄이 안 바뀜 |
| `acceptShiftRequest` (대타 수락) | O | 수락 롤백 — 수락이 반영 안 됨 |
| `rejectShiftRequest` (대타 거절) | O | 거절 롤백 |
| `approveScheduleRequest` (근무 요청 승인) | O | 승인 롤백 |
| `rejectScheduleRequest` (근무 요청 거절) | O | 거절 롤백 |

의도는 알림 메시지에 `[지점명]`을 붙이고 수신 대상을 바꾸는 간단한 수정이었음. 하지만 이 변경이 급여 확정, 대타 승인, 근무 스케줄 변경까지 전부 망가뜨릴 수 있는 구조였음.

### 해결

프로덕션 코드에서 알림 발송부를 try-catch로 격리했음. 알림이 실패해도 핵심 로직은 정상 커밋됨.

```java
@Transactional
public ConfirmationResult confirmBulkSalaries(Long branchId, ...) {
    // 핵심 로직
    createBulkConfirmations(branchId, startDate, endDate, ...);
    saveBranchBulkConfirmation(branchId, startDate, endDate, null);

    // 알림 발행 — 실패해도 확정에는 영향 없음
    try {
        String branchName = branchServiceClient.getBranch(branchId).getName();
        for (Long userId : foundUserIds) {
            notificationEventPublisher.publishSalaryConfirmed(userId, branchId, branchName, ...);
        }
    } catch (Exception e) {
        log.error("급여 확정 알림 발행 실패 - branchId:{}, error:{}", branchId, e.getMessage());
    }

    return ConfirmationResult.success();
}
```

`@Transactional` 안에 있는 알림 호출 9곳 전부 동일하게 try-catch를 적용했음.

테스트 코드에서는 `BranchServiceClient` mock을 추가하고 verify 시그니처를 업데이트했음.

```java
@Mock
private BranchServiceClient branchServiceClient;

@BeforeEach
void setUp() {
    BranchInfoDTO branchInfo = BranchInfoDTO.builder()
        .id(branchId).name("테스트지점").build();
    lenient().when(branchServiceClient.getBranch(anyLong()))
        .thenReturn(branchInfo);
}

// 파라미터 5개로 맞춤
verify(notificationEventPublisher, never())
    .publishSalaryConfirmed(anyLong(), anyLong(), any(), any(), any());
verify(notificationEventPublisher)
    .publishSalaryConfirmed(eq(1L), eq(branchId), any(), eq(startDate), eq(endDate));
```

### 테스트 코드가 없었다면

| 단계 | 테스트 + CI 있음 | 테스트 없음 |
|---|---|---|
| PR 시점 | 컴파일 에러로 머지 차단 | 검증 없이 머지 |
| 배포 | 에러 수정 후 정상 배포 | 그대로 운영 배포 |
| 운영 | 정상 동작 | 알림 발송에서 예외 발생 시 급여 확정 자체가 롤백 |
| 발견 시점 | 개발 중 (분 단위) | 사장님 문의 후 (시간~일 단위) |

테스트가 없었으면 CI가 잡을 게 없었음. 배포까지 그대로 나가고, 알림 발송 코드에서 예외가 터지는 시점에 급여 확정이 롤백되는 장애가 발생했을 것. try-catch 없이 `@Transactional` 안에서 외부 호출을 하고 있었기 때문. 테스트 코드의 컴파일 에러가 이 구조적 문제를 들여다보는 계기가 된 셈.

### 교훈

알림 서비스를 별도 마이크로서비스(notification-service)로 분리했더라도, 알림을 트리거하는 코드는 비즈니스 서비스 안에 있음. 이 트리거 코드가 `@Transactional` 안에서 외부 호출(Feign, RabbitMQ)을 하면, 부가 기능의 실패가 핵심 기능을 롤백시킴. 서비스 분리와 관계없이, 트랜잭션 경계 안의 외부 호출은 반드시 try-catch로 격리해야 함.

---

### 실전 사례 2. Feign Client DTO 불일치로 인한 서비스 간 통신 장애

### 장애 상황

크루이지 앱에서 근무 요청을 보내면 "대타수락 실패"라는 에러가 발생했음. 에러 메시지가 근무 요청과 관련 없어 보여서 원인 추적에 시간이 걸렸음.

원인은 서비스 간 DTO 불일치였음.

```
branch-service의 BranchInfoDTO에 weekStartDay, salaryVisibility 필드 추가
  → workpay-service의 BranchInfoDTO에는 해당 필드 없음
  → Jackson이 알 수 없는 필드를 만나 UnrecognizedPropertyException 발생
  → branchServiceClient.getBranch() 호출이 실패
  → BranchInfo를 조회하는 모든 기능 장애 (대타수락, 근무요청, 급여 확정 등)
```

각 서비스가 독립 빌드이므로 branch-service에 필드를 추가해도 workpay-service의 컴파일은 정상 통과함. 런타임에 Feign 호출이 실패해야 문제를 알 수 있었음. 서비스가 7개, Feign Client가 15개인 상황에서 필드 하나 바꿀 때마다 모든 Consumer를 수동으로 확인하는 것은 현실적이지 않았음.

### 근본 원인 분석

마이크로서비스 간 Feign Client 통신에서 DTO 불일치가 발생하는 경로는 세 가지.

| 변경 유형 | 증상 | 위험도 |
|---|---|---|
| Provider가 필드 추가 | Consumer에 해당 필드 없으면 `UnrecognizedPropertyException` → 500 에러 | 즉시 장애 |
| Provider가 필드 삭제/이름 변경 | Consumer는 에러 없이 null을 받음 → 다른 곳에서 NPE | 지연 장애 (추적 어려움) |
| Consumer에 `@JsonIgnoreProperties` 누락 | 위의 "필드 추가" 경우에 크래시 | 잠재적 장애 |

세 가지 모두 컴파일 에러가 나지 않으므로, 테스트 없이는 배포 후에야 발견됨.

### 해결. 서비스 간 DTO 호환성 자동 검증

Provider(데이터를 보내는 서비스)의 DTO와 Consumer(데이터를 받는 서비스)의 DTO가 서로 맞는지를 CI에서 자동으로 검증하는 구조를 만들었음. Provider DTO 소스에서 JSON을 자동 생성하고, 그 JSON을 Consumer DTO로 역직렬화(JSON 문자열을 Java 객체로 변환)해서 모든 필드가 정상 매핑되는지 확인함. 사람이 직접 확인하지 않아도, DTO가 어긋나면 PR 시점에 CI가 자동으로 잡아냄.

#### 전체 구조

모노레포(한 레포에 모든 서비스)이므로 CI에서 Provider 소스를 직접 읽어 Consumer DTO와 자동 크로스 검증할 수 있음. 멀티레포(서비스마다 별도 레포)였다면 Pact, Spring Cloud Contract 같은 별도 도구가 필요하지만, 모노레포에서는 셸 스크립트와 JUnit만으로 충분함.

```
CI 시작 (PR to main, 어떤 서비스가 바뀌든 항상 실행)
    │
    ▼
① generate-contract-fixtures.sh
   Provider DTO 소스 파일을 직접 읽어서 JSON fixture 자동 생성
   (사람이 JSON을 관리할 필요 없음)
    │
    │   branch-service/dto/BranchInfoDTO.java ──→ workpay-service/test/contracts/branch-BranchInfoDTO.json
    │   user-service/dto/UserInfoDTO.java     ──→ workpay-service/test/contracts/user-UserInfoDTO.json
    │   billing-service/dto/CouponResponseDTO ──→ branch-service/test/contracts/billing-CouponResponseDTO.json
    │                ↑ Provider 소스                          ↑ Consumer 테스트용 fixture
    ▼
② feign-contract-check.sh
   Provider/Consumer DTO 소스의 필드명을 비교 (1초 미만)
    │
    ▼
③ FeignContractTest (workpay-service + branch-service)
   자동 생성된 JSON → Consumer DTO로 Jackson 역직렬화 → 전체 필드 assertNotNull
    │
    ▼
실패 시 빌드/배포 차단
```

Provider DTO가 바뀌면 사람이 할 일은 없음. `generate-contract-fixtures.sh`가 Provider 소스를 직접 읽어서 JSON을 재생성하고, `FeignContractTest`가 Consumer DTO와 맞는지 자동 검증함.

#### 1단계. `@JsonIgnoreProperties(ignoreUnknown = true)` — 런타임 크래시 방지

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public class BranchInfoDTO {
    private Long id;
    private String name;
    // ...
}
```

Jackson(JSON 직렬화 라이브러리)의 기본 동작은 DTO에 없는 필드가 JSON에 있으면 `UnrecognizedPropertyException`을 던지는 것. 이 어노테이션을 붙이면 모르는 필드는 무시하고 나머지만 매핑함.

이것만으로 충분하지 않은 이유는, Provider가 기존 필드를 삭제하거나 이름을 바꾸면 Consumer는 에러 없이 null을 받기 때문. 크래시는 안 나지만 잘못된 데이터가 조용히 흘러가므로, 실제 역직렬화를 검증하는 테스트가 필요했음.

모든 Consumer DTO에 적용 완료했음.

| Consumer DTO | 서비스 |
|---|---|
| `calendar/dto/BranchInfoDTO` | workpay-service |
| `salary/dto/BranchInfoDTO` | workpay-service |
| `calendar/dto/UserInfoDTO` | workpay-service |
| `salary/dto/UserInfoDTO` | workpay-service |
| `salary/dto/WorkerDTO` | workpay-service |
| `salary/dto/UserWageSettingsDTO` | workpay-service |
| `salary/dto/UserInfoWithCreatedAtDTO` | workpay-service |
| `CouponServiceClient.CouponResponseDTO` (inner class) | branch-service |
| `ReferralServiceClient.ReferralInfo` (inner class) | billing-service |

#### 2단계. `generate-contract-fixtures.sh` — Provider DTO → JSON 자동 생성

Provider DTO Java 소스에서 필드와 타입을 파싱해서 테스트용 JSON fixture를 자동 생성함. CI에서 매번 실행되므로 Provider가 바뀌면 JSON도 자동으로 바뀜.

```bash
# Provider DTO 소스에서 필드 추출 → 타입에 맞는 더미 값으로 JSON 생성
# Long → 1, String → "test_name", BigDecimal → 10000, List<String> → ["test"] ...

generate_json \
    "$REPO_ROOT/branch-service/src/main/java/com/epicode/dto/BranchInfoDTO.java" \
    "$WORKPAY_OUT/branch-BranchInfoDTO.json"
```

생성되는 JSON 예시 (branch-service `BranchInfoDTO` 기준).

```json
{
  "id": 1,
  "name": "test_name",
  "address": "test_address",
  "dialNumbers": "test_dialNumbers",
  "basicCost": 10000,
  "openTime": "test_openTime",
  "endTime": "test_endTime",
  "roles": ["test"],
  "weekStartDay": "test_weekStartDay",
  "salaryVisibility": 1
}
```

크로스 서비스 fixture 생성 목록.

| Provider 소스 (읽는 파일) | Consumer fixture (생성되는 파일) |
|---|---|
| `branch-service/dto/BranchInfoDTO.java` | `workpay-service/test/contracts/branch-BranchInfoDTO.json` |
| `branch-service/dto/WorkerDTO.java` | `workpay-service/test/contracts/branch-WorkerDTO.json` |
| `branch-service/dto/UserWageSettingsResponseDTO.java` | `workpay-service/test/contracts/branch-UserWageSettingsResponseDTO.json` |
| `user-service/dto/UserInfoDTO.java` | `workpay-service/test/contracts/user-UserInfoDTO.json` |
| `user-service/dto/UserWageSettingsDTO.java` | `workpay-service/test/contracts/user-UserWageSettingsDTO.json` |
| `billing-service/dto/CouponResponseDTO.java` | `branch-service/test/contracts/billing-CouponResponseDTO.json` |

#### 3단계. `FeignContractTest` — Jackson 역직렬화 계약 테스트 (핵심)

자동 생성된 JSON fixture를 Consumer DTO로 역직렬화하고, 모든 필드가 정상 매핑되는지 assert하는 테스트. Spring Context 없이 순수 ObjectMapper만 쓰므로 실행 시간 1초 미만.

```java
// 자동 생성된 JSON fixture를 읽어서 Consumer DTO로 역직렬화
String json = loadFixture("branch-BranchInfoDTO.json");
var dto = mapper.readValue(json, BranchInfoDTO.class);

// 모든 필드가 null이 아닌지 검증 — Provider가 필드를 삭제/변경하면 여기서 실패
assertAll(
    () -> assertNotNull(dto.getId(), "id"),
    () -> assertNotNull(dto.getName(), "name"),
    () -> assertNotNull(dto.getWeekStartDay(), "weekStartDay"),
    () -> assertNotNull(dto.getSalaryVisibility(), "salaryVisibility")
);
```

2개 서비스에서 실행함.

workpay-service (`FeignContractTest` — 11개 테스트).

| Provider | Consumer DTO | 테스트 내용 |
|---|---|---|
| branch-service `BranchInfoDTO` | calendar `BranchInfoDTO` | 전체 10개 필드 매핑 |
| branch-service `BranchInfoDTO` | salary `BranchInfoDTO` | 전체 10개 필드 매핑 |
| branch-service `BranchInfoDTO` | 양쪽 | 미지의 필드 추가 시 크래시 안 남 |
| branch-service `WorkerDTO` | salary `WorkerDTO` | 핵심 10개 필드 매핑 |
| user-service `UserInfoDTO` | calendar `UserInfoDTO` | 전체 5개 필드 매핑 |
| user-service `UserInfoDTO` | salary `UserInfoDTO` | 전체 5개 필드 매핑 |
| user-service `UserInfoDTO` | calendar `UserInfoDTO` | batch(List) 응답 역직렬화 |
| user-service `UserWageSettingsDTO` | salary `UserWageSettingsDTO` | user-service 제공 응답 |
| branch-service `UserWageSettingsResponseDTO` | salary `UserWageSettingsDTO` | branch-service 제공 응답 |
| branch-service batch | salary `UserWageSettingsDTO` | Map<Long, DTO> 역직렬화 |
| user-service `UserInfoDTO` | salary `UserInfoWithCreatedAtDTO` | Provider가 안 보내는 필드 null 확인 |

branch-service (`FeignContractTest` — 2개 테스트).

| Provider | Consumer DTO | 테스트 내용 |
|---|---|---|
| billing-service `CouponResponseDTO` | `CouponServiceClient.CouponResponseDTO` | 전체 10개 필드 매핑 |
| billing-service `CouponResponseDTO` | `CouponServiceClient.CouponResponseDTO` | 미지의 필드 추가 시 크래시 안 남 |

user-service의 Feign Client(6개)는 전부 void/BigDecimal/Map 반환이라 DTO 역직렬화 이슈가 없어서 계약 테스트 대상에서 제외했음.

#### 보조. `feign-contract-check.sh` — 필드명 정적 비교

JUnit 테스트와 별개로, Provider/Consumer DTO 소스의 필드명을 grep으로 비교하는 셸 스크립트. JVM 기동 없이 1초 미만에 실행됨.

### 이 검증이 방지하는 시나리오

시나리오 1. Provider가 필드 추가 (이번에 발생한 장애).

```
branch-service BranchInfoDTO에 weekStartDay 추가
  → generate-contract-fixtures.sh: Provider 소스에서 weekStartDay 포함된 JSON 자동 생성
  → FeignContractTest: Consumer DTO에 weekStartDay 없으면 assertNotNull 실패
  → CI 차단, 배포 안 됨
```

시나리오 2. Provider가 필드 삭제/이름 변경.

```
user-service UserInfoDTO에서 phoneNums → phoneNumber로 변경
  → generate-contract-fixtures.sh: phoneNumber로 JSON 자동 생성 (phoneNums 없음)
  → FeignContractTest: Consumer DTO의 phoneNums가 null → assertNotNull 실패
  → CI 차단, 배포 안 됨
  → @JsonIgnoreProperties만 있었으면 에러 없이 null 전달 → NPE 장애
```

시나리오 3. Consumer DTO에 @JsonIgnoreProperties 깜빡한 경우.

```
새 Feign Client 추가, Consumer DTO에 @JsonIgnoreProperties 안 붙임
  → FeignContractTest: unknownFields() 실패 → CI 차단
  → feign-contract-check.sh: ERROR "@JsonIgnoreProperties 누락" → CI 차단
```

세 시나리오 모두 사람이 깜빡해도 CI가 자동으로 잡음. JSON fixture가 Provider 소스에서 자동 생성되기 때문.

### 모노레포이기 때문에 가능한 구조

이 방식은 모노레포(한 레포에 모든 서비스)에서만 동작함. `generate-contract-fixtures.sh`가 다른 서비스의 소스 파일을 직접 읽기 때문. 서비스마다 별도 레포인 멀티레포 구조에서는 Provider 소스를 직접 읽을 수 없으므로 Pact, Spring Cloud Contract 같은 별도 계약 관리 도구가 필요함.

| | 모노레포 (현재 구조) | 멀티레포 |
|---|---|---|
| 크로스 검증 | 스크립트가 옆 폴더 소스 읽으면 됨 | 다른 레포 소스를 못 읽음 |
| 도구 | 셸 스크립트 + JUnit | Pact, Spring Cloud Contract 등 |
| 계약 관리 | 자동 (소스에서 생성) | 별도 계약 파일/서버 필요 |

### 새 Feign Client 추가 시

1. `scripts/generate-contract-fixtures.sh`에 `generate_json` 호출 추가
2. 해당 서비스의 `FeignContractTest.java`에 역직렬화 + assert 추가
3. `scripts/feign-contract-check.sh`에 `check_contract` 호출 추가

```bash
# generate-contract-fixtures.sh
generate_json \
    "$REPO_ROOT/provider-service/.../ProviderDTO.java" \
    "$CONSUMER_OUT/provider-ProviderDTO.json"
```

```java
// FeignContractTest.java
@Test
void newConsumerDTO() throws Exception {
    String json = loadFixture("provider-ProviderDTO.json");
    var dto = mapper.readValue(json, NewConsumerDTO.class);
    assertAll(
        () -> assertNotNull(dto.getField1(), "field1"),
        () -> assertNotNull(dto.getField2(), "field2")
    );
}
```

### 계약 테스트가 없었다면

| 단계 | 계약 테스트 + CI 있음 | 없음 |
|---|---|---|
| PR 시점 | DTO 불일치 자동 감지 → 머지 차단 | 검증 없이 머지 |
| 배포 | DTO 동기화 후 정상 배포 | 그대로 운영 배포 |
| 운영 | 정상 동작 | Feign 호출 실패 → 관련 기능 전체 장애 |
| 발견 시점 | 개발 중 (분 단위) | 사용자 신고 후 (시간~일 단위) |
| 사람 개입 | 불필요 (자동 생성 + 자동 검증) | 매번 수동 확인 필요 |

### 돌아보며

TDD는 아니었음. 기능 먼저 만들고, 장애 터지고 나서 테스트를 붙였음.

- 급여 계산 테스트 → 소수점 오차로 급여가 틀리게 나간 후
- 알림 시그니처 테스트 → CI가 컴파일 에러 잡아서 `@Transactional` 롤백 문제 발견한 후
- Feign 계약 테스트 → BranchInfoDTO 불일치로 앱 기능 전체 장애 난 후

다음에는 핵심 로직부터 테스트를 먼저 작성하는 게 목표.

### 기술 스택

GitHub Actions, Gradle, JUnit 5, JaCoCo, Jakarta Bean Validation, Jackson ObjectMapper
