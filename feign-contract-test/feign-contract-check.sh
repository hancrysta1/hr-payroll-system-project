#!/bin/bash
# =============================================================================
# Feign Client DTO 계약 검증 스크립트
# =============================================================================
#
# [목적]
#   마이크로서비스 간 Feign Client로 통신할 때,
#   Provider(보내는 쪽)와 Consumer(받는 쪽) DTO 필드가 일치하는지
#   CI 단계에서 사전 검증하여, 배포 후 런타임 장애를 방지한다.
#
# [실행 시점]
#   - GitHub Actions CI 파이프라인에서 모든 PR에 대해 자동 실행
#   - 이 검증을 통과하지 못하면 빌드/배포가 진행되지 않음 (exit 1)
#
# [검증 항목]
#   1. Consumer DTO에 @JsonIgnoreProperties(ignoreUnknown = true) 누락 여부
#   2. Consumer가 기대하는 필드가 Provider에 없는 경우 (ERROR → CI 실패)
#   3. Provider가 보내는 필드가 Consumer에 없는 경우 (WARNING → 로그만)
#
# [방지 가능한 장애 예시]
#   - branch-service BranchInfoDTO에 weekStartDay 필드 추가
#     → workpay-service BranchInfoDTO에 해당 필드 없음
#     → Jackson 역직렬화 실패 → Feign 호출 에러
#     → 대타수락, 근무요청 등 BranchInfo 조회하는 모든 기능 장애
#     ※ 이 스크립트가 있었다면 PR 단계에서 "Consumer에 weekStartDay 없음"
#       경고가 떠서 배포 전에 수정 가능했음
#
# [새 Feign Client 추가 시]
#   아래 "계약 정의" 섹션에 check_contract 호출을 추가하면 됨:
#     check_contract \
#       "라벨" \
#       "$REPO_ROOT/provider-service/.../ProviderDTO.java" \
#       "$REPO_ROOT/consumer-service/.../ConsumerDTO.java"
#
# =============================================================================

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ERRORS=0
WARNINGS=0

RED='\033[0;31m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
NC='\033[0m'

# Java 파일에서 필드 목록 추출 (private Type fieldName; 패턴)
extract_fields() {
    local file="$1"
    if [ ! -f "$file" ]; then
        echo "__FILE_NOT_FOUND__"
        return
    fi
    grep -E '^\s+(private|public)\s+\S+\s+\w+\s*;' "$file" \
        | sed 's/.*\(private\|public\)\s\+\(\S\+\)\s\+\(\w\+\)\s*;.*/\3/' \
        | sort
}

# 두 DTO를 비교
check_contract() {
    local label="$1"
    local provider_file="$2"
    local consumer_file="$3"

    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  $label"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  Provider: ${provider_file#$REPO_ROOT/}"
    echo "  Consumer: ${consumer_file#$REPO_ROOT/}"

    local provider_fields consumer_fields

    provider_fields=$(extract_fields "$provider_file")
    if [ "$provider_fields" = "__FILE_NOT_FOUND__" ]; then
        echo -e "  ${RED}ERROR: Provider 파일이 없습니다${NC}"
        ERRORS=$((ERRORS + 1))
        return
    fi

    consumer_fields=$(extract_fields "$consumer_file")
    if [ "$consumer_fields" = "__FILE_NOT_FOUND__" ]; then
        echo -e "  ${RED}ERROR: Consumer 파일이 없습니다${NC}"
        ERRORS=$((ERRORS + 1))
        return
    fi

    # Provider가 보내는데 Consumer에 없는 필드 (Consumer가 무시하는 필드)
    local provider_only
    provider_only=$(comm -23 <(echo "$provider_fields") <(echo "$consumer_fields") || true)
    if [ -n "$provider_only" ]; then
        echo -e "  ${YELLOW}WARNING: Provider가 보내지만 Consumer에 없는 필드 (무시됨):${NC}"
        echo "$provider_only" | while read -r field; do
            echo -e "    - $field"
        done
        WARNINGS=$((WARNINGS + $(echo "$provider_only" | wc -l | tr -d ' ')))
    fi

    # Consumer가 기대하는데 Provider에 없는 필드 (항상 null)
    local consumer_only
    consumer_only=$(comm -13 <(echo "$provider_fields") <(echo "$consumer_fields") || true)
    if [ -n "$consumer_only" ]; then
        echo -e "  ${RED}ERROR: Consumer가 기대하지만 Provider가 보내지 않는 필드 (항상 null):${NC}"
        echo "$consumer_only" | while read -r field; do
            echo -e "    - $field"
        done
        ERRORS=$((ERRORS + $(echo "$consumer_only" | wc -l | tr -d ' ')))
    fi

    if [ -z "$provider_only" ] && [ -z "$consumer_only" ]; then
        echo -e "  ${GREEN}OK: 필드 일치${NC}"
    fi

    # @JsonIgnoreProperties 확인
    if ! grep -q "JsonIgnoreProperties" "$consumer_file"; then
        echo -e "  ${RED}ERROR: Consumer DTO에 @JsonIgnoreProperties(ignoreUnknown = true) 누락${NC}"
        ERRORS=$((ERRORS + 1))
    fi
}

echo ""
echo "╔═══════════════════════════════════════════════╗"
echo "║  Feign Client DTO 계약 검증                    ║"
echo "╚═══════════════════════════════════════════════╝"

# =============================================================================
# 계약 정의: Provider DTO → Consumer DTO 매핑
# 새로운 Feign Client가 추가되면 여기에 매핑을 추가하세요.
# =============================================================================

# branch-service → workpay-service (calendar)
check_contract \
    "BranchInfoDTO: branch-service → workpay-service/calendar" \
    "$REPO_ROOT/branch-service/src/main/java/com/example/dto/BranchInfoDTO.java" \
    "$REPO_ROOT/workpay-service/src/main/java/com/example/calendar/dto/BranchInfoDTO.java"

# branch-service → workpay-service (salary)
check_contract \
    "BranchInfoDTO: branch-service → workpay-service/salary" \
    "$REPO_ROOT/branch-service/src/main/java/com/example/dto/BranchInfoDTO.java" \
    "$REPO_ROOT/workpay-service/src/main/java/com/example/salary/dto/BranchInfoDTO.java"

# user-service → workpay-service (calendar)
check_contract \
    "UserInfoDTO: user-service → workpay-service/calendar" \
    "$REPO_ROOT/user-service/src/main/java/com/example/dto/UserInfoDTO.java" \
    "$REPO_ROOT/workpay-service/src/main/java/com/example/calendar/dto/UserInfoDTO.java"

# user-service → workpay-service (salary)
check_contract \
    "UserInfoDTO: user-service → workpay-service/salary" \
    "$REPO_ROOT/user-service/src/main/java/com/example/dto/UserInfoDTO.java" \
    "$REPO_ROOT/workpay-service/src/main/java/com/example/salary/dto/UserInfoDTO.java"

# branch-service → workpay-service (salary) WorkerDTO
check_contract \
    "WorkerDTO: branch-service → workpay-service/salary" \
    "$REPO_ROOT/branch-service/src/main/java/com/example/dto/WorkerDTO.java" \
    "$REPO_ROOT/workpay-service/src/main/java/com/example/salary/dto/WorkerDTO.java"

# user-service → workpay-service (salary) UserWageSettingsDTO
check_contract \
    "UserWageSettingsDTO: user-service → workpay-service/salary" \
    "$REPO_ROOT/user-service/src/main/java/com/example/dto/UserWageSettingsDTO.java" \
    "$REPO_ROOT/workpay-service/src/main/java/com/example/salary/dto/UserWageSettingsDTO.java"

# =============================================================================
# 결과 요약
# =============================================================================

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  결과 요약"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

if [ "$ERRORS" -gt 0 ]; then
    echo -e "  ${RED}ERRORS: $ERRORS${NC}"
fi
if [ "$WARNINGS" -gt 0 ]; then
    echo -e "  ${YELLOW}WARNINGS: $WARNINGS${NC}"
fi
if [ "$ERRORS" -eq 0 ] && [ "$WARNINGS" -eq 0 ]; then
    echo -e "  ${GREEN}모든 계약 검증 통과!${NC}"
fi

echo ""

if [ "$ERRORS" -gt 0 ]; then
    echo -e "${RED}DTO 불일치가 발견되었습니다. Provider/Consumer DTO를 동기화하세요.${NC}"
    exit 1
fi

echo -e "${GREEN}계약 검증 완료${NC}"
exit 0
