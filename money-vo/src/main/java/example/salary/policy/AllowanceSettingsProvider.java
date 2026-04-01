package example.salary.policy;

import example.salary.model.AllowanceSettings;

/**
 * 직원별 수당/공제 ON/OFF 설정 조회 인터페이스
 *
 * 실서비스에서는 DB(user_salary_allowances, user_salary_deductions)에서
 * 직원별로 조회. 사장님이 앱에서 직원마다 ON/OFF를 설정.
 * 테스트에서는 mock 구현으로 대체.
 */
public interface AllowanceSettingsProvider {
    AllowanceSettings getSettings(Long userId, Long branchId);
}
