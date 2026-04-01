package example.salary.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 급여 계산 서비스 — 트랜잭션 전파 전략
 *
 * MANDATORY: 스케줄 생성/수정 시 → 반드시 호출한 쪽의 트랜잭션에 참여.
 *            단독 호출 시 IllegalTransactionStateException.
 *
 * REQUIRES_NEW: 배치 수정(Fix) 작업 시 → 독립 트랜잭션으로 격리.
 *               다른 작업에 영향 주지 않도록.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalaryCalculateService {

    // ... (의존성 주입 생략)

    // 스케줄 생성/수정 시 사용 — 기존 트랜잭션에 참여 (외부 트랜잭션 필수)
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean processDailySalary(ScheduleWorkedEventDTO dto) {
        return processDailySalary(dto, false);
    }

    // 스케줄 생성/수정 시 사용 — 기존 트랜잭션에 참여
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean processDailySalaryInTransaction(ScheduleWorkedEventDTO dto) {
        return processFullDailySalary(dto, false);
    }

    // Fix 작업 전용 — 독립 트랜잭션
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean processSimplifiedDailySalary(ScheduleWorkedEventDTO dto) {
        // 기본 시급으로 간단한 계산만 수행
        // ...
        return true;
    }

    private boolean processFullDailySalary(ScheduleWorkedEventDTO dto, boolean forceCreate) {
        // 시급 결정 → 근무시간 계산 → 기본급 → 가산수당 → 저장
        // ...
        return true;
    }
}
