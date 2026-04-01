package example.contract;

import example.client.CouponServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feign Client DTO 계약 테스트 (branch-service)
 *
 * Provider DTO에서 자동 생성된 JSON fixture를 Consumer DTO로 역직렬화해서
 * 모든 필드가 정상적으로 매핑되는지 검증한다.
 *
 * JSON fixture는 scripts/generate-contract-fixtures.sh가 자동 생성.
 */
@DisplayName("Feign Client DTO 계약 검증 (branch-service)")
class FeignContractTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
    }

    private static String loadFixture(String filename) {
        try (InputStream is = FeignContractTest.class.getResourceAsStream("/contracts/" + filename)) {
            assertNotNull(is, "fixture not found: " + filename
                    + " — scripts/generate-contract-fixtures.sh를 먼저 실행하세요");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("fixture 읽기 실패: " + filename + " — " + e.getMessage());
        }
    }

    @Nested
    @DisplayName("billing-service → CouponResponseDTO")
    class CouponContract {

        @Test
        @DisplayName("Consumer DTO — 전체 필드 매핑")
        void couponResponseDTO() throws Exception {
            String json = loadFixture("billing-CouponResponseDTO.json");
            var dto = mapper.readValue(json, CouponServiceClient.CouponResponseDTO.class);
            assertAll(
                    () -> assertNotNull(dto.getId(), "id"),
                    () -> assertNotNull(dto.getCode(), "code"),
                    () -> assertNotNull(dto.getMonths(), "months"),
                    () -> assertNotNull(dto.getStatus(), "status"),
                    () -> assertNotNull(dto.getExpiresAt(), "expiresAt"),
                    () -> assertNotNull(dto.getIssuedToUserId(), "issuedToUserId"),
                    () -> assertNotNull(dto.getCreatedBy(), "createdBy"),
                    () -> assertNotNull(dto.getDescription(), "description"),
                    () -> assertNotNull(dto.getCreatedAt(), "createdAt"),
                    () -> assertNotNull(dto.getUpdatedAt(), "updatedAt")
            );
        }

        @Test
        @DisplayName("미지의 필드 추가 시 크래시 안 남")
        void unknownFields() throws Exception {
            String json = loadFixture("billing-CouponResponseDTO.json");
            String extraJson = json.replace("}", ", \"futureField\": \"new\"}");
            assertDoesNotThrow(() ->
                    mapper.readValue(extraJson, CouponServiceClient.CouponResponseDTO.class));
        }
    }
}
