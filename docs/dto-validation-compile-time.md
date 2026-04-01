# DTO 매핑 검증 — 런타임 에러를 배포 전 차단

## 문제

새 컬럼/필드 추가 시 DTO와 Entity 간 매핑 누락으로 데이터 삽입/조회가 제대로 이루어지지 않는 문제가 반복 발생했다.

- 응답에 새 필드가 null로 내려오거나, 요청에서 받은 값이 Entity에 반영되지 않음
- 컴파일 에러가 나지 않으므로 런타임에서야 발견되며, 배포 후 장애로 이어질 수 있음
- 급여 시스템처럼 직원별 설정이 많은 경우(급여 타입 3종 x 수당 4종 x 공제 5종) 필드 누락 가능성이 높음

## 분석

매핑 로직이 서비스 레이어 곳곳에 흩어져 있어서, 필드를 추가할 때 어디를 수정해야 하는지 한눈에 파악하기 어려웠다. Entity에 컬럼을 하나 추가하면 서비스 코드 여러 곳을 찾아 수정해야 했고, 하나라도 빠지면 런타임에서야 NPE나 null 응답으로 나타났다.

또한 급여 타입에 따라 필수 필드가 달라지는 교차 검증 요구사항이 있었는데, Jakarta Bean Validation의 `@NotNull` 같은 단일 필드 어노테이션만으로는 처리할 수 없었다. 이런 조건 분기를 서비스 레이어에서 if-else로 처리하다 보니 검증 로직이 비즈니스 로직과 뒤섞이고, 누락 시 500 에러(NPE)가 그대로 클라이언트에 노출되었다.

## 해결

### 1. 수동 Mapper 패턴으로 매핑 책임 분리

매핑 로직을 서비스 레이어에서 분리하여 전용 Mapper 클래스로 이관했다.

| Mapper | 역할 |
|---|---|
| `SalaryMapper` | DailySalaryCalculation(도메인 모델) → BigDecimal(DB 저장용) 변환 |
| `AllowanceSettingsMapper` | JPA Entity(UserSalaryAllowances + UserSalaryDeductions) → AllowanceSettings(도메인 모델) 변환 |

`final class` + `static method`로 구현하여 상태 없이 순수 변환만 수행한다. 서비스에서 매핑이 산재되던 것을 한 곳에서 관리할 수 있게 되었다.

### 2. 커스텀 Validator로 DTO 교차 필드 검증

급여 설정 DTO는 급여 타입에 따라 필수 필드가 달라지는 구조다.

- HOURLY → hourlyWage 필수
- DAILY → dailyWage 필수
- MONTHLY → monthlyWage 필수

Jakarta Bean Validation의 `@NotNull`만으로는 이런 교차 검증이 불가능하여 커스텀 어노테이션을 구현했다.

```
@ValidPayTypeWage   ← 커스텀 어노테이션
PayTypeWageValidator ← 급여 타입별 필수 필드 교차 검증
```

`PayTypeWageValidatorTest`에서 8개 케이스로 급여 타입별 유효/무효 시나리오를 검증한다.

### 3. Builder 패턴 + 검증 어노테이션 표준화

전체 DTO에 `@Builder` + Jakarta Validation을 적용했다.

- `@NotNull`, `@NotBlank` 등 표준 어노테이션으로 필수값 검증
- Controller에서 `@Valid`로 요청 시점에 검증 실행
- 검증 실패 시 400 Bad Request로 즉시 반환하며, 서비스 레이어까지 도달하지 않음

## 결과

| 지표 | Before | After |
|---|---|---|
| 매핑 누락 발견 시점 | 런타임 (배포 후) | 테스트/검증 시점 (배포 전) |
| 매핑 로직 위치 | 서비스 레이어에 산재 | Mapper 클래스에 집중 |
| 교차 필드 검증 | 서비스에서 if-else | 커스텀 어노테이션 + Validator |
| 검증 실패 응답 | 500 에러 (NPE 등) | 400 Bad Request (명확한 메시지) |
