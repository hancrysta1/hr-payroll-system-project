package example.salary.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 급여 확정 서비스 — 외부 호출 실패 격리 패턴
 *
 * 발견 경위: 알림 메시지에 지점명을 추가하는 수정 중,
 * 배포 전 CI 테스트에서 컴파일 에러 발생 (verify() 시그니처 불일치).
 * 에러를 고치면서 코드를 다시 보니, @Transactional 안에서 외부 호출을
 * 격리 없이 하고 있었음 → 알림 실패 시 급여 확정까지 롤백되는 구조.
 *
 * 같은 패턴이 9곳에 있었고, 전부 try-catch로 격리.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalaryConfirmationService {

    // ... (의존성 주입 생략)

    @Transactional
    public ConfirmationResult confirmBulkSalaries(Long branchId,
                                                  LocalDate startDate,
                                                  LocalDate endDate,
                                                  List<Long> requestedUserIds) {

        // 1. 해당 기간/지점에 실제 급여 데이터가 있는 유저들 조회
        List<DailyUserSalary> existingSalaries = dailyUserSalaryRepository
                .findSalariesByPeriod(startDate, endDate)
                .stream()
                .filter(salary -> salary.getBranchId().equals(branchId))
                .collect(Collectors.toList());

        Set<Long> foundUserIds = existingSalaries.stream()
                .map(DailyUserSalary::getUserId)
                .collect(Collectors.toSet());

        // 2~4. 유효성 검사, 이미 확정 여부 체크 (생략)

        // 5. 모든 직원이 개별 확정되어 있지 않으면 전체 확정 불가
        List<Long> notConfirmedUsers = new ArrayList<>();
        for (Long userId : foundUserIds) {
            if (!isUserIndividualConfirmedForPeriod(userId, branchId, startDate, endDate)) {
                notConfirmedUsers.add(userId);
            }
        }

        if (!notConfirmedUsers.isEmpty()) {
            return ConfirmationResult.failure(branchId, startDate, endDate, notConfirmedUsers);
        }

        // 6. 전체 확정 진행
        createBulkConfirmations(branchId, startDate, endDate, new ArrayList<>(foundUserIds), true);

        // 7. 브랜치 전체확정 레코드 저장
        saveBranchBulkConfirmation(branchId, startDate, endDate, null);

        // 8. 확정된 직원들에게 알림 발행 (실패해도 확정에는 영향 없음)
        try {
            String branchName = branchServiceClient.getBranch(branchId).getName();
            for (Long userId : foundUserIds) {
                notificationEventPublisher.publishSalaryConfirmed(userId, branchId, branchName, startDate, endDate);
            }
        } catch (Exception e) {
            log.error("급여 전체확정 알림 발행 실패 - branchId:{}, error:{}", branchId, e.getMessage());
        }

        return ConfirmationResult.success();
    }
}
