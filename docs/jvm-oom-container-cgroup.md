# JVM 컨테이너 cgroup 무시로 인한 OOM 해결

## 문제

새벽에 서비스 전체 응답 불가. AWS CloudWatch 대시보드에는 인스턴스 상태 "정상"으로 표시.

```
$ free -h
total    used    free    shared  buff/cache  available
Mem:          7.6Gi   7.6Gi   160Mi   9.5Mi   134Mi       79Mi
```

available 79Mi — 리눅스 커널이 새 프로세스에 메모리를 할당할 수 없는 상태.
docker ps 확인 결과 대부분의 컨테이너가 중단되어 있었다.

### 왜 CloudWatch는 정상이었나

CloudWatch 기본 모니터링은 EC2 인스턴스의 상태 체크(네트워크 도달 가능 여부, 하이퍼바이저 정상 여부)를 본다. 호스트 OS는 살아있고 SSH도 가능했기 때문에 "정상"으로 판단된 것이다.

CloudWatch 기본 메트릭에 메모리 사용량은 포함되지 않는다. 메모리 모니터링은 CloudWatch Agent를 별도 설치해야 수집된다. 즉, 메모리만 바닥나는 장애는 기본 CloudWatch로는 감지 불가능하다.

## 분석

### 물리적 원인 — 8GB에 너무 많은 JVM

```
$ ps aux --sort=-%mem | head
Kafka(Java)       RSS ~1.2GB   (-Xmx1024m -Xms1024m)
java -jar app.jar x 8개        각 470MB ~ 1GB
```

합계가 호스트 메모리 8GB를 초과한다. 리눅스 OOM Killer가 컨테이너 프로세스를 kill하거나, 메모리 할당 실패로 JVM이 자체 종료된다.

### 근본 원인 1 — Kafka가 메모리 제한 없이 1.2GB 점유

이 장애의 가장 큰 원인은 Kafka가 힙 제한과 컨테이너 메모리 제한 없이 기본값으로 실행되고 있었다는 것이다.

Kafka의 기본 힙 설정은 -Xmx1g -Xms1g으로, 별도 설정 없이 실행하면 시작과 동시에 1GB의 힙을 고정 점유한다. 여기에 OS 페이지 캐시, 로그 세그먼트 관리, 스레드 등 비힙 메모리까지 합치면 RSS 1.2GB 이상을 차지한다. mem_limit도 설정되지 않아 사용량 상한 자체가 없었다.

8GB 서버에서 Kafka 혼자 15%를 차지하니, 나머지 8개 Java 서비스 + 모니터링 스택이 쓸 수 있는 메모리가 부족해지는 건 당연한 결과였다.

매뉴얼 서비스를 지점 서비스로 병합하여 -400m 절감.

### 근본 원인 2 — -XX:-UseContainerSupport

docker-compose.yml의 모든 서비스에 다음 설정이 박혀 있었다.

```
JAVA_TOOL_OPTIONS: -XX:-UseContainerSupport
```

JDK 10부터 JVM은 cgroup을 읽어서 컨테이너의 메모리 제한을 인식한다. 예를 들어 컨테이너에 mem_limit: 512m을 걸면, JVM은 "나한테 주어진 메모리가 512MB구나"라고 인식하고 그 안에서 힙을 잡는다.

-XX:-UseContainerSupport는 이 기능을 끈다. JVM이 cgroup 정보를 무시하고 호스트 전체 메모리(8GB)를 기준으로 힙 크기를 자동 결정한다.

```
JVM 기본 힙 = 호스트 메모리의 1/4
8GB x 25% = 2GB (MaxHeapSize 기본값)
```

서비스 9개가 각각 최대 2GB까지 힙을 잡으려 하니 당연히 터진다.

### 근본 원인 3 — 컨테이너에 메모리 제한 없음

docker-compose.yml에 mem_limit이 하나도 설정되어 있지 않았다. 컨테이너가 호스트 메모리를 무제한으로 사용할 수 있는 상태였다.

-UseContainerSupport를 끄고 mem_limit도 없으면, JVM을 제어할 수단이 아무것도 없다. 각 JVM이 자기 마음대로 메모리를 점유한다.

### JVM 메모리 구조

이 장애를 이해하려면 JVM이 메모리를 어떻게 쓰는지 알아야 한다.

```
┌─────────────────────────────────┐
│         JVM 프로세스 메모리         │
├─────────────────────────────────┤
│  Heap (힙)                       │  ← 객체 생성, GC 대상
│   - Young Gen (Eden, Survivor)  │
│   - Old Gen                     │
├─────────────────────────────────┤
│  Non-Heap                       │
│   - Metaspace (클래스 메타데이터)   │  ← Spring Boot는 이게 큼
│   - Code Cache (JIT 컴파일 코드)  │
│   - Thread Stacks (스레드당 1MB)  │  ← Tomcat 스레드 200개 = 200MB
│   - Direct Buffers (NIO)        │
│   - GC 오버헤드                   │
├─────────────────────────────────┤
│  Native Memory                  │
│   - JNI, 압축 라이브러리 등         │
└─────────────────────────────────┘
```

핵심은 힙만이 JVM 메모리의 전부가 아니라는 것이다.

흔히 -Xmx384m으로 힙을 384MB로 제한하면 JVM이 384MB만 쓸 거라 생각하지만, 실제로는 힙 위에 Metaspace, Thread Stack, Code Cache, Direct Buffer, GC 오버헤드 등이 추가로 올라간다. 그래서 MaxRAMPercentage=75.0에서 75%를 힙에 할당하면, 나머지 25%가 이 비힙 영역들에 사용된다.

컨테이너 512MB 기준으로 보면 다음과 같다.

```
힙:       512 x 75% = 384MB
비힙:     512 x 25% = 128MB (Metaspace ~80MB + 스레드 ~40MB + 기타)
```

Spring Boot + JPA + Feign이면 75%로 운용 가능한 이유는 비힙 영역의 크기가 애플리케이션의 기술 스택에 따라 달라지기 때문이다. Spring Boot + JPA + Feign 조합은 비힙 사용량이 상대적으로 적다.

- JPA는 엔티티 매핑 위주의 ORM이라 Metaspace를 과도하게 쓰지 않음
- Feign은 단순 HTTP 클라이언트라 별도 스레드풀이나 대용량 버퍼를 잡지 않음
- Tomcat은 기본 스레드 200개지만, 실제 동시 요청 수에 비례해서 활성화됨

반면 Netty 기반 WebFlux + R2DBC 같은 리액티브 스택이라면, Direct Buffer(OS 레벨 메모리를 JVM 힙 바깥에서 직접 할당)를 대량으로 사용하기 때문에 비힙이 25%로는 부족할 수 있다. 기술 스택에 맞는 비율 설정이 중요하다.

### GC와 힙 크기의 관계

GC(Garbage Collection)는 힙이 부족할 때 발생한다.

JVM은 객체를 힙에 생성하고, 더 이상 참조되지 않는 객체를 GC가 회수한다. 힙이 넉넉하면 GC가 가끔 돌지만, 힙이 작으면 금방 차서 GC가 자주 돈다.

```
힙 2GB:  객체가 천천히 차 → GC 간격 김 → 응답 지연 거의 없음
힙 384MB: 객체가 빨리 차 → GC 간격 짧음 → 가끔 GC pause 발생
```

그렇다고 MaxRAMPercentage를 90%로 올리면? 힙은 넉넉해지지만 비힙 공간(Metaspace, 스레드)이 부족해서 컨테이너가 mem_limit을 초과 → OOM Kill 당한다.

75%는 힙과 비힙 사이의 균형점이다. Spring Boot 공식 문서와 커뮤니티에서 가장 널리 권장되는 값이다.

## 해결

### -XX:-UseContainerSupport 제거

```
# Before
JAVA_TOOL_OPTIONS: -XX:-UseContainerSupport

# After
JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75.0
```

-UseContainerSupport를 제거하면 JDK 기본값인 +UseContainerSupport가 활성화된다. JVM이 cgroup에서 컨테이너 메모리 제한을 읽고, 그 안에서 힙을 잡는다.

MaxRAMPercentage=75.0을 명시하여 힙이 컨테이너 limit의 75%를 넘지 않도록 보장한다.

### 서비스별 mem_limit 설정

```
discovery-service:    384m   (Eureka만, 가벼움)
apigateway-service:   512m   (라우팅)
branch-service:       512m
manual-service:       512m
user-service:         640m   (OAuth + 마이그레이션)
billing-service:      512m
workpay-service:      640m   (급여 계산)
notification-service: 512m
```

### Kafka 힙 축소 + 메모리 제한

```yaml
# Before: 힙 1024m, mem_limit 없음
# After: 힙 384m, mem_limit 512m, 로그 보존 24시간

kafka:
  mem_limit: 512m
  environment:
    KAFKA_HEAP_OPTS: "-Xmx384m -Xms384m"
    KAFKA_CFG_LOG_RETENTION_HOURS: "24"
    KAFKA_CFG_LOG_RETENTION_BYTES: "536870912"
```

### canary 배포 스크립트 동기화

deploy-canary.sh에도 -XX:-UseContainerSupport가 하드코딩되어 있었다. compose를 고쳐도 canary 배포 시 다시 원래대로 돌아가는 구조였다. 함께 수정했다.

### 메모리 총계

```
Before:
카프카 1.2GB + 서비스 제한없음(~6GB) + 모니터링 ~1GB = 8GB 초과 → OOM

After:
카프카 0.5GB + 서비스 4.3GB + 모니터링 ~1GB = 5.8GB → 여유 ~1.8GB
```

## 결과

제한 설정을 건 뒤의 상태는 다음과 같다.

```
ubuntu@ip-172-31-21-128:~/deploy$ docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}"
NAME                   MEM USAGE / LIMIT     MEM %
kafka                  82.9MiB / 512MiB      16.19%
grafana                87.11MiB / 192MiB     45.37%
prometheus             38.65MiB / 256MiB     15.10%
billing-service        441.5MiB / 800MiB     55.19%
workpay-service        551.2MiB / 800MiB     68.89%
notification-service   471.5MiB / 800MiB     58.93%
branch-service         526.7MiB / 832MiB     63.30%
user-service           508.4MiB / 800MiB     63.54%
apigateway-service     269.1MiB / 512MiB     52.55%
discovery-service      311.1MiB / 600MiB     51.85%
loki                   62.7MiB / 256MiB      24.49%
manezy_web             28.44MiB / 7.635GiB   0.36%
```

임의로 정해 둔 Limit인데 90%가 넘으면 재조정이 필요하다고 한다.
몇 개의 서비스가 좀 빡빡해보여서 재조정을 거쳤다.

### 깨달음

"설정 하나가 시스템 전체를 죽일 수 있다."

-XX:-UseContainerSupport 한 줄이 컨테이너 격리라는 개념 자체를 무력화시켰다. Docker에 mem_limit을 걸어도 JVM이 무시하면 의미가 없다. 컨테이너 = 격리라는 생각은 JVM처럼 자체 메모리 관리를 하는 런타임에서는 양쪽 다 설정해야 성립한다.

"미들웨어도 서비스다."

애플리케이션 서비스의 JVM 설정은 신경 쓰면서 Kafka는 기본값으로 방치했다. Kafka 역시 JVM 위에서 돌아가는 서비스이고, 같은 호스트에 올리는 이상 동일한 메모리 관리 원칙을 적용해야 한다. 미들웨어를 "인프라"로 분류하고 관리 대상에서 빠뜨리면, 그 미들웨어가 가장 큰 자원 독점자가 된다.

"모니터링이 안 잡으면 없는 게 아니라 모니터링이 부족한 것이다."

CloudWatch 기본 메트릭에 메모리가 없다는 사실을 몰랐다. 대시보드가 초록불이라 서버가 정상이라 믿었지만, 실제로는 메모리가 바닥난 상태였다. 측정하지 않는 것은 관리할 수 없다.
