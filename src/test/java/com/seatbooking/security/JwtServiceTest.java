package com.seatbooking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatbooking.config.AuthProperties;
import com.seatbooking.config.CacheProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "unit-test-jwt-secret-of-at-least-32-bytes";
    private static final Duration TTL = Duration.ofHours(1);
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

    private final JwtService jwtService = serviceAt(Clock.fixed(NOW, ZoneOffset.UTC), SECRET, "seat-booking");

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
        JwtService later = serviceAt(Clock.fixed(NOW.plus(TTL).plusSeconds(1), ZoneOffset.UTC), SECRET, "seat-booking");

        assertThatThrownBy(() -> later.verify(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void cachedTokenIsStillRejectedOnceItExpires() {
        MutableClock clock = new MutableClock(NOW);
        JwtService service = serviceAt(clock, SECRET, "seat-booking");
        String token = service.issue("alice", EnumSet.of(Role.USER)).value();
        assertThat(service.verify(token).userId()).isEqualTo("alice");
        assertThat(service.verify(token).userId()).isEqualTo("alice");

        clock.now = NOW.plus(TTL).plusSeconds(1);

        assertThatThrownBy(() -> service.verify(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void tamperedCopyOfACachedTokenIsRejected() {
        String token = jwtService.issue("alice", EnumSet.of(Role.USER)).value();
        jwtService.verify(token);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        assertThatThrownBy(() -> jwtService.verify(tampered)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        JwtService attacker = serviceAt(Clock.fixed(NOW, ZoneOffset.UTC),
                "another-secret-that-is-also-32-bytes-long", "seat-booking");
        String forged = attacker.issue("alice", EnumSet.of(Role.ADMIN)).value();

        assertThatThrownBy(() -> jwtService.verify(forged)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsTokenFromDifferentIssuer() {
        String token = serviceAt(Clock.fixed(NOW, ZoneOffset.UTC), SECRET, "someone-else")
                .issue("alice", EnumSet.of(Role.USER)).value();

        assertThatThrownBy(() -> jwtService.verify(token)).isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> jwtService.verify("not-a-jwt")).isInstanceOf(InvalidTokenException.class);
    }

    private static JwtService serviceAt(Clock clock, String secret, String issuer) {
        AuthProperties properties = new AuthProperties(secret, TTL, issuer, true, "admin-secret");
        return new JwtService(properties, clock, new CacheProperties(Duration.ofSeconds(10), 100, 100, 100));
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
