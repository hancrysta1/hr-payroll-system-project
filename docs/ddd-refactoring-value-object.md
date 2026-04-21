# 급여 도메인 DDD 리팩토링 -- 계산 로직 캡슐화 및 테스트 가능한 구조

> 작성일: 2026-03-04 (최종 업데이트: 2026-03-27)
> 대상 모듈: `workpay-service` / `com.workpay.salary`

---

## 문제

서비스 레이어(`SalaryCalculateService`, 2,100줄)에 모든 급여 계산 로직이 산재되어 있었다. BigDecimal 직접 연산, 하드코딩된 매직넘버(`480`, `12540`, `0.033`), 계산+I/O+저장 혼합 구조로 인해 다음과 같은 문제가 있었다.

- scale 값이 곳마다 다름 (0, 2) -- 일관성 없음
- `LegalAllowanceConstants`에는 시간 단위(8), 서비스 코드는 분 단위(480) -- 불일치
- 비율이 `50.0`(%)과 `0.5`(소수) 두 형태로 존재
- 계산 결과의 중간값(기본급, 가산수당, 세금)을 추적할 수 없음
- Spring 컨텍스트 없이 단위 테스트 불가 -- 기존 테스트는 `@Mock` 8개 + 수십 줄의 `when()` 설정이 필요

```
Controller → DTO → Service(비즈니스 로직 산재) → JPA Entity → Repository → DB
                     ↑
                 BigDecimal 직접 연산
                 하드코딩된 매직넘버
                 계산 + I/O + 저장 혼합
```

---

## 분석

DDD 원칙(Value Object, Domain Model, 계층 분리)으로 순수 계산 로직을 도메인 모델에 캡슐화하고, 하드코딩된 정책을 DB 테이블로 이동하여 직원별 다른 계산 공식(시급/일급/월급)과 수당/공제 설정을 데이터로 처리하는 구조를 목표로 했다.

```
Controller → DTO → Service(얇은 조율, I/O)
                        ↓
                   Domain Model(로직 내장)
                     ├── Value Object (Money, WorkMinutes, Percentage, HourlyWage)
                     └── Domain Model (DailySalaryCalculation, AllowanceSettings)
                        ↓
                   Mapper → JPA Entity → Repository → DB
```

서비스 레이어는 조율(orchestration) 역할만 수행하고, 순수 계산 로직은 도메인 모델에 캡슐화된다.

변경 요약

| 구분 | 파일 수 | 설명 |
|------|---------|------|
| 신규 Value Object | 4개 | `Money`, `WorkMinutes`, `Percentage`, `HourlyWage` |
| 신규 Domain Model | 2개 | `DailySalaryCalculation`, `AllowanceSettings` |
| 신규 Mapper | 2개 | `SalaryMapper`, `AllowanceSettingsMapper` |
| 수정 Service | 2개 | `SalaryCalculateService`, `PayTypeWageCalculator` |
| 신규 Test | 3개 | `MoneyTest`, `HourlyWageTest`, `DailySalaryCalculationTest` |
| 변경 없음 | Controller 29개, DTO 80+개, JPA Entity 33개, Repository 21개, FeignClient 4개 |

---

## 해결

### 패키지 구조

```
com.workpay.salary.domain/
  ├── (기존 JPA Entity 33개 — 변경 없음)
  ├── vo/                           ← NEW
  │   ├── Money.java                   금액 VO
  │   ├── WorkMinutes.java             근무시간 VO
  │   ├── Percentage.java              비율 VO
  │   └── HourlyWage.java             통상시급 VO
  ├── model/                        ← NEW
  │   ├── AllowanceSettings.java       수당 설정 도메인 모델
  │   └── DailySalaryCalculation.java  일급여 계산 도메인 모델
  └── mapper/                       ← NEW
      ├── SalaryMapper.java            도메인 → DB값 변환
      └── AllowanceSettingsMapper.java JPA 엔티티 → 도메인 변환
```

### Phase 1. Value Objects (4개)

#### Money -- 금액 VO

`BigDecimal` 금액을 래핑하여 KRW 기준 연산을 보장한다 (scale=0, HALF_UP).

```java
public final class Money {
    public static final Money ZERO = new Money(BigDecimal.ZERO);
    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = amount.setScale(0, RoundingMode.HALF_UP);  // KRW 기준 통일
    }

    public static Money of(BigDecimal amount) { ... }
    public static Money of(long amount) { ... }

    public Money add(Money other) { ... }
    public Money subtract(Money other) { ... }
    public Money multiply(Percentage percentage) { ... }            // 시급 x 50% 가산율
    public Money multiplyByMinutes(WorkMinutes minutes) { ... }     // 시급 x 근무분 / 60
    public Money applyTax(Percentage taxRate) { ... }               // 세금 차감 후 금액
    public Money taxAmount(Percentage taxRate) { ... }              // 세금 금액만
}
```

Before -> After

```java
// Before
BigDecimal nightAllowance = settings.hourlyWage
    .multiply(settings.nightAllowanceRate)
    .multiply(BigDecimal.valueOf(nightMinutes))
    .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);

// After
hourlyMoney.multiply(Percentage.NIGHT_RATE).multiplyByMinutes(WorkMinutes.of(nightMinutes));
```

#### WorkMinutes -- 근무시간 VO

`int` 근무시간(분)을 래핑하고 연장근로 판단 로직을 내장한다.

```java
public final class WorkMinutes {
    public static final WorkMinutes DAILY_LIMIT = new WorkMinutes(480);       // 8h
    public static final WorkMinutes WEEKLY_LIMIT = new WorkMinutes(2400);     // 40h
    public static final WorkMinutes MONTHLY_DEFAULT = new WorkMinutes(12540); // 209h

    public boolean exceedsDailyLimit() { return minutes > DAILY_LIMIT.minutes; }
    public WorkMinutes overtimeMinutes() {
        if (!exceedsDailyLimit()) return ZERO;
        return new WorkMinutes(minutes - DAILY_LIMIT.minutes);
    }
}
```

Before -> After

```java
// Before
if (totalMinutes > 480) {
    int overtimeMinutes = totalMinutes - 480;
}

// After
if (workedMinutes.exceedsDailyLimit()) {
    WorkMinutes overtime = workedMinutes.overtimeMinutes();
}
```

#### Percentage -- 비율 VO

50 = 50%로 표현하고, `toDecimal()`로 0.50 변환을 제공한다.

```java
public final class Percentage {
    public static final Percentage OVERTIME_RATE = new Percentage(BigDecimal.valueOf(50));   // 50%
    public static final Percentage NIGHT_RATE = new Percentage(BigDecimal.valueOf(50));      // 50%
    public static final Percentage HOLIDAY_RATE = new Percentage(BigDecimal.valueOf(50));    // 50%
    public static final Percentage TAX_RATE_3_3 = new Percentage(new BigDecimal("3.3"));    // 3.3%

    public BigDecimal toDecimal() {
        return rate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }
}
```

#### HourlyWage -- 통상시급 VO

시급/일급/월급 -> 통상시급 변환의 순수 계산 로직을 캡슐화한다.

```java
public final class HourlyWage {
    public static HourlyWage fromHourly(BigDecimal amount) { ... }
    public static HourlyWage fromDaily(BigDecimal dailyAmount, WorkMinutes dailyWorkMinutes) { ... }
    public static HourlyWage fromMonthly(BigDecimal monthlyAmount, WorkMinutes monthlyWorkMinutes) { ... }
    public Money toMoney() { return wage; }
}
```

Before -> After

```java
// Before (계산 + I/O 혼합)
BigDecimal dailyWorkMinutes = new BigDecimal("480");  // 매직넘버
BigDecimal dailyHourlyWage = amount.multiply(BigDecimal.valueOf(60))
    .divide(dailyWorkMinutes, 0, RoundingMode.HALF_UP);

// After (I/O는 서비스에 유지, 계산만 VO에 위임)
BigDecimal dailyHourlyWage = HourlyWage.fromDaily(amount, WorkMinutes.DAILY_DEFAULT).value();
```

### Phase 2. Domain Models (2개)

#### AllowanceSettings -- 수당 설정 도메인 모델

`UserSalaryAllowances` + `UserSalaryDeductions` 두 JPA 엔티티의 설정값을 하나의 불변 도메인 모델로 결합한다.

```java
public record AllowanceSettings(
        boolean weeklyAllowance,
        boolean overtimeEnabled,
        boolean nightWorkEnabled,
        boolean holidayWorkEnabled,
        boolean taxEnabled
) {
    public static AllowanceSettings disabled() {
        return new AllowanceSettings(false, false, false, false, false);
    }

    public static AllowanceSettings from(Boolean weeklyAllowance, Boolean overtime,
                                         Boolean nightWork, Boolean holidayWork,
                                         Boolean taxRate) {
        return new AllowanceSettings(
                Boolean.TRUE.equals(weeklyAllowance),  // null-safe
                Boolean.TRUE.equals(overtime),
                Boolean.TRUE.equals(nightWork),
                Boolean.TRUE.equals(holidayWork),
                Boolean.TRUE.equals(taxRate)
        );
    }
}
```

기존에는 null 체크가 8줄에 걸쳐 반복되었으나, `AllowanceSettings.from()`으로 한 곳에서 처리한다.

#### DailySalaryCalculation -- 일급여 계산 도메인 모델

기본급 -> 가산수당 -> 세금 차감까지의 전체 급여 계산 파이프라인을 캡슐화한다. JPA 의존성 없음.

```java
public class DailySalaryCalculation {
    private final String workType;
    private final Money amount;
    private final HourlyWage hourlyWage;
    private final AllowanceSettings settings;

    private Money baseSalary = Money.ZERO;
    private Money allowanceTotal = Money.ZERO;
    private Money taxDeduction = Money.ZERO;
    private Money netSalary = Money.ZERO;

    public void calculate(WorkMinutes workedMinutes, int nightMinutes, boolean isHoliday) {
        this.baseSalary = calculateBaseSalary(workedMinutes);
        this.allowanceTotal = calculateAllowances(workedMinutes, nightMinutes, isHoliday);
        Money totalBeforeTax = baseSalary.add(allowanceTotal);

        if (settings.taxEnabled()) {
            this.taxDeduction = totalBeforeTax.taxAmount(Percentage.TAX_RATE_3_3);
            this.netSalary = totalBeforeTax.subtract(taxDeduction);
        } else {
            this.taxDeduction = Money.ZERO;
            this.netSalary = totalBeforeTax;
        }
    }

    private Money calculateBaseSalary(WorkMinutes workedMinutes) {
        return switch (workType) {
            case "DAILY" -> amount;
            case "MONTHLY", "HOURLY" -> hourlyWage.toMoney().multiplyByMinutes(workedMinutes);
            default -> hourlyWage.toMoney().multiplyByMinutes(workedMinutes);
        };
    }

    private Money calculateAllowances(WorkMinutes workedMinutes, int nightMinutes, boolean isHoliday) {
        Money total = Money.ZERO;
        Money hourlyMoney = hourlyWage.toMoney();

        if (settings.nightWorkEnabled() && nightMinutes > 0) {
            total = total.add(hourlyMoney
                    .multiply(Percentage.NIGHT_RATE)
                    .multiplyByMinutes(WorkMinutes.of(nightMinutes)));
        }
        if (settings.overtimeEnabled() && workedMinutes.exceedsDailyLimit()) {
            total = total.add(hourlyMoney
                    .multiply(Percentage.OVERTIME_RATE)
                    .multiplyByMinutes(workedMinutes.overtimeMinutes()));
        }
        if (settings.holidayWorkEnabled() && isHoliday) {
            total = total.add(hourlyMoney
                    .multiply(Percentage.HOLIDAY_RATE)
                    .multiplyByMinutes(workedMinutes));
        }
        return total;
    }
}
```

기존에는 서비스 레이어 80줄에 걸쳐 인라인 계산이 이루어졌고, `totalSalary` BigDecimal 하나만 추적 가능했다. 이제 `baseSalary`, `allowanceTotal`, `taxDeduction`, `netSalary` 4개 중간 결과를 모두 추적할 수 있다.

설계 원칙: `calculateNightMinutes()`, `calculateBreakTimeRanges()` 등 시간 판단 로직(LocalTime 의존)은 서비스에 유지한다. 도메인 모델은 "시간이 몇 분인가"만 받아서 "금액이 얼마인가"만 계산한다.

### Phase 3. Mappers (2개)

```java
// AllowanceSettingsMapper — JPA 엔티티 → 도메인 모델 변환
public final class AllowanceSettingsMapper {
    public static AllowanceSettings toDomain(UserSalaryAllowances allowances,
                                              UserSalaryDeductions deductions) {
        return AllowanceSettings.from(
                allowances != null ? allowances.getWeeklyAllowance() : null,
                allowances != null ? allowances.getOvertimeEnabled() : null,
                allowances != null ? allowances.getNightWorkEnabled() : null,
                allowances != null ? allowances.getHolidayWorkEnabled() : null,
                deductions != null ? deductions.getTaxRate() : null
        );
    }
}

// SalaryMapper — 도메인 모델 → DB 저장용 값 추출
public final class SalaryMapper {
    public static BigDecimal toSalaryAmount(DailySalaryCalculation calculation) {
        return calculation.getNetSalary().value();
    }
}
```

### Phase 4. 서비스 레이어 수정

`SalaryCalculateService.processSimpleTimeSegment()`의 계산 부분을 도메인 모델에 위임했다.

```java
// 도메인 모델로 급여 계산 위임
AllowanceSettings allowanceSettings = AllowanceSettings.from(
        branchSettings.weeklyHolidayEnabled,
        branchSettings.overtimeAllowanceEnabled,
        branchSettings.nightAllowanceEnabled,
        branchSettings.holidayAllowanceEnabled,
        branchSettings.taxEnabled
);

HourlyWage hourlyWageVO = HourlyWage.fromHourly(branchSettings.hourlyWage);
DailySalaryCalculation calculation = new DailySalaryCalculation(
        branchSettings.workType, Money.of(branchSettings.amount),
        hourlyWageVO, allowanceSettings
);

calculation.calculate(WorkMinutes.of(totalWorkMinutes), nightMinutes, isHoliday);
BigDecimal totalSalary = SalaryMapper.toSalaryAmount(calculation);
```

| 항목 | Before | After |
|------|--------|-------|
| 기본급 계산 | `if-else` 3분기 + BigDecimal 직접 연산 | `DailySalaryCalculation.calculateBaseSalary()` |
| 가산수당 계산 | private 메서드 + BigDecimal 직접 연산 | `DailySalaryCalculation.calculateAllowances()` |
| 세금 차감 | `totalSalary.multiply(taxRate).setScale(2, ...)` 인라인 | `totalBeforeTax.taxAmount(Percentage.TAX_RATE_3_3)` |
| 결과 추출 | `totalSalary` BigDecimal 하나만 | `baseSalary`, `allowanceTotal`, `taxDeduction`, `netSalary` 4개 |

### Phase 5. 테스트 (3개, 20 케이스)

#### 기존 테스트 vs DDD 테스트의 근본적 차이

| 항목 | 기존 (서비스 테스트) | DDD (도메인 테스트) |
|------|--------------------|--------------------|
| 클래스 어노테이션 | `@ExtendWith(MockitoExtension.class)` | 없음 |
| DB/Feign Mock | `@Mock` Repository 2개, FeignClient 2개, Service 4개 | 불필요 |
| setUp() | `@BeforeEach` + Mock 행동 정의 30줄+ | 없음 |
| 테스트 데이터 | JPA 엔티티 `.builder().build()` | VO 팩토리 `Money.of()`, `HourlyWage.fromHourly()` |
| 실행 시간 | Mockito 프레임워크 초기화 필요 | 즉시 실행 (순수 Java) |
| 전체 20 테스트 | -- | 3초 |

DDD 테스트가 가능한 이유: 비즈니스 로직이 서비스가 아닌 도메인 모델 안에 있기 때문이다. 의존 그래프에 Spring, JPA, Mockito, DB, Redis가 단 하나도 없다.

```
DailySalaryCalculation ← HourlyWage ← Money ← BigDecimal (JDK)
         ↑                    ↑          ↑
    AllowanceSettings    WorkMinutes  Percentage
         ↑                    ↑          ↑
    (순수 Java)          (순수 Java)  (순수 Java)
```

#### 테스트 예시

MoneyTest (7 케이스)

```java
class MoneyTest {
    @Test
    void multiplyByMinutes() {
        Money hourlyWage = Money.of(10030);
        WorkMinutes worked = WorkMinutes.of(240);
        Money result = hourlyWage.multiplyByMinutes(worked);
        assertThat(result.value()).isEqualByComparingTo(new BigDecimal("40120"));
    }

    @Test
    void applyTax() {
        Money salary = Money.of(100000);
        Money afterTax = salary.applyTax(Percentage.TAX_RATE_3_3);
        assertThat(afterTax.value()).isEqualByComparingTo(new BigDecimal("96700"));
    }
    // + 5개 (세금 금액, 합산, 비율 적용, null 처리, 동등성)
}
```

DailySalaryCalculationTest (7 케이스)

```java
class DailySalaryCalculationTest {

    @Test
    void hourly_4hours_noAllowance() {
        DailySalaryCalculation calc = new DailySalaryCalculation(
                "HOURLY", Money.ZERO,
                HourlyWage.fromHourly(new BigDecimal("10030")),
                AllowanceSettings.disabled()
        );
        calc.calculate(WorkMinutes.of(240), 0, false);
        assertThat(calc.getBaseSalary().value()).isEqualByComparingTo("40120");
        assertThat(calc.getAllowanceTotal()).isEqualTo(Money.ZERO);
        assertThat(calc.getNetSalary().value()).isEqualByComparingTo("40120");
    }

    @Test
    void complexScenario() {
        // 연장 + 야간 + 세금 모두 ON
        AllowanceSettings settings = AllowanceSettings.from(false, true, true, false, true);
        DailySalaryCalculation calc = new DailySalaryCalculation(
                "HOURLY", Money.ZERO,
                HourlyWage.fromHourly(new BigDecimal("10000")), settings
        );
        calc.calculate(WorkMinutes.of(600), 120, false);

        assertThat(calc.getBaseSalary().value()).isEqualByComparingTo("100000");   // 10000x600/60
        assertThat(calc.getAllowanceTotal().value()).isEqualByComparingTo("20000"); // 야간+연장
        assertThat(calc.getTaxDeduction().value()).isEqualByComparingTo("3960");    // 120000x3.3%
        assertThat(calc.getNetSalary().value()).isEqualByComparingTo("116040");
    }
    // + 5개 (일급+세금, 연장수당, 야간수당, 휴일수당, 전부 비활성)
}
```

HourlyWageTest (6 케이스) -- 시급/일급/월급 변환, 기본값 적용, 동등성 검증

테스트 실행 결과

```
./gradlew test --tests "com.workpay.salary.domain.*"

MoneyTest                         7/7 PASSED
HourlyWageTest                    6/6 PASSED
DailySalaryCalculationTest        7/7 PASSED

BUILD SUCCESSFUL in 3s
```

---

## 결과

### 영향 범위 및 안전성

| 카테고리 | 파일 수 | 변경 여부 |
|---------|---------|----------|
| Controller | 29개 | 없음 |
| DTO | 80+개 | 없음 |
| JPA Entity | 33개 | 없음 |
| Repository | 21개 | 없음 |
| FeignClient | 4개 | 없음 |
| `build.gradle` | 1개 | 없음 |
| `application.yml` | 1개 | 없음 |

수정된 두 파일(`SalaryCalculateService`, `PayTypeWageCalculator`) 모두 내부 구현만 변경하며, 메서드 시그니처와 반환 타입이 동일하므로 호출자에 영향이 없다.

### 리팩토링 전후 핵심 비교

| 관점 | Before | After |
|------|--------|-------|
| 금액 연산 | `BigDecimal.multiply().divide(60, 0, HALF_UP)` 반복 | `Money.multiplyByMinutes(WorkMinutes)` 한 줄 |
| 비율 표현 | `0.5`(소수) vs `50.0`(%) 혼재 | `Percentage.OVERTIME_RATE` (50 = 50%) 통일 |
| 매직넘버 | `480`, `12540`, `0.033` 하드코딩 | `WorkMinutes.DAILY_LIMIT`, `Percentage.TAX_RATE_3_3` 상수 |
| 연장근로 판단 | `if (totalMinutes > 480)` 서비스에 산재 | `workedMinutes.exceedsDailyLimit()` VO 내장 |
| 통상시급 계산 | 계산+I/O 혼합 (PayTypeWageCalculator) | 순수 계산만 `HourlyWage.fromDaily()` |
| 수당 설정 | 2개 엔티티에서 각각 조회 + null 체크 8줄 | `AllowanceSettings.from()` 한 줄 |
| 급여 계산 파이프라인 | 서비스 80줄 인라인 | `DailySalaryCalculation.calculate()` 응집 |
| 계산 결과 추적 | `totalSalary` 하나만 | `baseSalary`, `allowanceTotal`, `taxDeduction`, `netSalary` |
| 단위 테스트 | Spring 컨텍스트 필요 (통합 테스트) | JPA/Spring 없이 순수 단위 테스트 (20 케이스) |
| 테스트 속도 | 수십 초 (Spring Boot 기동) | 3초 (순수 Java) |

### DB 기반 정책 처리 구조

정책(시급 타입, 수당 on/off, 공제 설정 등)이 코드 하드코딩이 아닌 DB 테이블에서 직원별로 관리되는 구조다. 동일한 계산 파이프라인이 직원별 설정(DB)을 참조하여 서로 다른 계산 결과를 산출한다.

| 직원 A (시급제) | 직원 B (일급제) | 직원 C (월급제) |
|---|---|---|
| 시급 10,030원 x 근무시간 | 일급 80,000원 (고정) | 월급 2,090,000원 / 209시간 x 근무시간 |
| 야간수당 ON, 연장수당 ON | 야간수당 OFF | 야간수당 ON, 연장수당 OFF |
| 3.3% 원천징수 | 세금 없음 | 4대보험 적용 |

현재 3가지 급여 타입(시급/일급/월급)은 switch 분기로 충분히 처리 가능하다. 전략 패턴(Strategy Pattern) 전환은 계산 타입이 10개 이상이거나 비개발자가 규칙을 직접 편집해야 할 때 검토한다.

### 크로스 서비스 DDD 적용 현황

workpay-service(계산 중심)와 billing-service(상태 전이 중심)에 각각 다른 DDD 전술 패턴을 적용했다.

| 서비스 | 도메인 특성 | 비즈니스 로직 위치 | 전략 |
|--------|-----------|-----------------|------|
| workpay-service | 계산 중심 (급여, 수당, 세금) | Value Object + Domain Model | VO가 계산 규칙 캡슐화 |
| billing-service | 상태 전이 중심 (결제, 구독, 쿠폰) | Entity 메서드 | Entity가 상태 판정/전이 수행 |

billing-service 예시
- `Coupon.isUsable()` -- "쿠폰은 ACTIVE 상태이고 만료일이 지나지 않았으면 사용 가능하다"
- `BillingKey.activate()/deactivate()` -- 빌링키 상태 전이를 Entity가 직접 관리
- `Payment.isCancelTransaction()` -- "originalPaymentId가 있으면 취소 거래"

### 정량적 성과

| 지표 | Before | After |
|------|--------|-------|
| 비즈니스 로직 위치 | Service 레이어 (2,100줄+) | Domain 객체 (VO 4개 + Model 2개 + Entity 4개) |
| 순수 단위 테스트 | 불가 (Spring 컨텍스트 필요) | 20 케이스, 3초 (Mock 없음) |
| 매직넘버 | `480`, `12540`, `0.033` 등 산재 | 도메인 상수로 의미 부여 |
| 도메인 용어 | 코드에서 추론해야 함 | `exceedsDailyLimit()`, `isUsable()` 등 명시적 |
| 서비스 간 일관성 | 없음 | 각 도메인 특성에 맞는 DDD 패턴 적용 |

---

## 연결 — 이후 빌드 단위 분리로 확장

VO·Domain Model로 계산 로직이 Spring 의존 없는 순수 Java가 되자, 이 순수 도메인을 **별도 Gradle 모듈(payroll-compute)** 로 떼어낼 수 있는 전제가 생겼다. 모듈 분리 이후 테스트 실행 시간·변경 영향 범위·빌드 격리 측면에서의 정량적 효과는 별도 문서에 측정.

→ [모듈 분리를 통한 테스트 실행 최적화](./module-separation-test-optimization.md)
