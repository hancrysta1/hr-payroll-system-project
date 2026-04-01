package example.salary.vo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 통상시급 Value Object
 *
 * 급여 타입(시급/일급/월급)에 따라 통상시급을 계산한다.
 */
public final class HourlyWage {

    private final Money wage;

    private HourlyWage(Money wage) {
        this.wage = wage;
    }

    /** 시급제: 금액 그대로 */
    public static HourlyWage fromHourly(BigDecimal amount) {
        return new HourlyWage(Money.of(amount));
    }

    /** 일급제: 일급 × 60 ÷ 일 근무시간(분). 기본 480분(8시간) */
    public static HourlyWage fromDaily(BigDecimal dailyAmount, WorkMinutes dailyWorkMinutes) {
        WorkMinutes effective = (dailyWorkMinutes == null || dailyWorkMinutes.value() <= 0)
                ? WorkMinutes.DAILY_LIMIT : dailyWorkMinutes;
        BigDecimal hourly = dailyAmount
                .multiply(BigDecimal.valueOf(60))
                .divide(BigDecimal.valueOf(effective.value()), 0, RoundingMode.HALF_UP);
        return new HourlyWage(Money.of(hourly));
    }

    /** 월급제: 월급 × 60 ÷ 월 근무시간(분). 기본 12540분(209시간) */
    public static HourlyWage fromMonthly(BigDecimal monthlyAmount, WorkMinutes monthlyWorkMinutes) {
        WorkMinutes effective = (monthlyWorkMinutes == null || monthlyWorkMinutes.value() <= 0)
                ? WorkMinutes.MONTHLY_DEFAULT : monthlyWorkMinutes;
        BigDecimal hourly = monthlyAmount
                .multiply(BigDecimal.valueOf(60))
                .divide(BigDecimal.valueOf(effective.value()), 0, RoundingMode.HALF_UP);
        return new HourlyWage(Money.of(hourly));
    }

    public Money toMoney() { return wage; }

    public BigDecimal value() { return wage.value(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HourlyWage that)) return false;
        return wage.equals(that.wage);
    }

    @Override
    public int hashCode() { return Objects.hash(wage); }

    @Override
    public String toString() { return "시급 " + wage; }
}
