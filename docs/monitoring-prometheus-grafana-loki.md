# 운영 서버 장애 진단 및 근본 원인 해결

## 문제

운영 중인 EC2 인스턴스에서 두 가지 장애가 발생했다.

1. EC2가 며칠 간격으로 반복 다운 (StatusCheckFailed).
2. 새벽 서버 OOM 크래시.

두 장애 모두 재부팅으로 복구는 가능했지만 재발했고, 서버가 죽은 뒤에야 인지하는 공통 문제가 있었다.

## 분석

두 사례 모두 동일한 계층적 범위 축소 방식으로 진단했다.

```
AWS Status Check (System vs Instance)
→ OS 리소스 (메모리 vs 디스크)
→ 디렉토리별 용량 / 컨테이너별 메모리
→ 서비스별 로그량 / 요청당 쿼리 수
→ 근본 원인 특정
```

| 단계 | 사례 1 (디스크) | 사례 2 (OOM) |
| --- | --- | --- |
| 인프라 레벨 | Instance Check 실패 → OS 내부 | 서버 접속 불가 → 재부팅 후 확인 |
| 리소스 특정 | 메모리 정상 → 디스크 | free -h → 메모리 |
| 범위 축소 | /var/log/journal 압도적 | workpay-service mem_limit 초과 |
| 근본 원인 | journald 용량 제한 미설정 | N+1 쿼리 구조 |
| 조치 기준 | systemd 공식문서 + RedHat 권장값 | Grafana 수치 + 코드 분석 |

### 사례 1 — EC2 반복 다운 (디스크 고갈)

AWS 대시보드에서 System Status Check 통과 / Instance Status Check 실패 확인 → OS 내부 문제로 특정. `free -m`으로 메모리 정상(5GB+ 가용) 확인 → 메모리 배제. `df -h`에서 루트 파일시스템 89% 사용 확인. `du -sh /var/log/*`로 journald 로그가 압도적 용량 차지 확인. 서비스별 로그량은 정상 범주 → 특정 서비스 폭주가 아니라 journald 용량 제한 미설정으로 무제한 적재가 근본 원인. systemd 공식문서에 따르면 "no limit set → journal may use all available disk space".

### 사례 2 — 새벽 서버 OOM 크래시

재부팅 후 `free -h`에서 가용 메모리 거의 0 확인 → OOM Killer 동작 특정. Docker 컨테이너별 메모리 확인 → workpay-service가 mem_limit 초과. k6 부하테스트 + Grafana에서 Queries/sec / RPS = 요청 1건당 쿼리 87~341개 실행 확인. 코드 분석 → 직원 루프 안에서 DB/Feign 7종 + 근무일수만큼 추가 쿼리를 반복하는 N+1 패턴이 근본 원인. 쿼리 수 = 2+(7+M)xN으로 직원 50명 시 1,902회.

## 해결

### 사례 1 — journald 용량 제한

systemd 공식문서 및 RedHat 권장값을 근거로 `journald.conf`에 `SystemMaxUse=200M` 설정. 미사용 Docker 컨테이너 추가 정리. 로그 삭제의 안전성은 systemd-journald 공식문서("Journal files are not required for the system to boot or operate")로 확인.

### 사례 2 — N+1 쿼리 구조 전환

모든 DB/Feign 조회를 루프 밖 배치 쿼리로 전환, `Map<userId, Data>` O(1) 조회 구조로 재설계. Caffeine 캐시 11종 + 복합 인덱스 11개 추가.

### 재발 방지 — Grafana Alert → Slack 자동 알림 체계

기존에 Prometheus + Grafana로 메트릭은 수집하고 있었지만 알림은 없었다. Grafana Unified Alerting을 활용하여 두 계층의 알림을 Slack `#error-alerts` 전용 채널로 분리 발송하도록 구성했다.

인프라 경고 (Prometheus 기반).

| 항목 | 임계치 | 지속 시간 | 심각도 |
| --- | --- | --- | --- |
| JVM Heap 메모리 | > 85% | 5분 | warning |
| 시스템 CPU | > 80% | 5분 | warning |
| DB 커넥션 풀 (HikariCP) | > 80% | 2분 | critical |
| 디스크 여유 공간 | < 15% | 5분 | critical |
| 서비스 다운 | DOWN | 1분 | critical |

에러 감지 (Loki 기반).

| 항목 | 임계치 | 심각도 |
| --- | --- | --- |
| 전체 서비스 ERROR | 5분간 10건 이상 | critical |
| Workpay 서비스 ERROR | 5분간 5건 이상 | warning |

알림 규칙 구조 — 2단계 쿼리 분리. PromQL에 임계치 비교(`> 85`)를 직접 포함하면 결과가 1(true) 또는 nil이 되어 실제 수치가 유실된다. 이를 방지하기 위해 쿼리(raw 값)와 조건(임계치 비교)을 `__expr__` Math 표현식으로 분리했다.

```yaml
data:
  - refId: memory_usage          # 1단계: 실제 수치 (예: 91.3)
    expr: "100 * sum by (job) (...) / sum by (job) (...)"

  - refId: memory_condition      # 2단계: 임계치 비교 (true/false)
    datasourceUid: __expr__
    expression: "$memory_usage > 85"

condition: memory_condition      # 조건 판단은 2단계로
annotations:
  value: "{{ $values.memory_usage.Value | printf \"%.1f\" }}%"  # 1단계의 실제 수치 표시
```

Slack 알림 형식. 여러 서비스가 동시에 firing될 때 `CommonLabels`는 공통 레이블만 포함하여 서비스명이 빈 값이 되는 문제가 있었다. `{{ range .Alerts }}`로 개별 알림을 순회하여 해결했다.

```
[인프라 경고 FIRING]

유형:    인프라 경고
서비스:  workpay-service
항목:    JVM 메모리 사용률 높음
현재 값: workpay-service 현재 91.3% 사용 중
임계치:  85% 이상 시 알림
시간:    2026-03-10 16:11:00
─────────────────────
해당 서비스의 메모리 누수 또는 과부하를 확인하세요. OOM Kill 위험이 있습니다.
```

동일 알림 반복 방지. `repeat_interval: 1h` (1시간 간격 재발송).

## 결과

| 항목 | Before | After |
| --- | --- | --- |
| 디스크 사용률 (사례 1) | 89% | 39% (여유 공간 11% → 61% 확보) |
| 요청당 쿼리 수 (사례 2) | 341개 | 49개 (86% 감소) |
| P95 응답시간 (사례 2) | 1.19s | 87ms (93% 감소) |
| HikariCP pending (사례 2) | 3 | 0 |
| 장애 재발 | 반복 발생 | 이후 재발 없음 |
| 장애 인지 방식 | 서버 다운 후 인지 | Grafana Alert → Slack 자동 알림으로 선제 인지 |

사례 1에 대입하면, 디스크 85% 도달 시점에 "[인프라 경고] 디스크 여유 공간 부족 — 디스크 여유 공간 12.4% 남음" 알림이 발송되어, 89%에서 다운되기 전에 조치할 수 있었을 것이다. 사례 2에 대입하면, JVM 메모리 85% 도달 시 "현재 91.3% 사용 중" 경고 + 에러 로그 급증 알림이 동시에 발송되어, 새벽 크래시 전에 인지할 수 있었을 것이다.
