package com.seatbooking.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public enum Role {
    USER,
    ADMIN;

    private static final String AUTHORITY_PREFIX = "ROLE_";

    public GrantedAuthority toAuthority() {
        return new SimpleGrantedAuthority(AUTHORITY_PREFIX + name());
    }
}
