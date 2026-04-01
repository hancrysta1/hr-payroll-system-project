package example.salary;

import example.salary.vo.Money;
import example.salary.vo.Percentage;
import example.salary.vo.WorkMinutes;
import example.salary.vo.HourlyWage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class MoneyVoTest {

    @Nested
    @DisplayName("Money — 금액은 항상 원 단위")
    class MoneyTest {

        @Test
        @DisplayName("어디서 만들든 소수점이 끼어들 수 없다")
        void always_rounds_to_won() {
            // 소수점이 있는 값으로 만들어도
            Money m = Money.of(new BigDecimal("10030.7"));
            assertEquals(Money.of(10031), m);  // 반올림됨
        }

        @Test
        @DisplayName("Money끼리만 연산 가능 — scale 불일치가 불가능한 구조")
        void add_always_consistent() {
            Money salary = Money.of(10030);
            Money allowance = Money.of(5015);
            Money total = salary.add(allowance);

            assertEquals(Money.of(15045), total);
        }

        @Test
        @DisplayName("시급 x 근무시간 = 급여")
        void multiply_by_minutes() {
            Money hourlyWage = Money.of(10000);           // 시급 10,000원
            WorkMinutes worked = WorkMinutes.of(480);      // 8시간

            Money baseSalary = hourlyWage.multiplyByMinutes(worked);
            // 10,000 x 480 / 60 = 80,000원
            assertEquals(Money.of(80000), baseSalary);
        }

        @Test
        @DisplayName("3.3% 원천징수")
        void tax_deduction() {
            Money total = Money.of(100000);

            Money tax = total.taxAmount(Percentage.TAX_RATE_3_3);
            assertEquals(Money.of(3300), tax);  // 100,000 x 3.3%

            Money afterTax = total.applyTax(Percentage.TAX_RATE_3_3);
            assertEquals(Money.of(96700), afterTax);  // 100,000 - 3,300
        }

        @Test
        @DisplayName("null이면 ZERO")
        void null_is_zero() {
            assertEquals(Money.ZERO, Money.of((BigDecimal) null));
        }
    }

    @Nested
    @DisplayName("WorkMinutes — 근무시간은 제약 조건을 가진다")
    class WorkMinutesTest {

        @Test
        @DisplayName("음수는 거부한다")
        void negative_rejected() {
            assertThrows(IllegalArgumentException.class, () -> WorkMinutes.of(-1));
        }

        @Test
        @DisplayName("8시간 초과 → 연장수당 대상")
        void exceeds_daily_limit() {
            WorkMinutes worked = WorkMinutes.of(600);  // 10시간
            assertTrue(worked.exceedsDailyLimit());
            assertEquals(WorkMinutes.of(120), worked.overtimeMinutes());  // 초과분 2시간
        }

        @Test
        @DisplayName("8시간 이하 → 연장수당 없음")
        void within_daily_limit() {
            WorkMinutes worked = WorkMinutes.of(420);  // 7시간
            assertFalse(worked.exceedsDailyLimit());
            assertEquals(WorkMinutes.ZERO, worked.overtimeMinutes());
        }

        @Test
        @DisplayName("정확히 8시간 → 초과 아님")
        void exactly_daily_limit() {
            WorkMinutes worked = WorkMinutes.of(480);
            assertFalse(worked.exceedsDailyLimit());
        }
    }

    @Nested
    @DisplayName("Percentage — 비율은 toDecimal()을 거쳐야 계산 가능")
    class PercentageTest {

        @Test
        @DisplayName("50 → 0.50 변환")
        void to_decimal() {
            assertEquals(
                    new BigDecimal("0.5000"),
                    Percentage.OVERTIME_RATE.toDecimal()
            );
        }

        @Test
        @DisplayName("3.3 → 0.033 변환")
        void tax_rate_to_decimal() {
            assertEquals(
                    new BigDecimal("0.0330"),
                    Percentage.TAX_RATE_3_3.toDecimal()
            );
        }
    }

    @Nested
    @DisplayName("HourlyWage — 급여 타입별 통상시급 계산")
    class HourlyWageTest {

        @Test
        @DisplayName("시급제: 금액 그대로")
        void hourly_type() {
            HourlyWage wage = HourlyWage.fromHourly(BigDecimal.valueOf(10000));
            assertEquals(Money.of(10000), wage.toMoney());
        }

        @Test
        @DisplayName("일급제: 일급 80,000원 ÷ 8시간 = 시급 10,000원")
        void daily_type() {
            HourlyWage wage = HourlyWage.fromDaily(BigDecimal.valueOf(80000), null);
            // 80,000 x 60 / 480 = 10,000
            assertEquals(Money.of(10000), wage.toMoney());
        }

        @Test
        @DisplayName("월급제: 월급 2,090,000원 ÷ 209시간 = 시급 10,000원")
        void monthly_type() {
            HourlyWage wage = HourlyWage.fromMonthly(BigDecimal.valueOf(2090000), null);
            // 2,090,000 x 60 / 12540 = 10,000
            assertEquals(Money.of(10000), wage.toMoney());
        }
    }
}
