package com.workpay.calendar.repository;

import com.workpay.calendar.model.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
    @Query("SELECT s FROM Schedule s WHERE s.branchId = :branchId AND FUNCTION('MONTH', s.startTime) = :month")
    List<Schedule> findByBranchIdAndMonth(@Param("branchId") Long branchId, @Param("month") Integer month);

    @Query("SELECT s FROM Schedule s WHERE s.branchId = :branchId AND s.workerId = :userId AND FUNCTION('MONTH', s.startTime) = :month")
    List<Schedule> findByBranchIdAndMonthAndUserId(@Param("branchId") Long branchId, @Param("month") Integer month, @Param("userId") Long userId);

    List<Schedule> findByRepeatGroupId(Long repeatGroupId);
    List<Schedule> findByRepeatGroupIdAndStartTimeGreaterThanEqual(Long repeatGroupId, Date startTime);

    @Query("SELECT COUNT(s) > 0 FROM Schedule s " +
            "WHERE s.workerId = :workerId " +
            "AND s.branchId = :branchId " +
            "AND (:excludeId IS NULL OR s.id != :excludeId) " +
            "AND s.startTime < :endTime AND s.endTime > :startTime")
    boolean existsOverlappingScheduleWithBranchId(
            @Param("workerId") Long workerId,
            @Param("startTime") Date startTime,
            @Param("endTime") Date endTime,
            @Param("excludeId") Long excludeId,
            @Param("branchId") Long branchId);

//    @Query("SELECT COUNT(s) > 0 FROM Schedule s " +
//            "WHERE s.workerId = :workerId " +
//            "AND (:excludeId IS NULL OR s.id != :excludeId) " +
//            "AND s.startTime < :endTime AND s.endTime > :startTime")
//    boolean existsOverlappingSchedule(
//            @Param("workerId") Long workerId,
//            @Param("startTime") Date startTime,
//            @Param("endTime") Date endTime,
//            @Param("excludeId") Long excludeId);

    @Query("SELECT s FROM Schedule s WHERE s.branchId = :branchId " +
           "AND ((s.startTime BETWEEN :startDate AND :endDate) " +
           "OR (s.endTime BETWEEN :startDate AND :endDate) " +
           "OR (s.startTime <= :startDate AND s.endTime >= :endDate))")
    List<Schedule> findByBranchIdAndDateRange(
            @Param("branchId") Long branchId,
            @Param("startDate") Date startDate,
            @Param("endDate") Date endDate);

    @Query("SELECT s FROM Schedule s WHERE s.branchId = :branchId AND s.workerId = :workerId " +
           "AND ((s.startTime BETWEEN :startDate AND :endDate) " +
           "OR (s.endTime BETWEEN :startDate AND :endDate) " +
           "OR (s.startTime <= :startDate AND s.endTime >= :endDate))")
    List<Schedule> findByBranchIdAndWorkerIdAndDateRange(
            @Param("branchId") Long branchId,
            @Param("workerId") Long workerId,
            @Param("startDate") Date startDate,
            @Param("endDate") Date endDate);

    @Query("SELECT s FROM Schedule s WHERE s.workerId = :workerId " +
           "AND ((s.startTime BETWEEN :startDate AND :endDate) " +
           "OR (s.endTime BETWEEN :startDate AND :endDate) " +
           "OR (s.startTime <= :startDate AND s.endTime >= :endDate))")
    List<Schedule> findByWorkerIdAndDateRange(
            @Param("workerId") Long workerId,
            @Param("startDate") Date startDate,
            @Param("endDate") Date endDate);
            
    // 특정 시간에 해당하는 모든 스케줄 조회
    List<Schedule> findByBranchIdAndStartTimeAndEndTime(
            @Param("branchId") Long branchId,
            @Param("startTime") Date startTime,
            @Param("endTime") Date endTime);
            
    // 특정 시간에 해당하는 특정 직원의 스케줄 조회
    List<Schedule> findByBranchIdAndWorkerIdAndStartTimeAndEndTime(
            @Param("branchId") Long branchId,
            @Param("workerId") Long workerId,
            @Param("startTime") Date startTime,
            @Param("endTime") Date endTime);

    // Race Condition 방지를 위한 비관적 락 적용 스케줄 조회
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Schedule s WHERE s.id = :id")
    Optional<Schedule> findByIdForUpdate(@Param("id") Long id);
    
    // 특정 지점들에 스케줄이 존재하는지 확인
    @Query("SELECT COUNT(s) > 0 FROM Schedule s WHERE s.branchId IN :branchIds")
    boolean existsByBranchIdIn(@Param("branchIds") List<Long> branchIds);

    // 특정 직원이 해당 지점에 스케줄을 가지고 있는지 확인
    @Query("SELECT COUNT(s) > 0 FROM Schedule s WHERE s.workerId = :workerId AND s.branchId = :branchId")
    boolean existsByWorkerIdAndBranchId(@Param("workerId") Long workerId, @Param("branchId") Long branchId);

    // 퇴직 시 해당 직원의 모든 스케줄 삭제
    void deleteByWorkerIdAndBranchId(Long workerId, Long branchId);

    // 퇴직일 이후 스케줄만 삭제
    void deleteByWorkerIdAndBranchIdAndStartTimeAfter(Long workerId, Long branchId, Date startTime);

    // 대타 여부 배치 조회 (급여 요약용 - id, isSubstitute만 프로젝션)
    @Query("SELECT s.id, s.isSubstitute FROM Schedule s WHERE s.id IN :ids")
    List<Object[]> findSubstituteByIds(@Param("ids") List<Long> ids);

    // 사용자의 특정 시간대 스케줄 충돌 확인 (지점 무관, 모든 지점에서의 충돌 확인)
    @Query("SELECT COUNT(s) > 0 FROM Schedule s " +
           "WHERE s.workerId = :workerId " +
           "AND s.startTime < :endTime AND s.endTime > :startTime")
    boolean existsOverlappingScheduleByUserId(
            @Param("workerId") Long workerId,
            @Param("startTime") Date startTime,
            @Param("endTime") Date endTime);
}
