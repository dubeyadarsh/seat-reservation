package com.seatbooking.dto.auth;

import com.seatbooking.security.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * @param userId identity to embed in the token's subject
 * @param role   optional; defaults to USER. ADMIN additionally requires the X-Admin-Secret header.
 */
public record TokenRequest(
        @NotBlank(message = "user_id is required")
        @Pattern(regexp = "^[A-Za-z0-9_.@-]{1,64}$",
                message = "user_id must be 1-64 characters: letters, digits, _ . @ -")
        String userId,
        Role role) {

    public Role roleOrDefault() {
        return role == null ? Role.USER : role;
    }
}
