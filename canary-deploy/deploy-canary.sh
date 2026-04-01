#!/bin/bash

SERVICE_NAME=$1

if [ -z "$SERVICE_NAME" ]; then
    echo "사용법: ./deploy-canary.sh <service-name>"
    echo "예시: ./deploy-canary.sh user-service"
    exit 1
fi

set -e

echo "=========================================="
echo "새 버전 배포 시작: $SERVICE_NAME"
echo "=========================================="

# Phase 1: Docker 이미지 확인
echo ""
echo "[Phase 1] Docker 이미지 확인 중..."

# 이미 docker load로 로드된 이미지를 canary 태그로 복사
if docker images ${SERVICE_NAME}:latest | grep -q ${SERVICE_NAME}; then
    docker tag ${SERVICE_NAME}:latest ${SERVICE_NAME}:canary
    echo "이미지 준비 완료: ${SERVICE_NAME}:canary"
else
    echo "오류: ${SERVICE_NAME}:latest 이미지를 찾을 수 없음"
    echo "docker load로 이미지를 먼저 로드해주세요"
    exit 1
fi

# Phase 2: 새 버전 인스턴스 시작
echo ""
echo "[Phase 2] 새 버전 인스턴스 배포 중..."

# 현재 실행 중인 서비스 컨테이너 찾기 (docker-compose 사용)
CONTAINER_ID=$(docker-compose ps -q ${SERVICE_NAME} 2>/dev/null)
if [ -z "$CONTAINER_ID" ]; then
    echo "기존 서비스를 찾을 수 없음"
    exit 1
fi
# Container ID를 Container Name으로 변환
CONTAINER_NAME=$(docker inspect --format='{{.Name}}' $CONTAINER_ID | sed 's/^\///')

# 현재 실행 중인 서비스 포트 확인
CURRENT_PORT=$(docker port ${CONTAINER_NAME} 2>/dev/null | head -1 | cut -d':' -f2)
if [ -z "$CURRENT_PORT" ]; then
    echo "포트 정보를 찾을 수 없음"
    exit 1
fi

# 기존 컨테이너가 사용 중인 네트워크 확인
CURRENT_NETWORK=$(docker inspect ${CONTAINER_NAME} --format='{{range $net, $conf := .NetworkSettings.Networks}}{{$net}}{{end}}')
echo "기존 컨테이너 네트워크: $CURRENT_NETWORK"

# 새 버전용 새 포트 할당 (기존 포트 + 1000)
CANARY_PORT=$((CURRENT_PORT + 1000))

echo "구버전 포트: $CURRENT_PORT"
echo "새 버전 포트: $CANARY_PORT"

# 새 버전 컨테이너 실행
CANARY_CONTAINER="${SERVICE_NAME}-canary"

# 기존 새 버전 컨테이너가 있으면 제거
if docker ps -a --format '{{.Names}}' | grep -q "^${CANARY_CONTAINER}$"; then
    echo "기존 새 버전 컨테이너 제거 중..."
    docker stop ${CANARY_CONTAINER} 2>/dev/null || true
    docker rm ${CANARY_CONTAINER} 2>/dev/null || true
fi

# 기존 컨테이너의 메모리 제한을 가져와서 동일하게 적용
MEM_LIMIT_BYTES=$(docker inspect --format='{{.HostConfig.Memory}}' ${CONTAINER_NAME} 2>/dev/null)
if [ "$MEM_LIMIT_BYTES" = "0" ] || [ -z "$MEM_LIMIT_BYTES" ]; then
  MEM_LIMIT="800m"
else
  MEM_LIMIT="${MEM_LIMIT_BYTES}"
fi
echo "메모리 제한: ${MEM_LIMIT}"

docker run -d \
  --name ${CANARY_CONTAINER} \
  --network ${CURRENT_NETWORK} \
  -p ${CANARY_PORT}:${CURRENT_PORT} \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseStringDeduplication" \
  --memory=${MEM_LIMIT} \
  --env-file .env \
  ${SERVICE_NAME}:canary

DOCKER_RUN_EXIT=$?

if [ $DOCKER_RUN_EXIT -ne 0 ]; then
    echo "새 버전 컨테이너 시작 실패"
    exit 1
fi

echo "새 버전 인스턴스 시작됨"

# Phase 3: Health Check 대기 및 검증
echo ""
echo "[Phase 3] Health Check 검증 중..."
RETRY_COUNT=0
MAX_RETRIES=12  # 2분 (12회 × 10초)
HEALTH_CHECK_URL="http://localhost:${CANARY_PORT}/actuator/health"

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    HEALTH_STATUS=$(curl -s $HEALTH_CHECK_URL 2>/dev/null | jq -r '.status' 2>/dev/null || echo "DOWN")

    if [ "$HEALTH_STATUS" = "UP" ]; then
        echo "Health Check 성공"
        break
    fi

    echo "Retry $((RETRY_COUNT + 1))/$MAX_RETRIES - Status: $HEALTH_STATUS"
    sleep 10
    RETRY_COUNT=$((RETRY_COUNT + 1))
done

if [ $RETRY_COUNT -eq $MAX_RETRIES ]; then
    echo "=========================================="
    echo "Health Check 실패 - 자동 롤백 시작"
    echo "=========================================="
    echo ""
    echo "[디버깅] ${CANARY_CONTAINER} 최근 로그:"
    echo "----------------------------------------"
    docker logs ${CANARY_CONTAINER} 2>&1 | tail -80
    echo "----------------------------------------"
    echo ""
    docker stop ${CANARY_CONTAINER}
    docker rm ${CANARY_CONTAINER}
    docker rmi ${SERVICE_NAME}:canary
    echo "롤백 완료. 구버전이 계속 실행 중"
    exit 1
fi

# Phase 4: Eureka 등록 확인
echo ""
echo "[Phase 4] Eureka 등록 확인 중..."

# discovery-service는 자기 자신이 Eureka 서버이므로 등록 체크 생략
if [ "$SERVICE_NAME" = "discovery-service" ]; then
    echo "discovery-service는 Eureka 서버이므로 등록 체크 생략"
else
    # 먼저 discovery-service(Eureka 서버) 준비 대기
    echo "Eureka 서버(discovery-service) 준비 대기 중..."
    DISCOVERY_WAIT=0
    DISCOVERY_MAX_WAIT=120  # 최대 2분

    while [ $DISCOVERY_WAIT -lt $DISCOVERY_MAX_WAIT ]; do
        EUREKA_SERVER_CHECK=$(curl -s http://localhost:18761/actuator/health 2>/dev/null | jq -r '.status' 2>/dev/null || echo "DOWN")
        if [ "$EUREKA_SERVER_CHECK" = "UP" ]; then
            echo "✓ Eureka 서버 준비 완료 (${DISCOVERY_WAIT}초 대기)"
            break
        fi

        sleep 5
        DISCOVERY_WAIT=$((DISCOVERY_WAIT + 5))

        # 15초마다 상태 출력
        if [ $((DISCOVERY_WAIT % 15)) -eq 0 ]; then
            echo "Eureka 서버 대기 중... (${DISCOVERY_WAIT}/${DISCOVERY_MAX_WAIT}초)"
        fi
    done

    if [ "$EUREKA_SERVER_CHECK" != "UP" ]; then
        echo "=========================================="
        echo "Eureka 서버 대기 타임아웃 - 자동 롤백"
        echo "=========================================="
        docker stop ${CANARY_CONTAINER}
        docker rm ${CANARY_CONTAINER}
        docker rmi ${SERVICE_NAME}:canary
        echo "롤백 완료. discovery-service를 먼저 배포하세요"
        exit 1
    fi

    # Eureka 등록 대기 (5초마다 체크, 즉시 반응)
    EUREKA_WAIT=0
    EUREKA_MAX_WAIT=60  # 최대 1분

    while [ $EUREKA_WAIT -lt $EUREKA_MAX_WAIT ]; do
        # 여러 패턴으로 Eureka 등록 확인
        EUREKA_REGISTERED=$(docker logs ${CANARY_CONTAINER} 2>&1 | grep -iE "registered with eureka|registration status: 204|Registering application" | wc -l)

        if [ $EUREKA_REGISTERED -gt 0 ]; then
            echo "✓ Eureka 등록 완료 (${EUREKA_WAIT}초 대기)"
            break
        fi

        sleep 5
        EUREKA_WAIT=$((EUREKA_WAIT + 5))

        # 15초마다 상태 출력
        if [ $((EUREKA_WAIT % 15)) -eq 0 ]; then
            echo "Eureka 등록 대기 중... (${EUREKA_WAIT}/${EUREKA_MAX_WAIT}초)"
        fi
    done

    if [ $EUREKA_REGISTERED -eq 0 ]; then
        echo "=========================================="
        echo "Eureka 등록 실패 - 자동 롤백 시작"
        echo "=========================================="
        echo ""
        echo "[디버깅] ${SERVICE_NAME}-canary 최근 로그:"
        echo "----------------------------------------"
        docker logs ${CANARY_CONTAINER} 2>&1 | tail -30
        echo ""
        echo "[디버깅] discovery-service 최근 로그:"
        echo "----------------------------------------"
        docker logs discovery-service 2>&1 | tail -30
        echo ""
        echo "[디버깅] discovery-service 상태:"
        echo "----------------------------------------"
        curl -s http://localhost:18761/actuator/health 2>/dev/null | jq . || echo "Health endpoint 응답 없음"
        echo ""

        docker stop ${CANARY_CONTAINER}
        docker rm ${CANARY_CONTAINER}
        docker rmi ${SERVICE_NAME}:canary
        echo "롤백 완료. 구버전이 계속 실행 중"
        exit 1
    fi
fi

# Phase 5: 트래픽 모니터링 (1분)
echo ""
echo "[Phase 5] 트래픽 모니터링 중 (1분)..."
echo "=========================================="
echo "현재 트래픽 분배:"
echo "  ${SERVICE_NAME} (구버전): 50%"
echo "  ${CANARY_CONTAINER} (신버전): 50%"
echo "=========================================="

for i in {1..6}; do
    HEALTH_STATUS=$(curl -s $HEALTH_CHECK_URL 2>/dev/null | jq -r '.status' 2>/dev/null || echo "DOWN")

    if [ "$HEALTH_STATUS" != "UP" ]; then
        echo "=========================================="
        echo "모니터링 중 오류 감지 - 자동 롤백 시작"
        echo "=========================================="
        docker stop ${CANARY_CONTAINER}
        docker rm ${CANARY_CONTAINER}
        docker rmi ${SERVICE_NAME}:canary
        echo "롤백 완료. 구버전이 계속 실행 중"
        exit 1
    fi

    echo "모니터링 $i/6 - Status: $HEALTH_STATUS"
    sleep 10
done

echo "모니터링 완료 - 오류 없음"

# Phase 6: 구버전 제거
echo ""
echo "[Phase 6] 구버전 제거 중..."

docker-compose stop ${SERVICE_NAME}
docker-compose rm -f ${SERVICE_NAME}

echo "구버전 제거 완료"
echo "현재 새 버전이 100% 트래픽을 처리 중"

# Phase 7: 새 버전을 정식 버전으로 전환
echo ""
echo "[Phase 7] 배포 완료 처리 중..."

# 이미지 태그 변경
docker tag ${SERVICE_NAME}:canary ${SERVICE_NAME}:latest
docker tag ${SERVICE_NAME}:canary ${SERVICE_NAME}

# 새 버전 컨테이너 중단 후 docker-compose로 재시작
docker stop ${CANARY_CONTAINER}
docker rm ${CANARY_CONTAINER}

docker-compose up -d ${SERVICE_NAME}

echo ""
echo "=========================================="
echo "새 버전 배포 완료"
echo "=========================================="
echo "서비스: ${SERVICE_NAME}"
echo "버전: v2.0 (신버전)"
echo "트래픽: 100%"
echo ""
echo "모니터링 명령어:"
echo "  docker logs -f ${SERVICE_NAME}"
echo "  curl http://localhost:18761"
echo "=========================================="