package com.seatbooking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatbooking.config.AuthProperties;
import com.seatbooking.config.CacheProperties;
import com.seatbooking.dto.auth.TokenRequest;
import com.seatbooking.dto.auth.TokenResponse;
import com.seatbooking.exception.ApiException;
import com.seatbooking.security.JwtService;
import com.seatbooking.security.Role;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AuthServiceTest {

    private static final String ADMIN_SECRET = "correct-admin-secret";
    private static final Duration TTL = Duration.ofMinutes(30);

    @Test
    void issuesUserTokenByDefault() {
        TokenResponse response = service(true, ADMIN_SECRET).issueToken(new TokenRequest("bob", null), null);

        assertThat(response.userId()).isEqualTo("bob");
        assertThat(response.roles()).containsExactly(Role.USER);
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(TTL.toSeconds());
        assertThat(response.accessToken()).isNotBlank();
    }

    @Test
    void issuesAdminTokenWithCorrectSecret() {
        TokenResponse response = service(true, ADMIN_SECRET)
                .issueToken(new TokenRequest("ops", Role.ADMIN), ADMIN_SECRET);

        assertThat(response.roles()).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
    }

    @Test
    void refusesAdminTokenWithWrongSecret() {
        AuthService service = service(true, ADMIN_SECRET);

        assertThatThrownBy(() -> service.issueToken(new TokenRequest("ops", Role.ADMIN), "guess"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void refusesAdminTokenWhenNoAdminSecretConfigured() {
        AuthService service = service(true, "");

        assertThatThrownBy(() -> service.issueToken(new TokenRequest("ops", Role.ADMIN), ""))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }

    @Test
    void hidesEndpointWhenDevTokensDisabled() {
        AuthService service = service(false, ADMIN_SECRET);

        assertThatThrownBy(() -> service.issueToken(new TokenRequest("bob", null), null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    private static AuthService service(boolean devTokenEnabled, String adminSecret) {
        AuthProperties properties = new AuthProperties(
                "unit-test-jwt-secret-of-at-least-32-bytes", TTL, "seat-booking", devTokenEnabled, adminSecret);
        return new AuthService(properties, new JwtService(properties, Clock.systemUTC(),
                new CacheProperties(Duration.ofSeconds(10), 100, 100, 100)));
    }
}
