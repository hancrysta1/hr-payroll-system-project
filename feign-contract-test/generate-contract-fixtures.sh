#!/bin/bash
# =============================================================================
# Provider DTO → JSON fixture 자동 생성
#
# Provider DTO Java 소스에서 필드와 타입을 추출해서
# 테스트용 JSON fixture 파일을 자동 생성한다.
# 계약 테스트가 이 JSON을 읽어서 Consumer DTO 역직렬화를 검증한다.
#
# 사람이 JSON을 관리할 필요 없음 — Provider DTO가 바뀌면 자동 반영.
# =============================================================================

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORKPAY_OUT="$REPO_ROOT/workpay-service/src/test/resources/contracts"
BRANCH_OUT="$REPO_ROOT/branch-service/src/test/resources/contracts"

mkdir -p "$WORKPAY_OUT" "$BRANCH_OUT"

# Java DTO 파일 → JSON 생성
generate_json() {
    local java_file="$1"
    local output_file="$2"

    if [ ! -f "$java_file" ]; then
        echo "  ERROR: $java_file not found"
        return 1
    fi

    grep -oE '(private|public)\s+[A-Za-z<>.]+\s+[a-zA-Z]+\s*;' "$java_file" \
        | awk '
    BEGIN { first=1; print "{" }
    {
        sub(/^(private|public) /, "")
        sub(/;$/, "")
        n = split($0, parts, " ")
        type = parts[1]
        name = parts[2]

        if (type == "Long" || type == "long") val = "1"
        else if (type == "Integer" || type == "int") val = "1"
        else if (type == "BigDecimal") val = "10000"
        else if (type == "Boolean" || type == "boolean") val = "false"
        else if (type == "List<String>") val = "[\"test\"]"
        else if (type == "LocalDate") val = "\"2025-01-01\""
        else if (type == "LocalDateTime") val = "\"2025-01-01T00:00:00\""
        else if (type ~ /\./) val = "\"TEST_VALUE\""
        else val = "\"test_" name "\""

        if (!first) printf ",\n"
        first = 0
        printf "  \"%s\": %s", name, val
    }
    END { printf "\n}\n" }
    ' > "$output_file"

    echo "  generated: ${output_file#$REPO_ROOT/}"
}

echo "Provider DTO → JSON fixture 생성"
echo ""

echo "[workpay-service Consumer fixtures]"
# branch-service → workpay-service
generate_json \
    "$REPO_ROOT/branch-service/src/main/java/com/example/dto/BranchInfoDTO.java" \
    "$WORKPAY_OUT/branch-BranchInfoDTO.json"

generate_json \
    "$REPO_ROOT/branch-service/src/main/java/com/example/dto/WorkerDTO.java" \
    "$WORKPAY_OUT/branch-WorkerDTO.json"

generate_json \
    "$REPO_ROOT/branch-service/src/main/java/com/example/dto/UserWageSettingsResponseDTO.java" \
    "$WORKPAY_OUT/branch-UserWageSettingsResponseDTO.json"

# user-service → workpay-service
generate_json \
    "$REPO_ROOT/user-service/src/main/java/com/example/dto/UserInfoDTO.java" \
    "$WORKPAY_OUT/user-UserInfoDTO.json"

generate_json \
    "$REPO_ROOT/user-service/src/main/java/com/example/dto/UserWageSettingsDTO.java" \
    "$WORKPAY_OUT/user-UserWageSettingsDTO.json"

echo ""
echo "[branch-service Consumer fixtures]"
# billing-service → branch-service
generate_json \
    "$REPO_ROOT/billing-service/src/main/java/com/example/billingservice/dto/CouponResponseDTO.java" \
    "$BRANCH_OUT/billing-CouponResponseDTO.json"

echo ""
echo "완료"
