package example.contract;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feign Client DTO 계약 테스트
 *
 * Provider DTO 소스에서 자동 생성된 JSON fixture를 Consumer DTO로 역직렬화해서
 * 모든 필드가 정상적으로 매핑되는지 검증한다.
 *
 * JSON fixture는 scripts/generate-contract-fixtures.sh가 자동 생성한다.
 * Provider DTO가 변경되면 CI에서 fixture가 재생성되고, 이 테스트가 Consumer DTO와의
 * 호환성을 자동으로 검증한다. 사람이 JSON을 관리할 필요 없음.
 *
 * - Spring Context 없이 순수 Jackson ObjectMapper로 테스트
 * - 실행 시간: 1초 미만
 */
@DisplayName("Feign Client DTO 계약 검증")
class FeignContractTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
    }

    /** contracts/ 디렉토리에서 자동 생성된 JSON fixture 읽기 */
    private static String loadFixture(String filename) {
        try (InputStream is = FeignContractTest.class.getResourceAsStream("/contracts/" + filename)) {
            assertNotNull(is, "fixture not found: " + filename
                    + " — scripts/generate-contract-fixtures.sh를 먼저 실행하세요");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("fixture 읽기 실패: " + filename + " — " + e.getMessage());
        }
    }

    // =========================================================================
    // branch-service → workpay-service
    // =========================================================================

    @Nested
    @DisplayName("branch-service → BranchInfoDTO")
    class BranchInfoContract {

        private static String providerJson;

        @BeforeAll
        static void load() {
            providerJson = loadFixture("branch-BranchInfoDTO.json");
        }

        @Test
        @DisplayName("calendar Consumer DTO — 전체 필드 매핑")
        void calendar() throws Exception {
            var dto = mapper.readValue(providerJson, example.calendar.dto.BranchInfoDTO.class);
            assertAll(
                    () -> assertNotNull(dto.getId(), "id"),
                    () -> assertNotNull(dto.getName(), "name"),
                    () -> assertNotNull(dto.getAddress(), "address"),
                    () -> assertNotNull(dto.getDialNumbers(), "dialNumbers"),
                    () -> assertNotNull(dto.getBasicCost(), "basicCost"),
                    () -> assertNotNull(dto.getOpenTime(), "openTime"),
                    () -> assertNotNull(dto.getEndTime(), "endTime"),
                    () -> assertNotNull(dto.getRoles(), "roles"),
                    () -> assertNotNull(dto.getWeekStartDay(), "weekStartDay"),
                    () -> assertNotNull(dto.getSalaryVisibility(), "salaryVisibility")
            );
        }

        @Test
        @DisplayName("salary Consumer DTO — 전체 필드 매핑")
        void salary() throws Exception {
            var dto = mapper.readValue(providerJson, example.salary.dto.BranchInfoDTO.class);
            assertAll(
                    () -> assertNotNull(dto.getId(), "id"),
                    () -> assertNotNull(dto.getName(), "name"),
                    () -> assertNotNull(dto.getAddress(), "address"),
                    () -> assertNotNull(dto.getDialNumbers(), "dialNumbers"),
                    () -> assertNotNull(dto.getBasicCost(), "basicCost"),
                    () -> assertNotNull(dto.getOpenTime(), "openTime"),
                    () -> assertNotNull(dto.getEndTime(), "endTime"),
                    () -> assertNotNull(dto.getRoles(), "roles"),
                    () -> assertNotNull(dto.getWeekStartDay(), "weekStartDay"),
                    () -> assertNotNull(dto.getSalaryVisibility(), "salaryVisibility")
            );
        }

        @Test
        @DisplayName("미지의 필드 추가 시 크래시 안 남 (@JsonIgnoreProperties 검증)")
        void unknownFields() throws Exception {
            String extraJson = providerJson.replace("}", ", \"futureField\": \"new\"}");
            assertDoesNotThrow(() ->
                    mapper.readValue(extraJson, example.calendar.dto.BranchInfoDTO.class));
            assertDoesNotThrow(() ->
                    mapper.readValue(extraJson, example.salary.dto.BranchInfoDTO.class));
        }
    }

    @Nested
    @DisplayName("branch-service → WorkerDTO")
    class WorkerContract {

        @Test
        @DisplayName("salary Consumer DTO — 핵심 필드 매핑")
        void salary() throws Exception {
            String json = loadFixture("branch-WorkerDTO.json");
            var dto = mapper.readValue(json, example.salary.dto.WorkerDTO.class);
            assertAll(
                    () -> assertNotNull(dto.getUserId(), "userId"),
                    () -> assertNotNull(dto.getName(), "name"),
                    () -> assertNotNull(dto.getEmail(), "email"),
                    () -> assertNotNull(dto.getPhoneNums(), "phoneNums"),
                    () -> assertNotNull(dto.getRoles(), "roles"),
                    () -> assertNotNull(dto.getCost(), "cost"),
                    () -> assertNotNull(dto.getStatus(), "status"),
                    () -> assertNotNull(dto.getIsManager(), "isManager"),
                    () -> assertNotNull(dto.getInviteStatus(), "inviteStatus"),
                    () -> assertNotNull(dto.getJoinedAt(), "joinedAt")
            );
        }
    }

    // =========================================================================
    // user-service → workpay-service
    // =========================================================================

    @Nested
    @DisplayName("user-service → UserInfoDTO")
    class UserInfoContract {

        private static String providerJson;

        @BeforeAll
        static void load() {
            providerJson = loadFixture("user-UserInfoDTO.json");
        }

        @Test
        @DisplayName("calendar Consumer DTO — 전체 필드 매핑")
        void calendar() throws Exception {
            var dto = mapper.readValue(providerJson, example.calendar.dto.UserInfoDTO.class);
            assertAll(
                    () -> assertNotNull(dto.getId(), "id"),
                    () -> assertNotNull(dto.getName(), "name"),
                    () -> assertNotNull(dto.getEmail(), "email"),
                    () -> assertNotNull(dto.getProfileImage(), "profileImage"),
                    () -> assertNotNull(dto.getPhoneNums(), "phoneNums")
            );
        }

        @Test
        @DisplayName("salary Consumer DTO — 전체 필드 매핑")
        void salary() throws Exception {
            var dto = mapper.readValue(providerJson, example.salary.dto.UserInfoDTO.class);
            assertAll(
                    () -> assertNotNull(dto.getId(), "id"),
                    () -> assertNotNull(dto.getName(), "name"),
                    () -> assertNotNull(dto.getEmail(), "email"),
                    () -> assertNotNull(dto.getProfileImage(), "profileImage"),
                    () -> assertNotNull(dto.getPhoneNums(), "phoneNums")
            );
        }

        @Test
        @DisplayName("batch 응답 (List) 역직렬화")
        void batch() throws Exception {
            String listJson = "[" + providerJson + "," + providerJson + "]";
            List<example.calendar.dto.UserInfoDTO> dtos = mapper.readValue(listJson,
                    new TypeReference<>() {});
            assertEquals(2, dtos.size());
            assertNotNull(dtos.get(0).getName());
        }
    }

    @Nested
    @DisplayName("user-service / branch-service → UserWageSettingsDTO")
    class UserWageSettingsContract {

        @Test
        @DisplayName("user-service 제공 응답 역직렬화")
        void fromUserService() throws Exception {
            String json = loadFixture("user-UserWageSettingsDTO.json");
            var dto = mapper.readValue(json, example.salary.dto.UserWageSettingsDTO.class);
            assertAll(
                    () -> assertNotNull(dto.getWorkType(), "workType"),
                    () -> assertNotNull(dto.getAmount(), "amount"),
                    () -> assertNotNull(dto.getWorkMinutes(), "workMinutes")
            );
        }

        @Test
        @DisplayName("branch-service 제공 응답 역직렬화")
        void fromBranchService() throws Exception {
            String json = loadFixture("branch-UserWageSettingsResponseDTO.json");
            var dto = mapper.readValue(json, example.salary.dto.UserWageSettingsDTO.class);
            assertAll(
                    () -> assertNotNull(dto.getWorkType(), "workType"),
                    () -> assertNotNull(dto.getAmount(), "amount"),
                    () -> assertNotNull(dto.getWorkMinutes(), "workMinutes")
            );
        }

        @Test
        @DisplayName("Map<Long, DTO> 응답 역직렬화")
        void batchMap() throws Exception {
            String json = loadFixture("branch-UserWageSettingsResponseDTO.json");
            String mapJson = "{\"1\": " + json + ", \"2\": " + json + "}";
            Map<String, example.salary.dto.UserWageSettingsDTO> result = mapper.readValue(mapJson,
                    new TypeReference<>() {});
            assertEquals(2, result.size());
            assertNotNull(result.get("1").getWorkType());
        }
    }

    @Nested
    @DisplayName("user-service → UserInfoWithCreatedAtDTO")
    class UserInfoWithCreatedAtContract {

        @Test
        @DisplayName("Provider 응답 역직렬화 — Provider가 안 보내는 필드는 null")
        void withMinimalResponse() throws Exception {
            String json = loadFixture("user-UserInfoDTO.json");
            var dto = mapper.readValue(json, example.salary.dto.UserInfoWithCreatedAtDTO.class);
            assertAll(
                    () -> assertNotNull(dto.getId(), "id"),
                    () -> assertNotNull(dto.getName(), "name"),
                    () -> assertNull(dto.getRoles(), "roles — Provider가 안 보냄"),
                    () -> assertNull(dto.getCreatedAt(), "createdAt — Provider가 안 보냄")
            );
        }
    }
}
