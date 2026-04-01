# 급여 시스템 설계 문서

> 최종 업데이트: 2026-03-25
> 대상 모듈: `workpay-service`
> DDD 리팩토링 상세는 [DDD_REFACTORING_SPEC.md](./DDD_REFACTORING_SPEC.md) 참조

---

## 문제

기존 급여 시스템은 시급 하드코딩, 수당/공제 일괄 적용, 계산 근거 미보존 등의 한계가 있었다. 직원별로 다른 급여 타입(시급/일급/월급)과 수당/공제 설정을 지원해야 했고, 확정 시점의 계산 근거를 보존하여 추적 가능한 급여 시스템이 필요했다.

---

## 분석

### 금융 설계 원칙 적용

이 시스템은 "돈을 다루는 시스템"으로서 아래 금융 설계 원칙을 따른다

- 이벤트 기반 상태 계산 -- 급여 금액을 직접 수정하는 경로는 없다. 스케줄(이벤트)이 변경되면 급여(상태)가 재계산된다. 원장(ledger) 설계의 핵심 패턴
- 정합성 우선 -- `Money` VO가 모든 금액의 scale/rounding을 강제하고, unique constraint + UPSERT로 멱등성 보장
- 제약 조건 기반 데이터 -- 연장수당(`> 480분`), 주휴수당(`>= 주 15시간`), 확정 전제조건(개별 확정 -> 전체 확정) 등 값이 아닌 제약 조건으로 관리
- 추적 가능성(Auditability) -- `reason_snapshot`으로 확정 시점 계산 근거 보존, `previousBasicSalary`로 시급 변경 이력 추적
- 외부 호출 실패 격리 -- 알림 발송 실패가 급여 확정 트랜잭션을 롤백시키지 않도록 try-catch 격리

### 정책 데이터를 코드 하드코딩에서 DB 테이블로 이동

| 정책 | 기존 (코드) | 현재 (DB 테이블) |
|------|------------|-----------------|
| 최저시급 | 상수값 하드코딩 | `minimum_wage` -- 연도별 저장 |
| 급여 타입 | 시급 고정 | `user_salary_wage` -- 직원별 시급/일급/월급 설정 |
| 수당 적용 여부 | 전체 일괄 | `user_salary_allowances` -- 직원별 주휴/연장/야간/휴일 ON/OFF |
| 공제 적용 여부 | 전체 일괄 | `user_salary_deductions` -- 직원별 세율, 4대보험 각각 ON/OFF |
| 보험 금액 | 고정값 | `user_insurance_amount` -- 직원별 커스텀 금액 입력 |
| 특정일 시급 | 불가 | `user_daily_wage` -- 특정 날짜만 시급 다르게 설정 |
| 가산수당 요율 | 상수 50% | `legal_allowance_policy` -- 지점별 요율 설정 |

---

## 해결

### 전체 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                         사장님이 설정                                │
│                                                                     │
│  user_salary_wage          user_salary_allowances                    │
│  (시급/일급/월급 설정)      (수당 ON/OFF)                             │
│                                                                     │
│  user_salary_deductions    user_insurance_amount                     │
│  (공제 ON/OFF)              (4대보험 금액)                            │
│                                                                     │
│  user_daily_wage           legal_allowance_policy                    │
│  (특정일 시급 변경)         (가산수당 요율)                            │
│                                                                     │
│  minimum_wage                                                       │
│  (연도별 최저시급)                                                    │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ 설정 참조 (캐싱됨)
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     근무 스케줄 확정                                  │
│                                                                     │
│  schedule (근무 일정)                                                │
│      │                                                              │
│      │ 스케줄 생성/수정 시 자동 트리거                                  │
│      ▼                                                              │
│  SalaryCalculateService                                             │
│  ├── 시급 결정 (설정 테이블 조회, 우선순위 적용)                        │
│  ├── 근무시간 계산 (휴게시간 차감)                                     │
│  ├── 야간/휴일/연장 수당 계산 (AllowanceCalculator)                   │
│  ├── 공제 계산 (3.3% + 4대보험)                                      │
│  └── 계산 근거 생성 (WageReason, OvertimeReason, DeductionReason)    │
│      │                                                              │
│      ▼                                                              │
│  daily_user_salary (일별 급여 저장)                                   │
│      │                                                              │
│      │ 집계                                                          │
│      ▼                                                              │
│  weekly_user_salary (주별 급여 + 주휴수당)                             │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       프론트 조회                                     │
│                                                                     │
│  GET /summary-list ──→ SalarySummaryService                         │
│  (전 직원 요약)          └── 기간 내 daily/weekly 집계 → 요약 응답     │
│                                                                     │
│  GET /detail ────────→ SalarySummaryService                         │
│  (직원 1명 기간 상세)    └── 일별 상세 + 주별 수당 + 연장 내역         │
│                                                                     │
│  GET /policy-detail ─→ DetailedSalaryService                        │
│  (근무 1건 명세)         └── 계산 근거(Reason) 포함 상세 응답          │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       급여 확정                                      │
│                                                                     │
│  SalaryConfirmationService                                          │
│  ├── 개별 확정 (confirmIndividualSalary)                              │
│  │   └── reason_snapshot에 확정 시점 계산 근거 JSON 저장              │
│  └── 전체 확정 (confirmBulkSalaries)                                 │
│      └── 해당 기간 전 직원 일괄 확정                                   │
│                                                                     │
│  salary_confirmation (확정 이력 + 스냅샷 보존)                        │
│  branch_bulk_confirmation (지점 전체 확정 이력)                       │
└─────────────────────────────────────────────────────────────────────┘
```

흐름 요약 -- 설정(시급/수당/공제) -> 스케줄 확정 시 자동 급여 계산 -> 프론트 조회(요약/상세/명세) -> 사장님 확정(근거 스냅샷 보존)

### 캐싱 전략 (Caffeine)

| 변경 빈도 | 테이블 | 캐시 | TTL |
|----------|--------|------|-----|
| 거의 안 변함 | `minimum_wage` | `minimumWageCache` | 24시간 |
| 거의 안 변함 | `user_salary_deductions` | `userDeductionsCache` | 24시간 (변경 시 evict) |
| 거의 안 변함 | `user_insurance_amount` | `userInsuranceAmountCache` | 24시간 (변경 시 evict) |
| 거의 안 변함 | `user_salary_allowances` | `userAllowancesCache` | 24시간 (변경 시 evict) |
| 가끔 변함 | `legal_allowance_policy` | `wagePolicyCache` | 1시간 |
| 가끔 변함 | `salary_history` | `salaryHistoryCache` | 1시간 |
| 자주 변함 | `daily_user_salary` | 없음 (매번 계산) | - |
| 집계 결과 | `weekly_user_salary` | `salarySummaryCache` | 30분 |

설정성 데이터는 자주 바뀌지 않으므로 긴 TTL로 캐싱하고, 변경 시 `@CacheEvict`로 즉시 무효화한다.

### 시급 결정 우선순위 체인

```
① user_daily_wage        (특정일 시급 override)
        │ 없으면
        ▼
② salary_history          (시급 변경 이력, 기간별)
        │ 없으면
        ▼
③ user_branch.personal_cost (직원별 기본 시급)
        │ 없으면
        ▼
④ branch.basic_cost       (지점 기본 시급)
        │ 없으면
        ▼
⑤ minimum_wage            (연도별 법정 최저시급)
```

### 급여 계산 파이프라인

```
스케줄 확정
    │
    ▼
① 시급 결정 (5단계 우선순위 체인)
   [캐싱] salaryHistoryCache 1h / basicSalaryCache 1h / minimumWageCache 24h
    │
    ▼
② 근무시간 계산 — 캐싱 없음 (매번 계산)
   총 근무분 - 휴게시간 = 실근무분
   (자정 넘는 경우: 23:59까지 + 00:00부터 별도 계산)
    │
    ▼
③ 기본급 계산 (급여 타입별) — 캐싱 없음 (매번 계산)
   HOURLY:  시급 x 실근무분 / 60
   DAILY:   일급 고정
   MONTHLY: (월급 / 209시간) x 실근무분 / 60
    │
    ▼
④ 가산수당 (시간 슬롯별 판정)
   [캐싱] userAllowancesCache 24h / wagePolicyCache 1h
   야간 (22~06시)     → +50%
   연장 (일8h/주40h 초과) → +50%
   휴일 (공휴일/일요) → +50% (8h이내) / +100% (8h초과)
   중복 시 요율 합산
    │
    ▼
⑤ 일별 급여 저장 (daily_user_salary) — 캐싱 없음
   = 기본급 + 가산수당 (- 3.3%)
    │
    ▼ 주 단위 집계
⑥ 주간 합산 + 주휴수당 판정
   [캐싱] salarySummaryCache 30m
   조건: allowance ON + 주 15시간 이상
   주휴수당 = 시급 x min(주근무분, 2400) x 8 / 2400
    │
    ▼
⑦ 공제
   [캐싱] userDeductionsCache 24h / userInsuranceAmountCache 24h
   세전 = 기본급 + 수당합계 + 주휴수당
   - 3.3% 원천징수 (ON/OFF)
   - 국민연금 (ON/OFF, 정액)
   - 건강보험 (ON/OFF, 정액)
   - 고용보험 (ON/OFF, 정액)
   - 산재보험 (ON/OFF, 정액)
   세후 = 세전 - 세금 - 보험합계
    │
    ▼
⑧ weekly_user_salary 저장 — 캐싱 없음
```

### 테이블 구조

일별 급여 `daily_user_salary`

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | |
| user_id | BIGINT | 직원 |
| branch_id | BIGINT | 지점 |
| schedule_id | BIGINT FK | schedule 참조 (ON DELETE SET NULL) |
| work_date | DATE | 근무일 |
| work_time | VARCHAR | "18:00 - 02:00" |
| start_time / end_time | DATETIME | 출퇴근 시간 |
| work_type | TEXT (JSON) | ["open"], ["close"] 등 |
| worked_minutes | INT | 실 근무시간 (휴게시간 제외) |
| original_minutes | INT | 원래 근무시간 (휴게시간 제외 전) |
| daily_salary | DECIMAL(10,0) | 일 급여 |
| break_time_enabled | BOOLEAN | 휴게시간 적용 여부 |
| break_time_minutes | INT | 휴게시간(분) |
| is_break_time_night | BOOLEAN | 야간 휴게 여부 |

유니크: `(user_id, branch_id, work_date, start_time, end_time)` -- 하루 다건 교대 지원

주별 급여 `weekly_user_salary`

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | |
| user_id / branch_id | BIGINT | |
| year / month / week | INT | 연/월/주차 |
| total_minutes | INT | 주간 총 근무시간(분) |
| calculated_salary | DECIMAL(10,0) | 주간 급여 |
| weekly_allowance_eligible | TINYINT(1) | 주휴수당 자격 여부 |
| weekly_allowance_amount | DECIMAL(10,0) | 주휴수당 금액 |
| total_amount | DECIMAL(10,0) | 주간 총액 |

유니크: `(user_id, branch_id, year, month, week)`

급여 확정 `salary_confirmation`

| 컬럼 | 타입 | 설명 |
|------|------|------|
| id | BIGINT PK | |
| branch_id / work_date / user_id | | |
| confirmed_at | DATETIME | 확정 시점 |
| is_bulk_confirmation | BOOLEAN | 전체 확정 여부 |
| reason_snapshot | TEXT | 계산 근거 JSON 스냅샷 |

유니크: `(branch_id, work_date, user_id)`

`reason_snapshot`에 확정 시점의 계산 근거를 JSON으로 저장한다. 이후 설정이 바뀌어도 확정 당시의 계산 근거가 보존된다.

### 가산수당 요율 상수

| 구분 | 기준 | 값 |
|------|------|-----|
| 야간 시작 | NIGHT_START_TIME | 22:00 |
| 야간 종료 | NIGHT_END_TIME | 06:00 |
| 일 기준 | DAILY_WORK_LIMIT | 8시간 (480분) |
| 주 기준 | WEEKLY_WORK_LIMIT | 40시간 (2400분) |
| 야간수당 | MIN_NIGHT_RATE | 50% |
| 연장수당 | MIN_OVERTIME_RATE | 50% |
| 휴일수당 (8h이내) | MIN_HOLIDAY_RATE | 50% |
| 휴일수당 (8h초과) | MIN_HOLIDAY_OVERTIME_RATE | 100% |

중복 적용 예: 일요일 23:00~03:00 근무 -> 휴일(50%) + 야간(50%) = 100% 가산

### 계산 근거 스냅샷 구조 (`reason_snapshot`)

확정 시 JSON 직렬화하여 `salary_confirmation.reason_snapshot`에 저장한다.

```
ReasonSnapshot
├── workTime: "18:00 - 02:00"
├── wageReason        ← 시급 결정 출처 (user_daily_wage / salary_history / minimum_wage 등)
├── overtimeReason    ← 연장수당 적용/미적용 근거
├── nightReason       ← 야간수당 적용 근거
├── holidayReason     ← 휴일수당 적용 근거
└── deductionReason   ← 공제(세금/4대보험) 근거
```

WageResolutionReason
- wageType: "HOURLY" / "DAILY" / "MONTHLY"
- resolvedSource: 시급을 어디서 가져왔는지
- hourlyWage: 적용된 시급

OvertimeReason
- settingEnabled / applied / notAppliedReason
- dailyThresholdMinutes (480) / actualDailyMinutes

DeductionReason
- tax: { enabled, rate, baseAmount, deductedAmount }
- insurance: { 국민연금/건강/고용/산재 각각 enabled + amount }

---

## 결과

### API 전후 비교

급여 요약 (`GET /summary-list`)

| 항목 | BEFORE | AFTER |
|------|--------|-------|
| 전체 확정 여부 | 없음 | `isAllConfirmed` 추가 |
| 역할 | 없음 | `role` 추가 |
| 기본급 | `totalSalary` (모호) | `baseSalary` (수당 미포함 명확) |
| 세전/세후 | `finalSalary` / `finalTaxSalary` | `totalBeforeTax` / `totalAfterTax` |

급여 상세 명세 (`GET /policy-detail`)

| 항목 | BEFORE | AFTER |
|------|--------|-------|
| 급여 구분 | `totalSalary` 하나 | `baseSalary`(기본급) + `totalSalary`(세후) 분리 |
| 야간수당 | 없음 | `night.items[]` -- 시간대, 금액, 시급, 근무시간 상세 |
| 휴일수당 | 없음 | `holiday.items[]` -- 휴일 종류, 8시간 이내 여부 |
| 공제 설정 | 없음 | `fourMajorInsurance` -- 4대보험 각각 ON/OFF |
| 공제 금액 | 없음 | `insuranceAmounts` -- 보험별 금액 |
| 시급 이력 | 없음 | `wageHistory[]` -- 기간별 시급 변경 추적 |
| 시급 결정 근거 | 없음 | `wageReason` -- 어디서 시급을 가져왔는지 |
| 연장수당 근거 | 없음 | `overtimeReason` -- 왜 적용/미적용됐는지 |
| 공제 근거 | 없음 | `deductionReason` -- 세금/보험 계산 과정 |

API 역할 비교

| API | 용도 | 단위 |
|-----|------|------|
| `summary-list` | 전 직원 급여 한눈에 보기 | 기간 x 전체 직원 |
| `detail` | 특정 직원 기간 상세 (일별 + 주별 수당) | 기간 x 1명 |
| `policy-detail` | 특정 근무 1건의 계산 근거 명세 | 1일 x 1명 |
