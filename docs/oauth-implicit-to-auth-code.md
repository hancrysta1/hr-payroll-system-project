# OAuth 2.0 Implicit→Authorization Code Flow 보안 전환

## 문제

OAuth 2.0 초기에 SPA를 위해 설계된 Implicit Flow를 사용하고 있었다. 서버가 인증 완료 후 JWT 토큰을 URL fragment(#)에 직접 담아 프론트엔드로 리다이렉트하는 방식이었다.

```
사용자 → 카카오 인증 → 서버 콜백 → 302 redirect
→ https://manezy.epicode.co.kr/auth/callback#token=eyJhbGciOiJ...&userId=123&email=user@example.com
```

JWT 토큰 전체가 URL에 노출되고, 브라우저 히스토리에 남으며, Referer 헤더를 통해 외부로 유출될 수 있었다. OAuth 2.0 Security Best Practice(RFC 6819)에서도 Implicit Flow 사용 중단을 권고하고 있다.

## 분석

| 항목 | Implicit Flow (기존) | Authorization Code Flow (현재) |
|------|---------------------|-------------------------------|
| 토큰 전달 | URL fragment (#token=...) | JSON 응답 (POST /token) |
| URL 토큰 노출 | O | X |
| 브라우저 히스토리 노출 | O | X (코드만 남고, 1회용이라 의미 없음) |
| 중간 코드 | 없음 | 1회용 임시 코드 (1분 TTL) |
| 캐시 | 없음 | Caffeine (JVM 로컬 메모리) |

적용 대상은 웹 소셜 로그인 3개(OAuth 리다이렉트 방식을 사용하는 엔드포인트)에 한정된다.

| 엔드포인트 | 적용 |
|-----------|------|
| GET /api/web/auth/kakao/callback | O |
| GET /api/web/auth/naver/callback | O |
| GET /api/web/auth/google/callback | O |
| POST /api/web/auth/token (신규) | 임시 코드 → JWT 교환 |

앱 로그인(POST /api/auth/kakao/callback 등)은 JSON 응답 방식이라 해당 없다. 웹 일반 로그인(POST /api/web/auth/login)도 JSON 응답이라 해당 없다.

## 해결

Authorization Code Flow로 전환했다. 토큰 대신 1회용 임시 코드를 발급하고, 프론트엔드가 별도 API 호출로 토큰을 교환하는 방식이다.

```
사용자 → 카카오 인증 → 서버 콜백
→ JWT를 서버 캐시(Caffeine)에 저장, 임시 코드 발급
→ 302 redirect → https://manezy.epicode.co.kr/auth/callback?code=a1b2c3d4-uuid

프론트엔드:
→ ?code= 파싱
→ POST /api/web/auth/token { "code": "a1b2c3d4-uuid" }
→ 응답: { "token": "eyJ...", "refreshToken": "xxx", "userId": 123, ... }
```

임시 코드는 1회용(사용 즉시 삭제)이며 1분 TTL(만료 후 자동 삭제)을 가진다. 토큰은 HTTPS POST 응답(JSON body)으로만 전달된다.

OAuth state 파라미터에 프론트엔드 origin을 포함하여, 환경별(프로덕션/로컬) 리다이렉트를 동적으로 처리한다.

```
state = Base64({"origin":"http://localhost:3001"})
→ 콜백에서 디코딩 → 화이트리스트 검증 → 해당 origin으로 리다이렉트
```

허용 origin 목록은 다음과 같다.

- https://manezy.epicode.co.kr (프로덕션)
- http://localhost:3000 (로컬)
- http://localhost:3001 (로컬)

화이트리스트에 없는 origin은 https://manezy.epicode.co.kr 로 fallback하여 Open Redirect를 방지한다.

## 결과

- JWT가 URL에 노출되지 않음
- 임시 코드는 1회용이고 1분 TTL로 자동 만료됨
- 토큰은 HTTPS POST 응답으로만 전달되어 브라우저 히스토리, Referer 헤더 유출 경로가 차단됨
- OAuth 2.0 Security Best Practice(RFC 6819) 권고를 충족
