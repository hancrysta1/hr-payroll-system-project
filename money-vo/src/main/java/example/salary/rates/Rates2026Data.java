package example.salary.rates;

/**
 * 2026년 요율·상수 — {@link Rates} 정의.
 *
 * 출처:
 * <ul>
 *   <li>4대보험: 국민연금공단·건강보험공단 2026년 고시</li>
 *   <li>소득세: 소득세법 제134조 + 간이세액표 (rates/simplified_tax_2026.json)</li>
 *   <li>비과세: 소득세법 시행령 제12조 (2026년 기준)</li>
 * </ul>
 *
 * 값 변경 시 PR에 고시 문서/법령 조항 링크를 반드시 첨부할 것.
 */
public final class Rates2026Data {

    public static final Rates INSTANCE = new Rates(
        /* version             */ "RATES_2026",
        /* year                */ 2026,

        /* nationalPension     */ new Rates.InsuranceConfig(
            /* rate    */ 0.0475,
            /* minBase */ 390_000L,
            /* maxBase */ 6_370_000L
        ),

        /* healthInsurance     */ new Rates.InsuranceConfig(
            /* rate    */ 0.03595,
            /* minBase */ 279_266L,
            /* maxBase */ 119_625_106L
        ),

        /* longTermCareRateOnHealth  */ 0.1314,
        /* employmentInsuranceRate   */ 0.009,

        /* incomeTax            */ new Rates.IncomeTaxConfig(
            /* childDeduction1     */ 20_830L,
            /* childDeduction2     */ 45_830L,
            /* childDeductionBase  */ 45_830L,
            /* childDeductionExtra */ 33_330L,
            /* localRate           */ 0.1
        ),

        /* businessIncome       */ new Rates.BusinessIncomeConfig(
            /* incomeTaxRate */ 0.03,
            /* localTaxRate  */ 0.003
        ),

        /* taxFreeLimits        */ new Rates.TaxFreeLimits(
            /* meal                */ 200_000L,
            /* car                 */ 200_000L,
            /* childcarePerChild   */ 200_000L
        )
    );

    private Rates2026Data() {}
}
