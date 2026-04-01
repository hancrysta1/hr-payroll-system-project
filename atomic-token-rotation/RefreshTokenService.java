package example.auth.service;

import example.auth.domain.RefreshToken;
import example.auth.repository.RefreshTokenRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${jwt.refresh-expiration-days:14}")
    private int refreshExpirationDays;

    @Transactional
    public String createRefreshToken(Long userId) {
        refreshTokenRepository.deleteByUserId(userId);

        String token = UUID.randomUUID().toString();
        RefreshToken refreshToken = RefreshToken.builder()
                .userId(userId)
                .token(token)
                .expiryDate(LocalDateTime.now().plusDays(refreshExpirationDays))
                .createdAt(LocalDateTime.now())
                .build();

        refreshTokenRepository.save(refreshToken);
        log.info("리프레시 토큰 생성: userId={}", userId);
        return token;
    }

    /**
     * 토큰 검증 + 회전을 하나의 트랜잭션에서 원자적으로 수행.
     * 비관적 락(SELECT FOR UPDATE)으로 동시 요청 시 레이스 컨디션 방지.
     *
     * @return 검증 실패 시 empty, 성공 시 {userId, newRefreshToken}
     */
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
        log.info("리프레시 토큰 갱신: userId={}", userId);

        return Optional.of(new RefreshTokenRotationResult(userId, newToken));
    }

    public record RefreshTokenRotationResult(Long userId, String newRefreshToken) {}
}
