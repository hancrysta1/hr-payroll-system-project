# 급여 API 쿼리 비용 97% 절감 (954회→28회)

## 문제

`SalarySummaryService.getWeeklySummaryByBranch()` — 전 직원 급여 요약 리스트 API.

기준 규모 — 직원 15명, 20일 근무 (일별 급여 300건).

### 최초 상태 (N+1 전면)

모든 조회가 직원 루프 안에서 1명씩 호출되던 구조.

```
for (Long userId : branchUserIds) {          // 15명 반복
    userServiceClient.getUser(userId);                      // Feign x15
    branchServiceClient.getBranch(branchId);                // Feign x15 (같은 branchId!)
    userSalaryAllowancesService.getUserAllowances(...);     // DB x15
    branchServiceClient.getUserWageSettings(...);           // Feign x15
    userSalaryDeductionsService.getUserDeductions(...);     // DB x15
    userInsuranceAmountService.getUserInsuranceAmount(...);  // DB x15
    salaryRepository.findAll...(...);                       // DB x15
    userDailyWageService.getHourlyWage(...) x 근무일수;     // DB x15x20
}
```

| 구분 | 횟수 | 상세 |
|------|------|------|
| Feign HTTP (마이크로서비스 간) | 46회 | 1(급여데이터) + 15(getUser) + 15(getBranch) + 15(wageSettings) |
| 로컬 DB 쿼리 | 360회 | 15(allowances) + 15(deductions) + 15(insurance) + 15(salaryHistory) + 300(dailyWage) |
| 총 I/O | ~406회 | |

## 분석

### 1차 배치 최적화 후 현재 상태

루프 밖에서 배치 조회로 전환한 항목들.

#### 루프 밖 (고정 비용) — 이미 최적화됨

| # | 조회 | 방식 | 횟수 | 비고 |
|---|------|------|------|------|
| 1 | 일별 급여 데이터 | 로컬 DB | 1회 | `findSalariesByBranchAndPeriod` |
| 2 | 직원 ID 목록 | Feign -> user-service | 1회 | `getUserIdsByBranchId` |
| 3 | 급여 확정 여부 | 로컬 DB | 1회 | `isBranchPeriodBulkConfirmed` |
| 4 | 지점 정보 | Feign -> branch-service | 1회 | `getBranch` (기존 N회 -> 1회) |
| 5 | 직원 정보 | Feign -> user-service | 1회 | `getUsers` 배치 (기존 N회 -> 1회) |
| 6 | 직원 역할 | Feign -> branch-service | 1회 | `getBranchWorkers` |
| 7 | 가산수당 설정 | 로컬 DB | 1회 | `getUserAllowancesMapByBranch` 배치 (기존 N회 -> 1회) |
| 8 | 급여 설정 | Feign -> branch-service | 1회 | `getUsersWageSettingsByBranch` 배치 (기존 N회 -> 1회) |
| 9 | 확정 정보 맵 | 로컬 DB | 1회 | `getConfirmationMapForPeriod` |
| | 소계 | | Feign 5회 + DB 4회 = 9회 | |

#### 루프 안 (N에 비례) — 아직 남은 N+1

| # | 조회 | 방식 | 횟수 (15명) | 캐시 | 비고 |
|---|------|------|-------------|------|------|
| 10 | 공제 설정 (세금/4대보험 ON/OFF) | 로컬 DB | 15회 | userDeductionsCache (24h) | `getUserDeductions` |
| 11 | 보험 금액 (4대보험 금액) | 로컬 DB | 15회 | userInsuranceAmountCache (24h) | `getUserInsuranceAmount` |
| 12 | 시급 변경 이력 | 로컬 DB | 15회 | 없음 | `salaryRepository.findAll...` (calculateWageHistory 내부) |
| 13 | 특정일 시급 (userDailyWage) | 로컬 DB | 최대 300회 | 없음 | `getHourlyWage(userId, branchId, workDate)` - 근무일마다 호출 |
| | 소계 (캐시 미스) | | 최대 345회 | | |
| | 소계 (캐시 히트) | | 최대 315회 | | 10,11만 캐시 적용 |

#### 현재 총 비용

| 시나리오 | Feign | DB | 총 I/O |
|---------|-------|-----|--------|
| 캐시 전체 미스 | 5회 | 4 + 345 = 349회 | 354회 |
| 캐시 히트 (10,11만) | 5회 | 4 + 315 = 319회 | 324회 |

### 숨겨진 N+1 — 직원 루프 안의 내부 메서드 호출 추적

```
for (Long userId : branchUserIds) {                          // 15명
    |
    +-- calculateWageHistory(userId, branchId, ...)           // 직원당 1회
    |   +-- salaryRepository.findAllByUserIdAndBranch...()    // DB x1 (직원당)
    |   +-- for (DailyUserSalary : salaries)                  // 근무일 20일
    |       +-- minimumWageService.getMinimumWageForDate()    // 캐시됨 (무시)
    |
    +-- calculateWeekAverageHourlyWage(...)                   // 주차별 호출
    |   +-- for (DailyUserSalary : weekSalaries)              // 근무일별
    |       +-- userDailyWageService.getHourlyWage(...)       // DB x1 (근무일당!)
    |
    +-- calculateWeeklyAllowanceByWeekWithHistory(...)
    |   +-- calculateWeekAverageHourlyWage(...)               // 위와 동일 패턴 반복
    |       +-- userDailyWageService.getHourlyWage(...)       // DB x1 (근무일당!)
    |
    +-- calculateOvertimeAllowanceWithHistory(...)
    |   +-- userDailyWageService.getHourlyWage(...)           // DB x1 (근무일당!)
    |
    +-- calculateAllowancesBreakdown(...)                     // 순수 CPU 계산 (DB 없음)
    |
    +-- getUserDeductions(userId, branchId)                   // DB x1 (캐시 있음)
    +-- getUserInsuranceAmount(userId, branchId)              // DB x1 (캐시 있음)
```

### 핵심 병목 — userDailyWageService.getHourlyWage()

`calculateWeekAverageHourlyWage`가 여러 메서드에서 중복 호출되며, 매번 근무일마다 DB를 조회한다.

| 호출 위치 | 호출 횟수 (15명 x 20일) |
|-----------|----------------------|
| `calculateWeekAverageHourlyWage` (주휴수당용) | 최대 300회 |
| `calculateWeekAverageHourlyWage` (연장수당용) | 최대 300회 |
| `calculateOvertimeAllowanceWithHistory` 내부 | 최대 300회 |
| 합계 | 최대 ~900회 (중복 포함) |

### 실제 총 비용 (숨겨진 쿼리 포함)

#### 현재 상태 전체 정리 (15명 x 20일)

| 카테고리 | 호출 | 횟수 | 캐시 |
|---------|------|------|------|
| Feign HTTP | | | |
| 직원 ID 목록 | `getUserIdsByBranchId` | 1 | - |
| 지점 정보 | `getBranch` | 1 | - |
| 직원 정보 배치 | `getUsers` | 1 | - |
| 직원 역할 | `getBranchWorkers` | 1 | - |
| 급여 설정 배치 | `getUsersWageSettingsByBranch` | 1 | - |
| 소계 | | 5회 | |
| 로컬 DB (고정) | | | |
| 일별 급여 데이터 | `findSalariesByBranchAndPeriod` | 1 | - |
| 급여 확정 여부 | `isBranchPeriodBulkConfirmed` | 1 | - |
| 가산수당 설정 배치 | `getUserAllowancesMapByBranch` | 1 | - |
| 확정 정보 맵 | `getConfirmationMapForPeriod` | 1 | - |
| 소계 | | 4회 | |
| 로컬 DB (직원 N=15) | | | |
| 공제 설정 | `getUserDeductions` | 15 | 24h 캐시 |
| 보험 금액 | `getUserInsuranceAmount` | 15 | 24h 캐시 |
| 시급 이력 | `salaryRepo.findAll...` | 15 | 없음 |
| 소계 | | 45회 | |
| 로컬 DB (근무일 M=20, 중복 호출) | | | |
| 특정일 시급 (주휴수당 계산) | `userDailyWageService.getHourlyWage` | ~300 | 없음 |
| 특정일 시급 (연장수당 계산) | `userDailyWageService.getHourlyWage` | ~300 | 없음 |
| 특정일 시급 (연장수당 히스토리) | `userDailyWageService.getHourlyWage` | ~300 | 없음 |
| 소계 | | ~900회 | |

#### 총합

| 시나리오 | Feign | DB | 총 I/O |
|---------|-------|-----|--------|
| 최초 상태 | ~46회 | ~360회 | ~406회 |
| 현재 (캐시 미스) | 5회 | 4 + 45 + 900 = 949회 | 954회 |
| 현재 (캐시 히트, deductions/insurance만) | 5회 | 4 + 15 + 900 = 919회 | 924회 |

1차 배치 최적화로 Feign은 크게 줄었지만, `userDailyWageService.getHourlyWage()`의 중복 호출이 오히려 총 DB 횟수를 늘리고 있음. 이는 `calculateWeekAverageHourlyWage`가 여러 메서드에서 반복 호출되기 때문.

## 해결

### Phase 1 — 루프 안 N+1 제거 (배치 조회)

| 대상 | 현재 | 개선 | 효과 |
|------|------|------|------|
| 공제 설정 | N회 (캐시) | `findByBranchId` 배치 -> Map | 15회 -> 1회 |
| 보험 금액 | N회 (캐시) | `findByBranchId` 배치 -> Map | 15회 -> 1회 |
| 시급 이력 | N회 | `findByBranchId` 배치 -> Map | 15회 -> 1회 |

### Phase 2 — 특정일 시급 중복 호출 제거

| 대상 | 현재 | 개선 | 효과 |
|------|------|------|------|
| userDailyWage | ~900회 (중복) | 직원별 1회 배치 조회 -> 메모리 Map | 900회 -> 15회 |

구현 방법. `userDailyWageService.getAllDailyWages(userId, branchId)` 이미 존재.
루프 진입 시 한 번 조회하고 `Map<LocalDate, BigDecimal>` 으로 메모리에서 조회.

또는 branchId 기준 전체 배치로 `findByBranchId` 추가 -> Map<userId, Map<workDate, BigDecimal>>

### Phase 3 (선택) — Fetch Join / 복합 쿼리

여러 설정 테이블을 한 번의 쿼리로 조인할 수 있다.
```sql
SELECT u.id, usa.weekly_allowance, usa.overtime_enabled, ...,
       usd.tax_rate, usd.national_pension_enabled, ...,
       uia.national_pension_amount, ...
FROM user_branch ub
JOIN user_salary_allowances usa ON usa.user_id = ub.user_id AND usa.branch_id = ub.branch_id
LEFT JOIN user_salary_deductions usd ON usd.user_id = ub.user_id AND usd.branch_id = ub.branch_id
LEFT JOIN user_insurance_amount uia ON uia.user_id = ub.user_id AND uia.branch_id = ub.branch_id
WHERE ub.branch_id = :branchId
```

1회 쿼리로 allowances + deductions + insurance 모두 조회 가능.

## 결과

### Phase 1+2 완료 시 예상 비용 (15명 x 20일)

| 카테고리 | 현재 | 최적화 후 |
|---------|------|----------|
| Feign HTTP | 5회 | 5회 |
| 로컬 DB (고정) | 4회 | 4회 |
| 로컬 DB (직원 N) | 45회 | 3회 (deductions 배치 + insurance 배치 + salaryHistory 배치) |
| 로컬 DB (근무일) | ~900회 | 15회 (직원당 1회 배치) 또는 1회 (branchId 배치) |
| 총 DB | ~949회 | ~23회 |
| 총 I/O | ~954회 | ~28회 |

### Phase 3 포함 시 (Fetch Join)

| 카테고리 | Phase 1+2 | Phase 3 |
|---------|-----------|---------|
| Feign HTTP | 5회 | 5회 |
| 로컬 DB | ~23회 | ~8회 |
| 총 I/O | ~28회 | ~13회 |

### I/O 횟수 감소율

| 단계 | 총 I/O | 최초 대비 감소 |
|------|--------|--------------|
| 최초 상태 | ~406회 | - |
| 현재 (1차 최적화) | ~954회 | 오히려 증가 (숨겨진 중복) |
| Phase 1+2 | ~28회 | 97% 감소 |
| Phase 1+2+3 | ~13회 | 97% 감소 |

### 예상 응답 시간 (15명 x 20일)

| 단계 | DB I/O | CPU 가공 | 예상 응답시간 |
|------|--------|---------|-------------|
| 현재 | ~949 x 1~3ms = 1~3초 | 300건 가공 ~500ms | 1.5~3.5초 |
| Phase 1+2 | ~23 x 1~3ms = 23~69ms | 300건 가공 ~500ms (동일) | 500ms~600ms |
| Phase 1+2+3 | ~8 x 1~3ms = 8~24ms | 300건 가공 ~500ms (동일) | 500ms~530ms |

CPU 가공(수당 계산, 시간 슬롯 분할 등)이 전체의 ~80%를 차지하게 됨.
DB 최적화만으로는 Phase 2까지가 가장 효과적이고, Phase 3은 이미 미미한 차이.

### 직원 수 별 스케일링

| 직원 수 | 현재 총 I/O | Phase 1+2 총 I/O | 감소율 |
|---------|------------|------------------|-------|
| 5명 | ~325회 | ~15회 | 95% |
| 15명 | ~954회 | ~28회 | 97% |
| 30명 | ~1,900회 | ~48회 | 97% |
| 50명 | ~3,160회 | ~68회 | 98% |

현재 구조는 직원 수에 선형 이상으로 증가 (N x M 근무일), 최적화 후 거의 고정 비용.

### 우선순위 및 작업 범위

| 순위 | 작업 | 효과 | 난이도 |
|------|------|------|--------|
| 1 | `userDailyWage` 배치 조회 + 메모리 Map | ~900회 -> 15회 | 중 (기존 메서드에 Map 파라미터 추가) |
| 2 | `deductions`, `insurance` 배치 조회 | 30회 -> 2회 | 하 (Repository에 findByBranchId 추가) |
| 3 | `salaryHistory` 배치 조회 | 15회 -> 1회 | 하 |
| 4 | Fetch Join 복합 쿼리 | 3회 -> 1회 | 중 (새 DTO + 복합 쿼리) |

Phase 1+2만으로 97% 감소. Phase 3은 추가 효과 미미하므로 선택 사항.
