package com.seatbooking.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.seatbooking.config.AuthProperties;
import com.seatbooking.config.CacheProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.EnumSet;
import java.util.Set;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies HS256-signed JWTs. Stateless: any instance can verify any token.
 *
 * <p>A burst sends the same token many times, so a successfully verified token is remembered and its
 * signature and JSON are not re-parsed. Only tokens that passed full verification are cached, keyed by
 * the exact token string, and a cached token is still rejected the moment it expires. Tokens cannot be
 * revoked before expiry, so caching changes no security outcome.
 */
@Service
public class JwtService {

    private static final String ROLES_CLAIM = "roles";

    private final AuthProperties properties;
    private final Clock clock;
    private final SecretKey signingKey;
    private final JwtParser parser;
    private final Cache<String, VerifiedToken> verified;

    public JwtService(AuthProperties properties, Clock clock, CacheProperties cacheProperties) {
        this.properties = properties;
        this.clock = clock;
        this.signingKey = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.parser = Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.issuer())
                .clock(() -> Date.from(clock.instant()))
                .build();
        this.verified = Caffeine.newBuilder()
                .maximumSize(cacheProperties.verifiedTokenMaxEntries())
                .expireAfterWrite(properties.tokenTtl())
                .build();
    }

    public IssuedToken issue(String userId, Set<Role> roles) {
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(properties.tokenTtl());
        String token = Jwts.builder()
                .issuer(properties.issuer())
                .subject(userId)
                .claim(ROLES_CLAIM, roles.stream().map(Role::name).toList())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /** @throws InvalidTokenException if the signature, issuer, expiry or claims are invalid */
    public AuthenticatedUser verify(String token) {
        VerifiedToken cached = verified.getIfPresent(token);
        if (cached != null && clock.instant().isBefore(cached.expiresAt())) {
            return cached.user();
        }
        VerifiedToken fresh = parse(token);
        verified.put(token, fresh);
        return fresh.user();
    }

    private VerifiedToken parse(String token) {
        Claims claims;
        try {
            claims = parser.parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException("Invalid or expired token", e);
        }
        String userId = claims.getSubject();
        if (userId == null || userId.isBlank()) {
            throw new InvalidTokenException("Token has no subject");
        }
        if (claims.getExpiration() == null) {
            throw new InvalidTokenException("Token has no expiry");
        }
        return new VerifiedToken(
                new AuthenticatedUser(userId, parseRoles(claims.get(ROLES_CLAIM))),
                claims.getExpiration().toInstant());
    }

    private static Set<Role> parseRoles(Object claim) {
        if (!(claim instanceof Collection<?> values) || values.isEmpty()) {
            throw new InvalidTokenException("Token has no roles");
        }
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (Object value : values) {
            try {
                roles.add(Role.valueOf(String.valueOf(value)));
            } catch (IllegalArgumentException e) {
                throw new InvalidTokenException("Token has an unknown role", e);
            }
        }
        return roles;
    }

    private record VerifiedToken(AuthenticatedUser user, Instant expiresAt) {
    }
}
