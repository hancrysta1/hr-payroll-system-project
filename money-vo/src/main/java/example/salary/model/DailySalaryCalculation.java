package example.salary.model;

import example.salary.vo.HourlyWage;
import example.salary.vo.Money;
import example.salary.vo.Percentage;
import example.salary.vo.WorkMinutes;

/**
 * 일급여 계산 도메인 모델
 *
 * POJO — JPA/Spring 의존 없음.
 * 기본급 → 가산수당 → 세금 차감 파이프라인을 하나의 도메인 모델에 응집.
 * 서비스는 입력만 넣고 결과만 꺼낸다.
 *
 * 시급, 가산율, 수당 설정은 외부(DB/캐시)에서 조회하여 주입.
 * 이 객체는 "주어진 조건으로 얼마인지 계산"하는 책임만 가진다.
 */
public class DailySalaryCalculation {

    private final String workType;
    private final Money amount;
    private final HourlyWage hourlyWage;
    private final AllowanceSettings settings;
    private final AllowanceRates rates;

    private Money baseSalary = Money.ZERO;
    private Money overtimeAllowance = Money.ZERO;
    private Money nightAllowance = Money.ZERO;
    private Money holidayAllowance = Money.ZERO;
    private Money allowanceTotal = Money.ZERO;
    private Money taxDeduction = Money.ZERO;
    private Money netSalary = Money.ZERO;

    public DailySalaryCalculation(String workType, Money amount, HourlyWage hourlyWage,
                                   AllowanceSettings settings, AllowanceRates rates) {
        this.workType = workType != null ? workType : "HOURLY";
        this.amount = amount != null ? amount : Money.ZERO;
        this.hourlyWage = hourlyWage;
        this.settings = settings != null ? settings : AllowanceSettings.disabled();
        this.rates = rates != null ? rates : AllowanceRates.legalDefault();
    }

    /**
     * 급여 계산 실행
     */
    public void calculate(WorkMinutes workedMinutes, int nightMinutes, boolean isHoliday) {
        this.baseSalary = calculateBaseSalary(workedMinutes);
        this.allowanceTotal = calculateAllowances(workedMinutes, nightMinutes, isHoliday);

        Money totalBeforeTax = baseSalary.add(allowanceTotal);

        if (settings.taxEnabled()) {
            this.taxDeduction = totalBeforeTax.taxAmount(Percentage.TAX_RATE_3_3);
            this.netSalary = totalBeforeTax.subtract(taxDeduction);
        } else {
            this.taxDeduction = Money.ZERO;
            this.netSalary = totalBeforeTax;
        }
    }

    private Money calculateBaseSalary(WorkMinutes workedMinutes) {
        return switch (workType) {
            case "DAILY" -> amount;
            case "MONTHLY", "HOURLY" -> hourlyWage.toMoney().multiplyByMinutes(workedMinutes);
            default -> hourlyWage.toMoney().multiplyByMinutes(workedMinutes);
        };
    }

    private Money calculateAllowances(WorkMinutes workedMinutes, int nightMinutes, boolean isHoliday) {
        Money total = Money.ZERO;
        Money hourlyMoney = hourlyWage.toMoney();

        // 가산율은 DB(legal_allowance_policy)에서 지점별로 조회된 값
        if (settings.nightWorkEnabled() && nightMinutes > 0) {
            this.nightAllowance = hourlyMoney
                    .multiply(rates.nightRate())
                    .multiplyByMinutes(WorkMinutes.of(nightMinutes));
            total = total.add(this.nightAllowance);
        }

        if (settings.overtimeEnabled() && workedMinutes.exceedsDailyLimit()) {
            WorkMinutes overtime = workedMinutes.overtimeMinutes();
            this.overtimeAllowance = hourlyMoney
                    .multiply(rates.overtimeRate())
                    .multiplyByMinutes(overtime);
            total = total.add(this.overtimeAllowance);
        }

        if (settings.holidayWorkEnabled() && isHoliday) {
            this.holidayAllowance = hourlyMoney
                    .multiply(rates.holidayRate())
                    .multiplyByMinutes(workedMinutes);
            total = total.add(this.holidayAllowance);
        }

        return total;
    }

    public Money getBaseSalary() { return baseSalary; }
    public Money getOvertimeAllowance() { return overtimeAllowance; }
    public Money getNightAllowance() { return nightAllowance; }
    public Money getHolidayAllowance() { return holidayAllowance; }
    public Money getAllowanceTotal() { return allowanceTotal; }
    public Money getTaxDeduction() { return taxDeduction; }
    public Money getNetSalary() { return netSalary; }
}
