package com.workpay.calendar.scheduler;

import com.workpay.calendar.repository.ShiftRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftRequestExpiryScheduler {

    private final ShiftRequestRepository shiftRequestRepository;

    /**
     * 15분마다: 스케줄 종료 시간이 지난 PENDING 또는 ACCEPTED 상태 대타 요청 → EXPIRED 처리
     */
    @Scheduled(fixedRate = 900000) // 15분 = 900,000ms
    @Transactional
    public void expireStaleRequests() {
        int expired = shiftRequestRepository.expireStaleRequests();
        if (expired > 0) {
            log.info("만료된 대타 요청 처리 완료: {}건", expired);
        }
    }
}