package example.salary.policy;

import example.salary.vo.Percentage;

/**
 * 가산수당 정책 조회 인터페이스
 *
 * 실서비스에서는 DB(legal_allowance_policy)에서 지점별 가산율을 조회.
 * 테스트에서는 mock 구현으로 대체.
 */
public interface AllowancePolicyProvider {
    Percentage getOvertimeRate(Long branchId);
    Percentage getNightRate(Long branchId);
    Percentage getHolidayRate(Long branchId);
}
