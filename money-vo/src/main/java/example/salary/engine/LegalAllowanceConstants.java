package example.salary.engine;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 법정 수당 관련 상수 — V1 salary 패키지 원본 그대로 복제 (parity 유지).
 * 값 변경 시 V1과 동기화 필수.
 */
public final class LegalAllowanceConstants {

    // 연장근로 기준
    public static final int DAILY_WORK_LIMIT = 8; // 1일 8시간
    public static final int WEEKLY_WORK_LIMIT = 40; // 1주 40시간

    // 야간근로 기준
    public static final LocalTime NIGHT_START_TIME = LocalTime.of(22, 0);
    public static final LocalTime NIGHT_END_TIME = LocalTime.of(6, 0);

    // 휴일근로 기준
    public static final int HOLIDAY_OVERTIME_THRESHOLD = 8;

    // 법정 최저 가산율
    public static final BigDecimal MIN_OVERTIME_RATE = BigDecimal.valueOf(50.0);
    public static final BigDecimal MIN_NIGHT_RATE = BigDecimal.valueOf(50.0);
    public static final BigDecimal MIN_HOLIDAY_RATE = BigDecimal.valueOf(50.0);
    public static final BigDecimal MIN_HOLIDAY_OVERTIME_RATE = BigDecimal.valueOf(100.0);

    private LegalAllowanceConstants() {}
}
