package com.seatbooking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatbooking.config.AuthProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "unit-test-jwt-secret-of-at-least-32-bytes";
    private static final Duration TTL = Duration.ofHours(1);
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

    private final JwtService jwtService = serviceAt(NOW, SECRET, "seat-booking");

    @Test
    void issuedTokenVerifiesToSameIdentity() {
        IssuedToken token = jwtService.issue("alice", EnumSet.of(Role.USER, Role.ADMIN));

        AuthenticatedUser user = jwtService.verify(token.value());

        assertThat(user.userId()).isEqualTo("alice");
        assertThat(user.roles()).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
        assertThat(token.expiresAt()).isEqualTo(NOW.plus(TTL));
    }

    @Test
    void rejectsExpiredToken() {
        String token = jwtService.issue("alice", EnumSet.of(Role.USER)).value();
        JwtService later = serviceAt(NOW.plus(TTL).plusSeconds(1), SECRET, "seat-booking");

        assertThatThrownBy(() -> later.verify(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        JwtService attacker = serviceAt(NOW, "another-secret-that-is-also-32-bytes-long", "seat-booking");
        String forged = attacker.issue("alice", EnumSet.of(Role.ADMIN)).value();

        assertThatThrownBy(() -> jwtService.verify(forged)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsTokenFromDifferentIssuer() {
        String token = serviceAt(NOW, SECRET, "someone-else").issue("alice", EnumSet.of(Role.USER)).value();

        assertThatThrownBy(() -> jwtService.verify(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> jwtService.verify("not-a-jwt")).isInstanceOf(InvalidTokenException.class);
    }

    private static JwtService serviceAt(Instant now, String secret, String issuer) {
        AuthProperties properties = new AuthProperties(secret, TTL, issuer, true, "admin-secret");
        return new JwtService(properties, Clock.fixed(now, ZoneOffset.UTC));
    }
}
