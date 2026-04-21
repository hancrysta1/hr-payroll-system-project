package example.salary.rates;

/**
 * 급여 계산 요율·한도 마스터 (연도별).
 *
 * 불변 record. 컴파일 시점에 검증 가능한 구조로, 다년도 지원을 위해
 * {@link RatesRegistry}가 연도 → Rates 매핑을 관리한다.
 *
 * <p><b>단위 규약 (절대 변경 금지):</b></p>
 * <ul>
 *   <li>요율 {@code rate}: 소수 (0.0475 = 4.75%)</li>
 *   <li>기준금액 {@code minBase/maxBase}, 공제액, 한도: <b>원(KRW)</b></li>
 * </ul>
 */
public record Rates(
    String version,
    int year,
    InsuranceConfig nationalPension,
    InsuranceConfig healthInsurance,
    double longTermCareRateOnHealth,
    double employmentInsuranceRate,
    IncomeTaxConfig incomeTax,
    BusinessIncomeConfig businessIncome,
    TaxFreeLimits taxFreeLimits
) {
    /**
     * 보수월액 클램프가 있는 4대보험 요율 (국민연금·건강보험).
     * 고용보험은 클램프 없으므로 이 타입 안 씀.
     */
    public record InsuranceConfig(double rate, long minBase, long maxBase) {}

    /**
     * 근로소득세 보조 상수 (자녀공제 + 지방세 요율).
     * 본체는 간이세액표(외부 JSON)가 담당.
     */
    public record IncomeTaxConfig(
        long childDeduction1,      // 자녀 1명
        long childDeduction2,      // 자녀 2명
        long childDeductionBase,   // 3명 이상 기본 (= 2명값)
        long childDeductionExtra,  // 3명 초과 1명당 추가
        double localRate           // 지방소득세 요율 (= 소득세 × 10%)
    ) {}

    /**
     * 사업소득(3.3%) 원천징수 요율.
     */
    public record BusinessIncomeConfig(
        double incomeTaxRate,   // 0.03
        double localTaxRate     // 0.003
    ) {}

    /**
     * 비과세 한도 (월 기준).
     */
    public record TaxFreeLimits(
        long meal,                  // 식대
        long car,                   // 자가운전보조금
        long childcarePerChild      // 출산·자녀보육수당 (자녀 1명당)
    ) {}
}
