package example.salary.vo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 비율 Value Object (50 = 50%)
 *
 * toDecimal()을 거쳐야 계산 가능 — 50을 그대로 곱하는 실수 차단.
 */
public final class Percentage {

    // 실서비스에서는 DB(legal_allowance_policy)에서 지점별로 조회.
    // 이 프로젝트에서는 계산 정합성 검증을 위해 법정 기본값을 상수로 사용.
    public static final Percentage OVERTIME_RATE = new Percentage(BigDecimal.valueOf(50));
    public static final Percentage NIGHT_RATE = new Percentage(BigDecimal.valueOf(50));
    public static final Percentage HOLIDAY_RATE = new Percentage(BigDecimal.valueOf(50));
    public static final Percentage TAX_RATE_3_3 = new Percentage(new BigDecimal("3.3"));
    public static final Percentage ZERO = new Percentage(BigDecimal.ZERO);

    private final BigDecimal rate;

    private Percentage(BigDecimal rate) {
        this.rate = rate;
    }

    public static Percentage of(BigDecimal rate) {
        if (rate == null) return ZERO;
        return new Percentage(rate);
    }

    public static Percentage of(double rate) {
        return new Percentage(BigDecimal.valueOf(rate));
    }

    public BigDecimal toDecimal() {
        return rate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    public BigDecimal value() { return rate; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Percentage that)) return false;
        return rate.compareTo(that.rate) == 0;
    }

    @Override
    public int hashCode() { return Objects.hash(rate.stripTrailingZeros()); }

    @Override
    public String toString() { return rate.toPlainString() + "%"; }
}
