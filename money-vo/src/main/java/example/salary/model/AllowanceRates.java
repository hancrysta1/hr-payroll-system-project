package example.salary.model;

import example.salary.vo.Percentage;

/**
 * 지점별 가산수당 요율
 *
 * 실서비스에서는 DB(legal_allowance_policy)에서 지점별로 조회.
 * 법정 기본값은 50%이지만 지점마다 다르게 설정 가능.
 */
public record AllowanceRates(
        Percentage overtimeRate,
        Percentage nightRate,
        Percentage holidayRate
) {
    /** 법정 기본값 (50%) */
    public static AllowanceRates legalDefault() {
        return new AllowanceRates(
                Percentage.OVERTIME_RATE,
                Percentage.NIGHT_RATE,
                Percentage.HOLIDAY_RATE
        );
    }

    /** 지점별 커스텀 요율 */
    public static AllowanceRates of(double overtimePercent, double nightPercent, double holidayPercent) {
        return new AllowanceRates(
                Percentage.of(overtimePercent),
                Percentage.of(nightPercent),
                Percentage.of(holidayPercent)
        );
    }
}
