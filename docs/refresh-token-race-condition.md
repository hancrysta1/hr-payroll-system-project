# Refresh Token Rotation 레이스 컨디션 해결

## 문제

프로덕션 로그에서 특정 유저들이 토큰 갱신 직후 "리프레시 토큰이 유효하지 않거나 만료되었습니다" 에러를 반복적으로 받는 현상을 발견했다.

```
11:34:14.189 INFO  리프레시 토큰 갱신: userId=5528
11:34:14.235 DEBUG Writing [{error=리프레시 토큰이 유효하지 않거나 만료되었습니다.}]  ← 46ms 후
```

갱신 성공 로그 직후에 에러가 나는 패턴이었고, 같은 유저의 동시 요청이 원인이었다.

## 분석

Refresh Token Rotation은 토큰을 사용할 때마다 새 토큰을 발급하고 이전 것을 즉시 무효화하는 보안 메커니즘이다. 모바일 앱처럼 시크릿 키를 안전하게 저장할 수 없는 클라이언트(Public Client)에서 리프레시 토큰을 쓸 때는 토큰 회전이 국제 표준(RFC 9700)상 필수 요건이다.

문제는 검증과 회전이 별도 트랜잭션으로 분리되어 있었다는 점이다.

```java
// AuthController.java — 기존 구조
public ResponseEntity<?> refreshToken(...) {
    // 1. 검증 — @Transactional 없음
    Optional<RefreshToken> optionalToken = refreshTokenService.validateRefreshToken(tokenStr);

    // 2. 유저/지점 정보 조회 — 별도 트랜잭션
    User user = userRepository.findById(userId);
    List<BranchRoleProjection> branchData = userBranchRepository.findBranchIdsAndRolesByUserId(userId);

    // 3. 회전 — 별도 @Transactional
    String newRefreshToken = refreshTokenService.rotateRefreshToken(refreshToken);
}
```

`RefreshTokenService`의 `validateRefreshToken()`과 `rotateRefreshToken()`은 각각 독립된 메서드였고, 컨트롤러에서 조합해서 호출하는 구조였다. 생성은 생성, 검증은 검증, 회전은 회전이라는 단일 책임 원칙을 따른 설계였지만, 트랜잭션 원자성을 깨뜨리는 결과를 낳았다.

### 레이스 컨디션 시나리오

모바일 앱에서 백그라운드 복귀 시, 또는 탭 여러 개에서 동시에 토큰 갱신 요청이 발생할 수 있다.

```
요청 A                                    요청 B
├─ validate(T1) ✅ DB에 존재
├─ 유저 정보 조회 중...                    ├─ validate(T1) ✅ 아직 존재
├─ rotate → T1 삭제, T2 생성              ├─ 유저 정보 조회 중...
└─ return T2 ✅                          ├─ rotate(T1) → ❌ 이미 삭제됨
                                         └─ 401 에러
```

검증(1단계)과 회전(3단계) 사이에 다른 요청이 토큰을 삭제하면, 뒤따르는 요청은 실패한다. Spring의 트랜잭션은 프록시 기반이라, 서로 다른 서비스 메서드 호출은 각각 별도 트랜잭션으로 실행된다.

### 컨트롤러에 @Transactional을 거는 것으로는 해결되지 않는 이유

컨트롤러에 `@Transactional`을 걸어도, `refreshTokenService`의 각 메서드에 이미 `@Transactional`이 있으면 기본 전파 옵션(`REQUIRED`)에 의해 외부 트랜잭션에 참여하긴 한다. 하지만 이 경우 유저 조회, 지점 조회, JWT 생성까지 전부 하나의 트랜잭션에 묶이면서 DB 커넥션을 불필요하게 오래 점유하고, 비관적 락의 범위가 넓어진다. 컨트롤러는 트랜잭션 경계를 관리하는 적절한 레이어가 아니다.

## 해결

검증과 회전을 서비스 레이어의 단일 메서드로 합쳐서, 하나의 `@Transactional` 안에서 비관적 락과 함께 원자적으로 실행하도록 변경했다.

### 변경된 구조

```
기존:
Controller
├─ refreshTokenService.validateRefreshToken()   ← 트랜잭션 없음
├─ userRepository.findById()                    ← 별도 트랜잭션
├─ userBranchRepository.find...()               ← 별도 트랜잭션
├─ jwtUtil.createJwtToken()
└─ refreshTokenService.rotateRefreshToken()     ← 별도 @Transactional

변경:
Controller
├─ refreshTokenService.validateAndRotate(token)  ← 하나의 @Transactional (락 + 검증 + 회전)
│   └─ returns {userId, newRefreshToken}
├─ userRepository.findById(userId)               ← 읽기 전용, 트랜잭션 밖
├─ jwtUtil.createJwtToken()
└─ return response
```

### 핵심 변경 1 — 비관적 락 쿼리 추가

```java
// RefreshTokenRepository.java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT r FROM RefreshToken r WHERE r.token = :token")
Optional<RefreshToken> findByTokenForUpdate(String token);
```

`SELECT ... FOR UPDATE`로 토큰 행을 잠근다. 동시 요청이 같은 토큰을 조회하면, 먼저 락을 잡은 요청이 트랜잭션을 완료할 때까지 두 번째 요청은 대기한다. 첫 번째 요청이 토큰을 삭제하고 커밋하면, 두 번째 요청의 조회 결과는 empty가 되어 401을 반환한다.

### 핵심 변경 2 — 원자적 검증+회전 메서드

```java
// RefreshTokenService.java
@Transactional
public Optional<RefreshTokenRotationResult> validateAndRotate(String token) {
    Optional<RefreshToken> optionalToken = refreshTokenRepository.findByTokenForUpdate(token);

    if (optionalToken.isEmpty() || optionalToken.get().isExpired()) {
        return Optional.empty();
    }

    RefreshToken oldToken = optionalToken.get();
    Long userId = oldToken.getUserId();

    refreshTokenRepository.delete(oldToken);

    String newToken = UUID.randomUUID().toString();
    RefreshToken refreshToken = RefreshToken.builder()
            .userId(userId)
            .token(newToken)
            .expiryDate(LocalDateTime.now().plusDays(refreshExpirationDays))
            .createdAt(LocalDateTime.now())
            .build();

    refreshTokenRepository.save(refreshToken);
    return Optional.of(new RefreshTokenRotationResult(userId, newToken));
}

public record RefreshTokenRotationResult(Long userId, String newRefreshToken) {}
```

하나의 `@Transactional` 안에서 락 획득, 만료 확인, 삭제, 생성이 원자적으로 실행된다. 이 트랜잭션이 끝나기 전까지 다른 요청은 같은 토큰에 접근할 수 없다.

### 핵심 변경 3 — 컨트롤러 호출 단순화

```java
// AuthController.java
Optional<RefreshTokenService.RefreshTokenRotationResult> rotationResult =
        refreshTokenService.validateAndRotate(refreshTokenStr);
if (rotationResult.isEmpty()) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of("error", "리프레시 토큰이 유효하지 않거나 만료되었습니다."));
}

Long userId = rotationResult.get().userId();
String newRefreshToken = rotationResult.get().newRefreshToken();
```

컨트롤러는 원자적 결과만 받아서 유저 정보 조회와 응답 구성에 집중한다. 트랜잭션 경계는 서비스 레이어가 관리한다.

## 결과

단일 책임이 트랜잭션 원자성을 깨뜨리면 안 된다. `validateRefreshToken()`과 `rotateRefreshToken()`을 각각 만든 건 메서드를 잘게 쪼개라는 원칙을 따른 것이었다. 하지만 정합성이 깨지는 분리는 분리가 아니라 버그다. 검증과 회전은 논리적으로 하나의 원자적 연산이었고, 물리적으로도 하나의 트랜잭션이어야 했다.

이 문제는 단위 테스트로는 발견하기 어렵다. 동시 요청이라는 조건이 필요하기 때문이다. 프로덕션 로그의 타임스탬프 패턴(갱신 성공 후 수십 ms 만에 에러 발생)에서 레이스 컨디션을 추론한 것이 발견의 핵심이었다.

향후 개선 가능 사항.

| 항목 | 현재 | 개선 방향 |
|---|---|---|
| Reuse Detection | 미구현 | Token Family 개념 도입 — 이미 회전된 토큰이 재사용되면 전체 Family 폐기 (탈취 탐지) |
| 만료 토큰 정리 | `deleteExpiredTokens()` 존재하나 호출하는 스케줄러 없음 | `@Scheduled`로 주기적 정리 |
| Grace Period | 없음 (동시 요청 시 두 번째는 즉시 401) | Okta처럼 0~60초 유예 기간 설정 |

기술 스택. Spring Boot, JPA (`@Lock(PESSIMISTIC_WRITE)`), MySQL (row-level lock)
