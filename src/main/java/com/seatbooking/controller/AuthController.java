package com.seatbooking.controller;

import com.seatbooking.config.SecurityConfig;
import com.seatbooking.dto.auth.CurrentUserResponse;
import com.seatbooking.dto.auth.TokenRequest;
import com.seatbooking.dto.auth.TokenResponse;
import com.seatbooking.security.AuthenticatedUser;
import com.seatbooking.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/auth", "/api/v1/auth"})
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/token")
    public TokenResponse issueToken(
            @Valid @RequestBody TokenRequest request,
            @RequestHeader(name = SecurityConfig.ADMIN_SECRET_HEADER, required = false) String adminSecret) {
        return authService.issueToken(request, adminSecret);
    }

    /** Echoes the identity the server derived from the token; handy for verifying a token works. */
    @GetMapping("/me")
    public CurrentUserResponse currentUser(@AuthenticationPrincipal AuthenticatedUser user) {
        return CurrentUserResponse.from(user);
    }
}
