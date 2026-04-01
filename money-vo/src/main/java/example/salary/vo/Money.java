package example.salary.vo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 금액 Value Object (KRW 기준)
 *
 * final class + 생성자에서 setScale(0, HALF_UP) 강제.
 * - final이므로 상속 불가 → 하위 클래스에서 연산 규칙을 오버라이드 불가
 * - 어디서 Money를 만들든 항상 원 단위 반올림
 */
public final class Money {

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    private final BigDecimal amount;

    private Money(BigDecimal amount) {
        this.amount = amount.setScale(0, RoundingMode.HALF_UP);
    }

    public static Money of(BigDecimal amount) {
        if (amount == null) return ZERO;
        return new Money(amount);
    }

    public static Money of(long amount) {
        return new Money(BigDecimal.valueOf(amount));
    }

    public Money add(Money other) {
        return new Money(this.amount.add(other.amount));
    }

    public Money subtract(Money other) {
        return new Money(this.amount.subtract(other.amount));
    }

    public Money multiply(Percentage percentage) {
        return new Money(this.amount.multiply(percentage.toDecimal()));
    }

    public Money multiplyByMinutes(WorkMinutes minutes) {
        BigDecimal result = this.amount
                .multiply(BigDecimal.valueOf(minutes.value()))
                .divide(BigDecimal.valueOf(60), 0, RoundingMode.HALF_UP);
        return new Money(result);
    }

    public Money applyTax(Percentage taxRate) {
        BigDecimal tax = this.amount.multiply(taxRate.toDecimal())
                .setScale(0, RoundingMode.HALF_UP);
        return new Money(this.amount.subtract(tax));
    }

    public Money taxAmount(Percentage taxRate) {
        BigDecimal tax = this.amount.multiply(taxRate.toDecimal())
                .setScale(0, RoundingMode.HALF_UP);
        return new Money(tax);
    }

    public BigDecimal value() { return amount; }

    public boolean isPositive() {
        return amount.compareTo(BigDecimal.ZERO) > 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money money)) return false;
        return amount.compareTo(money.amount) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros());
    }

    @Override
    public String toString() { return amount.toPlainString() + "원"; }
}
