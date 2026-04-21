package com.workpay.salary.integration;

import com.workpay.salary.domain.EmployeePayrollAutoTax;
import com.workpay.salary.domain.EmployeePayrollExtension;
import com.workpay.salary.domain.ManualDeductionTemplate;
import com.workpay.salary.domain.PayItemTemplate;
import com.workpay.salary.domain.TaxFreeType;
import com.workpay.salary.domain.UserInsuranceAmount;
import com.workpay.salary.dto.EngineDeductionDetailDTO;
import com.workpay.salary.repository.BranchPayItemTemplateRepository;
import com.workpay.salary.repository.EmployeePayrollAutoTaxRepository;
import com.workpay.salary.repository.EmployeePayrollExtensionRepository;
import com.workpay.salary.repository.ManualDeductionTemplateRepository;
import com.workpay.salary.repository.PayItemTemplateRepository;
import com.workpay.salary.repository.UserInsuranceAmountRepository;
import com.workpay.salary.service.payroll.PayrollComputeAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2 공제 통합테스트 — H2 in-memory DB.
 *
 * <h2>모델: 3-boolean 고용형태 × isAutoTax × 4대보험 마스킹</h2>
 * <ul>
 *   <li>고용형태는 {@code isEmployee / isFreelancer / isNoDeduction} 중 정확히 하나 true</li>
 *   <li>근로자(isEmployee=true)일 때만 isAutoTax와 4대보험 boolean이 의미를 가짐</li>
 *   <li>자동세금: {@code employee_payroll_auto_tax}의 family/child/taxRate 로 간이세액표 계산</li>
 *   <li>수동: {@code user_insurance_amount}의 4대보험 금액 + 소득세/지방세 그대로 공제</li>
 * </ul>
 *
 * <h2>페르소나: 김근로</h2>
 * 시급 12,000 × 200시간 = 2,400,000원
 */
@DataJpaTest(excludeAutoConfiguration = CacheAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:schema-payroll-v1v2.sql",
    "spring.datasource.url=jdbc:h2:mem:payroll_v1v2;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "eureka.client.enabled=false",
    "spring.cloud.discovery.enabled=false",
    "spring.cloud.compatibility-verifier.enabled=false",
    "spring.kafka.bootstrap-servers=",
    "spring.rabbitmq.listener.simple.auto-startup=false",
    "logging.level.com.github.loki4j=OFF"
})
@EnableAutoConfiguration(exclude = {
    org.springframework.cloud.client.discovery.simple.SimpleDiscoveryClientAutoConfiguration.class
})
@Import(PayrollComputeAdapter.class)
@DisplayName("V2 공제 — 김근로 페르소나 (H2)")
class PayrollV1V2ComparisonIntegrationTest {

    private static final Long USER_ID = 6096L;
    private static final Long BRANCH_ID = 1699L;
    private static final BigDecimal GROSS = new BigDecimal("2400000");

    @Autowired private EmployeePayrollExtensionRepository extensionRepo;
    @Autowired private EmployeePayrollAutoTaxRepository autoTaxRepo;
    @Autowired private UserInsuranceAmountRepository insuranceAmountRepo;
    @Autowired private PayItemTemplateRepository payItemRepo;
    @Autowired private ManualDeductionTemplateRepository manualDeductionRepo;
    @Autowired private BranchPayItemTemplateRepository branchPayItemRepo;
    @Autowired private PayrollComputeAdapter adapter;

    // ============================================================
    // 시나리오 ① — V2 설정 없음 + V1 설정도 없음 → 레거시 수동 경로, 공제 0
    // ============================================================

    @Test
    @DisplayName("[시나리오①] V2 ext 없음 + V1 설정 없음 → 레거시 수동 경로에서 공제 0, netPay = grossPay")
    void scenario1_noExt_noV1Settings() {
        EngineDeductionDetailDTO v2 = adapter.computeDeductionsForGross(USER_ID, BRANCH_ID, GROSS);

        assertThat(v2.getDeductionType()).isEqualTo("EMPLOYMENT_INCOME_MANUAL_INPUT");
        assertThat(v2.getNationalPension()).isEqualByComparingTo("0");
        assertThat(v2.getHealthInsurance()).isEqualByComparingTo("0");
        assertThat(v2.getIncomeTax()).isEqualByComparingTo("0");
        assertThat(v2.getLocalIncomeTax()).isEqualByComparingTo("0");
        assertThat(v2.getNetPay()).isEqualByComparingTo("2400000");
    }

    // ============================================================
    // 시나리오 ② — 공제없음 명시 설정
    // ============================================================

    @Test
    @DisplayName("[시나리오②] isNoDeduction=true → 세금·보험 전부 0")
    void scenario2_noDeduction() {
        extensionRepo.save(EmployeePayrollExtension.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .isEmployee(false).isFreelancer(false).isNoDeduction(true)
            .isAutoTax(false)
            .nationalPensionEnabled(false).healthInsuranceEnabled(false)
            .employmentInsuranceEnabled(false).industrialAccidentEnabled(false)
            .isProbation(false).probationRate(BigDecimal.ONE)
            .build());

        EngineDeductionDetailDTO v2 = adapter.computeDeductionsForGross(USER_ID, BRANCH_ID, GROSS);

        assertThat(v2.getDeductionType()).isEqualTo("NO_DEDUCTION");
        assertThat(v2.getNationalPension()).isEqualByComparingTo("0");
        assertThat(v2.getIncomeTax()).isEqualByComparingTo("0");
        assertThat(v2.getNetPay()).isEqualByComparingTo("2400000");
    }

    // ============================================================
    // 시나리오 ③ — 프리랜서 (3.3%)
    // ============================================================

    @Test
    @DisplayName("[시나리오③] isFreelancer=true → 3.3% 원천징수, 4대보험 0")
    void scenario3_freelancer() {
        extensionRepo.save(EmployeePayrollExtension.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .isEmployee(false).isFreelancer(true).isNoDeduction(false)
            .isAutoTax(false)
            .nationalPensionEnabled(false).healthInsuranceEnabled(false)
            .employmentInsuranceEnabled(false).industrialAccidentEnabled(false)
            .isProbation(false).probationRate(BigDecimal.ONE)
            .build());

        EngineDeductionDetailDTO v2 = adapter.computeDeductionsForGross(USER_ID, BRANCH_ID, GROSS);

        assertThat(v2.getDeductionType()).isEqualTo("BUSINESS_INCOME");
        assertThat(v2.getIncomeTax()).isEqualByComparingTo("72000");
        assertThat(v2.getLocalIncomeTax()).isEqualByComparingTo("7200");
        assertThat(v2.getNationalPension()).isEqualByComparingTo("0");
        assertThat(v2.getHealthInsurance()).isEqualByComparingTo("0");
        assertThat(v2.getNetPay()).isEqualByComparingTo("2320800");
    }

    // ============================================================
    // 시나리오 ④ — 근로자 + 자동세금 (누진세)
    // ============================================================

    @Test
    @DisplayName("[시나리오④] 근로자+자동 + 부양3·자녀1·수습0.9·식대200K → 간이세액표 자동")
    void scenario4_employee_auto_progressive_tax() {
        extensionRepo.save(EmployeePayrollExtension.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .isEmployee(true).isFreelancer(false).isNoDeduction(false)
            .isAutoTax(true)
            .nationalPensionEnabled(true).healthInsuranceEnabled(true)
            .employmentInsuranceEnabled(true).industrialAccidentEnabled(false)
            .isProbation(true).probationRate(new BigDecimal("0.90"))
            .build());

        autoTaxRepo.save(EmployeePayrollAutoTax.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .familyCount(3).childCount(1).taxRateOption(100)
            .build());

        payItemRepo.save(PayItemTemplate.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .name("식대").category(PayItemTemplate.PayCategory.NON_TAXABLE)
            .amount(200_000L).taxFreeType(TaxFreeType.MEAL).isActive(true)
            .build());

        EngineDeductionDetailDTO v2 = adapter.computeDeductionsForGross(USER_ID, BRANCH_ID, GROSS);

        assertThat(v2.getDeductionType()).isEqualTo("EMPLOYMENT_INCOME");
        assertThat(v2.getFamilyCount()).isEqualTo(3);
        assertThat(v2.getChildCount()).isEqualTo(1);
        assertThat(v2.getIsProbation()).isTrue();

        assertThat(v2.getGrossPay()).isEqualByComparingTo("2600000");
        assertThat(v2.getNonTaxableTotal()).isEqualByComparingTo("200000");
        assertThat(v2.getTaxableBase()).isEqualByComparingTo("2160000");

        assertThat(v2.getNationalPension()).isEqualByComparingTo("102600");
        assertThat(v2.getHealthInsurance()).isEqualByComparingTo("77652");
        assertThat(v2.getLongTermCare()).isEqualByComparingTo("10203");
        assertThat(v2.getEmploymentInsurance()).isEqualByComparingTo("19440");

        assertThat(v2.getIncomeTax()).isEqualByComparingTo("0");
        assertThat(v2.getLocalIncomeTax()).isEqualByComparingTo("0");

        assertThat(v2.getTotalDeduction()).isEqualByComparingTo("209895");
        assertThat(v2.getNetPay()).isEqualByComparingTo("2390105");
    }

    // ============================================================
    // 시나리오 ⑤ — 근로자 + 자동 + 보험 마스킹 (health off)
    // ============================================================

    @Test
    @DisplayName("[시나리오⑤] 근로자+자동 healthInsuranceEnabled=false → HI·LTC 0")
    void scenario5_employee_auto_health_masked() {
        extensionRepo.save(EmployeePayrollExtension.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .isEmployee(true).isFreelancer(false).isNoDeduction(false)
            .isAutoTax(true)
            .nationalPensionEnabled(true).healthInsuranceEnabled(false)
            .employmentInsuranceEnabled(true).industrialAccidentEnabled(false)
            .isProbation(false).probationRate(BigDecimal.ONE)
            .build());
        autoTaxRepo.save(EmployeePayrollAutoTax.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .familyCount(1).childCount(0).taxRateOption(100)
            .build());

        EngineDeductionDetailDTO v2 = adapter.computeDeductionsForGross(USER_ID, BRANCH_ID, GROSS);

        assertThat(v2.getHealthInsurance()).isEqualByComparingTo("0");
        assertThat(v2.getLongTermCare()).isEqualByComparingTo("0");
        assertThat(v2.getNationalPension()).isNotEqualByComparingTo("0");
        assertThat(v2.getEmploymentInsurance()).isNotEqualByComparingTo("0");
    }

    // ============================================================
    // 시나리오 ⑥ — 근로자 + 수동 (user_insurance_amount 수기입력값 그대로)
    // ============================================================

    @Test
    @DisplayName("[시나리오⑥] 근로자+수동 → user_insurance_amount 값이 그대로 공제됨")
    void scenario6_employee_manual_input() {
        extensionRepo.save(EmployeePayrollExtension.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .isEmployee(true).isFreelancer(false).isNoDeduction(false)
            .isAutoTax(false)
            .nationalPensionEnabled(true).healthInsuranceEnabled(true)
            .employmentInsuranceEnabled(true).industrialAccidentEnabled(false)
            .isProbation(false).probationRate(BigDecimal.ONE)
            .build());

        insuranceAmountRepo.save(UserInsuranceAmount.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .nationalPensionAmount(new BigDecimal("50000"))
            .healthInsuranceAmount(new BigDecimal("40000"))
            .employmentInsuranceAmount(new BigDecimal("10000"))
            .industrialAccidentInsuranceAmount(new BigDecimal("0"))
            .incomeTaxAmount(new BigDecimal("20000"))
            .localTaxAmount(new BigDecimal("2000"))
            .build());

        EngineDeductionDetailDTO v2 = adapter.computeDeductionsForGross(USER_ID, BRANCH_ID, GROSS);

        assertThat(v2.getDeductionType()).isEqualTo("EMPLOYMENT_INCOME_MANUAL_INPUT");
        assertThat(v2.getNationalPension()).isEqualByComparingTo("50000");
        assertThat(v2.getHealthInsurance()).isEqualByComparingTo("40000");
        assertThat(v2.getEmploymentInsurance()).isEqualByComparingTo("10000");
        assertThat(v2.getIncomeTax()).isEqualByComparingTo("20000");
        assertThat(v2.getLocalIncomeTax()).isEqualByComparingTo("2000");

        assertThat(v2.getTotalDeduction()).isEqualByComparingTo("122000");
        assertThat(v2.getNetPay()).isEqualByComparingTo("2278000");
    }

    // ============================================================
    // 시나리오 ⑦ — 근로자 + 수동 + 보험 마스킹
    // ============================================================

    @Test
    @DisplayName("[시나리오⑦] 근로자+수동 + nationalPensionEnabled=false → NP 0, 나머지 수기입력값")
    void scenario7_employee_manual_pension_masked() {
        extensionRepo.save(EmployeePayrollExtension.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .isEmployee(true).isFreelancer(false).isNoDeduction(false)
            .isAutoTax(false)
            .nationalPensionEnabled(false).healthInsuranceEnabled(true)
            .employmentInsuranceEnabled(true).industrialAccidentEnabled(false)
            .isProbation(false).probationRate(BigDecimal.ONE)
            .build());

        insuranceAmountRepo.save(UserInsuranceAmount.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .nationalPensionAmount(new BigDecimal("50000"))
            .healthInsuranceAmount(new BigDecimal("40000"))
            .employmentInsuranceAmount(new BigDecimal("10000"))
            .incomeTaxAmount(new BigDecimal("20000"))
            .localTaxAmount(new BigDecimal("2000"))
            .build());

        EngineDeductionDetailDTO v2 = adapter.computeDeductionsForGross(USER_ID, BRANCH_ID, GROSS);

        assertThat(v2.getNationalPension()).isEqualByComparingTo("0");
        assertThat(v2.getHealthInsurance()).isEqualByComparingTo("40000");
        assertThat(v2.getEmploymentInsurance()).isEqualByComparingTo("10000");
        assertThat(v2.getIncomeTax()).isEqualByComparingTo("20000");
    }

    // ============================================================
    // 시나리오 ⑧ — 박고소득 페르소나 (월 700만, NP 상한 cap 검증)
    //   NP base cap = 6,370,000 → NP = floor(6,370,000 × 0.0475) = 302,575
    //   HI max cap(119,625,106)는 미도달 → 7M × 0.03595 그대로
    //   IT: 간이세액표 [7000,7020).family5 = 497,350 (자녀0, rate 100%)
    //   EI: 7M × 0.009 = 62,999.999… → floor = 62,999 (double 부동소수점 특성)
    // ============================================================

    @Test
    @DisplayName("[시나리오⑧] 박고소득 월 700만 — NP 상한 cap (302,575)")
    void scenario8_highEarner_npCapped() {
        BigDecimal highGross = new BigDecimal("7000000");
        extensionRepo.save(EmployeePayrollExtension.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .isEmployee(true).isFreelancer(false).isNoDeduction(false)
            .isAutoTax(true)
            .nationalPensionEnabled(true).healthInsuranceEnabled(true)
            .employmentInsuranceEnabled(true).industrialAccidentEnabled(false)
            .isProbation(false).probationRate(BigDecimal.ONE)
            .build());
        autoTaxRepo.save(EmployeePayrollAutoTax.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .familyCount(5).childCount(0).taxRateOption(100)
            .build());

        EngineDeductionDetailDTO v2 = adapter.computeDeductionsForGross(USER_ID, BRANCH_ID, highGross);

        // 4대보험
        assertThat(v2.getNationalPension()).isEqualByComparingTo("302575"); // ← cap 작동
        assertThat(v2.getHealthInsurance()).isEqualByComparingTo("251650");
        assertThat(v2.getLongTermCare()).isEqualByComparingTo("33066");
        assertThat(v2.getEmploymentInsurance()).isEqualByComparingTo("62999"); // 7M × 0.009 = 62999.999… → floor
        // 세금
        assertThat(v2.getIncomeTax()).isEqualByComparingTo("497350");
        assertThat(v2.getLocalIncomeTax()).isEqualByComparingTo("49730");
        // 합계
        assertThat(v2.getTotalDeduction()).isEqualByComparingTo("1197370");
        assertThat(v2.getNetPay()).isEqualByComparingTo("5802630");
    }

    // ============================================================
    // 시나리오 ⑨ — 이저소득 페르소나 (월 25만, NP/HI 하한 floor 검증)
    //   NP base floor = 390,000 → NP = floor(390,000 × 0.0475) = 18,525
    //   HI base floor = 279,266 → HI = floor(279,266 × 0.03595) = 10,039
    //   IT: salaryInThousand=250 → 테이블 최소 770 미만 → 0
    // ============================================================

    @Test
    @DisplayName("[시나리오⑨] 이저소득 월 25만 — NP/HI 하한 floor (18,525 / 10,039)")
    void scenario9_lowEarner_npHiFloored() {
        BigDecimal lowGross = new BigDecimal("250000");
        extensionRepo.save(EmployeePayrollExtension.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .isEmployee(true).isFreelancer(false).isNoDeduction(false)
            .isAutoTax(true)
            .nationalPensionEnabled(true).healthInsuranceEnabled(true)
            .employmentInsuranceEnabled(true).industrialAccidentEnabled(false)
            .isProbation(false).probationRate(BigDecimal.ONE)
            .build());
        autoTaxRepo.save(EmployeePayrollAutoTax.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .familyCount(1).childCount(0).taxRateOption(100)
            .build());

        EngineDeductionDetailDTO v2 = adapter.computeDeductionsForGross(USER_ID, BRANCH_ID, lowGross);

        assertThat(v2.getNationalPension()).isEqualByComparingTo("18525");  // ← floor
        assertThat(v2.getHealthInsurance()).isEqualByComparingTo("10039");  // ← floor
        assertThat(v2.getLongTermCare()).isEqualByComparingTo("1319");
        assertThat(v2.getEmploymentInsurance()).isEqualByComparingTo("2250");
        assertThat(v2.getIncomeTax()).isEqualByComparingTo("0");
        assertThat(v2.getLocalIncomeTax()).isEqualByComparingTo("0");
        assertThat(v2.getTotalDeduction()).isEqualByComparingTo("32133");
        assertThat(v2.getNetPay()).isEqualByComparingTo("217867");
    }

    // ============================================================
    // 시나리오 ⑩ — 서식대초 페르소나 (식대 30만 → 10만 과세 전환)
    //   nonTaxableOver = 100,000, taxableBase = 2,500,000
    //   IT: 간이세액표 [2500,2510).family1 = 35,600
    //   warnings: TAX_FREE_OVER 포함
    // ============================================================

    @Test
    @DisplayName("[시나리오⑩] 서식대초 식대 30만 — 10만 과세 전환 + TAX_FREE_OVER 경고")
    void scenario10_nonTaxableOverage() {
        extensionRepo.save(EmployeePayrollExtension.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .isEmployee(true).isFreelancer(false).isNoDeduction(false)
            .isAutoTax(true)
            .nationalPensionEnabled(true).healthInsuranceEnabled(true)
            .employmentInsuranceEnabled(true).industrialAccidentEnabled(false)
            .isProbation(false).probationRate(BigDecimal.ONE)
            .build());
        autoTaxRepo.save(EmployeePayrollAutoTax.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .familyCount(1).childCount(0).taxRateOption(100)
            .build());
        payItemRepo.save(PayItemTemplate.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .name("식대").category(PayItemTemplate.PayCategory.NON_TAXABLE)
            .amount(300_000L).taxFreeType(TaxFreeType.MEAL).isActive(true)
            .build());

        EngineDeductionDetailDTO v2 = adapter.computeDeductionsForGross(USER_ID, BRANCH_ID, GROSS);

        // 과세/비과세 분해
        assertThat(v2.getGrossPay()).isEqualByComparingTo("2700000");
        assertThat(v2.getNonTaxableTotal()).isEqualByComparingTo("200000");
        assertThat(v2.getNonTaxableOver()).isEqualByComparingTo("100000");
        assertThat(v2.getTaxableBase()).isEqualByComparingTo("2500000");
        // 4대보험
        assertThat(v2.getNationalPension()).isEqualByComparingTo("118750");
        assertThat(v2.getHealthInsurance()).isEqualByComparingTo("89875");
        assertThat(v2.getLongTermCare()).isEqualByComparingTo("11809");
        assertThat(v2.getEmploymentInsurance()).isEqualByComparingTo("22500");
        // 세금
        assertThat(v2.getIncomeTax()).isEqualByComparingTo("35600");
        assertThat(v2.getLocalIncomeTax()).isEqualByComparingTo("3560");
        // 합계
        assertThat(v2.getTotalDeduction()).isEqualByComparingTo("282094");
        assertThat(v2.getNetPay()).isEqualByComparingTo("2417906");
        // 경고
        assertThat(v2.getWarnings()).anyMatch(w -> w.startsWith("TAX_FREE_OVER"));
    }

    // ============================================================
    // 시나리오 ⑪ — 조수기공제 페르소나 (프리랜서 + 기숙사비 15만 수기공제)
    //   IT: 3.3% = 72,000 + LT 7,200
    //   manualDeductionsTotal = 150,000
    //   totalDeduction = 72,000 + 7,200 + 150,000 = 229,200
    // ============================================================

    @Test
    @DisplayName("[시나리오⑪] 조수기공제 프리랜서+기숙사비 15만 → 수기공제 반영")
    void scenario11_freelancer_with_manualDeduction() {
        extensionRepo.save(EmployeePayrollExtension.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .isEmployee(false).isFreelancer(true).isNoDeduction(false)
            .isAutoTax(false)
            .nationalPensionEnabled(false).healthInsuranceEnabled(false)
            .employmentInsuranceEnabled(false).industrialAccidentEnabled(false)
            .isProbation(false).probationRate(BigDecimal.ONE)
            .build());
        manualDeductionRepo.save(ManualDeductionTemplate.builder()
            .userId(USER_ID).branchId(BRANCH_ID)
            .name("기숙사비").amount(150_000L).isActive(true)
            .build());

        EngineDeductionDetailDTO v2 = adapter.computeDeductionsForGross(USER_ID, BRANCH_ID, GROSS);

        assertThat(v2.getDeductionType()).isEqualTo("BUSINESS_INCOME");
        assertThat(v2.getIncomeTax()).isEqualByComparingTo("72000");
        assertThat(v2.getLocalIncomeTax()).isEqualByComparingTo("7200");
        assertThat(v2.getManualDeductionsTotal()).isEqualByComparingTo("150000");
        assertThat(v2.getTotalDeduction()).isEqualByComparingTo("229200");
        assertThat(v2.getNetPay()).isEqualByComparingTo("2170800");
    }
}
