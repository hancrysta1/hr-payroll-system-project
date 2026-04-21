package example.salary.rates;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import example.salary.engine.PayrollEngine.TaxTableEntry;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 간이세액표 연도별 로더 — {@code classpath:/rates/simplified_tax_{year}.json}을 읽어
 * {@link TaxTableEntry} 리스트로 반환.
 *
 * <p>새 연도 추가 방법: {@code simplified_tax_2027.json} 파일을 같은 경로에 추가하면 끝.
 * 코드 변경 없이 {@link #forYear(int) forYear(2027)} 호출 시 자동 로드됨.</p>
 *
 * <h3>단위 보존 (절대 변환 금지)</h3>
 * <ul>
 *   <li>{@code salaryFrom / salaryTo} : 천원 단위 (KRW_thousand) — JSON 정수값 그대로</li>
 *   <li>{@code family1 ~ family11}    : 원 단위 (KRW) — JSON 정수값 그대로</li>
 * </ul>
 *
 * 단위 메타({@code unit.salaryFrom_salaryTo == "KRW_thousand"}, {@code unit.family1_to_11 == "KRW"})를
 * 로더가 검증 — 메타 불일치 시 {@link IllegalStateException} 으로 부팅 실패.
 */
public final class TaxTableLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ConcurrentHashMap<Integer, List<TaxTableEntry>> CACHE = new ConcurrentHashMap<>();

    private TaxTableLoader() {}

    /**
     * 해당 연도 간이세액표 조회.
     * 최초 호출 시 리소스 로드 + 파싱, 이후엔 캐시된 동일 인스턴스 반환.
     *
     * @throws IllegalStateException 해당 연도 JSON 리소스 누락 / 단위 메타 불일치 / rows 비어있음
     */
    public static List<TaxTableEntry> forYear(int year) {
        return CACHE.computeIfAbsent(year, TaxTableLoader::parseFromResource);
    }

    private static List<TaxTableEntry> parseFromResource(int year) {
        String resource = "/rates/simplified_tax_" + year + ".json";
        try (InputStream in = TaxTableLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("간이세액표 리소스 누락: " + resource
                    + " — 새 연도 추가 시 동일 네이밍으로 JSON 파일 배치 필요");
            }
            JsonNode root = MAPPER.readTree(in);
            JsonNode rows = root.path("rows");
            if (!rows.isArray() || rows.isEmpty()) {
                throw new IllegalStateException("rows 배열이 비어있음: " + resource);
            }
            JsonNode unit = root.path("unit");
            String saryUnit = unit.path("salaryFrom_salaryTo").asText();
            String taxUnit = unit.path("family1_to_11").asText();
            if (!"KRW_thousand".equals(saryUnit) || !"KRW".equals(taxUnit)) {
                throw new IllegalStateException(
                    "간이세액표 단위 메타 불일치 (" + resource + ") — 기대: salaryFrom/To=KRW_thousand, family=KRW; 실제: "
                        + saryUnit + ", " + taxUnit);
            }

            List<TaxTableEntry> result = new ArrayList<>(rows.size());
            for (JsonNode row : rows) {
                result.add(new TaxTableEntry(
                    row.path("salaryFrom").asInt(),
                    row.path("salaryTo").asInt(),
                    row.path("family1").asLong(),
                    row.path("family2").asLong(),
                    row.path("family3").asLong(),
                    row.path("family4").asLong(),
                    row.path("family5").asLong(),
                    row.path("family6").asLong(),
                    row.path("family7").asLong(),
                    row.path("family8").asLong(),
                    row.path("family9").asLong(),
                    row.path("family10").asLong(),
                    row.path("family11").asLong()
                ));
            }
            return Collections.unmodifiableList(result);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("간이세액표 리소스 읽기 실패: " + resource, e);
        }
    }
}
