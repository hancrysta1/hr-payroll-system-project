package example.salary;

import example.salary.model.AllowanceRates;
import example.salary.model.AllowanceSettings;
import example.salary.model.DailySalaryCalculation;
import example.salary.vo.HourlyWage;
import example.salary.vo.Money;
import example.salary.vo.Percentage;
import example.salary.vo.WorkMinutes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DailySalaryCalculation — 일급여 계산 파이프라인")
class DailySalaryCalculationTest {

    // 시급, 설정, 가산율은 실서비스에서 DB/캐시에서 조회됨.
    // 테스트에서는 직접 주입하여 계산 로직만 검증.
    private final HourlyWage wage10000 = HourlyWage.fromHourly(BigDecimal.valueOf(10000));
    private final AllowanceRates legalRates = AllowanceRates.legalDefault();  // 법정 기본 50%

    @Nested
    @DisplayName("기본급 계산")
    class BaseSalary {

        @Test
        @DisplayName("시급 10,000원 x 8시간 = 80,000원")
        void hourly_type() {
            var calc = new DailySalaryCalculation(
                    "HOURLY", null, wage10000, AllowanceSettings.disabled(), legalRates);

            calc.calculate(WorkMinutes.of(480), 0, false);

            assertEquals(Money.of(80000), calc.getBaseSalary());
            assertEquals(Money.ZERO, calc.getAllowanceTotal());
            assertEquals(Money.of(80000), calc.getNetSalary());
        }

        @Test
        @DisplayName("일급제: 근무시간과 관계없이 고정 금액")
        void daily_type() {
            var calc = new DailySalaryCalculation(
                    "DAILY", Money.of(100000), wage10000, AllowanceSettings.disabled(), legalRates);

            calc.calculate(WorkMinutes.of(480), 0, false);

            assertEquals(Money.of(100000), calc.getBaseSalary());
        }
    }

    @Nested
    @DisplayName("가산수당 — DB에서 조회한 설정(ON/OFF)과 가산율에 따라 적용")
    class Allowances {

        @Test
        @DisplayName("야간수당: 시급 x 가산율(DB) x 야간시간")
        void night_allowance_with_policy_rate() {
            // 직원 설정: 야간수당 ON (DB: user_salary_allowances)
            var settings = new AllowanceSettings(false, true, false, false);
            // 지점 가산율: 50% (DB: legal_allowance_policy)
            var rates = AllowanceRates.legalDefault();

            var calc = new DailySalaryCalculation("HOURLY", null, wage10000, settings, rates);
            calc.calculate(WorkMinutes.of(480), 120, false);  // 야간 2시간

            // 10,000 x 50% x 120/60 = 10,000원
            assertEquals(Money.of(10000), calc.getNightAllowance());
        }

        @Test
        @DisplayName("지점별 커스텀 가산율 적용 — 70%로 설정된 경우")
        void custom_branch_rate() {
            var settings = new AllowanceSettings(false, true, false, false);
            // 이 지점은 야간 가산율을 70%로 설정 (DB에서 조회)
            var customRates = AllowanceRates.of(50, 70, 50);

            var calc = new DailySalaryCalculation("HOURLY", null, wage10000, settings, customRates);
            calc.calculate(WorkMinutes.of(480), 120, false);

            // 10,000 x 70% x 120/60 = 14,000원
            assertEquals(Money.of(14000), calc.getNightAllowance());
        }

        @Test
        @DisplayName("연장수당: 8시간 초과분에만 적용")
        void overtime_only_exceeding() {
            var settings = new AllowanceSettings(true, false, false, false);

            var calc = new DailySalaryCalculation("HOURLY", null, wage10000, settings, legalRates);
            calc.calculate(WorkMinutes.of(600), 0, false);  // 10시간

            // 기본급: 10,000 x 600/60 = 100,000
            // 연장: 10,000 x 50% x 120/60 = 10,000 (초과 2시간분)
            assertEquals(Money.of(100000), calc.getBaseSalary());
            assertEquals(Money.of(10000), calc.getOvertimeAllowance());
        }

        @Test
        @DisplayName("8시간 이하면 연장수당 없음 — 설정이 ON이어도 조건 미충족")
        void no_overtime_within_limit() {
            var settings = new AllowanceSettings(true, false, false, false);

            var calc = new DailySalaryCalculation("HOURLY", null, wage10000, settings, legalRates);
            calc.calculate(WorkMinutes.of(420), 0, false);  // 7시간

            assertEquals(Money.ZERO, calc.getOvertimeAllowance());
        }

        @Test
        @DisplayName("휴일수당: 휴일 근무 시 가산율 적용")
        void holiday_allowance() {
            var settings = new AllowanceSettings(false, false, true, false);

            var calc = new DailySalaryCalculation("HOURLY", null, wage10000, settings, legalRates);
            calc.calculate(WorkMinutes.of(480), 0, true);

            // 10,000 x 50% x 480/60 = 40,000
            assertEquals(Money.of(40000), calc.getHolidayAllowance());
        }

        @Test
        @DisplayName("야간 + 연장 + 휴일 중복 적용")
        void multiple_allowances() {
            var calc = new DailySalaryCalculation(
                    "HOURLY", null, wage10000, AllowanceSettings.allEnabled(), legalRates);

            calc.calculate(WorkMinutes.of(600), 120, true);  // 휴일 10시간, 야간 2시간

            assertEquals(Money.of(100000), calc.getBaseSalary());
            assertEquals(Money.of(10000), calc.getNightAllowance());
            assertEquals(Money.of(10000), calc.getOvertimeAllowance());
            assertEquals(Money.of(50000), calc.getHolidayAllowance());
            assertEquals(Money.of(70000), calc.getAllowanceTotal());
        }
    }

    @Nested
    @DisplayName("세금 차감")
    class Tax {

        @Test
        @DisplayName("3.3% 원천징수")
        void tax_deduction() {
            var settings = new AllowanceSettings(false, false, false, true);

            var calc = new DailySalaryCalculation("HOURLY", null, wage10000, settings, legalRates);
            calc.calculate(WorkMinutes.of(480), 0, false);

            // 세전: 80,000 / 세금: 80,000 x 3.3% = 2,640 / 세후: 77,360
            assertEquals(Money.of(2640), calc.getTaxDeduction());
            assertEquals(Money.of(77360), calc.getNetSalary());
        }
    }

    @Nested
    @DisplayName("전체 파이프라인 — DB에서 시급/설정/가산율 조회 → 계산 → 결과")
    class FullPipeline {

        @Test
        @DisplayName("시급(DB) + 야간수당(설정 ON, 가산율 50%) + 세금(3.3%)")
        void full_calculation() {
            // 실서비스 흐름:
            // 1. WageProvider.getHourlyWage(userId, branchId, workDate) → 시급 10,000원
            // 2. AllowanceSettingsProvider.getSettings(userId, branchId) → 야간 ON, 세금 ON
            // 3. AllowancePolicyProvider.getNightRate(branchId) → 50%
            // 4. DailySalaryCalculation에 주입하여 계산

            var settings = new AllowanceSettings(false, true, false, true);

            var calc = new DailySalaryCalculation("HOURLY", null, wage10000, settings, legalRates);
            calc.calculate(WorkMinutes.of(480), 120, false);

            // 기본급: 80,000
            // 야간수당: 10,000
            // 세전합계: 90,000
            // 세금: 90,000 x 3.3% = 2,970
            // 세후: 87,030
            assertEquals(Money.of(80000), calc.getBaseSalary());
            assertEquals(Money.of(10000), calc.getNightAllowance());
            assertEquals(Money.of(2970), calc.getTaxDeduction());
            assertEquals(Money.of(87030), calc.getNetSalary());
        }
    }
}
