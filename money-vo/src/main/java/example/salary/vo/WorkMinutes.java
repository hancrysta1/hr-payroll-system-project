package example.salary.vo;

import java.util.Objects;

/**
 * 근무시간(분) Value Object
 *
 * 음수 방지, 법정 한도 상수 내장.
 * 480 → DAILY_LIMIT, 12540 → MONTHLY_DEFAULT
 */
public final class WorkMinutes {

    public static final WorkMinutes DAILY_LIMIT = new WorkMinutes(480);
    public static final WorkMinutes WEEKLY_LIMIT = new WorkMinutes(2400);
    public static final WorkMinutes MONTHLY_DEFAULT = new WorkMinutes(12540);
    public static final WorkMinutes ZERO = new WorkMinutes(0);

    private final int minutes;

    private WorkMinutes(int minutes) {
        if (minutes < 0) {
            throw new IllegalArgumentException("근무시간은 음수일 수 없습니다: " + minutes);
        }
        this.minutes = minutes;
    }

    public static WorkMinutes of(int minutes) {
        if (minutes == 0) return ZERO;
        return new WorkMinutes(minutes);
    }

    public int value() { return minutes; }

    public boolean exceedsDailyLimit() {
        return minutes > DAILY_LIMIT.minutes;
    }

    public WorkMinutes overtimeMinutes() {
        if (!exceedsDailyLimit()) return ZERO;
        return new WorkMinutes(minutes - DAILY_LIMIT.minutes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkMinutes that)) return false;
        return minutes == that.minutes;
    }

    @Override
    public int hashCode() { return Objects.hash(minutes); }

    @Override
    public String toString() { return minutes + "분"; }
}
