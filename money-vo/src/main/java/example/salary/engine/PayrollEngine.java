package example.salary.engine;

import example.salary.rates.Rates;

import java.util.ArrayList;
import java.util.List;

/**
 * Payroll Compute
 *
 *
 * <p>요율({@link Rates})과 간이세액표({@link TaxTableEntry} 리스트)는 호출자가 주입.
 * 연도·출처를 모름 — 그래서 다년도 정산·과거 재계산이 자연스럽게 된다.</p>
 */
public class PayrollEngine {

    public static Result compute(Request request, Rates rates, List<TaxTableEntry> taxTable) {
        List<Warning> warnings = new ArrayList<>();

        // ── 1. 과세/비과세 분리 ──
        long taxableTotal = 0;
        long nonTaxableTotal = 0;
        long nonTaxableOver = 0;

        for (PayItem item : request.payItems) {
            if (item.category == PayCategory.TAXABLE) {
                taxableTotal += item.amount;
            } else if (item.category == PayCategory.NON_TAXABLE) {
                long limit = getTaxFreeLimit(item.taxFreeType, request.extension.childCount, rates);
                if (item.taxFreeType == null || item.taxFreeType.isEmpty()) {
                    // 비과세 타입 누락 → 전액 과세 전환
                    taxableTotal += item.amount;
                    warnings.add(new Warning("TAX_FREE_TYPE_MISSING",
                            item.name + " 비과세 타입 미지정 → 전액 과세 전환"));
                } else if (item.amount > limit) {
                    long over = item.amount - limit;
                    nonTaxableTotal += limit;
                    nonTaxableOver += over;
                    warnings.add(new Warning("TAX_FREE_OVER",
                            item.name + " 한도 초과분 " + over + "원 과세 전환"));
                } else {
                    nonTaxableTotal += item.amount;
                }
            }
        }

        // ── 2. 과세 기준 금액 ──
        long taxableBase = taxableTotal + nonTaxableOver;

        // 수습 감액
        if (request.extension.isProbation && request.extension.probationRate > 0
                && request.extension.probationRate < 1.0) {
            taxableBase = Math.round(taxableBase * request.extension.probationRate);
        }

        // ── 3. 공제 계산 ──
        long nationalPension = 0;
        long healthInsurance = 0;
        long longTermCare = 0;
        long employmentInsurance = 0;
        long incomeTax = 0;
        long localIncomeTax = 0;
        long manualDeductionsTotal = 0;

        switch (request.extension.deductionType) {
            case EMPLOYMENT_INCOME:
                nationalPension = computeNationalPension(taxableBase, rates);
                healthInsurance = computeHealthInsurance(taxableBase, rates);
                longTermCare = computeLongTermCare(healthInsurance, rates);
                employmentInsurance = computeEmploymentInsurance(taxableBase, rates);
                incomeTax = computeIncomeTax(taxableBase,
                        request.extension.familyCount,
                        request.extension.childCount,
                        request.extension.taxRateOption,
                        rates, taxTable);
                localIncomeTax = computeLocalIncomeTax(incomeTax, rates);
                break;

            case BUSINESS_INCOME:
                incomeTax = floor(taxableBase * rates.businessIncome().incomeTaxRate());
                localIncomeTax = floor(taxableBase * rates.businessIncome().localTaxRate());
                break;

            case MANUAL:
                // 수기 공제: 4대보험/세금 자동 계산 없음 (manualDeductions만 반영)
                break;

            default:
                break;
        }

        // 수기 공제 합산 (모든 타입에서 추가 가능)
        for (ManualDeduction d : request.manualDeductions) {
            manualDeductionsTotal += d.amount;
        }

        long totalDeduction = nationalPension + healthInsurance + longTermCare
                + employmentInsurance + incomeTax + localIncomeTax + manualDeductionsTotal;

        long grossPay = taxableTotal + nonTaxableTotal + nonTaxableOver;
        long netPay = grossPay - totalDeduction;

        if (netPay < 0) {
            warnings.add(new Warning("NEGATIVE_NET",
                    "실수령액이 음수입니다: " + netPay + "원"));
        }

        return new Result(
                grossPay, taxableTotal, nonTaxableTotal, nonTaxableOver, taxableBase,
                nationalPension, healthInsurance, longTermCare, employmentInsurance,
                incomeTax, localIncomeTax, manualDeductionsTotal,
                totalDeduction, netPay,
                rates.version(), warnings
        );
    }

    // ── 4대보험 ──

    static long computeNationalPension(long taxableBase, Rates rates) {
        Rates.InsuranceConfig c = rates.nationalPension();
        long clamped = clamp(taxableBase, c.minBase(), c.maxBase());
        return floor(clamped * c.rate());
    }

    static long computeHealthInsurance(long taxableBase, Rates rates) {
        Rates.InsuranceConfig c = rates.healthInsurance();
        long clamped = clamp(taxableBase, c.minBase(), c.maxBase());
        return floor(clamped * c.rate());
    }

    static long computeLongTermCare(long healthInsuranceAmount, Rates rates) {
        // 장기요양 = 건강보험료 × 요율 (건강보험료 기준, 보수월액 아님)
        return floor(healthInsuranceAmount * rates.longTermCareRateOnHealth());
    }

    static long computeEmploymentInsurance(long taxableBase, Rates rates) {
        return floor(taxableBase * rates.employmentInsuranceRate());
    }

    // ── 소득세: 간이세액표 조회 + 자녀 공제 ──

    static long computeIncomeTax(long taxableBase, int familyCount, int childCount,
                                  int taxRateOption, Rates rates, List<TaxTableEntry> taxTable) {
        // 1. 부양가족 수 (본인+배우자 포함, 최소 1)
        int family = Math.max(familyCount, 1);
        if (family > 11) family = 11; // 테이블 최대 11, 초과는 별도 공식

        // 2. 월급여액을 천원 단위로 변환
        int salaryInThousand = (int) (taxableBase / 1000);

        // 3. 간이세액표에서 조회
        long tableTax = lookupTaxTable(taxTable, salaryInThousand, family);

        // 4. 부양가족 11명 초과 처리
        if (familyCount > 11) {
            long tax10 = lookupTaxTable(taxTable, salaryInThousand, 10);
            long tax11 = lookupTaxTable(taxTable, salaryInThousand, 11);
            long diff = tax10 - tax11;
            tableTax = Math.max(0, tax11 - diff * (familyCount - 11));
        }

        // 5. 자녀 공제 (8~20세)
        long childDeduction = computeChildDeduction(childCount, rates);
        long tax = tableTax - childDeduction;
        if (tax < 0) tax = 0;

        // 6. 세율 옵션 적용 (80%, 100%, 120%)
        int option = (taxRateOption == 0) ? 100 : taxRateOption;
        tax = Math.round(tax * option / 100.0);

        // 7. 10원 미만 절사
        return (tax / 10) * 10;
    }

    /**
     * 간이세액표 조회
     * @param salaryInThousand 월급여 천원 단위
     * @param familyCount 부양가족 수 (1~11)
     */
    static long lookupTaxTable(List<TaxTableEntry> taxTable, int salaryInThousand, int familyCount) {
        if (taxTable == null || taxTable.isEmpty()) return 0;

        // 10,000천원 초과: 공식으로 계산
        if (salaryInThousand >= 10000) {
            long baseTax = lookupTaxTable10000(taxTable, familyCount);
            return computeOverTenMillion(baseTax, salaryInThousand);
        }

        // 테이블에서 구간 찾기
        for (TaxTableEntry entry : taxTable) {
            if (salaryInThousand >= entry.salaryFrom && salaryInThousand < entry.salaryTo) {
                return entry.getTaxByFamily(familyCount);
            }
        }

        return 0; // 구간 못 찾으면 0 (770천원 미만 등)
    }

    /**
     * 10,000천원 행의 세액 조회 (초과 공식의 base)
     */
    private static long lookupTaxTable10000(List<TaxTableEntry> taxTable, int familyCount) {
        for (TaxTableEntry entry : taxTable) {
            if (entry.salaryFrom == 10000 && entry.salaryTo == 10000) {
                return entry.getTaxByFamily(familyCount);
            }
        }
        return 0;
    }

    /**
     * 10,000천원 초과 소득세 계산
     */
    private static long computeOverTenMillion(long baseTax, int salaryInThousand) {
        long excess = salaryInThousand - 10000;
        if (excess <= 0) return baseTax;

        if (salaryInThousand <= 14000) {
            return baseTax + floor(excess * 1000 * 0.98 * 0.35) + 25000;
        } else if (salaryInThousand <= 28000) {
            long over14000 = salaryInThousand - 14000;
            return baseTax + 1_397_000 + floor(over14000 * 1000 * 0.98 * 0.38);
        } else if (salaryInThousand <= 30000) {
            long over28000 = salaryInThousand - 28000;
            return baseTax + 6_610_600 + floor(over28000 * 1000 * 0.98 * 0.40);
        } else if (salaryInThousand <= 45000) {
            long over30000 = salaryInThousand - 30000;
            return baseTax + 7_394_600 + floor(over30000 * 1000 * 0.40);
        } else if (salaryInThousand <= 87000) {
            long over45000 = salaryInThousand - 45000;
            return baseTax + 13_394_600 + floor(over45000 * 1000 * 0.42);
        } else {
            long over87000 = salaryInThousand - 87000;
            return baseTax + 31_034_600 + floor(over87000 * 1000 * 0.45);
        }
    }

    /**
     * 자녀 공제 계산 (간이세액표 조회 후 차감)
     * - 1명: childDeduction1
     * - 2명: childDeduction2
     * - 3명 이상: base + (초과 1명당 extra)
     */
    static long computeChildDeduction(int childCount, Rates rates) {
        if (childCount <= 0) return 0;
        Rates.IncomeTaxConfig c = rates.incomeTax();
        if (childCount == 1) return c.childDeduction1();
        if (childCount == 2) return c.childDeduction2();
        return c.childDeductionBase() + (long)(childCount - 2) * c.childDeductionExtra();
    }

    static long computeLocalIncomeTax(long incomeTax, Rates rates) {
        long raw = floor(incomeTax * rates.incomeTax().localRate());
        return (raw / 10) * 10; // 10원 미만 절사
    }

    // ── 비과세 한도 ──

    static long getTaxFreeLimit(String taxFreeType, int childCount, Rates rates) {
        if (taxFreeType == null) return 0;
        Rates.TaxFreeLimits limits = rates.taxFreeLimits();
        return switch (taxFreeType) {
            case "MEAL" -> limits.meal();
            case "CAR" -> limits.car();
            case "CHILDCARE" -> limits.childcarePerChild() * Math.max(childCount, 1);
            default -> 0;
        };
    }

    // ── 유틸 ──

    private static long floor(double value) {
        return (long) Math.floor(value);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(value, max));
    }

    // ── DTO ──

    public record Request(PayrollExtension extension, List<PayItem> payItems, List<ManualDeduction> manualDeductions) {}
    public record PayrollExtension(DeductionType deductionType, int familyCount, int childCount,
                                    int taxRateOption, boolean isProbation, double probationRate) {}
    public record PayItem(String name, PayCategory category, long amount, String taxFreeType) {}
    public record ManualDeduction(String name, long amount) {}
    public record Warning(String code, String message) {}
    public record Result(
            long grossPay, long taxableTotal, long nonTaxableTotal, long nonTaxableOver, long taxableBase,
            long nationalPension, long healthInsurance, long longTermCare, long employmentInsurance,
            long incomeTax, long localIncomeTax, long manualDeductionsTotal,
            long totalDeduction, long netPay,
            String ratesVersion, List<Warning> warnings
    ) {}
    public enum DeductionType { EMPLOYMENT_INCOME, BUSINESS_INCOME, MANUAL }
    public enum PayCategory { TAXABLE, NON_TAXABLE }

    /**
     * 간이세액표 1행 (DB simplified_tax_table 매핑)
     */
    public record TaxTableEntry(int salaryFrom, int salaryTo,
                                 long family1, long family2, long family3, long family4,
                                 long family5, long family6, long family7, long family8,
                                 long family9, long family10, long family11) {
        public long getTaxByFamily(int familyCount) {
            return switch (Math.min(Math.max(familyCount, 1), 11)) {
                case 1 -> family1;
                case 2 -> family2;
                case 3 -> family3;
                case 4 -> family4;
                case 5 -> family5;
                case 6 -> family6;
                case 7 -> family7;
                case 8 -> family8;
                case 9 -> family9;
                case 10 -> family10;
                case 11 -> family11;
                default -> 0;
            };
        }
    }
}
