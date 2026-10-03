package com.seatbooking.security;

import java.util.List;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;

/**
 * The caller's identity, derived only from a verified JWT. Controllers receive it via
 * {@code @AuthenticationPrincipal}; request bodies never carry a user id.
 */
public record AuthenticatedUser(String userId, Set<Role> roles) {

    public AuthenticatedUser {
        roles = Set.copyOf(roles);
    }

    public List<GrantedAuthority> authorities() {
        return roles.stream().map(Role::toAuthority).toList();
    }
}
