package com.seatbooking.dto.auth;

import com.seatbooking.security.AuthenticatedUser;
import com.seatbooking.security.Role;
import java.util.Set;

public record CurrentUserResponse(String userId, Set<Role> roles) {

    public static CurrentUserResponse from(AuthenticatedUser user) {
        return new CurrentUserResponse(user.userId(), user.roles());
    }
}
