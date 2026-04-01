# N+1 쿼리 제거로 응답시간 94% 개선 (3.2초→198ms)

급여 관리 서비스에서 부하 테스트 시 OOM이 발생했다. 메모리나 커넥션 풀을 늘리는 대신 근본 원인을 추적하여 N+1 쿼리를 제거하고, 도메인 모델을 구조화하여 성능과 유지보수성을 동시에 개선한 기록이다.

- 사용 기술. Java (JDK-21), Spring Boot, JPA, Caffeine Cache, Docker, k6, Grafana/Prometheus
- 기여도. 100%

---

## 문제

### 장애 발생

새벽에 서버가 터졌다. 호스트 머신의 물리 메모리 8GB를 전부 소진하여 OOM(Out of Memory)이 발생했다. Docker 컨테이너에 메모리 제한을 걸어두지 않은 상태에서 여러 마이크로서비스(workpay-service, branch-service, user-service, billing-service, notification-service, kafka, prometheus, grafana, loki 등)가 각자 필요한 만큼 메모리를 잡아먹다가 호스트 전체 메모리가 고갈된 것이다. Linux OOM Killer가 프로세스를 강제 종료했고, 서비스 전체가 내려갔다.

### 1차 대응 - Docker 컨테이너별 메모리 제한

한 서비스가 메모리를 비정상적으로 소비하더라도 다른 서비스에 영향을 주지 않도록, 각 Docker 컨테이너에 `mem_limit`으로 메모리 사용량의 절대값 상한을 설정했다.

```yaml
# docker-compose.yml
workpay-service:
  mem_limit: 1280m      # 급여 계산 — 가장 연산 집약적

branch-service:
  mem_limit: 1280m      # 매장/직원 설정 관리

user-service:
  mem_limit: 1280m      # 사용자 인증/정보

billing-service:
  mem_limit: 1024m

notification-service:
  mem_limit: 1024m

kafka:
  mem_limit: 1024m

prometheus:
  mem_limit: 256m       # 메트릭 수집 — 경량

loki:
  mem_limit: 256m       # 로그 수집 — 경량

grafana:
  mem_limit: 256m       # 대시보드 — 경량
```

### 2차 대응 - JVM 힙 메모리를 컨테이너의 60%로 제한

컨테이너 메모리를 제한하는 것만으로는 충분하지 않다. JVM은 기본적으로 컨테이너에 할당된 메모리의 대부분을 힙(Heap)으로 잡으려 하는데, 그러면 힙 외 영역(메타스페이스, 스레드 스택, JIT 컴파일러, 네이티브 메모리, Direct ByteBuffer 등)에 사용할 메모리가 부족해져 컨테이너 레벨에서 OOM이 발생할 수 있다.

```yaml
environment:
  JAVA_TOOL_OPTIONS: "-XX:MaxRAMPercentage=60.0 -XX:+UseStringDeduplication"
```

JVM 프로세스는 힙 메모리만 쓰는 것이 아니다. 컨테이너 1,280MB 기준으로 다음과 같이 분배된다.

| 영역 | 용도 | 대략적 크기 |
| --- | --- | --- |
| 힙 (Heap) | 객체 생성, GC 대상 | 60% = 약 768MB |
| 메타스페이스 (Metaspace) | 클래스 메타데이터, 리플렉션 | 50~150MB |
| 스레드 스택 | 스레드당 1MB, Tomcat 기본 200스레드 | 약 200MB |
| JIT 컴파일러 (CodeCache) | 컴파일된 네이티브 코드 | 50~100MB |
| Direct ByteBuffer | NIO, 네트워크 I/O 버퍼 | 수십 MB |
| Native Memory | JNI, OS 레벨 할당 | 수십 MB |

힙을 75%나 80%로 잡으면 나머지 영역에 할당할 메모리가 부족해진다. 특히 부하 상황에서 Tomcat 스레드가 증가하면 스레드 스택 메모리가 늘어나고, 동시에 힙에서 GC 빈도가 높아지면서 힙과 비힙이 동시에 메모리를 경쟁하게 된다. 60%로 설정하면 약 512MB의 여유가 생겨, 부하가 걸려도 비힙 영역이 안정적으로 동작한다.

### 그래도 부하 테스트에서 서비스가 터졌다

Docker 메모리 제한과 JVM 힙 60% 설정으로 서버 전체가 터지는 상황은 방지했다. 그러나 Apache Bench(ab)로 부하 테스트를 했더니 workpay-service 컨테이너 자체가 OOM으로 터졌다.

```
ab -c 300 -n 600 http://서버/api/salaries/{branchId}/summary
→ 동시 연결 300개, 총 요청 600개
```

| 동접 | 총 요청 | 결과 |
| --- | --- | --- |
| 300 | 600 | Non-2xx 응답 600개 - 전부 실패 |
| (재시도) | 600 | 479/600 실패 |

HikariCP 커넥션 풀 20개 전부 점유, pending 178개 적체 상태에서 컨테이너 OOM으로 강제 종료되었다. 1,280MB를 2,048MB로 늘려도 코드가 비효율적이면 부하가 조금만 올라가도 같은 일이 반복될 뿐이다. 코드 자체를 개선해서 같은 자원으로 더 많은 요청을 처리하게 만들어야 했다.

### OOM 발생 메커니즘 - 힙 메모리 관점

JVM 힙은 살아있는 객체로 채워진다. 요청이 처리되는 동안 그 요청에 연결된 객체들(HTTP 요청 객체, DB 결과셋, DTO, 응답 버퍼 등)은 힙에 살아있다. 요청이 완료되어야 GC가 이 객체들을 수거할 수 있다.

```
정상 상태:
  요청 도착 → 객체 생성(힙 사용 ↑) → 처리 완료 → GC 수거(힙 사용 ↓) → 여유 확보

부하 + N+1 상태:
  300개 요청 동시 도착
  → 각 요청이 수백 개 쿼리를 순차 실행 (N+1)
  → 쿼리 실행 중에는 커넥션을 점유하고, 요청이 "처리 중" 상태로 계속 살아있음
  → 커넥션 20개가 전부 점유됨
  → 나머지 280개 요청은 커넥션을 기다리며 스레드에서 블로킹
  → 블로킹된 스레드마다 요청 객체 + 파라미터 + 중간 결과가 힙에 계속 살아있음
  → GC가 수거할 수 없음 (아직 "처리 완료"가 아니니까)
  → 힙이 768MB(60%) 한계에 도달
  → Full GC 빈발 → STW(Stop-The-World)로 모든 스레드 일시 정지
  → 정지 동안 요청 타임아웃 → 더 많은 재시도 → 악순환
  → 결국 컨테이너 메모리 1,280MB 초과 → OOM Killer → 강제 종료
```

핵심은 커넥션 점유 시간이 길어지면 요청이 적체되고, 적체된 요청의 객체들이 GC에 수거되지 못한 채 힙에 누적된다는 것이다. 메모리를 늘려도 커넥션 점유 시간이 그대로면 요청이 더 많이 쌓일 뿐이다. 근본 원인인 커넥션을 오래 잡고 있게 만드는 코드를 고쳐야 한다.

---

## 분석

### 부하 테스트 체계 구축 - ab에서 k6로

ab는 0에서 300으로 즉시 동시 폭격하는 구조라 현실 트래픽과 동떨어져 있다. 실제 서비스에서 300명이 같은 밀리초에 같은 API를 동시 호출할 확률은 극히 낮다.

```
09:00:01  사장A → 급여조회,  직원B → 캘린더,  직원C → 출근기록
09:00:02  사장D → 급여조회,  직원E → 캘린더
09:00:03  직원F → 캘린더,    사장G → 급여조회,  직원H → 캘린더
```

| 항목 | Apache Bench (ab -c 300) | k6 (VU 기반) |
| --- | --- | --- |
| 동시 요청 | 300개 즉시 동시 폭격 | VU 수만큼 점진적 증가 |
| 램프업 | 없음 (0에서 300 즉시) | stages로 서서히 증가 |
| 요청 패턴 | 같은 밀리초에 전부 도착 | VU가 응답 받은 후 다음 요청 |
| 메트릭 | 제한적 (avg, p50 정도) | Prometheus 연동, Grafana 실시간 시각화 |

ab로는 "부하를 올리면 터진다"는 것만 확인할 수 있을 뿐, "어디서, 왜, 어떤 지점에서 병목이 시작되는지"를 관측할 수 없었다. 그래서 k6로 전환했다.

### k6 + 모니터링 스택 구축

k6(부하 생성) + Prometheus(메트릭 수집) + Grafana(시각화) + Promtail/Loki(로그 수집)를 연동하여, 부하 테스트 중 서버 자원과 성능 지표를 실시간 관측할 수 있는 대시보드를 구축했다.

Grafana 대시보드 핵심 패널 5개.

| 순서 | 패널 | 관측 대상 | 병목 신호 |
| --- | --- | --- | --- |
| 1 | K6 P95/P99/Avg 응답시간 | 클라이언트 체감 응답 속도 | P95가 1초 이상이면 느린 것 |
| 2 | 엔드포인트별 P95 | API별 응답시간 비교 | 특정 API만 느리면 해당 API에 병목 |
| 3 | Spring Boot P95 (URI별) | 서버 내부 처리시간 | K6 P95와 차이가 크면 네트워크 병목 |
| 4 | HikariCP Connections | DB 커넥션 점유 상태 | active=max + pending > 0이면 커넥션 부족 |
| 5 | Hibernate Query Count | 초당 쿼리 실행 수 | RPS 대비 비정상적으로 높으면 N+1 의심 |

병목 유형별 판단 기준.

| 지표 | 병목 판단 | 해결 방향 |
| --- | --- | --- |
| CPU 높음 | CPU 바운드 | scale out / 코드 최적화 |
| DB 쿼리 느림 | DB 바운드 | 쿼리 튜닝 / 인덱스 / 파티셔닝 |
| HikariCP pending 높음 | 커넥션 부족 | pool 증가. 단, 점유 시간이 원인이면 점유 원인 먼저 해결 |
| 같은 데이터 반복 조회 | 캐시 부재 | 캐시 도입 |
| P95 응답시간 높음 | API 구조 | 비동기 / 배치 처리 |

### 테스트 프로파일

VU(Virtual User)는 동시에 요청을 보내는 가상 사용자다. VU 1명이 쉬지 않고 반복 요청을 보내므로 VU 40이면 초당 약 400요청이 발생한다. 실제 사용자 400명은 페이지를 보고, 클릭하고, 기다리는 시간이 있어 초당 1~2요청 수준이므로 VU 40이 실사용자 약 400명에 대응된다.

| 프로파일 | VU | 시간 | 시뮬레이션 |
| --- | --- | --- | --- |
| smoke | 1 | 1분 | 기본 동작 확인 |
| average | 20 | 9분 | 평상시 (동접 ~200명) |
| stress | 40 | 15분 | 급여일 피크 (~400명) |
| spike | 80 | 6분 | 최악 상황 (~800명) |

### Before 테스트 결과

smoke부터 spike까지 단계적으로 부하를 올리며 테스트를 진행했다.

| 프로파일 | VU | P99 | P95 | Avg | HikariCP | Queries/sec | RPS |
| --- | --- | --- | --- | --- | --- | --- | --- |
| smoke | 1 | 174ms | 166ms | 138ms | idle 5, pending 0 | 9.71 | 0.422 |
| average | 20 | 123ms | 116ms | 101ms | idle 5, pending 0 | 11.8 | 0.511 |
| stress | 40 | 2.30s | 1.19s | 239ms | active 8, pending 0 | 1,629 | 18.7 |
| spike | 80 | 1.01s | 773ms | 428ms | active 20, pending 3 | 11,192 | 32.8 |

smoke(VU 1)과 average(VU 20)에서는 P95가 166ms, 116ms로 정상이었고 HikariCP도 idle 5로 여유가 있었다. stress(VU 40)부터 수치가 급격히 악화되었다.

- 응답시간. P95가 average의 116ms에서 stress에서 1.19s로 10배 증가했다.
- Queries/sec. average의 11.8에서 stress에서 1,629로 138배 증가했다. 그런데 RPS는 0.511에서 18.7로 37배밖에 안 늘었다. RPS는 37배 늘었는데 쿼리는 138배 늘었다는 것은, 부하가 올라갈수록 요청 1건당 쿼리 수가 비정상적으로 증가한다는 의미다.
- 커넥션 풀. spike에서 active 20(max), pending 3. 커넥션 풀(`maximum-pool-size: 20`)이 전부 점유되어 대기가 발생했다.

### 원인 특정 - N+1 문제

처음에는 커넥션 풀 20개(`maximum-pool-size: 20`)가 부족한 것으로 보였다. 그러나 바로 pool size를 늘리지 않았다.

- stress(VU 40)에서는 pending이 0이었다. 커넥션 수 자체가 부족한 것이 아니라, spike에서만 문제가 된다면 커넥션을 오래 점유하게 만드는 원인이 있다고 봐야 한다.
- pending은 원인이 아니라 결과다. 커넥션 점유 시간이 길어지면 자연히 pending이 발생한다. pool size를 40으로 늘려도 점유 시간이 그대로면 VU가 더 올라가면 또 pending이 발생한다.

Queries/sec를 RPS로 나누면 요청 1건당 실행되는 쿼리 수를 산출할 수 있다.

| 프로파일 | Queries/sec | RPS | 요청당 쿼리 수 |
| --- | --- | --- | --- |
| smoke | 9.71 | 0.422 | ~23개 |
| average | 11.8 | 0.511 | ~23개 |
| stress | 1,629 | 18.7 | ~87개 |
| spike | 11,192 | 32.8 | ~341개 |

smoke/average에서 요청당 약 23개로 일정하던 쿼리 수가, stress에서 87개, spike에서 341개로 부하가 올라갈수록 비선형적으로 증가했다. 이것은 N+1 패턴의 전형적 징후다.

요청당 쿼리 수가 부하에 따라 달라지는 이유는 다음과 같다.

- 동시 요청 간 캐시 경합. 같은 데이터를 여러 요청이 동시에 조회하면, 캐시가 채워지기 전에 다른 요청이 먼저 DB를 조회한다(cache stampede).
- 커넥션 대기 시간 증가. 커넥션을 기다리는 동안 다른 요청이 추가로 쌓여서, 처리 시간이 길어지고, 그 사이에 더 많은 쿼리가 실행된다.
- N+1 구조에서의 증폭. 루프 안에서 DB를 호출하면, 동시 요청 수만큼 루프가 병렬로 돌아가며 쿼리를 쏟아낸다.

### N+1 코드 확인

Claude AI를 활용해 해당 API(`SalarySummaryService.getWeeklySummaryByBranch()`)의 코드를 분석한 결과, 직원 루프 안에서 1명마다 DB/Feign 호출을 반복하는 구조가 확인되었다.

```java
// [루프 밖] 2개 쿼리 — 여기까지는 정상
List<DailyUserSalary> salaries = dailySalaryRepo
        .findSalariesByBranchAndPeriod(branchId, start, end);           // ① DB 1회
List<Long> branchUserIds = userServiceClient
        .getUserIdsByBranchId(branchId);                                // ② Feign 1회

// [루프 시작] 직원 1명마다 8 + M개 쿼리 — 여기가 문제
for (Long userId : branchUserIds) {
    // 같은 매장인데 직원마다 반복 호출
    BranchInfoDTO branchInfo = branchServiceClient.getBranch(branchId); // Feign N회

    // 1명씩 개별 호출
    UserInfoDTO userInfo = userServiceClient.getUser(userId);           // Feign N회
    UserWageSettingsDTO wageSettings = branchServiceClient
            .getUserWageSettings(branchId, userId);                     // Feign N회

    // DB도 1명씩 개별 조회
    UserSalaryAllowances allowances = allowancesRepository
            .findByUserIdAndBranchId(userId, branchId).orElse(null);   // DB N회
    UserSalaryDeductions deductions = deductionsRepository
            .findByUserIdAndBranchId(userId, branchId).orElse(null);   // DB N회
    UserInsuranceAmount insurance = insuranceRepository
            .findByUserIdAndBranchId(userId, branchId).orElse(null);   // DB N회
    List<Salary> salaryHistory = salaryRepository
            .findAllByUserIdAndBranchIdOrderByStartDateDesc(
                    userId, branchId);                                  // DB N회

    // 최악: 직원 x 근무일수만큼 DB 호출
    for (DailyUserSalary ds : userSalaries) {
        dailyWageRepository.findByUserIdAndBranchIdAndWorkDate(
                userId, branchId, ds.getWorkDate());                   // DB N x M회
    }
}
```

루프 안에서 호출되는 내부 메서드들이 같은 DB 조회를 중복으로 반복하고 있었다.

```
for (Long userId : branchUserIds) {                          // N명
    +-- calculateWeekAverageHourlyWage(...)                   // 주휴수당 계산
    |   +-- userDailyWageService.getHourlyWage(...)           // DB x M (근무일당)
    |
    +-- calculateWeeklyAllowanceByWeekWithHistory(...)        // 연장수당 계산
    |   +-- calculateWeekAverageHourlyWage(...)               // 위와 동일한 쿼리를 또 호출
    |       +-- userDailyWageService.getHourlyWage(...)       // DB x M (또)
    |
    +-- calculateOvertimeAllowanceWithHistory(...)
        +-- userDailyWageService.getHourlyWage(...)           // DB x M (또!)
```

`userDailyWageService.getHourlyWage()`가 3곳에서 중복 호출되어, 직원 15명 x 근무일 20일 기준 최대 약 900회 DB 호출이 숨겨져 있었다.

### 쿼리 수 공식

```
총 쿼리 = 2 + (8 + M) x N + 3 x N x M   (숨겨진 중복 포함)

직원 10명, 근무 30일: 2 + 38 x 10 + 3 x 10 x 30 = 1,282개
직원 15명, 근무 20일: 2 + 28 x 15 + 3 x 15 x 20 = 1,322개
```

이 계산이 stress에서 관측된 요청당 ~87개, spike에서 ~341개와 대응된다(캐시 히트, 중복 쿼리 제거 등에 의해 이론값보다 낮게 관측됨).

### 네트워크 비용

쿼리 1개당 네트워크 왕복(DB/Feign) 평균 1~5ms 소요. 쿼리 수가 곧 네트워크 I/O 횟수이고, 이것이 응답시간과 커넥션 점유 시간을 결정한다.

```
Before: ~382개 x 평균 3ms = 약 1,146ms (네트워크 대기에만 소비)
After:   ~11개 x 평균 3ms = 약   33ms

→ 코드 로직이 아닌, 순수 네트워크 대기에서만 약 1,100ms 차이
```

---

## 해결

### N+1 제거 - 배치 조회 + Map 전환

모든 데이터를 루프 전에 배치 조회하고 Map으로 변환한 뒤, 루프 안에서는 DB/Feign 호출 없이 `Map.get()` O(1) 메모리 조회만 수행하도록 재설계했다.

#### 직원 정보, 급여 설정, 가산수당 (N+1에서 고정 5회로)

Before. 직원 1명당 최소 4회 I/O, 50명이면 200회 이상.

```java
for (Long userId : branchUserIds) {
    UserInfoDTO userInfo = userServiceClient.getUser(userId);           // Feign 1회
    UserWageSettingsDTO wage = branchServiceClient.getWageSettings(userId); // Feign 1회
    UserSalaryAllowances allowances = allowancesRepo
            .findByUserIdAndBranchId(userId, branchId);                // DB 1회
    Boolean confirmed = confirmationService.isConfirmed(userId, branchId, date); // DB 1회
}
```

After. N에 무관하게 고정 5회.

```java
// 루프 전: 배치 조회 → Map 변환
Map<Long, UserInfoDTO> userInfoMap = userServiceClient
        .getUsers(branchUserIds);                                        // Feign 1회 (배치 API 신규 생성)
Map<Long, UserWageSettingsDTO> wageSettingsMap = branchServiceClient
        .getUsersWageSettingsByBranch(branchId);                         // Feign 1회 (배치 API 신규 생성)
Map<Long, UserSalaryAllowances> allowancesMap = allowancesService
        .getUserAllowancesMapByBranch(branchId);                         // DB 1회 (findByBranchId)

// 루프 안: 네트워크 호출 0회
for (Long userId : branchUserIds) {
    UserInfoDTO userInfo = userInfoMap.get(userId);          // 메모리 O(1)
    UserWageSettingsDTO wage = wageSettingsMap.get(userId);   // 메모리 O(1)
}
```

검증 사항.

- 배치 조회 범위. 전체 테이블이 아닌 단일 지점(branch) 단위로 제한. 한 지점의 직원 수는 통상 수십 명이므로 과도한 크기가 될 가능성이 낮다.
- 메모리 사용량. Map에 적재되는 데이터는 경량 DTO(수당/공제/보험 설정값). 직원 100명 기준 수십 KB. 기존에도 루프 안에서 같은 데이터를 매번 조회/역직렬화하고 있었으므로 총 메모리 사용량은 오히려 감소(중복 객체 생성 제거).
- 데이터 정합성. 배치 조회와 개별 조회의 데이터 소스가 동일(같은 Repository/Feign endpoint)하고, 조회 시점도 같은 요청 내. 배치 조회 실패 시 개별 조회로 fallback하는 방어 로직을 포함했다.
- 테스트 조건. Before/After 모두 동일한 k6 시나리오, 동일한 데이터셋, 인프라 스펙(pool size 20, JVM MaxRAMPercentage=60.0, 컨테이너 1280m) 변경 없음.

#### 공제, 보험 조회 (2N회에서 고정 2회로)

Before. 직원 N명이면 2N회 쿼리.

```java
for (Long userId : branchUserIds) {
    UserSalaryDeductions deductions = userSalaryDeductionsService
            .getUserDeductions(userId, branchId);                       // DB 1회
    UserInsuranceAmount insuranceAmounts = userInsuranceAmountService
            .getUserInsuranceAmount(userId, branchId);                  // DB 1회
}
```

After. 고정 2회.

```java
Map<Long, UserSalaryDeductions> deductionsMap = userSalaryDeductionsService
        .getDeductionsMapByBranch(branchId);                           // DB 1회
Map<Long, UserInsuranceAmount> insuranceAmountMap = userInsuranceAmountService
        .getInsuranceAmountMapByBranch(branchId);                      // DB 1회

for (Long userId : branchUserIds) {
    UserSalaryDeductions deductions = deductionsMap.get(userId);       // 메모리 O(1)
    UserInsuranceAmount insuranceAmounts = insuranceAmountMap.get(userId); // 메모리 O(1)
}
```

#### Schedule Lazy Loading 제거 (147쿼리에서 1쿼리로)

급여 상세 생성 시 대타 여부 확인을 위해 Schedule 엔티티를 참조하는데, JPA Lazy Loading으로 급여 건마다 SELECT가 발생했다. 실측 결과 3월 한 달 직원 4명 기준 147쿼리가 발생.

Before. N건의 Lazy Loading.

```java
for (DailyUserSalary s : salaries) {
    s.getSchedule() != null && Boolean.TRUE.equals(s.getSchedule().getIsSubstitute())
    // → SELECT * FROM schedule WHERE id = ? — 급여 건수만큼 반복
}
```

After. 1회 배치 프로젝션(id와 isSubstitute만 조회).

```java
Map<Long, Boolean> substituteMap = new HashMap<>();
List<Long> scheduleIds = salaries.stream()
        .map(DailyUserSalary::getScheduleId)
        .filter(Objects::nonNull).distinct().toList();
if (!scheduleIds.isEmpty()) {
    scheduleRepository.findSubstituteByIds(scheduleIds).forEach(row ->
            substituteMap.put((Long) row[0], Boolean.TRUE.equals(row[1])));
}

for (DailyUserSalary s : salaries) {
    substituteMap.getOrDefault(s.getScheduleId(), false);  // 메모리 O(1)
}
```

전체 엔티티가 아닌 필요한 컬럼(id, isSubstitute)만 프로젝션하여 메모리 사용량도 절감했다.

#### 개선 요약

| 개선 항목 | Before | After | 방법 |
| --- | --- | --- | --- |
| 매장 정보 | N번 반복 | 1번 | 루프 밖 이동 |
| 직원 정보 | N번 개별 Feign | 1번 | 배치 API 신규 생성 |
| 급여 설정 | N번 개별 Feign | 1번 | 배치 API 신규 생성 |
| 가산수당 | N번 개별 DB | 1번 | `findByBranchId()` 배치 쿼리 |
| 공제 설정 | N번 개별 DB | 1번 | `findByBranchId()` 배치 쿼리 |
| 보험 금액 | N번 개별 DB | 1번 | `findByBranchId()` 배치 쿼리 |
| 시급 이력 | N번 개별 DB | 1번 | 배치 쿼리 + `groupingBy` |
| 일별 시급 | N x M번 개별 DB | 1번 | 배치 쿼리 + 이중 Map |
| 스케줄 대타 | N번 Lazy Loading | 1번 | 배치 프로젝션 쿼리 |
| 루프 내 조회 방식 | DB/Feign 네트워크 | 메모리 조회 | `Map.get()` O(1) |

#### 쿼리 수 비교 (이론값)

| 직원 수 | 근무일 | Before | After | 감소율 |
| --- | --- | --- | --- | --- |
| 10명 | 30일 | 382개 | 11개 | 97.1% |
| 50명 | 30일 | 1,902개 | 11개 | 99.4% |
| 100명 | 30일 | 3,802개 | 11개 | 99.7% |

Before는 직원 수에 선형 이상으로 증가(N x M)하고, After는 직원 수에 무관하게 고정 11회다.

### 캐싱 적용

배치 조회로 N+1은 해결했지만, 같은 데이터를 여러 API에서 반복 조회하는 경우가 있었다. 예를 들어 summary-list API에서 공제 설정을 배치 조회한 후, 같은 직원의 detail API에서 또 조회하는 식이다. 변경 빈도가 낮고 반복 조회되는 데이터에 Caffeine 인메모리 캐시를 적용했다.

모든 캐시에 `recordStats()`로 통계를 활성화하여 Grafana에서 히트율을 모니터링할 수 있다.

| 캐시명 | TTL | 최대 크기 | 용도 | 변경 빈도 |
| --- | --- | --- | --- | --- |
| `userDeductionsCache` | 24h | 500 | 공제 설정 (세금/4대보험 ON/OFF) | 관리자가 변경할 때만 |
| `userInsuranceAmountCache` | 24h | 500 | 보험 금액 (4대보험 금액) | 관리자가 변경할 때만 |
| `userAllowancesCache` | 24h | 500 | 가산수당 설정 (주휴/연장/야간/휴일) | 관리자가 변경할 때만 |
| `holidayCache` | 24h | 100 | 공휴일 데이터 | 연 1회 |
| `minimumWageCache` | 24h | 10 | 최저임금 | 연 1회 |
| `basicSalaryCache` | 1h | 500 | 기본 시급 | 관리자가 변경할 때 |
| `salaryHistoryCache` | 1h | 500 | 시급 변경 이력 | 시급 변경 시 |
| `salaryByDateCache` | 1h | 2000 | 특정일 시급 | 시급 변경 시 |
| `wagePolicyCache` | 1h | 200 | 임금 정책 | 정책 변경 시 |
| `scheduleParseCache` | 1h | 1000 | 스케줄 파싱 결과 (AI) | 같은 텍스트면 재파싱 불필요 |
| `holidayNameCache` | 24h | 500 | 공휴일 이름 | 연 1회 |

캐시 무효화 전략.

- 키 구조. `"userId:branchId"` (예: `"123:1918"`)
- 무효화. 각 서비스의 `upsert()`, `update*()`, `delete()` 메서드에 `@CacheEvict` 적용. 데이터 변경 시 해당 캐시 엔트리가 자동 무효화된다.
- 배치 조회 시 캐시 warm-up. `getDeductionsMapByBranch(branchId)`로 지점 전체를 1회 배치 조회하면서, 각 직원의 결과를 개별 캐시에 채워넣는다. 이후 detail API에서 캐시 히트가 발생한다.

캐싱 효과.

- summary-list API. 배치 조회 1회로 캐시 warm-up 후 직원별 추가 쿼리 0회.
- 이후 detail API. 캐시 히트로 DB 쿼리 0회.
- 직원 N명 기준, 캐시 cold 시 최대 2N회에서 고정 2회로 감소. 이후 요청은 0회.

### 인덱스 최적화

배치 조회로 전환하면서 쿼리 패턴이 변경되었다. 기존에는 `WHERE user_id = ? AND branch_id = ?`(개별)였지만, 배치 조회는 `WHERE branch_id = ?`(지점 전체)다. 기존 Unique Key가 `(user_id, branch_id, ...)`로 시작하여 `branch_id`가 두 번째 컬럼이었으므로, `branch_id`만으로 조회하면 인덱스를 타지 못하고 풀스캔이 발생했다. 복합 인덱스는 선행 컬럼부터 순서대로 사용되기 때문이다.

추가한 인덱스.

| 테이블 | 인덱스명 | 컬럼 | 용도 |
| --- | --- | --- | --- |
| `daily_user_salary` | `idx_daily_salary_branch_date` | `(branch_id, work_date)` | 지점별 기간 급여 조회 |
| `schedule` | `idx_schedule_branch_start` | `(branch_id, start_time)` | 지점별 스케줄 조회 |
| `schedule` | `idx_schedule_worker_start` | `(worker_id, start_time)` | 직원별 스케줄 조회 |
| `user_salary_allowances` | `idx_allowances_branch_id` | `(branch_id)` | 가산수당 배치 조회 |
| `user_salary_deductions` | `idx_deductions_branch_id` | `(branch_id)` | 공제 설정 배치 조회 |
| `user_insurance_amount` | `idx_insurance_branch_id` | `(branch_id)` | 보험 금액 배치 조회 |
| `salary` | `idx_salary_branch_id` | `(branch_id, user_id, salary_start_date DESC)` | 시급 이력 배치 조회 |
| `user_daily_wage` | `idx_user_branch_date` | `(user_id, branch_id, work_date)` | 특정일 시급 조회 |

`daily_user_salary`의 경우, 지점별 일별 급여 조회가 주 패턴이므로 `(branch_id, work_date)` 복합 인덱스를 구성하여 range scan이 가능하도록 했다.

결과. 18,582 row 스캔에서 1 row 스캔으로 전환.

### 급여 계산 로직 구조화

#### Money VO - 금액 연산의 타입 안전성

BigDecimal을 직접 사용하면 `scale`(소수점 자릿수)을 호출 시점마다 수동 지정해야 한다. 같은 기본급 계산인데 한 곳은 `scale=0`, 다른 곳은 `scale=2`면 결과가 달라진다. 또한 `BigDecimal.equals()`는 scale까지 비교하므로 `new BigDecimal("10.0").equals(new BigDecimal("10"))`이 `false`다.

Money VO가 생성 시점에 `scale=0, HALF_UP`으로 고정하여, 이후 어떤 연산을 해도 원화 단위 일관성이 보장된다.

```java
public final class Money {
    private Money(BigDecimal amount) {
        this.amount = amount.setScale(0, RoundingMode.HALF_UP);
    }
    public boolean equals(Object o) {
        if (!(o instanceof Money money)) return false;
        return amount.compareTo(money.amount) == 0;  // scale 무관 비교
    }
}
```

VO 도입의 객체 생성 비용은 무시할 수 있다. 급여 계산은 요청당 수십~수백 건이고, BigDecimal 자체가 이미 불변 객체를 매 연산마다 생성하므로 Money로 감싸는 추가 비용은 미미하다. 오히려 scale 불일치 버그를 원천 차단하는 안전성이 실익이다.

#### HourlyWage VO - 시급 변환 공식의 단일 관리 지점

시급/일급/월급에서 시급으로의 변환 분기가 `SalaryCalculateService`, `SalarySummaryService` 등 여러 서비스에 중복되어 있었다. 한 곳에서 공식을 수정해도 다른 곳에서 누락될 위험이 있었다.

팩토리 메서드가 변환 공식을 단일 지점에서 관리한다. 법정 기준 시간(209시간)이 변경되면 `WorkMinutes.MONTHLY_DEFAULT` 하나만 수정하면 된다.

```java
HourlyWage.fromHourly(BigDecimal.valueOf(10000));                                // 시급 → 그대로
HourlyWage.fromDaily(BigDecimal.valueOf(80000), WorkMinutes.DAILY_DEFAULT);      // 일급 ÷ 8h
HourlyWage.fromMonthly(BigDecimal.valueOf(2090000), WorkMinutes.MONTHLY_DEFAULT); // 월급 ÷ 209h
```

#### WorkMinutes, Percentage - 원시 타입 오용 방지

```java
// Before: 480이 분인지 시간인지, 50이 %인지 금액인지 타입으로 구분 불가
calculateAllowance(480, 50, totalWorkMinutes);

// After: 타입으로 의미 명확, 컴파일 타임에 오류 차단
WorkMinutes.of(480).exceedsDailyLimit();              // 8시간 초과 여부
WorkMinutes.of(540).overtimeMinutes();                // → 60분 (연장분만)
hourlyWage.toMoney().multiply(Percentage.NIGHT_RATE); // 시급 × 50%
```

#### DailySalaryCalculation - 순수 도메인 모델

급여 계산을 테스트하려면 DB, Feign Client, Spring Context를 모두 띄워야 했다. 계산 전체를 JPA/외부서비스 의존 없는 순수 도메인 모델에 위임하여, `new DailySalaryCalculation(...)` -> `calculate()` -> `getNetSalary()`만으로 단위 테스트가 가능하도록 했다.

```java
// Spring 컨텍스트 필요 없음, Mock 필요 없음
@Test
void 일급_계산() {
    Money result = DailySalaryCalculation.calculate(hourlyWage, workedHours);
    assertEquals(expected, result);
}
```

8개 시나리오(시급/일급, 세금 유무, 연장/야간/휴일, 복합 적용)에 대한 테스트를 작성하여 계산 정확성을 검증했다.

POJO/순수 함수 기반 설계의 이점.

1. 프레임워크 독립적. Spring 없어도 동작하므로 Spring 버전 업그레이드해도 로직은 안 깨진다.
2. 재사용 가능. 다른 서비스 모듈에서도 그대로 가져다 쓸 수 있다.
3. 로직이 명확하게 드러남. Service에 비즈니스 로직 + DB 호출 + 트랜잭션이 섞이면 읽기 어렵지만, POJO에 계산 로직만 분리하면 "이 클래스는 급여 계산만 한다"가 바로 보인다.
4. 동시성 안전. 상태(필드)를 바꾸지 않는 불변 객체면 멀티스레드에서도 안전하다.
5. 디버깅 용이. 문제 생기면 해당 POJO만 단독으로 돌려보면 된다. Spring을 띄울 필요 없이 main에서 바로 확인 가능하다.

#### 공제 계산 함수 통합 - 중복 54줄 제거

3.3% 세금과 4대보험(국민연금, 건강보험, 고용보험, 산재보험) 공제 계산이 `SalarySummaryService` 내 2곳에 중복되어 있었다. `calculateDeductions()` 함수로 통합하여 54줄의 중복을 제거하고, 공제 항목 추가/변경 시 수정 지점을 1곳으로 줄였다.

---

## 결과

### k6 부하 테스트 결과 (Before vs After)

pool size, JVM 메모리 등 인프라 스펙 변경 없이, 동일 k6 시나리오(stress/spike)에서 비교한 결과다.

After 측정 결과.

| 프로파일 | VU | P99 | P95 | Avg | HikariCP | Queries/sec | RPS |
| --- | --- | --- | --- | --- | --- | --- | --- |
| smoke | 1 | 245ms | 134ms | 86.6ms | idle 0, pending 0 | 2.83 | 0.404 |
| stress | 40 | 182ms | 87.4ms | 76.6ms | active 5, pending 0 | 373 | 19.1 |
| spike | 80 | 522ms | 341ms | 153ms | active 11, pending 0 | 1,785 | 36.6 |

응답시간 비교.

| 프로파일 | 지표 | Before | After | 개선 |
| --- | --- | --- | --- | --- |
| stress (VU 40) | P95 | 1.19s | 87.4ms | 93% 감소 |
| stress | P99 | 2.30s | 182ms | 92% 감소 |
| stress | Avg | 239ms | 76.6ms | 68% 감소 |
| spike (VU 80) | P95 | 773ms | 341ms | 56% 감소 |
| spike | P99 | 1.01s | 522ms | 48% 감소 |
| spike | Avg | 428ms | 153ms | 64% 감소 |

요청당 쿼리 수 (N+1 해소 증거. Grafana Queries/sec / RPS).

| 프로파일 | Queries/sec | RPS | 요청당 쿼리 | 개선 |
| --- | --- | --- | --- | --- |
| stress Before | 1,629 | 18.7 | ~87개 | |
| stress After | 373 | 19.1 | ~20개 | 77% 감소 |
| spike Before | 11,192 | 32.8 | ~341개 | |
| spike After | 1,785 | 36.6 | ~49개 | 86% 감소 |

HikariCP 커넥션 풀 (커넥션 포화 해소 증거).

| 프로파일 | 지표 | Before | After |
| --- | --- | --- | --- |
| spike | active | 20 (max) | 11 |
| spike | pending | 3 (대기 발생) | 0 |
| spike | idle | 0 | 9 (여유) |

pool size를 늘린 것이 아니다. `maximum-pool-size: 20`은 그대로인데, 쿼리 수를 줄여서 커넥션 점유 시간이 짧아지니 같은 풀 크기로도 여유가 생긴 것이다.

처리량 (RPS).

| 프로파일 | Before | After | 개선 |
| --- | --- | --- | --- |
| stress | 18.7 | 19.1 | +2% |
| spike | 32.8 | 36.6 | +12% |

커넥션 점유 시간 감소로 커넥션 회전율이 증가하여, 같은 시간에 더 많은 요청을 처리하게 되었다.

### 스케일과 비용 관점

N+1을 해결하지 않고 리소스만 늘렸다면, 유저가 늘어날수록 서버 비용만 폭발했을 것이다.

| 동시 조회 | Before 쿼리 | After 쿼리 |
| --- | --- | --- |
| 10명 동시 | 3,410개 | 490개 |
| 50명 동시 | 17,050개 | 2,450개 |
| 100명 동시 | 34,100개 | 4,900개 |

전국 매장에서 사장님과 직원이 수시로 급여를 조회하는 서비스 특성상, 유저 수만 명 규모에서는 동시 조회가 수십~수백 건 들어올 수 있다.

### 주요 성과 종합

| 지표 | Stress 개선 | Spike 개선 |
| --- | --- | --- |
| 응답시간 (P95) | 93% 감소 (1.19s -> 87.4ms) | 56% 감소 (773ms -> 341ms) |
| 요청당 쿼리 수 | 77% 감소 (~87개 -> ~20개) | 86% 감소 (~341개 -> ~49개) |
| 커넥션 풀 | - | active 20/pending 3 -> 11/0 |
| 처리량 | - | +12% (32.8 -> 36.6 RPS) |
| 인덱스 스캔 | 18,582 row -> 1 row | |

1. N+1 쿼리 제거. 요청당 쿼리가 직원 수에 비례하던 구조(2+(7+M)xN)에서 고정 10~11회로 전환.
2. Schedule Lazy Loading 제거. 147회에서 1회 배치 프로젝션 쿼리로 전환.
3. 캐싱 + warm-up. Caffeine 캐시 11종, 배치 조회 결과를 개별 캐시에 채워넣어 후속 API도 캐시 히트.
4. 인덱스 최적화. 11개 복합 인덱스, branch_id 선두 컬럼 설계로 Full Scan에서 Index Seek으로 전환.
5. 도메인 모델 엔진화. Money/HourlyWage/WorkMinutes/Percentage VO + DailySalaryCalculation 순수 POJO로 계산 로직 격리, 단위 테스트 가능한 구조 확보.
6. 부하테스트 인프라 구축. k6 + Grafana + Prometheus 기반 4가지 시나리오(smoke/average/stress/spike), 벤치마크 서비스로 Before/After 정량 비교 체계 확보.

### 객체의 역할 분담 - 단일 책임 원칙(SRP)

급여 계산이라는 복잡한 비즈니스 로직을 서비스 메서드에 모두 몰아넣었더니 코드가 비대해지고 테스트가 어려웠다. 각 객체가 자신의 역할에만 충실하도록 분리한 결과.

| 객체 | 책임 | 변경 이유 |
| --- | --- | --- |
| Money | 금액 연산, scale 관리 | 원화 단위 정책이 바뀔 때 |
| HourlyWage | 시급/일급/월급에서 시급으로의 변환 | 변환 공식이 바뀔 때 |
| WorkMinutes | 근무시간 표현, 법정 기준 판단 | 법정 근로시간이 바뀔 때 |
| Percentage | 가산율, 세율 표현 | 법정 가산율이 바뀔 때 |
| DailySalaryCalculation | 일급 계산 전체 | 급여 계산 공식이 바뀔 때 |
| DeductionResult | 공제 계산 | 세금/보험 정책이 바뀔 때 |

법정 기준이 바뀌면 `WorkMinutes`의 상수만, 세금 정책이 바뀌면 `Percentage`의 상수만, 급여 계산 공식이 바뀌면 `DailySalaryCalculation`만 수정하면 된다. 서비스의 트랜잭션/저장 로직은 건드리지 않아도 된다.

### 회고 - 자원 관리에 대한 인식 변화

프로젝트를 진행하면서 메모리나 커넥션 풀 같은 자원을 어떻게 써야 하는지 고려한 적이 없었다. 이번 경험을 통해 가진 자원 내에서 최적의 효율과 안정적인 운영을 위해서는 다음이 필요하다는 것을 깨달았다.

- 메모리 격리. 컨테이너별 `mem_limit`으로 한 서비스가 호스트 전체를 잡아먹지 않게 방어.
- JVM 힙 비율 관리. `MaxRAMPercentage=60.0`으로 힙과 비힙 영역의 균형 확보. 힙을 너무 크게 잡으면 스레드 스택, 메타스페이스 등 비힙 영역이 부족해져 오히려 OOM이 발생한다.
- 커넥션 풀 크기보다 점유 시간. `maximum-pool-size: 20`을 늘리는 대신, 커넥션 점유 시간을 줄여서 같은 풀 크기로 더 많은 요청을 처리.
- 정량적 측정. "느리다"가 아니라 "P95가 1.19s, 요청당 쿼리 87개"로 측정해야 원인을 특정하고 개선 효과를 검증할 수 있다.

pool size를 늘리거나 메모리를 증설한 것이 아니라, 원인을 추적하여 N+1 문제까지 좁혔고, 쿼리 구조를 개선하니 커넥션 풀 포화와 OOM 문제까지 자연스럽게 해소되었다. 동시에 급여 계산을 Money VO 기반의 도메인 모델로 구조화하여 비즈니스 로직의 정확성과 유지보수성을 함께 확보했다.

### 추가 최적화 가능 방향

| 방향 | 내용 | 기대 효과 |
| --- | --- | --- |
| 쿼리 프로젝션 | 집계 시 엔티티 전체 대신 필요 컬럼만 DTO 프로젝션 | 메모리 사용량/전송량 절감 |
| DB 레벨 집계 | 주간 근무시간/야간 분 합산을 SUM/GROUP BY로 DB 위임 | 전송 row 수 감소, 자바 루프 제거 |
| 유저별 병렬 계산 | 유저별 계산을 CompletableFuture 병렬화 | 멀티코어 활용, 응답시간 추가 단축 |
| Feign 호출 통합 | 유저 정보/시급 설정/근무자 역할 3회 호출을 복합 API 1회로 | 네트워크 왕복 2회 절감 |
