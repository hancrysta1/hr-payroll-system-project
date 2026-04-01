package example.salary.model;

/**
 * 직원별 수당/공제 설정
 *
 * 실서비스에서는 DB(user_salary_allowances, user_salary_deductions)에서
 * 직원별로 조회. 사장님이 앱에서 직원마다 ON/OFF를 설정.
 * 이 프로젝트에서는 계산 파이프라인 검증을 위해 record로 단순화.
 */
public record AllowanceSettings(
        boolean overtimeEnabled,
        boolean nightWorkEnabled,
        boolean holidayWorkEnabled,
        boolean taxEnabled
) {
    public static AllowanceSettings allEnabled() {
        return new AllowanceSettings(true, true, true, true);
    }

    public static AllowanceSettings disabled() {
        return new AllowanceSettings(false, false, false, false);
    }
}
