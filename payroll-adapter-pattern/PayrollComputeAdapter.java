그package com.workpay.salary.service.payroll;

import com.workpay.payroll.domain.Rates;
import com.workpay.payroll.domain.RatesRegistry;
import com.workpay.payroll.domain.TaxTableLoader;
import com.workpay.payroll.engine.PayrollEngine;
import com.workpay.salary.domain.BranchPayItemTemplate;
import com.workpay.salary.domain.EmployeePayrollAutoTax;
import com.workpay.salary.domain.EmployeePayrollExtension;
import com.workpay.salary.domain.ManualDeductionTemplate;
import com.workpay.salary.domain.PayItemTemplate;
import com.workpay.salary.domain.UserInsuranceAmount;
import com.workpay.salary.domain.UserSalaryDeductions;
import com.workpay.salary.dto.EngineDeductionDetailDTO;
import com.workpay.salary.repository.BranchPayItemTemplateRepository;
import com.workpay.salary.repository.EmployeePayrollAutoTaxRepository;
import com.workpay.salary.repository.EmployeePayrollExtensionRepository;
import com.workpay.salary.repository.ManualDeductionTemplateRepository;
import com.workpay.salary.repository.PayItemTemplateRepository;
import com.workpay.salary.repository.UserInsuranceAmountRepository;
import com.workpay.salary.repository.UserSalaryDeductionsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * workpay-service ↔ payroll-compute 엔진 어댑터 (V2).
 *
 * <p>V2 설정({@link EmployeePayrollExtension} + 서브 테이블 2종)을 읽어 엔진 입력을 빌드한다.
 * V2 설정이 아직 없는 직원은 V1({@code user_salary_deductions} 토글 + {@code user_insurance_amount} 수기금액)을
 * 그대로 사용한다 (레거시 fallback).</p>
 *
 * <h3>고용형태 분기</h3>
 * <ul>
 *   <li>{@code ext == null} → 레거시 V1 수동 공제: V1 4대보험 토글 + {@code user_insurance_amount} 금액, 세금은
 *       V1 {@code taxRate=true}일 때 3.3% (소득세 3% + 지방세 0.3%) 자동 계산</li>
 *   <li>{@code isNoDeduction=true} → 공제 0 (NO_DEDUCTION)</li>
 *   <li>{@code isFreelancer=true} → 엔진 BUSINESS_INCOME 모드, 3.3% 고정</li>
 *   <li>{@code isEmployee=true} + {@code isAutoTax=true} → {@code employee_payroll_auto_tax} 읽어 간이세액표 자동계산</li>
 *   <li>{@code isEmployee=true} + {@code isAutoTax=false} → {@code user_insurance_amount} 수기입력값 그대로 공제</li>
 * </ul>
 *
 * <p>4대보험 boolean(nationalPensionEnabled 등)은 근로자 모드에서 결과 마스킹에 사용된다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayrollComputeAdapter {

    private final EmployeePayrollExtensionRepository extensionRepo;
    private final EmployeePayrollAutoTaxRepository autoTaxRepo;
    private final UserInsuranceAmountRepository insuranceAmountRepo;
    private final PayItemTemplateRepository payItemRepo;
    private final ManualDeductionTemplateRepository manualDeductionRepo;
    private final BranchPayItemTemplateRepository branchPayItemRepo;
    private final UserSalaryDeductionsRepository userSalaryDeductionsRepo;

    public PayrollEngine.Result compute(Long userId, Long branchId) {
        return compute(userId, branchId, LocalDate.now().getYear());
    }

    public PayrollEngine.Result compute(Long userId, Long branchId, int rateYear) {
        EmployeePayrollExtension ext = extensionRepo
            .findByUserIdAndBranchId(userId, branchId).orElse(null);

        List<PayItemTemplate> userItems = payItemRepo
            .findByUserIdAndBranchIdAndIsActiveTrue(userId, branchId);
        List<PayrollEngine.PayItem> payItems = userItems.isEmpty()
            ? branchPayItemRepo.findByBranchIdAndIsActiveTrue(branchId).stream()
                .map(PayrollComputeAdapter::fromBranchDefault).toList()
            : userItems.stream().map(PayrollComputeAdapter::toPayItem).toList();

        List<ManualDeductionTemplate> manuals = manualDeductionRepo
            .findByUserIdAndBranchIdAndIsActiveTrue(userId, branchId);

        return computeInternal(userId, branchId, ext, payItems, manuals, rateYear).rawOrFinal();
    }

    /**
     * summary/detail 전용 — gross는 외부(시급×근무시간 모델)에서 주입.
     */
    public EngineDeductionDetailDTO computeDeductionsForGross(Long userId, Long branchId, BigDecimal grossFromWorkedTime) {
        return computeDeductionsForGross(userId, branchId, grossFromWorkedTime, LocalDate.now().getYear());
    }

    public EngineDeductionDetailDTO computeDeductionsForGross(Long userId, Long branchId, BigDecimal grossFromWorkedTime, int rateYear) {
        long grossLong = grossFromWorkedTime != null ? grossFromWorkedTime.longValue() : 0L;

        EmployeePayrollExtension ext = extensionRepo
            .findByUserIdAndBranchId(userId, branchId).orElse(null);

        List<PayItemTemplate> userItems = payItemRepo
            .findByUserIdAndBranchIdAndIsActiveTrue(userId, branchId);
        List<PayrollEngine.PayItem> nonTaxableItems = (userItems.isEmpty()
                ? branchPayItemRepo.findByBranchIdAndIsActiveTrue(branchId).stream()
                    .filter(t -> t.getCategory() != null && "NON_TAXABLE".equals(t.getCategory().name()))
                    .map(PayrollComputeAdapter::fromBranchDefault).toList()
                : userItems.stream()
                    .filter(t -> t.getCategory() != null && "NON_TAXABLE".equals(t.getCategory().name()))
                    .map(PayrollComputeAdapter::toPayItem).toList());

        List<PayrollEngine.PayItem> payItems = new ArrayList<>(nonTaxableItems.size() + 1);
        payItems.add(new PayrollEngine.PayItem(
            "근무시간 기반 급여", PayrollEngine.PayCategory.TAXABLE, grossLong, null));
        payItems.addAll(nonTaxableItems);

        List<ManualDeductionTemplate> manuals = manualDeductionRepo
            .findByUserIdAndBranchIdAndIsActiveTrue(userId, branchId);

        ComputeBundle bundle = computeInternal(userId, branchId, ext, payItems, manuals, rateYear);

        log.debug("payroll deduction — userId={}, branchId={}, gross={}, mode={}, totalDed={}, net={}",
            userId, branchId, grossLong, bundle.mode, bundle.finalResult.totalDeduction(), bundle.finalResult.netPay());

        return EngineDeductionDetailDTO.from(bundle.finalResult, bundle.engineExt, bundle.mode, ext);
    }

    // ─── 내부 공통 계산 ───

    private ComputeBundle computeInternal(Long userId, Long branchId,
                                           EmployeePayrollExtension ext,
                                           List<PayrollEngine.PayItem> payItems,
                                           List<ManualDeductionTemplate> manuals,
                                           int rateYear) {
        EmploymentMode mode = resolveMode(ext);

        int family = 1, child = 0, rateOpt = 100;
        boolean isProbation = ext != null && Boolean.TRUE.equals(ext.getIsProbation());
        double probationRate = ext != null && ext.getProbationRate() != null
            ? ext.getProbationRate().doubleValue() : 1.0;

        if (mode == EmploymentMode.EMPLOYEE_AUTO) {
            EmployeePayrollAutoTax auto = autoTaxRepo
                .findByUserIdAndBranchId(userId, branchId)
                .orElseGet(() -> EmployeePayrollAutoTax.builder()
                    .userId(userId).branchId(branchId).build());
            family = auto.getFamilyCount() != null ? auto.getFamilyCount() : 1;
            child = auto.getChildCount() != null ? auto.getChildCount() : 0;
            rateOpt = auto.getTaxRateOption() != null ? auto.getTaxRateOption() : 100;
        }

        // 엔진 호출용 타입: 근로자(자동/수동)은 EMPLOYMENT로 호출(엔진 결과를 기반값으로 씀),
        // 프리랜서는 BUSINESS, 공제없음은 MANUAL.
        PayrollEngine.DeductionType engineType = switch (mode) {
            case EMPLOYEE_AUTO, EMPLOYEE_MANUAL -> PayrollEngine.DeductionType.EMPLOYMENT_INCOME;
            case FREELANCER -> PayrollEngine.DeductionType.BUSINESS_INCOME;
            case NO_DEDUCTION -> PayrollEngine.DeductionType.MANUAL;
        };

        PayrollEngine.PayrollExtension engineExt = new PayrollEngine.PayrollExtension(
            engineType, family, child, rateOpt, isProbation, probationRate);
        PayrollEngine.Request request = new PayrollEngine.Request(
            engineExt,
            payItems,
            manuals.stream().map(PayrollComputeAdapter::toManualDeduction).toList()
        );

        Rates rates = RatesRegistry.forYear(rateYear);
        PayrollEngine.Result raw = PayrollEngine.compute(request, rates, TaxTableLoader.forYear(rateYear));
        PayrollEngine.Result finalResult = applyMode(raw, mode, ext,
            () -> insuranceAmountRepo.findByUserIdAndBranchId(userId, branchId).orElse(null),
            () -> userSalaryDeductionsRepo.findByUserIdAndBranchId(userId, branchId).orElse(null));

        return new ComputeBundle(mode, engineExt, finalResult);
    }

    private static PayrollEngine.Result applyMode(PayrollEngine.Result raw, EmploymentMode mode,
                                                   EmployeePayrollExtension ext,
                                                   Supplier<UserInsuranceAmount> insuranceLoader,
                                                   Supplier<UserSalaryDeductions> v1DeductionsLoader) {
        long np, hi, ltc, ei, incomeTax, localTax;

        switch (mode) {
            case EMPLOYEE_AUTO -> {
                np = Boolean.TRUE.equals(ext.getNationalPensionEnabled()) ? raw.nationalPension() : 0L;
                hi = Boolean.TRUE.equals(ext.getHealthInsuranceEnabled()) ? raw.healthInsurance() : 0L;
                ltc = Boolean.TRUE.equals(ext.getHealthInsuranceEnabled()) ? raw.longTermCare() : 0L;
                ei = Boolean.TRUE.equals(ext.getEmploymentInsuranceEnabled()) ? raw.employmentInsurance() : 0L;
                incomeTax = raw.incomeTax();
                localTax = raw.localIncomeTax();
            }
            case EMPLOYEE_MANUAL -> {
                UserInsuranceAmount ua = insuranceLoader.get();
                if (ext != null) {
                    // V2 수동: ext 토글 + user_insurance_amount 수기금액
                    np = Boolean.TRUE.equals(ext.getNationalPensionEnabled())
                        ? longValue(ua == null ? null : ua.getNationalPensionAmount()) : 0L;
                    hi = Boolean.TRUE.equals(ext.getHealthInsuranceEnabled())
                        ? longValue(ua == null ? null : ua.getHealthInsuranceAmount()) : 0L;
                    ltc = 0L;
                    ei = Boolean.TRUE.equals(ext.getEmploymentInsuranceEnabled())
                        ? longValue(ua == null ? null : ua.getEmploymentInsuranceAmount()) : 0L;
                    incomeTax = longValue(ua == null ? null : ua.getIncomeTaxAmount());
                    localTax = longValue(ua == null ? null : ua.getLocalTaxAmount());
                } else {
                    // V1 레거시: user_salary_deductions 토글 + user_insurance_amount 금액.
                    // 세금은 taxRate=true일 때 3.3%(소득세 3% + 지방세 0.3%) 자동 계산.
                    UserSalaryDeductions v1 = v1DeductionsLoader.get();
                    np = v1 != null && Boolean.TRUE.equals(v1.getNationalPensionEnabled())
                        ? longValue(ua == null ? null : ua.getNationalPensionAmount()) : 0L;
                    hi = v1 != null && Boolean.TRUE.equals(v1.getHealthInsuranceEnabled())
                        ? longValue(ua == null ? null : ua.getHealthInsuranceAmount()) : 0L;
                    ltc = 0L;
                    ei = v1 != null && Boolean.TRUE.equals(v1.getEmploymentInsuranceEnabled())
                        ? longValue(ua == null ? null : ua.getEmploymentInsuranceAmount()) : 0L;
                    if (v1 != null && Boolean.TRUE.equals(v1.getTaxRate())) {
                        BigDecimal gross = BigDecimal.valueOf(raw.grossPay());
                        incomeTax = gross.multiply(new BigDecimal("0.030"))
                            .setScale(0, RoundingMode.HALF_UP).longValueExact();
                        localTax = gross.multiply(new BigDecimal("0.003"))
                            .setScale(0, RoundingMode.HALF_UP).longValueExact();
                    } else {
                        incomeTax = 0L;
                        localTax = 0L;
                    }
                }
            }
            case FREELANCER -> {
                np = 0L;
                hi = 0L;
                ltc = 0L;
                ei = 0L;
                incomeTax = raw.incomeTax();
                localTax = raw.localIncomeTax();
            }
            case NO_DEDUCTION -> {
                np = 0L; hi = 0L; ltc = 0L; ei = 0L;
                incomeTax = 0L; localTax = 0L;
            }
            default -> throw new IllegalStateException("unknown mode: " + mode);
        }

        long totalDeduction = np + hi + ltc + ei + incomeTax + localTax + raw.manualDeductionsTotal();
        long netPay = raw.grossPay() - totalDeduction;

        return new PayrollEngine.Result(
            raw.grossPay(), raw.taxableTotal(), raw.nonTaxableTotal(), raw.nonTaxableOver(), raw.taxableBase(),
            np, hi, ltc, ei,
            incomeTax, localTax, raw.manualDeductionsTotal(),
            totalDeduction, netPay,
            raw.ratesVersion(), raw.warnings()
        );
    }

    private static EmploymentMode resolveMode(EmployeePayrollExtension ext) {
        // ext 미등록 = V2 전환 전 직원. V1 설정(user_salary_deductions + user_insurance_amount)을
        // 수동 공제 경로로 재사용한다. applyMode(EMPLOYEE_MANUAL)가 ext==null 브랜치에서 V1을 읽음.
        if (ext == null) return EmploymentMode.EMPLOYEE_MANUAL;
        if (Boolean.TRUE.equals(ext.getIsNoDeduction())) return EmploymentMode.NO_DEDUCTION;
        if (Boolean.TRUE.equals(ext.getIsFreelancer())) return EmploymentMode.FREELANCER;
        if (Boolean.TRUE.equals(ext.getIsEmployee())) {
            return Boolean.TRUE.equals(ext.getIsAutoTax())
                ? EmploymentMode.EMPLOYEE_AUTO
                : EmploymentMode.EMPLOYEE_MANUAL;
        }
        // 데이터 불일치(셋 다 false): 안전하게 공제없음
        return EmploymentMode.NO_DEDUCTION;
    }

    private static long longValue(BigDecimal v) {
        return v == null ? 0L : v.longValue();
    }

    private static PayrollEngine.PayItem fromBranchDefault(BranchPayItemTemplate t) {
        return new PayrollEngine.PayItem(
            t.getName(),
            PayrollEngine.PayCategory.valueOf(t.getCategory().name()),
            t.getAmount(),
            t.getTaxFreeType() != null ? t.getTaxFreeType().name() : null
        );
    }

    private static PayrollEngine.PayItem toPayItem(PayItemTemplate t) {
        return new PayrollEngine.PayItem(
            t.getName(),
            PayrollEngine.PayCategory.valueOf(t.getCategory().name()),
            t.getAmount(),
            t.getTaxFreeType() != null ? t.getTaxFreeType().name() : null
        );
    }

    private static PayrollEngine.ManualDeduction toManualDeduction(ManualDeductionTemplate m) {
        return new PayrollEngine.ManualDeduction(m.getName(), m.getAmount());
    }

    // ─── 내부 타입 ───

    public enum EmploymentMode {
        EMPLOYEE_AUTO, EMPLOYEE_MANUAL, FREELANCER, NO_DEDUCTION
    }

    private record ComputeBundle(EmploymentMode mode,
                                  PayrollEngine.PayrollExtension engineExt,
                                  PayrollEngine.Result finalResult) {
        PayrollEngine.Result rawOrFinal() { return finalResult; }
    }
}
