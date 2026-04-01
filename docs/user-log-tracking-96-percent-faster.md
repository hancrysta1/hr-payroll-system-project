# 유저별 로그 추적으로 CS 에러 특정 96% 단축 (20분→1분)

## 문제

신생 서비스 특성상 급여 체계가 완벽히 안정화되기 전이었고, 서비스 구조를 잡아가는 과정에서 다양한 원인의 CS가 인입되었다.

- 원인 특정이 어려움. 급여 계산이 하드코딩 기반으로 이루어지기에, 고객이 관리하는 직원의 설정값 문제인지/급여 설정의 문제인지/서비스 자체의 버그인지 구분이 안 됨.
- 재현 불가. 고객 계정으로 직접 접속해 재현하는 것은 보안상 불가능했고, 유일한 단서인 로그에 유저 식별 정보가 없었다.
- 로그 수동 탐색. 수천 줄의 로그에서 시간대를 추정해 grep으로 찾는 것이 전부였다. 평균 15~20분 소요.
- 로그 유실. 서비스 재배포 시 Docker 컨테이너가 교체되면 기존 로그 전량 소실. 배포 이전 에러는 추적 자체가 불가능.
- 에러 인지 지연. 서버 자원(CPU, 메모리 등) 기반 Slack 알림은 있었지만, 애플리케이션 레벨의 에러는 고객이 연락하기 전까지 알 수 없었다.

## 분석

로그는 찍히고 있었지만 "누구의 요청인지" 연결할 컨텍스트가 없었다. 모든 로그가 서비스 단위로만 쌓이기 때문에, CS 인입 시 해당 유저의 요청을 특정하려면 시간대를 추정해서 수천 줄을 수동 탐색하는 것이 유일한 방법이었다.

서비스를 새로 배포하면 Docker 컨테이너가 교체되면서 기존 로그가 통째로 날아갔다. 배포 전에 발생한 에러는 재배포 이후엔 추적할 방법 자체가 없었다.

특히 QA에서 재현 불가능한 단말/환경 특이 케이스가 문제였다. 실제로 특정 모바일 기기에서 서비스 접속 자체가 안 되는 문의가 들어왔는데, 원인은 해당 기기 브라우저의 CORS Preflight 비표준 동작이었다. 구형 Android WebView나 제조사 커스텀 브라우저는 OPTIONS 요청의 캐싱(`Access-Control-Max-Age`) 처리나 헤더 파싱이 표준과 다르게 동작하는 케이스가 존재한다. 이런 문제는 표준 QA 환경에서 재현이 불가능하고, 해당 유저의 요청 로그를 실시간으로 확인할 수 있어야만 원인 특정이 가능했다.

기존 Prometheus + Grafana 기반의 서버 자원 알림(CPU, 메모리, 커넥션 풀 등)은 "서버가 죽기 전에 잡는" 인프라 레벨 경고일 뿐이었다. 애플리케이션 레벨에서 어떤 서비스가 에러를 뿜고 있는지, 어떤 유저에게 문제가 발생했는지는 고객 연락 이후에야 인지할 수 있었다.

## 해결

### 1단계 — MDC 기반 컨텍스트 로깅

API Gateway에서 JWT 검증 시 추출하는 userId, branchId를 각 서비스의 MDC(Mapped Diagnostic Context)에 자동 주입.

```
로그 패턴:
%d{yyyy-MM-dd HH:mm:ss.SSS} [%X{branchId:-N/A}][%X{userId:-N/A}][%X{serviceName:-unknown}] %-5level %logger{36} - %msg%n

출력 예시:
2026-03-10 14:23:01.123 [42][1057][workpay] ERROR SalaryServiceImpl - 급여 계산 실패
                         ^^  ^^^^  ^^^^^^^
                     branchId userId serviceName
```

모든 로그 라인에 지점(branchId), 유저(userId), 서비스(serviceName) 정보가 자동 부착되어 유저별 로그 필터링이 가능해짐.

### 2단계 — Promtail → Loki 중앙 로그 수집 (유실 방지)

컨테이너 내부에만 존재하던 로그를 Loki 외부 저장소로 중앙 수집하여, 재배포와 무관하게 로그가 보존되도록 구성했다.

```
Docker 컨테이너 로그
       │
  Promtail (로그 수집기)
  ├─ Docker SD로 서비스 컨테이너 자동 감지
  ├─ 정규식 파싱 → branchId, userId, serviceName 라벨 추출
  └─ 에러/경고 중심 필터링
       │
  Loki (로그 저장소)
  ├─ 라벨 기반 인덱싱 (전문 검색 아닌 라벨 필터 → 경량)
  └─ Docker named volume에 저장
       → 컨테이너 재시작/재배포에도 데이터 유지
```

### 3단계 — 디스크 보호 (로테이션 + 보존 정책)

중앙 수집으로 유실 문제는 해결됐지만, Docker 로그 로테이션이 미설정된 상태에서 로그가 무한 축적되고 있었다.

| 서비스 | 실측 로그 크기 |
| --- | --- |
| workpay-service-test | 2.1GB |
| user-service | 1.6GB |
| branch-service | 960MB |
| apigateway-service | 600MB |
| 기타 (7개 컨테이너) | ~220MB |
| 합계 | ~5.4GB (무제한 축적 중) |

Docker 로그 로테이션. 컨테이너당 최대 150MB(50MB x 3파일)로 제한.

```json
// /etc/docker/daemon.json
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "50m",
    "max-file": "3"
  }
}
```

전체 14개 컨테이너 기준 최대 ~2.1GB로 물리적 디스크 부담 제한.

Loki 보존 정책. 주요 로그 30일 보존 후 자동 삭제.

```yaml
# loki-config.yml
limits_config:
  retention_period: 720h        # 30일 보존

compactor:
  working_directory: /loki/compactor
  retention_enabled: true       # 만료 로그 자동 삭제
```

에러/경고 중심 필터링으로 동일 기간 72MB만 사용하면서, 30일간 유저별 추적이 가능한 주요 정보를 보존.

### 4단계 — 모니터링 및 알림 시스템

Prometheus(서버 자원) + Loki(애플리케이션 로그) 기반 모니터링 시스템. Grafana 알림 규칙을 통해 임계치 초과 시 Slack으로 자동 발송.

```
┌─────────────┐     ┌────────────┐     ┌──────────┐
│  Spring Boot │────▶│ Prometheus │────▶│          │
│  /actuator   │     │  (메트릭)   │     │          │
└─────────────┘     └────────────┘     │          │     ┌───────┐
                                       │ Grafana  │────▶│ Slack │
┌─────────────┐     ┌────────────┐     │ (알림)    │     └───────┘
│  Docker Logs │────▶│  Promtail  │────▶│          │
│  (stdout)    │     │  → Loki    │     │          │
└─────────────┘     └────────────┘     └──────────┘
```

인프라 경고 (Prometheus 기반)

| 항목 | 임계치 | 지속 시간 | 심각도 | PromQL |
| --- | --- | --- | --- | --- |
| JVM 메모리 (Heap) | > 85% | 5분 | warning | `100 * sum by (job) (jvm_memory_used_bytes{area="heap"}) / sum by (job) (jvm_memory_max_bytes{area="heap"})` |
| 시스템 CPU | > 80% | 5분 | warning | `100 * system_cpu_usage` |
| DB 커넥션 풀 (HikariCP) | > 80% | 2분 | critical | `100 * hikaricp_connections_active / hikaricp_connections_max` |
| 디스크 여유 공간 | < 15% | 5분 | critical | `100 * disk_free_bytes / disk_total_bytes` |
| 서비스 다운 | DOWN | 1분 | critical | `up{job=~".*-service"} == 0` |

에러 감지 (Loki 기반)

| 항목 | 임계치 | 심각도 | LogQL |
| --- | --- | --- | --- |
| 전체 서비스 ERROR | 5분간 10건 이상 | critical | `sum by (container) (count_over_time({container=~".*-service"} \|= "ERROR" [5m]))` |
| Workpay 서비스 ERROR | 5분간 5건 이상 | warning | `count_over_time({container="workpay-service"} \|= "ERROR" [5m])` |

알림 규칙 구조 — 2단계 쿼리 분리. PromQL에 임계치 비교(`> 85`)를 직접 포함하면 결과가 1(true) 또는 nil이 되어 실제 수치가 유실된다. 이를 방지하기 위해 쿼리(raw 값)와 조건(임계치 비교)을 분리했다.

```yaml
data:
  # 1단계: 실제 수치 쿼리 (비교 연산 없음)
  - refId: memory_usage
    datasourceUid: prometheus
    model:
      expr: |
        100 * sum by (job) (jvm_memory_used_bytes{area="heap"})
        / sum by (job) (jvm_memory_max_bytes{area="heap"})

  # 2단계: __expr__ Math로 임계치 비교
  - refId: memory_condition
    datasourceUid: __expr__
    model:
      type: math
      expression: "$memory_usage > 85"

# 조건 판단은 Math 표현식으로
condition: memory_condition

annotations:
  # 1단계의 실제 수치를 .Value로 참조
  value: "{{ $values.memory_usage.Value | printf \"%.1f\" }}%"
```

`$values.refId`는 객체를 반환하므로 반드시 `.Value`를 붙여야 실제 숫자값이 출력된다. `.Value` 없이 `printf "%.1f"`를 적용하면 `%!f(<nil>)%`가 출력됨.

알림 라우팅 정책

```yaml
# policies.yml
policies:
  - receiver: slack-errors          # 기본 수신자
    group_wait: 30s                 # 그룹 첫 알림 대기
    group_interval: 5m              # 같은 그룹 추가 알림 간격
    repeat_interval: 1h             # 동일 알림 반복 방지 (1시간)
    routes:
      - receiver: slack-infra       # 인프라 → slack-infra 포맷
        matchers: [type = infra]
        group_by: [alertname, job]

      - receiver: slack-errors      # 에러 → slack-errors 포맷
        matchers: [type = error]
        group_by: [alertname, container]
```

Slack 알림 형식 (에러 감지)

```
🔴 [에러 감지 FIRING]

유형:      에러 로그 감지
서비스:    workpay-service
심각도:    critical
감지 건수: 최근 5분간 ERROR 12건
기준:      10건 이상 시 알림
시간:      2026-03-10 16:11:00
─────────────────────
Grafana Explore에서 해당 서비스 로그를 확인하세요.
```

Grafana Explore — LogQL 조회 예시

```
# 특정 유저의 에러/경고 로그
{container=~".*-service"} | json | userId="1057" |~ "ERROR|WARN"

# 특정 지점의 에러만
{container="workpay-service"} |= "ERROR" | json | branchId="42"

# 지점별 에러 빈도 (시계열)
sum by (branchId) (count_over_time({container=~".*-service"} |= "ERROR" [5m]))
```

설정 파일 경로

| 파일 | 설명 |
| --- | --- |
| `grafana/provisioning/alerting/rules.yml` | 알림 규칙 (인프라 + 에러) |
| `grafana/provisioning/alerting/contactpoints.yml` | Slack 알림 포맷 |
| `grafana/provisioning/alerting/policies.yml` | 알림 라우팅 정책 |
| `grafana/provisioning/datasources/prometheus.yml` | Prometheus 데이터소스 |
| `grafana/provisioning/datasources/loki.yml` | Loki 데이터소스 |
| `prometheus.yml` | Prometheus 스크래핑 설정 |
| `loki-config.yml` | Loki 저장/보존 설정 (30일) |
| `promtail-config.yml` | Promtail 로그 수집 파이프라인 |
| `docker-compose.yml` | Grafana, Prometheus, Loki, Promtail 컨테이너 |

### 트러블슈팅

`%!f(<nil>)%` — 수치가 표시되지 않음. PromQL에 비교 연산(`> 85`)이 포함되어 결과가 1 또는 nil이 되어 `$values.refId`가 nil 객체가 된다. 쿼리와 조건을 2단계로 분리하여 해결. raw 값 쿼리에서 비교 연산 제거, `__expr__` Math로 조건 분리.

`[no value]` — 에러 건수가 표시되지 않음. `$values.error_count`가 객체를 반환하는데 `.Value` 없이 사용한 것이 원인. `{{ $values.error_count.Value }}`로 변경.

서비스명이 빈 값으로 표시. `CommonLabels.job`은 모든 firing 알림에 공통인 레이블만 포함한다. 여러 서비스가 동시에 firing되면 `job`이 공통이 아니므로 빈 값이 된다. `{{ range .Alerts }}` 순회 후 `{{ .Labels.job }}`으로 개별 접근하여 해결.

Loki `compactor.delete-request-store` 에러. `retention_enabled: true`인데 `delete_request_store`가 미설정된 것이 원인. `loki-config.yml`에 `delete_request_store: filesystem` 추가.

Broken pipe (`ClientAbortException`). 클라이언트가 응답 수신 전 연결 끊김(타임아웃 등)으로 발생하는 무해한 에러. `GlobalExceptionHandler`에 `ClientAbortException` 전용 핸들러 추가, WARN 레벨로 로깅.

## 결과

### 정량

| 지표 | Before | After |
| --- | --- | --- |
| CS 에러 특정 소요 시간 | 15~20분 (로그 grep / 시간대 추정) | 1분 이내 (userId 필터 즉시 조회) |
| 대응 속도 개선율 | — | 약 96% (20분 → 1분 기준) |
| 에러 인지 방식 | 고객 연락 후 인지 (수동) | Slack 자동 알림으로 선제 인지 |
| 에러 감지 알림 | 인프라 경고만 존재 | 인프라 경고 + 에러 로그 감지 추가 |
| 배포 이전 로그 보존 | 재배포 시 전량 소실 | 30일 보존 보장 (named volume + retention) |
| Docker 로그 디스크 사용 | 5.4GB+ (무제한 축적) | 최대 ~2.1GB (로테이션 제한) |
| Loki 중앙 저장소 | — | 72MB (30일, 에러 중심 필터링) |
| QA 미검출 엣지케이스 | 재현 불가 시 미해결 | 로그 기반 원인 특정 가능 |

### 정성

- 단말 특이 케이스 대응 근거 확보. 특정 기기의 CORS Preflight 비표준 동작 같은, QA에서 재현 불가능한 문제를 유저 로그로 즉시 특정. 구형/커스텀 WebView의 OPTIONS 처리 차이는 실제로 발생하며, 이를 로그 기반으로 입증하고 대응한 사례를 확보.
- 선제 대응 체계 전환. 기존 인프라 경고(서버 자원)에 에러 로그 감지를 추가하여, 고객이 연락하기 전에 에러를 인지하고 원인 파악 후 먼저 안내하는 프로세스로 전환.
- 고객 응대 품질 전환. "확인해보겠습니다"에서 "해당 시점에 이러한 에러가 발생했고, 원인은 ~입니다"로 즉답 가능.
- 디스크 안정성 확보. 로그 무한 축적(5.4GB+)을 로테이션 + 보존 정책으로 통제하여 디스크 부족 장애 사전 방지.
- 서비스 신뢰도 직결. 신생 서비스에서 문제 대응 속도가 곧 고객 신뢰도. 빠른 원인 특정과 해결이 이탈 방지로 이어짐.

### 기술 스택

`MDC (SLF4J)` · `Logback` · `Promtail` · `Loki` · `Grafana Unified Alerting` · `Prometheus` · `Slack Webhook` · `Docker Compose`

> 한 줄 요약. 유일한 단서인 로그에 유저 식별 정보가 없고, 재배포 시 유실까지 되던 문제를 해결하기 위해 MDC 기반 유저 컨텍스트 로깅 + Loki 중앙 수집(30일 보존, 72MB) + 기존 인프라 알림에 에러 로그 감지 Slack 알림을 추가하여, CS 에러 특정 시간을 96% 단축하고 선제 대응 체계를 확보했다.
