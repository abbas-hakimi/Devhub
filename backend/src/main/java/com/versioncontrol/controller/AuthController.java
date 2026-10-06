package com.versioncontrol.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.versioncontrol.dto.auth.AuthResponse;
import com.versioncontrol.dto.auth.LoginRequest;
import com.versioncontrol.dto.auth.RegisterRequest;
import com.versioncontrol.dto.user.UserResponse;
import com.versioncontrol.security.UserPrincipal;
import com.versioncontrol.service.AuthenticationService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationService authenticationService;
    public AuthController(AuthenticationService authenticationService) { this.authenticationService = authenticationService; }

    @PostMapping("/register") @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) { return authenticationService.register(request); }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) { return authenticationService.login(request); }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UserPrincipal principal) {
        return authenticationService.currentUser(principal.getId());
    }
}
