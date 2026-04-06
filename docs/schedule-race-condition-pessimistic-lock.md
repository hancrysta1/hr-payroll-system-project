# 스케줄 수정 Race Condition 해결 — 비관적 락 + MANDATORY 전파

관련 블로그 글: https://velog.io/@hansjour/%EC%8A%A4%ED%94%84%EB%A7%81%EB%B6%80%ED%8A%B8-Transactional-%EC%A0%84%ED%8C%8C-%EC%86%8D%EC%84%B1%EC%9C%BC%EB%A1%9C-%ED%8A%B8%EB%9E%9C%EC%9E%AD%EC%85%98-%EC%A0%9C%EC%96%B4%ED%95%98%EA%B8%B0


## 문제

스케줄이 수정되면 해당 날짜의 급여도 현재 시급 기준으로 재계산되어야 한다. 이때 기존 급여를 UPDATE하지 않고 DELETE → 재계산 → INSERT 방식을 사용한다. 이 구조에서 두 가지 동시성/트랜잭션 문제가 발생했다.

문제 1 — Race Condition. 같은 스케줄에 대해 빠르게 연속으로 수정 요청이 들어오면, 요청 A의 급여 생성이 완료되기 전에 요청 B가 진입하여 스케줄을 삭제해버리면서 스케줄과 급여가 모두 사라지는 상태가 발생할 수 있었다.

```
요청 A: 급여 삭제 → 스케줄 삭제 → 스케줄 생성 → [급여 생성 중...]
요청 B:                급여 삭제 → 스케줄 삭제 → ...
```

문제 2 — 트랜잭션 분리. ScheduleServiceImpl에서 SalaryCalculateService의 메서드를 호출할 때, 각 메서드에 @Transactional이 개별로 붙어 있어 별도 트랜잭션으로 실행되고 있었다. Spring AOP 프록시 특성상, 다른 빈의 메서드 호출은 프록시를 통과하여 새로운 트랜잭션 컨텍스트로 진입한다. 트랜잭션 B가 커밋된 뒤 트랜잭션 A에서 예외가 발생하면, 급여는 이미 삭제되었는데 스케줄은 롤백되는 불일치 상태가 만들어졌다.

```
ScheduleServiceImpl.updateSchedule()       ← 트랜잭션 A
├── salaryCalculateService.delete()      ← 트랜잭션 B (별도 프록시 통과)
├── scheduleRepository.deleteById()      ← 트랜잭션 A
├── scheduleRepository.save()            ← 트랜잭션 A
└── salaryCalculateService.create()      ← 트랜잭션 C (별도 프록시 통과)
```

## 분석

### Race Condition 원인

수정/삭제 시 findById()로 스케줄을 조회하는데, 이 일반 조회는 락을 걸지 않는다. 두 요청이 동시에 같은 스케줄을 읽고 각자 삭제→생성을 진행하면서 충돌이 발생했다.

### DELETE+INSERT 패턴을 사용하는 이유

1. 레코드 수가 변할 수 있다. 자정을 넘기는 근무(22:00~06:00)의 경우 1개 스케줄이 2개의 일별 급여 레코드로 분리된다. 기존 1개 레코드를 UPDATE하는 것으로는 1→2 레코드 변환을 처리할 수 없다.
2. 계산 의존성이 복잡하다. 시급 정책(월급/시급/일급), 야간수당, 휴일수당, 공제 등 여러 조건에 따라 결과가 달라진다. 기존 값 위에 일부만 수정하면 이전 계산의 잔여값이 남을 위험이 있어, 깨끗한 재계산이 더 안전하다.
3. 기존 생성 로직 재활용. 생성 시 사용하는 급여 계산 함수(processDailySalary)를 그대로 재사용할 수 있어 코드 중복 없이 단순하게 구현 가능하다.

### 락 방식 비교

| 방안 | 동작 | 판단 |
| --- | --- | --- |
| 낙관적 락 (Optimistic) | 충돌 시 재시도 필요 → DELETE→INSERT 구조에서 재시도 구현 복잡 | 부적합 |
| SERIALIZABLE 격리 수준 | 전체 테이블에 영향 → 조회 성능까지 저하 | 과도함 |
| 비관적 락 (Pessimistic) | 해당 레코드만 잠금, 구현 단순, 스케줄 수정은 빈번하지 않아 대기 비용 낮음 | 적합 |

### 트랜잭션 전파 속성 비교

- REQUIRED. 기존 트랜잭션이 있으면 참여, 없으면 조용히 새로 생성한다. 실수로 트랜잭션 밖에서 호출해도 에러 없이 통과.
- MANDATORY. 기존 트랜잭션이 있으면 참여, 없으면 즉시 IllegalTransactionStateException. 잘못된 호출을 즉시 감지.

급여 삭제/생성은 반드시 스케줄 트랜잭션 안에서만 실행되어야 하므로, 단독 호출 자체를 차단하는 MANDATORY를 선택했다.

## 해결

### 비관적 락 도입

```java
// ScheduleRepository.java:91-93
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT s FROM Schedule s WHERE s.id = :id")
Optional<Schedule> findByIdForUpdate(@Param("id") Long id);
```

SELECT ... FOR UPDATE로 해당 스케줄 레코드에 Write Lock을 건다. 선행 트랜잭션이 커밋될 때까지 후행 요청은 대기한다. 수정/삭제 경로에만 선별 적용하고, 단순 조회에는 기존 findById()를 유지했다.

```java
// 락 적용 (수정/삭제) — ScheduleServiceImpl.java:162, 222, 552
Schedule existing = scheduleRepository.findByIdForUpdate(scheduleId);

// 락 미적용 (단순 조회) — 기존 findById() 유지
Schedule schedule = scheduleRepository.findById(scheduleId);
```

동작 흐름.

```
요청 A: findByIdForUpdate(1) → Lock 획득 → 삭제→생성→커밋 → Lock 해제
요청 B: findByIdForUpdate(1) → 대기중...                     → Lock 획득 → 정상 처리
```

### Propagation.MANDATORY로 트랜잭션 참여 강제

```java
// SalaryCalculateService.java:57, 73 — 반드시 호출자 트랜잭션에 참여
@Transactional(propagation = Propagation.MANDATORY)
public void deleteDailySalaryBySchedule(Schedule schedule) { ... }

@Transactional(propagation = Propagation.MANDATORY)
public boolean processDailySalaryInTransaction(ScheduleWorkedEventDTO dto) { ... }

// ScheduleServiceImpl.java:158-216 — 트랜잭션의 시작점
@Transactional(rollbackFor = Exception.class)
public Schedule updateScheduleWithSalary(Long scheduleId, Schedule schedule) {
    Schedule existing = scheduleRepository.findByIdForUpdate(scheduleId);  // 비관적 락

    salaryCalculateService.deleteDailySalaryBySchedule(existing);  // MANDATORY → 같은 트랜잭션
    scheduleRepository.deleteById(scheduleId);
    Schedule newSchedule = scheduleRepository.save(schedule);
    createSalaryForSchedule(newSchedule);  // MANDATORY → 같은 트랜잭션

    // → 전부 성공해야 커밋, 하나라도 실패하면 전체 롤백
}
```

적용 후 트랜잭션 흐름.

```
@Transactional updateScheduleWithSalary()    ← 단일 트랜잭션, 단일 DB 커넥션
├── findByIdForUpdate()                       ← 비관적 락 획득
├── deleteDailySalaryBySchedule()             ← MANDATORY: 같은 트랜잭션 참여
├── scheduleRepository.deleteById()           ← 같은 트랜잭션
├── scheduleRepository.save()                 ← 같은 트랜잭션
├── processDailySalaryInTransaction()         ← MANDATORY: 같은 트랜잭션 참여
└── 성공 시 전체 커밋 / 실패 시 전체 롤백 + 락 해제
```

## 결과

| 항목 | Before | After |
| --- | --- | --- |
| 동시 요청 제어 | 없음 (Race Condition 발생) | 비관적 락으로 직렬화 보장 |
| 트랜잭션 수 | 2~3개 독립 트랜잭션 | 1개 단일 트랜잭션 |
| 스케줄-급여 정합성 | 빠른 요청 시 불일치 발생 가능 | 원자성 보장 (전체 성공 or 전체 롤백) |
| 잘못된 호출 감지 | 조용히 새 트랜잭션 생성 (REQUIRED) | 즉시 예외 발생 (MANDATORY) |
| DB 커넥션 사용 | 2~3개 동시 점유 | 1개로 통합 |

## Sources

- https://help-hrms.na.sage.com/en-us/2017/web/Content/Payroll/CorrectingPayrollDataAfterCalculatingPayroll.htm
- https://learn.microsoft.com/en-us/troubleshoot/sql/database-engine/replication/update-statements-replicated-as-delete-insert
- https://vladmihalcea.com/orphanremoval-jpa-hibernate/
- https://vladmihalcea.com/bulk-update-delete-jpa-hibernate/
