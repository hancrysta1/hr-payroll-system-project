package example.salary.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 시간별 가산수당 계산 — V1 {@code AllowanceCalculator} 로직과 동일
 *
 * <p><b>V1과 동일한 arithmetic 보장:</b> 함수 시그니처·내부 분기·BigDecimal 연산 순서 모두 그대로.
 * 차이점은 Spring bean 의존성(userSalaryAllowancesService, koreanPublicHolidayService) 제거 →
 * 호출자가 {@link AllowanceSettings}와 {@code Set<LocalDate>} 공휴일 셋을 인자로 주입.</p>
 *
 * <h3>지원 수당:</h3>
 * <ul>
 *   <li>연장근로(50%): 일 8시간 또는 주 40시간 초과분</li>
 *   <li>야간근로(50%): 22:00~06:00 (휴게시간 제외)</li>
 *   <li>휴일근로(50% + 8h 초과 100%): 공휴일·일요일</li>
 * </ul>
 */
public final class AllowanceCalc {

    private AllowanceCalc() {}

    /**
     * 시간별 가산수당 계산.
     *
     * @param settings        수당 설정(weeklyAllowance, overtimeEnabled, nightWorkEnabled, holidayWorkEnabled)
     * @param workStart       근무 시작 LocalDateTime
     * @param workEnd         근무 종료 LocalDateTime
     * @param baseSalary      기본 시급 (BigDecimal, 원)
     * @param dailyWorkedMinutes  해당 일의 총 근무분
     * @param weeklyWorkedMinutes 해당 주의 총 근무분
     * @param breakTimeRanges 휴게시간 구간 리스트 (null 허용)
     * @param publicHolidays  공휴일 날짜 집합 (일요일은 자동 포함되지 않음 — 일요일 체크는 내부에서)
     * @return 시간 슬롯별 계산 결과
     */
    public static Map<String, AllowanceResult> calculateHourlyAllowances(
            AllowanceSettings settings,
            LocalDateTime workStart, LocalDateTime workEnd,
            BigDecimal baseSalary, int dailyWorkedMinutes, int weeklyWorkedMinutes,
            List<BreakTimeRange> breakTimeRanges,
            Set<LocalDate> publicHolidays) {

        Map<String, AllowanceResult> results = new LinkedHashMap<>();

        if (breakTimeRanges == null) {
            breakTimeRanges = new ArrayList<>();
        }
        if (publicHolidays == null) {
            publicHolidays = Set.of();
        }

        LocalDateTime current = workStart;
        int cumulativeMinutes = 0;
        while (current.isBefore(workEnd)) {
            LocalDateTime nextHour = current.plusHours(1);
            if (nextHour.isAfter(workEnd)) {
                nextHour = workEnd;
            }

            LocalDateTime nextBoundary = getNextTimeBoundary(current, workEnd);
            if (nextBoundary != null && nextBoundary.isBefore(nextHour)) {
                nextHour = nextBoundary;
            }

            long minutes = ChronoUnit.MINUTES.between(current, nextHour);
            if (minutes <= 0) {
                current = nextHour;
                continue;
            }

            String timeSlot = current.toLocalTime() + "~" +
                (nextHour.toLocalTime().equals(LocalTime.MIDNIGHT) ? "00:00" : nextHour.toLocalTime());

            AllowanceResult result = calculateAllowanceForTimeSlot(
                settings, current, nextHour, baseSalary, minutes,
                dailyWorkedMinutes, weeklyWorkedMinutes, cumulativeMinutes,
                breakTimeRanges, publicHolidays
            );

            cumulativeMinutes += (int) minutes;
            results.put(timeSlot, result);
            current = nextHour;
        }

        return results;
    }

    /** 다음 시간 경계 — 자정 / 야간 시작(22:00) / 야간 종료(06:00). V1 원본 그대로. */
    private static LocalDateTime getNextTimeBoundary(LocalDateTime current, LocalDateTime workEnd) {
        LocalTime currentTime = current.toLocalTime();
        LocalDate currentDate = current.toLocalDate();

        List<LocalDateTime> boundaries = new ArrayList<>();

        if (!currentTime.equals(LocalTime.MIDNIGHT)) {
            LocalDateTime midnight = currentDate.plusDays(1).atStartOfDay();
            if (current.isBefore(midnight) && (midnight.isBefore(workEnd) || midnight.equals(workEnd))) {
                boundaries.add(midnight);
            }
        }

        LocalDateTime nightStart = currentDate.atTime(LegalAllowanceConstants.NIGHT_START_TIME);
        if (current.isBefore(nightStart) && (nightStart.isBefore(workEnd) || nightStart.equals(workEnd))) {
            boundaries.add(nightStart);
        }

        LocalDateTime nightEnd = currentDate.atTime(LegalAllowanceConstants.NIGHT_END_TIME);
        if (nightEnd.isBefore(current) || nightEnd.equals(current)) {
            nightEnd = currentDate.plusDays(1).atTime(LegalAllowanceConstants.NIGHT_END_TIME);
        }
        if (current.isBefore(nightEnd) && (nightEnd.isBefore(workEnd) || nightEnd.equals(workEnd))) {
            boundaries.add(nightEnd);
        }

        return boundaries.stream().min(LocalDateTime::compareTo).orElse(null);
    }

    private static AllowanceResult calculateAllowanceForTimeSlot(
            AllowanceSettings settings, LocalDateTime start, LocalDateTime end,
            BigDecimal baseSalary, long minutes, int dailyWorkedMinutes, int weeklyWorkedMinutes,
            int cumulativeMinutes,
            List<BreakTimeRange> breakTimeRanges,
            Set<LocalDate> publicHolidays) {

        AllowanceResult result = new AllowanceResult();
        result.timeSlot = start.toLocalTime() + "~" + end.toLocalTime();
        result.minutes = minutes;
        result.baseSalary = baseSalary;

        BigDecimal totalAllowanceRate = BigDecimal.ZERO;
        List<String> appliedAllowances = new ArrayList<>();

        // 1. 연장근로 — 8시간 초과분만 50%
        BigDecimal overtimeAllowanceAmount = BigDecimal.ZERO;
        if (settings.overtimeEnabled) {
            int dailyThreshold = LegalAllowanceConstants.DAILY_WORK_LIMIT * 60;
            boolean isDailyOvertime = dailyWorkedMinutes > dailyThreshold;
            boolean isWeeklyOvertime = weeklyWorkedMinutes > LegalAllowanceConstants.WEEKLY_WORK_LIMIT * 60;

            if (isDailyOvertime || isWeeklyOvertime) {
                int slotStart = cumulativeMinutes;
                int slotEnd = cumulativeMinutes + (int) minutes;
                int overtimeMinutes = Math.max(0, slotEnd - Math.max(slotStart, dailyThreshold));

                if (overtimeMinutes > 0) {
                    overtimeAllowanceAmount = baseSalary
                        .multiply(LegalAllowanceConstants.MIN_OVERTIME_RATE.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP))
                        .multiply(BigDecimal.valueOf(overtimeMinutes))
                        .divide(BigDecimal.valueOf(60), 0, RoundingMode.HALF_UP);
                    appliedAllowances.add("연장근로(" + LegalAllowanceConstants.MIN_OVERTIME_RATE + "%," + overtimeMinutes + "분)");
                }
            }
        }

        // 2. 야간근로 — 휴게시간 제외
        boolean isNight = isNightTimeSlot(start);
        boolean isBreakTime = isBreakTimeSlot(start, end, breakTimeRanges);

        if (settings.nightWorkEnabled && isNight && !isBreakTime) {
            totalAllowanceRate = totalAllowanceRate.add(LegalAllowanceConstants.MIN_NIGHT_RATE);
            appliedAllowances.add("야간근로(" + LegalAllowanceConstants.MIN_NIGHT_RATE + "%)");
        }

        // 3. 휴일근로 — 8시간 이내 50%, 8시간 초과 100%
        LocalDate workDate = start.toLocalDate();
        BigDecimal holidayAllowanceAmount = BigDecimal.ZERO;
        if (settings.holidayWorkEnabled && isHoliday(workDate, publicHolidays)) {
            int threshold = LegalAllowanceConstants.HOLIDAY_OVERTIME_THRESHOLD * 60;
            int slotStart = cumulativeMinutes;
            int slotEnd = cumulativeMinutes + (int) minutes;

            int within8h = Math.max(0, Math.min(threshold, slotEnd) - slotStart);
            if (within8h < 0) within8h = 0;
            int over8h = (int) minutes - within8h;

            if (within8h > 0) {
                BigDecimal amount = baseSalary
                    .multiply(LegalAllowanceConstants.MIN_HOLIDAY_RATE.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP))
                    .multiply(BigDecimal.valueOf(within8h))
                    .divide(BigDecimal.valueOf(60), 0, RoundingMode.HALF_UP);
                holidayAllowanceAmount = holidayAllowanceAmount.add(amount);
                appliedAllowances.add("휴일근로(" + LegalAllowanceConstants.MIN_HOLIDAY_RATE + "%," + within8h + "분)");
            }
            if (over8h > 0) {
                BigDecimal amount = baseSalary
                    .multiply(LegalAllowanceConstants.MIN_HOLIDAY_OVERTIME_RATE.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP))
                    .multiply(BigDecimal.valueOf(over8h))
                    .divide(BigDecimal.valueOf(60), 0, RoundingMode.HALF_UP);
                holidayAllowanceAmount = holidayAllowanceAmount.add(amount);
                appliedAllowances.add("휴일연장(" + LegalAllowanceConstants.MIN_HOLIDAY_OVERTIME_RATE + "%," + over8h + "분)");
            }
        }

        // 4. 최종 계산 — 기본급 + 가산수당 분리
        BigDecimal baseAmount = baseSalary
            .multiply(BigDecimal.valueOf(minutes))
            .divide(BigDecimal.valueOf(60), 0, RoundingMode.HALF_UP);

        BigDecimal allowanceAmount;
        // 야간 금액만 별도 (totalAllowanceRate에는 야간 50%만 들어있음)
        BigDecimal nightAmount = BigDecimal.ZERO;
        if (totalAllowanceRate.compareTo(BigDecimal.ZERO) > 0) {
            nightAmount = baseSalary
                .multiply(totalAllowanceRate.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP))
                .multiply(BigDecimal.valueOf(minutes))
                .divide(BigDecimal.valueOf(60), 0, RoundingMode.HALF_UP);
        }
        allowanceAmount = nightAmount.add(overtimeAllowanceAmount).add(holidayAllowanceAmount);

        result.allowanceRate = totalAllowanceRate;
        result.baseAmount = baseAmount;
        result.allowanceAmount = allowanceAmount;
        result.totalAmount = baseAmount.add(allowanceAmount);
        result.appliedAllowances = appliedAllowances;

        // 수당별 금액 분리 저장 (adapter가 집계할 때 사용)
        result.overtimeAmount = overtimeAllowanceAmount;
        result.nightAmount = nightAmount;
        result.holidayAmount = holidayAllowanceAmount;

        return result;
    }

    /** 야간 시간대 판단 — 시작 시각만 체크 (경계에서 분할되므로). V1 원본 그대로. */
    private static boolean isNightTimeSlot(LocalDateTime start) {
        LocalTime startTime = start.toLocalTime();
        if (startTime.isBefore(LegalAllowanceConstants.NIGHT_END_TIME)) return true;
        return startTime.equals(LegalAllowanceConstants.NIGHT_START_TIME)
            || startTime.isAfter(LegalAllowanceConstants.NIGHT_START_TIME);
    }

    /** 휴게시간 구간과 겹치는지. V1 원본 그대로. */
    private static boolean isBreakTimeSlot(LocalDateTime start, LocalDateTime end, List<BreakTimeRange> breakTimeRanges) {
        if (breakTimeRanges == null || breakTimeRanges.isEmpty()) return false;

        for (BreakTimeRange breakRange : breakTimeRanges) {
            LocalDateTime breakStart = breakRange.date().atTime(breakRange.startTime());
            LocalDateTime breakEnd = breakRange.date().atTime(breakRange.endTime());
            if (breakRange.endTime().isBefore(breakRange.startTime()) || breakRange.endTime().equals(LocalTime.MIDNIGHT)) {
                breakEnd = breakRange.date().plusDays(1).atTime(breakRange.endTime());
            }
            if (!start.isAfter(breakEnd) && !end.isBefore(breakStart)) return true;
        }
        return false;
    }

    /** 공휴일 판단 — 공휴일 집합 + 일요일(getDayOfWeek() == 7). */
    private static boolean isHoliday(LocalDate date, Set<LocalDate> publicHolidays) {
        return publicHolidays.contains(date) || date.getDayOfWeek().getValue() == 7;
    }

    // ── 타입 ──

    /** 가산수당 설정 — V1 {@code UserSalaryAllowances} 값 전달용. */
    public record AllowanceSettings(
        boolean weeklyAllowance,
        boolean overtimeEnabled,
        boolean nightWorkEnabled,
        boolean holidayWorkEnabled
    ) {
        public static final AllowanceSettings NONE = new AllowanceSettings(false, false, false, false);
    }

    /** 휴게시간 구간 — V1 {@code SalaryCalculateService.BreakTimeRange} 대응. */
    public record BreakTimeRange(LocalDate date, LocalTime startTime, LocalTime endTime, int minutes) {}

    /** 시간 슬롯별 계산 결과. V2 전용 확장: overtimeAmount/nightAmount/holidayAmount 분리 저장. */
    public static final class AllowanceResult {
        public String timeSlot;
        public long minutes;
        public BigDecimal baseSalary;
        public BigDecimal baseAmount = BigDecimal.ZERO;
        public BigDecimal allowanceRate = BigDecimal.ZERO;
        public BigDecimal allowanceAmount = BigDecimal.ZERO;  // 수당 합계 (overtime + night + holiday)
        public BigDecimal totalAmount = BigDecimal.ZERO;
        public List<String> appliedAllowances = new ArrayList<>();

        // V2 확장 — 수당 종류별 분리 (adapter 집계용)
        public BigDecimal overtimeAmount = BigDecimal.ZERO;
        public BigDecimal nightAmount = BigDecimal.ZERO;
        public BigDecimal holidayAmount = BigDecimal.ZERO;

        // V1 호환 getters
        public String getTimeSlot() { return timeSlot; }
        public long getMinutes() { return minutes; }
        public BigDecimal getBaseSalary() { return baseSalary; }
        public BigDecimal getBaseAmount() { return baseAmount; }
        public BigDecimal getAllowanceRate() { return allowanceRate; }
        public BigDecimal getAllowanceAmount() { return allowanceAmount; }
        public BigDecimal getTotalAmount() { return totalAmount; }
        public List<String> getAppliedAllowances() { return appliedAllowances; }
        public BigDecimal getOvertimeAmount() { return overtimeAmount; }
        public BigDecimal getNightAmount() { return nightAmount; }
        public BigDecimal getHolidayAmount() { return holidayAmount; }
    }
}
