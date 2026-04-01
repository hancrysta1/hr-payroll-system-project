# OpenAI API 비용 46% 절감, 응답시간 60초→1.8초

## 문제

알바 스케줄 관리 서비스의 AI 파싱 기능을 개발하며 API 비용과 응답 속도 문제를 발견했다. 초기에는 Assistant API를 사용했으나, 요청 특성(요청 간 맥락 없음, 매번 독립적인 스케줄 변환)을 분석한 결과 Thread 관리와 Run 상태 폴링이 불필요한 복잡성만 추가한다고 판단했다.

사용 기술은 Java(JDK-21), Spring Boot, OpenAI Chat Completions API(gpt-4o), Caffeine Cache, WebClient이다. 기여도 100%.

### Assistant API의 구조적 문제

스케줄 파싱 요청은 다음과 같은 특성을 가진다.

```
요청 A: "김민진 9시-13시 오픈" → JSON 변환
요청 B: "박수연 14시-18시 마감" → JSON 변환
요청 C: [이미지: 주간 스케줄표]  → JSON 변환
```

- 형식(시스템 프롬프트)은 항상 동일하다. "스케줄 데이터를 받아서 정해진 JSON 스키마로 변환해라."
- 내용(유저 입력)은 매번 다르다. 직원 이름, 시간, 날짜가 전부 다르다.
- 대화 연속성은 없다. 각 요청은 독립적이며, 이전 파싱 결과를 참조할 필요가 없다.

Assistant API의 핵심 장점은 Thread를 통한 대화 맥락 유지인데, 우리 스케줄 파싱에서는 다음과 같은 이유로 가치가 없었다.

- 요청 간 맥락이 없다. "김민진 9시-13시"를 파싱한 결과가 다음 "박수연 14시-18시" 파싱에 영향을 주지 않는다.
- Thread에 이전 메시지를 쌓아둘 이유가 없다. 매번 새로운 스케줄 데이터를 독립적으로 변환할 뿐이다.
- Run 상태 폴링(queued → in_progress → completed)이 불필요한 복잡성을 추가한다.

| 항목 | Chat Completions | Assistant API |
| --- | --- | --- |
| 요청 구조 | 1번의 HTTP 요청으로 완료 | Thread 생성 → Message 추가 → Run → 조회 (4단계) |
| 상태 관리 | Stateless (서버 저장 없음) | thread_id 저장/관리 필요 |
| Thread 저장 비용 | $0 | $0.03/GB/일 |
| 에러 핸들링 | 단일 요청 타임아웃만 관리 | Run 상태 폴링 필요 |
| 스케일링 | Stateless이므로 수평 확장 용이 | Thread 상태 의존 |

```
[Chat Completions - 1단계]
POST /chat/completions (시스템 프롬프트 + 스케줄 데이터) → JSON 응답. 끝.

[Assistant API - 4단계]
Thread 생성 → Message 추가 → Run 실행 (폴링 대기) → 결과 조회
```

### 시스템 프롬프트 반복 전송 비용

Chat Completions API를 선택하면서 하나의 단점이 남았다. Assistant API는 시스템 프롬프트를 Assistant에 1회 저장하므로 매 요청에서 프롬프트 토큰이 과금되지 않지만, Chat Completions는 매 요청마다 동일한 시스템 프롬프트를 전체 재전송해야 한다.

현재 시스템 프롬프트 크기는 `ScheduleParserPrompts.java`의 `SYSTEM_PROMPT` 198줄(18~215행), 한글 + 영어 + JSON 예시 포함, 추정 약 2,500~3,000 tokens이다.

| 방식 | Input 토큰 | 비용 |
| --- | --- | --- |
| Chat Completions | (3,000 + 200) x 1,000건 = 3,200K tokens | $8.00 |
| Assistant API | 200 x 1,000건 = 200K tokens + Thread 저장 | $0.77 |

> 토큰 단가: Input $2.50 / 1M tokens ($0.0025 / 1K)
> 출처: [OpenAI Pricing](https://openai.com/api/pricing/)

### 504 Gateway Timeout

대용량 엑셀 데이터 파싱 시 504 Gateway Timeout 문제도 발생했다. 기존에는 @Async와 CompletableFuture를 사용했지만 Spring MVC가 Future 완료를 대기하여 실질적으로 동기 처리와 동일했다. S3에 엑셀 파일을 업로드하여 백엔드에서 파싱하는 방식도 검토했으나, 파일 크기가 수십~수백KB로 경미하고 504의 원인이 바디 크기가 아닌 GPT 대기 시간이었기 때문에 S3 경유로는 근본 해결이 불가능했다.

---

## 분석

### API 방식 전환 근거

응답속도 97% 개선의 핵심 원인은 아키텍처 전환에 있었다. Assistant API는 매 요청마다 Thread 생성 → Message 추가 → Run 실행 → 상태 폴링 → 결과 조회의 4단계를 거친다. 특히 Run 실행 후 `queued → in_progress → completed`를 폴링하며 대기하는 구간이 지배적이었다. Chat Completions는 이 전체를 1번의 HTTP POST로 대체한다. 60초 → ~3초로의 응답시간 단축은 대부분 이 아키텍처 전환에서 비롯되었다.

Assistant API는 Thread에 대화 상태가 저장되므로 서버가 2대면 "이 Thread는 어느 서버가 처리 중인가?" 같은 상태 관리가 필요하다. Chat Completions는 매 요청이 독립적이라 아무 서버가 아무 요청을 처리할 수 있어 로드밸런서 뒤에 서버를 그냥 늘리면 된다.

### 비용 최적화 전략 분석

구조적 이점(Stateless, 단순 아키텍처)을 유지하면서 시스템 프롬프트 반복 전송 비용을 줄일 방법이 필요했다. 세 가지 전략을 도출했다.

1. OpenAI Prompt Caching. 고정 시스템 프롬프트를 `static final` 상수로 분리하고, 변동 유저 입력을 프롬프트 뒤에 배치하여 캐시 히트율을 극대화한다.
2. Caffeine 로컬 캐시. 동일 텍스트 + 같은 날짜 요청은 API 호출 자체를 스킵하여 비용 100% 절감한다.
3. 이미지 `detail: low`. 이미지 요청 시 고정 85토큰만 과금하여 이미지 처리 비용을 최소화한다.

| 최적화 | 응답속도 기여 | 비용 기여 | 근거 |
| --- | --- | --- | --- |
| Assistant → Chat Completions 전환 | 가장 큼 (60초 → ~3초) | 간접적 | 4단계 폴링 → 1회 HTTP로 단축 |
| OpenAI Prompt Caching | 추가 레이턴시 감소 | 시스템 프롬프트 50% 할인 | [공식 문서](https://openai.com/index/api-prompt-caching/) |
| Caffeine 로컬 캐시 | 캐시 히트 시 0ms (API 호출 스킵) | 히트 시 100% 절감 | 같은 텍스트+같은 날짜 = API 미호출 |
| image-detail: low | - | 이미지 토큰 최소화 | 고정 85토큰 vs high모드 수백~수천 토큰 |

---

## 해결

### 1단계. OpenAI Prompt Caching

gpt-4o 모델에서 1024토큰 이상의 프롬프트는 별도 설정 없이 자동으로 캐싱된다. 현재 사용 중인 모델이 `gpt-4o`(`application.yml:81`)이고, 시스템 프롬프트가 ~3,000토큰으로 1024토큰 이상이므로 캐싱 적용 대상이다.

> "Prompt Caching works automatically on all your API requests (no code changes required)"
> -- [OpenAI Prompt Caching Guide](https://developers.openai.com/api/docs/guides/prompt-caching/)

프롬프트의 앞부분(prefix)을 해시하여 이전 요청과 비교한다. prefix가 동일하면 캐시 히트, 다르면 캐시 미스.

| 항목 | 캐시 미스 | 캐시 히트 |
| --- | --- | --- |
| 시스템 프롬프트 토큰 비용 | 100% | 50% 할인 |
| 응답 레이턴시 | 기본 | 최대 80% 감소 |

시스템 프롬프트를 고정 상수로 분리 -- `ScheduleParserPrompts.java:18-215`

```java
public static final String SYSTEM_PROMPT = """
        역할
        - 너는 알바 스케줄 관리 시스템을 위한 전담 어시스턴트다.
        - 사용자가 보내는 텍스트, 표, 이미지에서 근무 스케줄을 읽어서 JSON으로 추출한다.
        ...
        (198줄의 고정 지시사항 — 응답 스키마, 날짜 처리 규칙, 직원 이름 규칙 등)
        (약 2,500~3,000 tokens, 1024토큰 캐싱 임계치 초과)
        """;
```

`static final` 상수이기 때문에 어떤 요청이 들어와도 이 값은 변하지 않는다. 공식 문서에서 "prefix의 해시로 라우팅한다"고 명시했으므로, 프롬프트 내용이 동적으로 변하면 해시가 달라져 캐시 히트가 발생하지 않는다. 따라서 프롬프트 내용을 동적으로 조합하지 않고 상수로 고정하는 것이 캐시 히트의 전제 조건이다.

유저 프롬프트는 변동 영역으로 분리 -- `ScheduleParserServiceImpl.java:138-142`

```java
private String buildUserPrompt(ScheduleParseRequest request) {
    String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    String content = request.getContent() != null ? request.getContent() : "이미지에서 스케줄을 추출해주세요.";
    return String.format(ScheduleParserPrompts.USER_PROMPT_TEMPLATE, content, today);
}
```

```java
public static final String USER_PROMPT_TEMPLATE = """
        다음 스케줄 데이터를 파싱해주세요:

        %s          ← 매번 다른 스케줄 데이터

        오늘 날짜: %s   ← 매번 다른 날짜
        """;
```

메시지 배열에서 고정을 앞에, 변동을 뒤에 배치 -- `ScheduleParserServiceImpl.java:157-166`

```java
List<OpenAIRequest.Message> messages = Arrays.asList(
    // messages[0]: 고정 prefix → 캐시 대상
    OpenAIRequest.Message.builder()
        .role("system")
        .content(ScheduleParserPrompts.SYSTEM_PROMPT)  // ← 항상 동일한 상수
        .build(),

    // messages[1]: 변동 부분 → 캐시 대상 아님
    OpenAIRequest.Message.builder()
        .role("user")
        .content(userPrompt)  // ← 매번 다른 스케줄 데이터
        .build()
);
```

이미지 요청도 동일한 구조다 -- `ScheduleParserServiceImpl.java:194-203`

```java
List<OpenAIRequest.Message> messages = Arrays.asList(
    // messages[0]: 고정 prefix → 캐시 대상 (텍스트 요청과 동일한 상수)
    OpenAIRequest.Message.builder()
        .role("system")
        .content(ScheduleParserPrompts.SYSTEM_PROMPT)  // ← 항상 동일한 상수
        .build(),

    // messages[1]: 변동 부분 (텍스트 + 이미지 base64)
    OpenAIRequest.Message.builder()
        .role("user")
        .content(userContent)  // ← 매번 다른 이미지/텍스트
        .build()
);
```

텍스트 요청과 이미지 요청 모두 `messages[0]`이 동일한 시스템 프롬프트 상수이므로, 두 종류의 요청이 같은 캐시를 공유할 수 있다.

Structured Outputs로 응답 안정성도 확보했다 -- `ScheduleParserServiceImpl.java:171`

```java
.responseFormat(OpenAIJsonSchema.RESPONSE_FORMAT)  // { "type": "json_object" }
```

JSON Schema를 정의하고 시스템 프롬프트에서 스키마를 명시하여 OpenAI가 항상 유효한 JSON만 반환하도록 강제한다. JSON 파싱 실패로 인한 재요청이 사라져 불필요한 API 호출 비용이 제거되었고, `ObjectMapper.readValue()`로 바로 역직렬화가 가능해 후처리 로직이 단순해졌다.

캐시가 깨지는 안티패턴은 다음과 같다.

```java
// 안티패턴 1: 변동 내용을 시스템 프롬프트에 포함
// 날짜가 매일 바뀌므로 prefix 해시가 매일 달라짐
OpenAIRequest.Message.builder()
    .role("system")
    .content(SYSTEM_PROMPT + "\n오늘 날짜: " + today)  // ← prefix가 매일 바뀜
    .build()

// 안티패턴 2: 유저 메시지를 앞에 배치
// 변동 내용이 prefix가 되어 캐시 히트 불가
messages = Arrays.asList(
    userMessage,    // ← 변동 내용이 prefix가 됨
    systemMessage   // ← 고정 내용이 뒤로 밀림
);
```

### 2단계. Caffeine 로컬 캐시

OpenAI Prompt Caching은 시스템 프롬프트 토큰을 50% 할인해주지만, 여전히 API 호출 자체는 발생한다. 동일한 스케줄 데이터를 같은 날에 다시 파싱하는 경우, API 호출 자체가 불필요하다.

캐시 설정 -- `CacheConfig.java:36`

```java
buildCache("scheduleParseCache", 1, TimeUnit.HOURS, 1000)
```

- TTL은 1시간 (같은 날 동일 요청에 대해 유효)
- 최대 1,000건 저장
- Caffeine 기반, `recordStats()` 활성화로 히트율 모니터링 가능

캐시 키 설계 -- `ScheduleParserServiceImpl.java:68`

```java
cacheKey = request.getContent().hashCode() + ":" + LocalDate.now();
```

- `content.hashCode()`는 스케줄 텍스트의 해시값
- `LocalDate.now()`는 오늘 날짜 (날짜가 바뀌면 캐시 미스 → 프롬프트에 오늘 날짜가 포함되므로 결과가 달라질 수 있기 때문)

캐시 체크 흐름 -- `ScheduleParserServiceImpl.java:66-77`

```java
// 텍스트 요청인 경우 캐시 확인 (이미지는 캐싱 제외)
if (!isImage && request.getContent() != null) {
    cacheKey = request.getContent().hashCode() + ":" + LocalDate.now();
    Cache cache = cacheManager.getCache("scheduleParseCache");
    if (cache != null) {
        Cache.ValueWrapper cached = cache.get(cacheKey);
        if (cached != null) {
            log.info("스케줄 파싱 캐시 히트 - cacheKey: {}", cacheKey);
            return CompletableFuture.completedFuture((ChatGPTScheduleResponse) cached.get());
            // ↑ API 호출 없이 즉시 반환. 비용 $0, 응답시간 ~0ms.
        }
    }
}
```

이미지는 캐싱에서 제외했다. 이미지 base64는 용량이 크고 hashCode 충돌 가능성이 있으며, 동일 이미지를 재전송하는 유즈케이스가 거의 없기 때문이다.

| 시나리오 | Prompt Caching만 | Prompt Caching + 로컬 캐시 |
| --- | --- | --- |
| 최초 요청 | 전체 과금 (캐시 미스) | 전체 과금 (캐시 미스) |
| 같은 텍스트 재요청 (같은 날) | 시스템 프롬프트 50% 할인 | API 호출 안 함 -- 비용 $0 |
| 다른 텍스트 요청 | 시스템 프롬프트 50% 할인 | 시스템 프롬프트 50% 할인 |

토큰 48% 감소(318,219 → 165,253)에는 로컬 캐시 히트가 기여했다. 61건 중 일부가 캐시 히트되어 OpenAI API 자체를 호출하지 않았으므로, 해당 요청의 토큰이 0으로 집계된 것이다. Prompt Caching만으로는 토큰 수가 줄어들지 않는다(비용만 할인). 토큰 수 자체가 줄어든 것은 로컬 캐시의 효과다.

### 3단계. image-detail: low

설정 -- `application.yml:86`

```yaml
image-detail: low  # low: 비용 절감(65토큰), high: 고품질, auto: 자동
```

적용 코드 -- `ScheduleParserServiceImpl.java:191`

```java
OpenAIRequest.ImageContent.of(imageDataUrl, imageDetail)  // imageDetail = "low"
```

> "low resolution: the model receives a low-res 512px x 512px version of the image, and the image is represented with a budget of 85 tokens."
> -- [OpenAI Vision Guide](https://platform.openai.com/docs/guides/vision)

| 설정 | 토큰 과금 | 예시 (1024x768 이미지) |
| --- | --- | --- |
| `low` | 고정 85토큰 | 85 tokens |
| `high` | 타일 수에 비례 | ~765 tokens (4타일 x 170 + 85) |
| `auto` | OpenAI가 판단 | 예측 불가 |

스케줄표 이미지는 텍스트 위주의 표 형태이므로, `low` 해상도로도 충분히 인식 가능하다. `high` 대비 이미지당 약 80~90% 토큰 절감 효과가 있다.

### 보완. 캐시 품질 검증

Caffeine 로컬 캐시 적용 후, GPT가 잘못 파싱한 결과가 1시간 동안 캐시에 남아 동일 요청의 재시도 기회를 차단하는 문제가 발생했다.

실제 발생 사례 (2026.03.10)

- 프론트에서 Excel을 파싱하여 3월 1일~31일, 3명의 직원(한수정, 김사또, 이사라), 90개 행의 구조화된 데이터를 서버로 전송
- GPT 응답에서 workerName이 전부 `"Unknown"`, 3/1~3/11까지 30개 스케줄만 생성
- 이 잘못된 결과가 캐시에 저장되어, 같은 Excel로 재요청해도 1시간 동안 동일한 오류 결과가 반환

```java
// 기존: 파싱 성공 여부와 무관하게 무조건 캐시 저장
if (cacheKey != null) {
    cache.put(cacheKey, response);  // ← GPT가 잘못 파싱해도 저장됨
}
```

서버 로그를 분석한 결과, GPT의 응답 품질 저하 패턴을 확인했다.

이름 누락. 입력에 workerName이 명시되어 있음에도 전부 "Unknown" 반환.

```
// 입력 (프론트에서 Excel 파싱 후 전송한 구조화된 데이터)
1. { "date": "3월 1일", "workerName": "한수정", "shiftType": "오픈 (10~19)" }
2. { "date": "3월 1일", "workerName": "김사또", "shiftType": "마감 (14~23)" }
...

// GPT 출력 — 이름을 전부 무시
{ "workerName": "Unknown", "date": "2026-03-01", "startTime": "10:00", "endTime": "19:00" }
```

데이터 누락. 90개 입력 중 30개만 출력 (OFF 제외 시 60개 → 30개, 50%).

```
OpenAI API request completed. Total tokens: 6816  ← maxTokens 16,000 대비 여유 있음
Schedule parsing completed. Parsed schedules count: 30  ← 60개여야 정상
```

토큰 한도(16,000)에 한참 못 미치는 6,816토큰만 사용하고 멈춘 것으로, 토큰 부족이 아닌 GPT 모델의 게으른 요약(lazy generation) 현상이었다. temperature 1.0에서 입력을 1:1 매핑하지 않고 "매일 open/middle/close 3개" 패턴으로 단순화해버린 것이다.

캐시 저장 전에 응답 품질을 검증하여, 명백한 파싱 실패는 캐시하지 않는 전략을 설계했다.

| # | 검증 조건 | 잡는 실패 패턴 | 이번 사례 적용 |
| --- | --- | --- | --- |
| 1 | 스케줄 0개 또는 confidence < 0.5 | 완전 파싱 실패, GPT 자체 불확신 | -- |
| 2 | 전체 workerName = "Unknown" | 이름 정보 전부 누락 | 해당 (30개 전부 Unknown) |
| 3 | 출력 수 / 입력 행 수(OFF 제외) < 70% | 데이터 대량 누락 | 해당 (30/60 = 50%) |

캐시 저장 조건 추가 -- `ScheduleParserServiceImpl.java`

```java
// 변경 전: 무조건 캐시 저장
if (cacheKey != null) {
    cache.put(cacheKey, response);
}

// 변경 후: 품질 검증 통과 시에만 캐시 저장
if (cacheKey != null && isCacheWorthy(response, request.getContent())) {
    cache.put(cacheKey, response);
} else if (cacheKey != null) {
    log.warn("스케줄 파싱 결과 캐시 미저장 - 품질 검증 미통과");
}
```

품질 검증 메서드

```java
private boolean isCacheWorthy(ChatGPTScheduleResponse response, String content) {
    // 1. 스케줄 0개 → 캐시 안 함
    if (response.getSchedules() == null || response.getSchedules().isEmpty()) {
        return false;
    }
    // 2. confidence < 0.5 → 캐시 안 함
    if (response.getConfidence() != null && response.getConfidence() < 0.5) {
        return false;
    }
    // 3. workerName 전부 "Unknown" → 캐시 안 함
    boolean allUnknown = response.getSchedules().stream()
            .allMatch(s -> "Unknown".equalsIgnoreCase(s.getWorkerName()));
    if (allUnknown && response.getSchedules().size() > 1) {
        return false;
    }
    // 4. 입력 행 수 대비 출력 수 70% 미만 → 캐시 안 함
    if (content != null) {
        int expectedCount = countInputRows(content);
        if (expectedCount > 0) {
            int actualCount = response.getSchedules().size();
            double ratio = (double) actualCount / expectedCount;
            if (ratio < 0.7) {
                return false;
            }
        }
    }
    return true;
}
```

입력 행 수 추정 -- 구조화된 데이터(Excel 파싱 결과)에서만 동작

```java
private int countInputRows(String content) {
    // "1. {", "2. {" 등 번호 매겨진 JSON 객체 수 카운트
    int total = 0;
    Matcher matcher = Pattern.compile("^\\d+\\.\\s*\\{", Pattern.MULTILINE).matcher(content);
    while (matcher.find()) total++;

    // "shiftType": "OFF" 항목 제외 (OFF는 근무가 아니므로 출력에 포함되지 않음)
    int offCount = 0;
    Matcher offMatcher = Pattern.compile("\"shiftType\"\\s*:\\s*\"OFF\"", Pattern.CASE_INSENSITIVE).matcher(content);
    while (offMatcher.find()) offCount++;

    return total - offCount;  // 비구조화 입력은 0 반환 → 행 수 비교 건너뜀
}
```

이번 사례에 적용하면 다음과 같다.

| 검증 | 값 | 판정 |
| --- | --- | --- |
| 입력 총 행 | 90개 | |
| OFF 항목 | 30개 (매일 1명 OFF) | |
| 예상 근무 행 (expected) | 60개 | |
| GPT 출력 (actual) | 30개 | |
| 비율 | 30/60 = 50% | < 70% → 캐시 안 됨 |
| workerName | 전부 "Unknown" | 캐시 안 됨 |

2개 조건 모두에 걸려 캐시되지 않으므로, 사용자가 재요청하면 GPT를 다시 호출하여 정상 결과를 받을 기회를 갖는다.

함께 적용한 근본 원인 대응

| 변경 | 내용 | 목적 |
| --- | --- | --- |
| temperature 1.0 → 0.2 | `application.yml` | GPT의 게으른 요약 방지, 입력 데이터 충실 매핑 유도 |
| 프롬프트 v1.3.3 → v1.3.4 | `ScheduleParserPrompts.java` | 구조화된 입력의 1:1 매핑 강제, "Unknown" 금지, OFF 제외 규칙 명시 |

캐시 품질 검증은 GPT 응답이 여전히 잘못될 경우의 안전망이며, temperature 조정과 프롬프트 강화가 근본 원인 대응이다.

### 504 해소. Fire-and-Forget 태스크 패턴

POST 요청 시 즉시 taskId를 반환하고, 백그라운드에서 GPT 호출 후 인메모리 TaskStore에 결과를 저장하는 Fire-and-Forget 방식으로 전환하여 프론트 응답시간을 30~60초에서 100ms 이내로 단축했다.

### 추가 개선 포인트. cached_tokens 모니터링

현재 `OpenAIResponse.Usage` 클래스에는 `promptTokens`, `completionTokens`, `totalTokens`만 존재한다.

```java
// 현재 Usage 구조
public static class Usage {
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    // cached_tokens 필드 없음
}
```

OpenAI API는 Prompt Caching 적용 시 응답에 `prompt_tokens_details.cached_tokens`를 포함하여 캐시 히트된 토큰 수를 알려준다. 현재는 이 필드를 파싱하지 않으므로, Prompt Caching이 실제로 얼마나 히트되고 있는지 서버 로그로 확인할 수 없다. 다음과 같이 확장하면 캐시 히트율을 모니터링할 수 있다.

```java
// 개선안: cached_tokens 추적
public static class Usage {
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

    @JsonProperty("prompt_tokens_details")
    private PromptTokensDetails promptTokensDetails;

    @Data
    public static class PromptTokensDetails {
        @JsonProperty("cached_tokens")
        private Integer cachedTokens;
    }
}
```

### OpenAI Prompt Caching 유지 시간

> "Cached prefixes generally remain active for 5 to 10 minutes of inactivity, up to a maximum of one hour."
> -- [OpenAI Prompt Caching Guide](https://developers.openai.com/api/docs/guides/prompt-caching/)

기본적으로 5~10분 비활동 시 캐시가 만료된다. 요청 간격이 길다면 `prompt_cache_retention: "24h"` 파라미터를 추가하여 최대 24시간까지 연장할 수 있다.

---

## 결과

동일 요청량 61건 기준, OpenAI 대시보드 데이터.

| 지표 | 적용 전 (25.12.06) | 적용 후 (26.02.27) | 개선율 |
| --- | --- | --- | --- |
| 토큰 사용량 | 318,219 tokens | 165,253 tokens | 48% 감소 |
| API 비용 | $0.72 | $0.39 | 46% 절감 |
| 평균 응답시간 | 60초 | 1.82초 | 97% 단축 |

적용 전 (25.12.06)

![image.png](attachment:021c63ef-785f-44ea-b068-fbfc7a0ba1e2:image.png)

- 61 requests, 318,219 tokens, 0.72 달러, 응답 평균 시간 1분(60초 내외)

적용 이후 (26.02.27)

![image.png](attachment:9b3e61b6-3812-4e37-b1f7-8d808934864e:image.png)

- 61 requests, 165,253 tokens, 0.39 달러, 응답 평균 시간 1.82초

### 성과 분해

- 응답속도 97% 개선의 대부분은 Assistant API → Chat Completions 전환 (4단계 폴링 → 1회 HTTP)에서 왔다.
- 비용 46% 절감은 Prompt Caching(시스템 프롬프트 50% 할인) + 로컬 캐시(API 호출 스킵) + image-detail:low(이미지 토큰 89% 절감)의 복합 효과다.
- 토큰 48% 감소는 로컬 캐시 히트로 API 미호출 건이 발생 + image-detail:low 효과다.
- 504 해소는 동기 CompletableFuture 대기를 Fire-and-Forget 태스크 패턴으로 전환한 결과이며, 프론트 응답시간이 30~60초에서 100ms 미만으로 단축되었다.
- 토큰 48% 감소 vs 비용 46% 절감이 거의 비례하는 이유는, 토큰 감소의 주요 원인이 로컬 캐시 히트(API 미호출 → 토큰 0)이고, Prompt Caching은 토큰 수는 유지하되 단가를 할인하기 때문이다. 두 효과가 합쳐져 비용 절감률(46%)이 토큰 감소률(48%)과 비슷하게 나온 것이다.

### 이론적 비용 비교 (월 1,000건 기준)

| 방식 | 월 비용 (1,000건) | 비고 |
| --- | --- | --- |
| Chat Completions (캐시 없음) | $8.00 | 매번 전체 프롬프트 과금 |
| Chat Completions (3단계 최적화 적용) | ~$4.25 이하 | Prompt Caching 50% + 로컬 캐시 히트분 추가 절감 |
| Assistant API | $0.77 | 시스템 프롬프트 1회 저장 |

Assistant API 대비 비용은 높지만, Thread 관리/상태 폴링 등 불필요한 복잡성 없이 Stateless 구조의 운영 이점을 유지하면서 캐싱 없는 경우 대비 약 46% 비용 절감을 달성했다.

### 전체 최적화 아키텍처

```
클라이언트 요청
    │
    ▼
[1단계] Caffeine 로컬 캐시 확인
    │   key = content.hashCode() + ":" + today
    │   TTL: 1시간, 최대 1000건
    │   이미지: 캐싱 제외
    │
    ├── HIT → 즉시 반환 (비용 $0, ~0ms)
    │
    └── MISS ↓
        │
        ▼
[2단계] OpenAI Chat Completions API 호출
    │   messages[0]: SYSTEM_PROMPT (static final, 198줄, ~3000토큰)  ← 캐시 대상
    │   messages[1]: USER_PROMPT (변동)                              ← 매번 새로 과금
    │   response_format: json_object (Structured Outputs)
    │   image-detail: low (이미지 시 85토큰 고정)
    │
    ├── Prompt Cache HIT → 시스템 프롬프트 50% 할인
    └── Prompt Cache MISS → 전액 과금
        │
        ▼
[3단계] 응답 파싱 + 로컬 캐시 저장
    │   ObjectMapper.readValue() → ChatGPTScheduleResponse
    │   텍스트 요청: 캐시에 저장 (다음 동일 요청 시 API 스킵)
    │
    ▼
클라이언트 응답
```

---

## Sources

- [OpenAI Prompt Caching Guide](https://developers.openai.com/api/docs/guides/prompt-caching/)
- [Prompt Caching in the API | OpenAI](https://openai.com/index/api-prompt-caching/)
- [OpenAI API Pricing](https://openai.com/api/pricing/)
- [OpenAI Vision Guide](https://platform.openai.com/docs/guides/vision)
