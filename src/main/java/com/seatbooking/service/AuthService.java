package com.seatbooking.service;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.seatbooking.config.AuthProperties;
import com.seatbooking.dto.auth.TokenRequest;
import com.seatbooking.dto.auth.TokenResponse;
import com.seatbooking.exception.ApiException;
import com.seatbooking.security.IssuedToken;
import com.seatbooking.security.JwtService;
import com.seatbooking.security.Role;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Development token issuer standing in for a real identity provider; disabled unless explicitly enabled. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String TOKEN_TYPE = "Bearer";

    private final AuthProperties properties;
    private final JwtService jwtService;

    public TokenResponse issueToken(TokenRequest request, String providedAdminSecret) {
        if (!properties.devTokenEnabled()) {
            throw ApiException.notFound("No endpoint at this path");
        }
        Role requestedRole = request.roleOrDefault();
        if (requestedRole == Role.ADMIN && !isValidAdminSecret(providedAdminSecret)) {
            log.warn("admin token refused", kv("requested_user_id", request.userId()));
            throw ApiException.forbidden("ADMIN role requires a valid X-Admin-Secret header");
        }

        Set<Role> roles = requestedRole == Role.ADMIN ? EnumSet.of(Role.USER, Role.ADMIN) : EnumSet.of(Role.USER);
        IssuedToken token = jwtService.issue(request.userId(), roles);
        log.info("token issued", kv("issued_to", request.userId()), kv("roles", roles));

        return new TokenResponse(
                token.value(), TOKEN_TYPE, properties.tokenTtl().toSeconds(), request.userId(), roles);
    }

    /** Constant-time comparison so response timing does not leak how much of the secret matched. */
    private boolean isValidAdminSecret(String provided) {
        String expected = properties.adminSecret();
        if (expected == null || expected.isBlank() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
