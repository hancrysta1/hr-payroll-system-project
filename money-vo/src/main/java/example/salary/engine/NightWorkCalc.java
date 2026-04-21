package example.salary.engine;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 야간근로(22:00~06:00) 분 계산 — V1 {@code NightWorkCalculator} 로직과 동일
 *
 * <p><b>V1과 동일한 arithmetic 보장:</b> 함수 시그니처·내부 분기·Duration 계산 순서 모두 그대로.
 * 차이점은 {@code @Component} 제거 + {@code static} 메서드화뿐.</p>
 */
public final class NightWorkCalc {

    private static final LocalTime NIGHT_START = LegalAllowanceConstants.NIGHT_START_TIME;
    private static final LocalTime NIGHT_END = LegalAllowanceConstants.NIGHT_END_TIME;

    private NightWorkCalc() {}

    /**
     * LocalDateTime 기반 야간 근무 분 계산 — null이면 0 반환. V1 {@code calculateMinutes(DailyUserSalary)}와 동일 분기.
     */
    public static int calculateNightMinutes(LocalDateTime startDateTime, LocalDateTime endDateTime) {
        if (startDateTime == null || endDateTime == null) {
            return 0;
        }
        LocalTime startTime = startDateTime.toLocalTime();
        LocalTime endTime = endDateTime.toLocalTime();
        boolean crossesMidnight = !startDateTime.toLocalDate().equals(endDateTime.toLocalDate())
            || endTime.equals(LocalTime.MIDNIGHT);
        return calculateNightMinutes(startTime, endTime, crossesMidnight);
    }

    /**
     * 야간 근무 시간(분) 계산. V1 {@code NightWorkCalculator.calculateNightMinutes} 복제.
     */
    public static int calculateNightMinutes(LocalTime startTime, LocalTime endTime, boolean crossesMidnight) {
        int nightMinutes = 0;

        if (crossesMidnight) {
            LocalTime firstSegmentStart = startTime.isBefore(NIGHT_START) ? NIGHT_START : startTime;
            int minutesToMidnight = (24 * 60) - (firstSegmentStart.getHour() * 60 + firstSegmentStart.getMinute());
            if (minutesToMidnight > 0) {
                nightMinutes += minutesToMidnight;
            }

            if (!endTime.equals(LocalTime.MIDNIGHT)) {
                LocalTime secondSegmentEnd = endTime.isAfter(NIGHT_END) ? NIGHT_END : endTime;
                nightMinutes += Duration.between(LocalTime.MIDNIGHT, secondSegmentEnd).toMinutes();
            }
        } else {
            // 새벽 구간: 시작이 06:00 이전이면 (00:00~06:00)
            if (startTime.isBefore(NIGHT_END)) {
                LocalTime effectiveEnd = endTime.isAfter(NIGHT_END) ? NIGHT_END : endTime;
                nightMinutes += Duration.between(startTime, effectiveEnd).toMinutes();
            }
            // 야간 구간: 끝이 22:00 이후이면 (22:00~24:00)
            if (endTime.isAfter(NIGHT_START)) {
                LocalTime effectiveStart = startTime.isAfter(NIGHT_START) ? startTime : NIGHT_START;
                nightMinutes += Duration.between(effectiveStart, endTime).toMinutes();
            }
        }

        return Math.max(0, nightMinutes);
    }

    /**
     * LocalTime 기반 야간 세그먼트 분할. V1 {@code calculateSegments(LocalTime, LocalTime, LocalDate)} 복제.
     */
    public static List<NightWorkSegment> calculateSegments(LocalTime startTime, LocalTime endTime, LocalDate workDate) {
        List<NightWorkSegment> segments = new ArrayList<>();
        boolean crossesMidnight = endTime.isBefore(startTime) || endTime.equals(LocalTime.MIDNIGHT);

        if (crossesMidnight) {
            LocalTime firstSegmentStart = startTime.isBefore(NIGHT_START) ? NIGHT_START : startTime;
            if (!firstSegmentStart.equals(LocalTime.MIDNIGHT)) {
                int minutes = (int) ((24 * 60) - (firstSegmentStart.getHour() * 60 + firstSegmentStart.getMinute()));
                if (minutes > 0) {
                    segments.add(new NightWorkSegment(workDate, minutes,
                        String.format("%02d:%02d~24:00", firstSegmentStart.getHour(), firstSegmentStart.getMinute())));
                }
            }
            if (!endTime.equals(LocalTime.MIDNIGHT) && endTime.isBefore(NIGHT_END)) {
                int minutes = (int) Duration.between(LocalTime.MIDNIGHT, endTime).toMinutes();
                if (minutes > 0) {
                    segments.add(new NightWorkSegment(workDate.plusDays(1), minutes,
                        String.format("00:00~%02d:%02d", endTime.getHour(), endTime.getMinute())));
                }
            }
        } else {
            if (startTime.isBefore(NIGHT_END)) {
                LocalTime effectiveEnd = endTime.isAfter(NIGHT_END) ? NIGHT_END : endTime;
                int minutes = (int) Duration.between(startTime, effectiveEnd).toMinutes();
                if (minutes > 0) {
                    segments.add(new NightWorkSegment(workDate, minutes,
                        String.format("%02d:%02d~%02d:%02d", startTime.getHour(), startTime.getMinute(),
                            effectiveEnd.getHour(), effectiveEnd.getMinute())));
                }
            }
            if (endTime.isAfter(NIGHT_START)) {
                LocalTime effectiveStart = startTime.isAfter(NIGHT_START) ? startTime : NIGHT_START;
                int minutes = (int) Duration.between(effectiveStart, endTime).toMinutes();
                if (minutes > 0) {
                    segments.add(new NightWorkSegment(workDate, minutes,
                        String.format("%02d:%02d~%02d:%02d", effectiveStart.getHour(), effectiveStart.getMinute(),
                            endTime.getHour(), endTime.getMinute())));
                }
            }
        }

        return segments;
    }

    /**
     * LocalDateTime 기반 세그먼트 분할. V1 {@code calculateSegments(LocalDateTime, LocalDateTime)} 복제.
     */
    public static List<NightWorkSegment> calculateSegments(LocalDateTime startDateTime, LocalDateTime endDateTime) {
        List<NightWorkSegment> segments = new ArrayList<>();
        LocalDate startDate = startDateTime.toLocalDate();
        LocalDate endDate = endDateTime.toLocalDate();
        LocalTime startTime = startDateTime.toLocalTime();
        LocalTime endTime = endDateTime.toLocalTime();

        boolean crossesMidnight = !startDate.equals(endDate) || endTime.equals(LocalTime.MIDNIGHT);

        if (crossesMidnight) {
            LocalTime segmentStart = startTime.isBefore(NIGHT_START) ? NIGHT_START : startTime;
            int minutesToMidnight = (24 * 60) - (segmentStart.getHour() * 60 + segmentStart.getMinute());
            if (minutesToMidnight > 0) {
                segments.add(new NightWorkSegment(startDate, minutesToMidnight,
                    String.format("%02d:%02d~24:00", segmentStart.getHour(), segmentStart.getMinute())));
            }

            if (!endTime.equals(LocalTime.MIDNIGHT)) {
                if (endTime.isBefore(NIGHT_END)) {
                    int minutes = (int) Duration.between(LocalTime.MIDNIGHT, endTime).toMinutes();
                    if (minutes > 0) {
                        segments.add(new NightWorkSegment(endDate, minutes,
                            String.format("00:00~%02d:%02d", endTime.getHour(), endTime.getMinute())));
                    }
                } else {
                    int minutes = (int) Duration.between(LocalTime.MIDNIGHT, NIGHT_END).toMinutes();
                    if (minutes > 0) {
                        segments.add(new NightWorkSegment(endDate, minutes,
                            String.format("00:00~%02d:%02d", NIGHT_END.getHour(), NIGHT_END.getMinute())));
                    }
                }
            }
        } else {
            if (startTime.isBefore(NIGHT_END)) {
                LocalTime effectiveEnd = endTime.isAfter(NIGHT_END) ? NIGHT_END : endTime;
                int minutes = (int) Duration.between(startTime, effectiveEnd).toMinutes();
                if (minutes > 0) {
                    segments.add(new NightWorkSegment(startDate, minutes,
                        String.format("%02d:%02d~%02d:%02d", startTime.getHour(), startTime.getMinute(),
                            effectiveEnd.getHour(), effectiveEnd.getMinute())));
                }
            }
            if (endTime.isAfter(NIGHT_START)) {
                LocalTime effectiveStart = startTime.isAfter(NIGHT_START) ? startTime : NIGHT_START;
                int minutes = (int) Duration.between(effectiveStart, endTime).toMinutes();
                if (minutes > 0) {
                    segments.add(new NightWorkSegment(startDate, minutes,
                        String.format("%02d:%02d~%02d:%02d", effectiveStart.getHour(), effectiveStart.getMinute(),
                            endTime.getHour(), endTime.getMinute())));
                }
            }
        }

        return segments;
    }

    /**
     * 야간 근무 세그먼트 — V1 {@code NightWorkCalculator.NightWorkSegment} 복제.
     */
    public static final class NightWorkSegment {
        public final LocalDate date;
        public final int minutes;
        public final String timeRange;

        public NightWorkSegment(LocalDate date, int minutes, String timeRange) {
            this.date = date;
            this.minutes = minutes;
            this.timeRange = timeRange;
        }
    }
}
