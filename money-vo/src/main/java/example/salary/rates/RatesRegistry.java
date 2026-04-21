package example.salary.rates;

import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

/**
 * 연도별 요율 레지스트리 — 계산 시점에 해당 연도의 {@link Rates}를 조회.
 *
 * <p>정산은 "현재 연도"로만 끝나지 않음. 과거 정산 재계산·감사 대응 등으로
 * 지나간 연도의 요율도 조회 가능해야 함. 새 연도 요율이 나오면 이 레지스트리에 등록만 하면 된다.</p>
 *
 * <p>미등록 연도 요청 시 {@link IllegalArgumentException}. fallback 하지 않음 —
 * 엔진이 잘못된 요율로 조용히 계산하는 것보다 명시적 실패가 안전.</p>
 */
public final class RatesRegistry {

    private static final Map<Integer, Rates> BY_YEAR;

    static {
        // TreeMap: 연도 순회 시 자동 정렬 (디버깅/로깅 편의)
        TreeMap<Integer, Rates> map = new TreeMap<>();
        map.put(2026, Rates2026Data.INSTANCE);
        // 2027 등록 예시:
        //   map.put(2027, Rates2027Data.INSTANCE);
        BY_YEAR = Map.copyOf(map);
    }

    private RatesRegistry() {}

    /**
     * 특정 연도 요율 조회. 미등록 연도면 예외.
     */
    public static Rates forYear(int year) {
        Rates r = BY_YEAR.get(year);
        if (r == null) {
            throw new IllegalArgumentException(
                year + "년도 요율 미정의 — 등록된 연도: " + BY_YEAR.keySet());
        }
        return r;
    }

    /**
     * 특정 날짜 기준 요율 (귀속 연도).
     */
    public static Rates forDate(LocalDate date) {
        return forYear(date.getYear());
    }

    /**
     * 등록된 연도 목록 — 디버깅·테스트용.
     */
    public static Map<Integer, Rates> all() {
        return BY_YEAR;
    }
}
