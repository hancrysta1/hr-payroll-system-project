package example.salary.service;

/**
 * 급여 요약 API — N+1 → 배치 전환 패턴
 *
 * Before: 직원 N명 루프 안에서 1명마다 DB/Feign 7종 + 근무일수만큼 추가 쿼리
 *         → 직원 15명, 20일 기준 총 I/O ~954회
 *
 * After:  루프 밖에서 배치 조회 → Map 변환 → 루프 안에서 O(1) 조회
 *         → 총 I/O ~28회 (97% 감소)
 */
public class SalarySummaryService {

    public List<WeeklyBranchUserSummaryDTO> getWeeklySummaryByBranch(
            Long branchId, LocalDate start, LocalDate end) {

        // ===== 루프 밖: 배치 조회 (고정 비용) =====

        // 1. 일별 급여 데이터 — 1회
        List<DailyUserSalary> salaries = dailySalaryRepo
                .findSalariesByBranchAndPeriod(branchId, start, end);

        // 2. 직원 ID 목록 — Feign 1회
        List<Long> branchUserIds = userServiceClient.getUserIdsByBranchId(branchId);

        // 3. 지점 정보 — Feign 1회 (기존: N회 반복 호출)
        BranchInfoDTO branchInfo = branchServiceClient.getBranch(branchId);

        // 4. 직원 정보 — Feign 배치 1회 (기존: N회 개별 호출)
        Map<Long, UserInfoDTO> userInfoMap = new HashMap<>();
        List<UserInfoDTO> userInfoList = userServiceClient.getUsers(branchUserIds);
        for (UserInfoDTO info : userInfoList) {
            userInfoMap.put(info.getId(), info);
        }

        // 5. 가산수당 설정 — DB 배치 1회 (기존: N회 개별 조회)
        Map<Long, UserSalaryAllowances> userAllowancesMap =
                userSalaryAllowancesService.getUserAllowancesMapByBranch(branchId);

        // 6. 급여 설정 — Feign 배치 1회 (기존: N회 개별 호출)
        Map<Long, UserWageSettingsDTO> wageSettingsMap =
                branchServiceClient.getUsersWageSettingsByBranch(branchId);

        // 7. 공제 설정 — DB 배치 1회 (기존: N회 개별 조회)
        Map<Long, UserSalaryDeductions> deductionsMap =
                userSalaryDeductionsService.getDeductionsMapByBranch(branchId);

        // 8. 보험 금액 — DB 배치 1회 (기존: N회 개별 조회)
        Map<Long, UserInsuranceAmount> insuranceMap =
                userInsuranceAmountService.getInsuranceMapByBranch(branchId);

        // 9. 시급 이력 — DB 배치 1회 (기존: N회 개별 조회)
        Map<Long, List<Salary>> salaryHistoryMap = salaryRepository
                .findAllByBranchIdOrderByUserIdAndStartDateDesc(branchId)
                .stream()
                .collect(Collectors.groupingBy(Salary::getUserId));

        // ===== 루프 안: O(1) 조회만 =====

        Map<Long, List<DailyUserSalary>> groupedByUser = salaries.stream()
                .collect(Collectors.groupingBy(DailyUserSalary::getUserId));

        for (Long userId : branchUserIds) {
            // Map.get() — DB/Feign 호출 없음
            UserInfoDTO userInfo = userInfoMap.get(userId);
            UserSalaryAllowances allowances = userAllowancesMap.get(userId);
            UserWageSettingsDTO wageSetting = wageSettingsMap.get(userId);
            UserSalaryDeductions deductions = deductionsMap.get(userId);
            UserInsuranceAmount insurance = insuranceMap.get(userId);
            List<Salary> history = salaryHistoryMap.getOrDefault(userId, List.of());

            // ... 계산 로직 (CPU만 사용, I/O 없음)
        }
    }
}
