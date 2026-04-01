# Kafka 메모리 72% 과소비 -> RabbitMQ 전환

## 문제

`docker stats`를 찍어봤다.

```
CONTAINER ID   NAME      CPU %     MEM USAGE / LIMIT   MEM %     NET I/O         BLOCK I/O         PIDS
9c07612735d0   kafka     1.18%     736.7MiB / 1GiB     71.95%    416MB / 288MB   1.68MB / 7.27GB   106
```

736.7MB. 알림 좀 보내는 서비스인데 메모리를 왜 이만큼 먹고 있지?
우리 서비스가 보내는 알림은 하루에 수천 건도 안 되는데, Kafka가 메모리의 72%를 잡아먹고 있었다.

디스크도 문제였다.

```
BLOCK I/O: 1.68MB / 7.27GB
```

쓰기가 7.27GB. Kafka는 Consumer가 메시지를 읽어가도 바로 삭제하지 않는다. retention 설정(우리는 24시간)동안 디스크에 계속 보관한다. 이게 Kafka의 핵심 설계인데, 나중에 오프셋을 되돌려서 메시지를 다시 처리할 수 있게 해주는 기능이다.

근데 솔직히 "어제 보낸 알림톡을 다시 보내야 하는 상황"이 있나? 없다. 우리한테는 필요 없는 기능이 디스크를 잡아먹고 있었다.

---

## 분석

### Kafka의 구조적 메모리 과소비

처음에는 단순히 docker-compose에 `Xms=512m`으로 설정해놔서 그런 줄 알았다. 그럼 이걸 줄이면 되는 거 아닌가?

근데 찾아보니까 그게 아니었다.

Kafka는 Java/Scala로 만들어진 애플리케이션이다. Docker로 띄웠어도 컨테이너 안에서 JVM이 돌아가고, JVM은 힙 외에도 구조적으로 먹는 메모리가 있다.

```
Docker 컨테이너 안:
┌──────────────────────┐
│  Kafka (Java 앱)      │
│  ────────────────── │
│  JVM                  │  ← 이게 메모리를 먹는 주범
│  ────────────────── │
│  Linux               │
└──────────────────────┘
```

JVM은 힙 말고도 이런 것들이 무조건 붙는다.

- Metaspace (~96MB). 클래스 메타데이터 저장. Confluent 공식 권장값이 `XX:MetaspaceSize=96m`이다.
- 스레드 스택. 스레드 하나당 ~1MB. `docker stats`에서 PIDS가 106개니까 ~106MB.
- GC, Direct Buffer 등. ~수십MB

> Confluent 공식 Deployment Guide에서 권장하는 GC 설정.
`-Xms6g -Xmx6g -XX:MetaspaceSize=96m -XX:+UseG1GC`
프로덕션 브로커 권장 RAM은 64GB.
https://docs.confluent.io/platform/current/kafka/deployment.html

그러니까 힙을 128MB로 줄여도 다음과 같다.

```
JVM 힙:         128 MB
Metaspace:      ~96 MB   ← 못 줄임
스레드 스택:     ~106 MB  ← 못 줄임
나머지:          ~50 MB
합계:           ~400 MB  ← 이게 사실상 하한선
```

설정 문제가 아니라 Kafka가 JVM 위에서 돌아가는 것 자체가 소규모 서비스에는 무겁다는 걸 이때 깨달았다.

Confluent 문서를 보면 Kafka는 아예 OS 페이지 캐시를 적극 활용하도록 설계되어 있다. 64GB RAM 중 힙 6GB 빼고 나머지 28~30GB를 파일시스템 캐싱에 쓰라고 권장한다. 애초에 대용량 스트리밍을 전제로 만들어진 도구인 거다.

### 대안 검토

RabbitMQ

Erlang이라는 언어로 만들어져 있고 Erlang VM(BEAM) 위에서 돈다. JVM과 근본적으로 다른 점은 다음과 같다.

- Erlang 프로세스 1개당 ~2KB (JVM 스레드는 ~1MB)
- 메모리를 사용한 만큼만 점유하고, 유휴 시 OS에 반환
- Consumer가 ACK 보내면 메시지 즉시 삭제

> RabbitMQ 공식 문서에 따르면 메모리 구성 요소(Connections, Queues, Binaries 등)가 실제 사용량에 비례하여 증감한다. 메시지 1건당 메타데이터 ~720B.
https://www.rabbitmq.com/docs/memory-use

```
Kafka 컨테이너:              RabbitMQ 컨테이너:
┌──────────────────┐        ┌──────────────────┐
│  Kafka (Java)     │        │  RabbitMQ (Erlang) │
│  JVM              │        │  Erlang VM (BEAM)  │
│  Linux            │        │  Linux             │
└──────────────────┘        └──────────────────┘
mem_limit: 1024m             mem_limit: 256m
```

Redis

메모리 측면에서는 더 가볍지만, Pub/Sub 모드에서 소비자가 다운되면 메시지가 유실된다. 알림(이메일, 알림톡, FCM)은 유실되면 안 되니까 탈락.

| 비교 항목 | Redis Pub/Sub | RabbitMQ |
|---|---|---|
| 메시지 유실 | 유실 가능 | 큐에 보관 (영속성) |
| 재처리 | 불가 | ACK/NACK 재시도 |
| 라우팅 | 채널 브로드캐스트만 | Exchange로 세밀한 라우팅 |
| 메모리 | 가벼움 (보관 안 함) | 큐 쌓이면 증가 (디스크 분산 가능) |
| 장애 복구 | 불가 | 재기동 시 큐에서 재처리 |
| 본래 목적 | 캐시 | 메시지 브로커 |

### 결론

|  | Kafka (JVM) | RabbitMQ (Erlang) | Redis (C) |
| --- | --- | --- | --- |
| 실측/예상 메모리 | 736.7 MB | ~100~150 MB | ~30 MB |
| 최적화해도 최소 | ~400 MB | ~80 MB | ~30 MB |
| 메시지 유실 방지 | O | O | X |
| 소비 후 메시지 | 24시간 보관 | 즉시 삭제 | 즉시 삭제 |

알림은 유실되면 안 되니까 Redis는 안 되고, Kafka는 우리 규모에 과하다. RabbitMQ로 가기로 했다.

---

## 해결

### build.gradle (5개 서비스 전부)

```
// 변경 전
implementation 'org.springframework.kafka:spring-kafka'

// 변경 후
implementation 'org.springframework.boot:spring-boot-starter-amqp'
```

### application.yml

```yaml
# 변경 전
spring:
  kafka:
    bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      retries: 3
      properties:
        enable.idempotence: true
        max.block.ms: 5000
        delivery.timeout.ms: 30000

# 변경 후
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:guest}
    password: ${RABBITMQ_PASSWORD:guest}
    template:
      mandatory: true
    publisher-confirm-type: correlated
    publisher-returns: true
```

### Consumer 설정 (notification-service)

기존 `KafkaConsumerConfig.java`는 62줄이었다. Deserializer, offset, poll, group-id 등 12개 설정을 직접 잡아야 했다.

```java
// 변경 후: 30줄
@Configuration
public class RabbitMQConfig {

    public static final String NOTIFICATION_QUEUE = "notification-events";
    public static final String NOTIFICATION_EXCHANGE = "notification-exchange";
    public static final String ROUTING_KEY = "notification.#";

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE)
                .withArgument("x-dead-letter-exchange", "dlx-exchange")
                .build();
    }

    @Bean
    public TopicExchange notificationExchange() {
        return new TopicExchange(NOTIFICATION_EXCHANGE);
    }

    @Bean
    public Binding binding(Queue notificationQueue, TopicExchange notificationExchange) {
        return BindingBuilder.bind(notificationQueue)
                .to(notificationExchange).with(ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
```

`Jackson2JsonMessageConverter` 하나면 직렬화 끝. ACK도 Spring AMQP가 알아서 처리해준다.

### Consumer 리스너

```java
// 변경 전
@KafkaListener(topics = TOPIC, groupId = GROUP_ID, containerFactory = "kafkaListenerContainerFactory")
public void consume(ConsumerRecord<String, NotificationEvent> record) {
    NotificationEvent event = record.value();
    processEvent(event);  // ← 이 아래로는 브로커를 모름
}

// 변경 후
@RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE)
public void consume(NotificationEvent event) {
    processEvent(event);  // ← 그대로
}
```

바뀐 건 어노테이션과 파라미터 타입뿐이다. `processEvent()` 이하 비즈니스 로직은 한 줄도 안 바꿨다.

이게 가능했던 이유가 있다. 기존 코드에서 `consume()` 메서드가 `ConsumerRecord`에서 `event`를 꺼낸 다음, 그 아래로는 Kafka에 의존하는 코드가 하나도 없었기 때문이다. `processEvent()`, `processAlimtalk()`, `processEmail()`, `processPush()` -- 이 메서드들은 전부 `NotificationEvent`라는 도메인 객체만 받아서 처리한다. Kafka의 `ConsumerRecord`도, 오프셋도, 파티션도 모른다.

만약 비즈니스 로직 안에서 `record.partition()`이나 `record.offset()` 같은 Kafka 의존 코드가 섞여 있었다면, `processAlimtalk()`, `processEmail()`, `processPush()` 다 합치면 400줄이 넘는데 이걸 전부 수정해야 했을 거다. 사실상 리팩토링이 아니라 재작성이 되는 거다.

### Producer (4개 서비스 공통)

```java
// 변경 전
private final KafkaTemplate<String, NotificationEvent> kafkaTemplate;

private void publishEvent(String key, NotificationEvent event) {
    CompletableFuture<SendResult<String, NotificationEvent>> future =
        kafkaTemplate.send(TOPIC, key, event);
    future.whenComplete((result, ex) -> {
        if (ex != null) {
            log.error("발행 실패: {}", ex.getMessage());
        } else {
            log.debug("발행 성공: partition={}, offset={}",
                result.getRecordMetadata().partition(),
                result.getRecordMetadata().offset());
        }
    });
}

// 변경 후
private final RabbitTemplate rabbitTemplate;

private void publishEvent(String routingKey, NotificationEvent event) {
    try {
        rabbitTemplate.convertAndSend(
            RabbitMQConfig.NOTIFICATION_EXCHANGE,
            "notification." + routingKey, event);
        log.debug("발행 성공: eventType={}", event.getEventType());
    } catch (AmqpException ex) {
        log.error("발행 실패: {}", ex.getMessage());
    }
}
```

`CompletableFuture` 비동기 콜백이 사라지고, 파티션/오프셋 로깅도 필요 없어졌다.
branch-service, user-service, workpay-service, billing-service 4개 서비스에 동일하게 적용.

### Docker Compose

```yaml
# 변경 전
kafka:
  image: bitnami/kafka:4.0.0
  mem_limit: 1024m
  environment:
    KAFKA_KRAFT_CLUSTER_ID: epicode-cluster-01
    KAFKA_CFG_NODE_ID: 1
    KAFKA_CFG_PROCESS_ROLES: broker,controller
    KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
    KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
    KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
    KAFKA_CFG_CONTROLLER_LISTENER_NAMES: CONTROLLER
    KAFKA_CFG_LOG_DIRS: /bitnami/kafka/data
    KAFKA_HEAP_OPTS: "-Xmx512m -Xms512m"
    KAFKA_CFG_LOG_RETENTION_HOURS: "24"
    KAFKA_CFG_LOG_RETENTION_BYTES: "536870912"

# 변경 후
rabbitmq:
  image: rabbitmq:3-management
  mem_limit: 256m
  ports:
    - "5672:5672"
    - "15672:15672"    # Management UI
  environment:
    RABBITMQ_DEFAULT_USER: ${RABBITMQ_USERNAME:guest}
    RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD:guest}
```

설정량 자체가 확 줄었다. KRaft 관련 설정 7줄이 통째로 사라진다.

### 수정된 코드 목록

| 분류 | 파일/서비스 | 변경 |
| --- | --- | --- |
| 의존성 (x5) | notification, branch, user, workpay, billing | `spring-kafka` -> `spring-boot-starter-amqp` |
| 설정 (x5) | 모든 서비스 application.yml | Kafka 설정 -> RabbitMQ 설정 |
| 인프라 | `docker-compose.yml` | kafka -> rabbitmq 서비스, 환경변수 교체 |
| 인프라 | `action.yml` | `SPRING_KAFKA_BOOTSTRAP_SERVERS` -> `RABBITMQ_*` |
| Config | `KafkaConsumerConfig.java` 삭제, `RabbitMQConfig.java` x5 신규 | Exchange, Queue, Binding, MessageConverter 정의 |
| Producer (x4) | branch, user, workpay, billing | `KafkaTemplate` -> `RabbitTemplate`, 비동기 콜백 -> try-catch |
| Consumer (x1) | `NotificationConsumer.java` | `@KafkaListener` + `ConsumerRecord` -> `@RabbitListener` + POJO |

### 트러블 슈팅: LocalDateTime 직렬화 실패

카프카에선 됐는데 RabbitMQ에선 `Failed to convert Message content`가 발생했다.

Spring Boot 앱이 뜰 때 자동으로 만들어주는 ObjectMapper에는 `JavaTimeModule`이 이미 등록되어 있다. 카프카의 JsonSerializer/JsonDeserializer는 이 ObjectMapper를 그대로 사용해서 `LocalDateTime` 직렬화에 문제가 없었다.

RabbitMQ의 `Jackson2JsonMessageConverter()`는 다르게 동작한다.

```java
// 기본 생성자 내부 구현
public Jackson2JsonMessageConverter() {
    this.objectMapper = new ObjectMapper();  // ← Spring 것을 안 쓰고 직접 새로 만듦
    // JavaTimeModule? 안 넣음.
}
```

그래서 `NotificationEvent`의 `LocalDateTime timestamp`를 직렬화할 수 없었다.

```java
// Before: 모듈 없는 빈 ObjectMapper
new Jackson2JsonMessageConverter();

// After: JavaTimeModule 등록된 ObjectMapper를 명시적으로 전달
ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new JavaTimeModule());
new Jackson2JsonMessageConverter(mapper);
```

한 줄 요약: 카프카는 Spring의 ObjectMapper를 재사용했고, RabbitMQ의 MessageConverter는 자체 ObjectMapper를 새로 만들어서 JavaTimeModule이 빠진 거다.

### 어디서 품이 들었나

난이도는 높지 않았는데 반복 작업이 많았다.

1. 5개 서비스 동시 변경. build.gradle, application.yml, Producer 코드를 5개 서비스에 다 바꿔야 했다. 특히 `NotificationEvent` DTO가 서비스마다 별도 패키지에 복사본으로 있어서 빠뜨리지 않게 신경 써야 했다. 변경 파일 총 ~12개.

2. 비동기 -> 동기 전환. `CompletableFuture<SendResult>` 패턴이 4개 서비스에 있었다. RabbitMQ의 `convertAndSend()`는 기본 동기라서 콜백 로직을 다 걷어내고 try-catch로 바꾸는 작업을 반복했다.

3. 멱등성 처리. Kafka에서 `enable.idempotence=true`로 중복 발행을 막고 있었는데, RabbitMQ에는 이 기능이 없다. 고민했지만 알림이 2번 가도 치명적이진 않아서 일단 `publisher-confirm`으로 발행 보장에 집중하고 넘어갔다.

---

## 결과

### 리소스 비교

| 항목 | Kafka (실측) | RabbitMQ (예상) |
| --- | --- | --- |
| 컨테이너 mem_limit | 1,024 MB | 256 MB |
| 실질 메모리 | 736.7 MB | ~100~150 MB |
| 힙 최적화해도 최소 | ~400 MB | ~80 MB |
| 디스크 누적 쓰기 | 7.27 GB | 소비 즉시 해제 |
| 스레드/프로세스 | 106개 | ~20~30개 |

RabbitMQ도 Docker 컨테이너로 올리는 건 동일하다. `mem_limit`을 256MB로 잡으면 Kafka 대비 컨테이너 상한 기준 768MB가 줄어든다. 실질 사용량은 전환 후 `docker stats`를 찍어봐야 정확히 알 수 있지만, Erlang VM의 동적 메모리 모델과 공식 문서 기준으로 100~150MB 수준을 예상하고 있다.

### 배운 것

Docker로 띄워도 안의 런타임이 중요하다. 같은 Docker 컨테이너여도 안에서 JVM이 도는지 Erlang VM이 도는지에 따라 메모리 특성이 완전히 다르다. 이걸 모르고 "Xms 설정만 바꾸면 되겠지"라고 생각했던 게 좀 부끄럽다.

도구는 규모에 맞게 써야 한다. Kafka가 나쁜 도구가 아니다. 수백만 건을 처리해야 하는 곳에서는 최적의 선택이다. 근데 우리처럼 하루 수천 건 알림 보내는 서비스에 Kafka를 쓰는 건, 동네 편의점에 물류센터 시스템을 도입한 거랑 비슷하다.

관심사 분리가 진짜 빛을 봤다. 이번에 제일 크게 느낀 건 이거다. Consumer에서 `ConsumerRecord`를 벗기고 도메인 객체만 넘기는 한 줄의 차이가, 400줄짜리 비즈니스 로직을 건드리지 않아도 되는 결과를 만들었다. 앞으로 외부 인프라에 의존하는 코드를 짤 때, 진입점에서 의존성을 끊어놓는 습관을 더 의식적으로 해야겠다.

### 주의할 점

- 서비스가 커져서 일 수십만 건 이상 처리해야 하면 Kafka 재도입 고려해야 함
- RabbitMQ 예상 메모리(~150MB)는 소비자가 정상 가동 중일 때 기준. 소비자가 죽으면 큐에 쌓이면서 메모리가 올라감
- 멱등성이 중요해지면 애플리케이션 레벨에서 메시지 ID 기반 중복 체크 구현 필요

---

## 참고 자료

- [Confluent Kafka Deployment Guide](https://docs.confluent.io/platform/current/kafka/deployment.html) -- JVM 힙 권장 6GB, MetaspaceSize=96m, 프로덕션 64GB RAM 권장
- [Confluent System Requirements](https://docs.confluent.io/platform/current/installation/system-requirements.html) -- 브로커 RAM 64GB, CPU 24코어
- [RabbitMQ Memory Use Analysis](https://www.rabbitmq.com/docs/memory-use) -- 메모리 구성 요소별 분석, 메시지당 ~720B 메타데이터
- [RabbitMQ Production Checklist](https://www.rabbitmq.com/docs/production-checklist) -- 프로덕션 최소 4GiB RAM, 256MiB 여유 유지
