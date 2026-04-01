package example.salary.policy;

import example.salary.vo.HourlyWage;

import java.time.LocalDate;

/**
 * 시급 조회 인터페이스 — 5단계 우선순위 체인
 *
 * ① 특정일 시급(user_daily_wage)
 * ② 시급 변경 이력(salary_history)
 * ③ 직원별 기본 시급(user_branch.personal_cost)
 * ④ 지점 기본 시급(branch.basic_cost)
 * ⑤ 법정 최저시급(minimum_wage)
 *
 * 실서비스에서는 캐시(Caffeine) + DB 조회로 구현.
 * 테스트에서는 mock 구현으로 대체.
 */
public interface WageProvider {
    HourlyWage getHourlyWage(Long userId, Long branchId, LocalDate workDate);
}
