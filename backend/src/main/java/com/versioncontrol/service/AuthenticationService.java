package com.versioncontrol.service;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.versioncontrol.dto.auth.AuthResponse;
import com.versioncontrol.dto.auth.LoginRequest;
import com.versioncontrol.dto.auth.RegisterRequest;
import com.versioncontrol.dto.user.UserResponse;
import com.versioncontrol.entity.User;
import com.versioncontrol.exception.DuplicateUserException;
import com.versioncontrol.repository.UserRepository;
import com.versioncontrol.security.JwtService;
import com.versioncontrol.security.UserPrincipal;

@Service
public class AuthenticationService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthenticationService(UserRepository users, PasswordEncoder encoder, AuthenticationManager authenticationManager, JwtService jwtService) {
        this.users = users; this.encoder = encoder; this.authenticationManager = authenticationManager; this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = request.username().trim();
        String email = request.email().trim().toLowerCase(java.util.Locale.ROOT);
        if (users.existsByUsernameIgnoreCase(username)) throw new DuplicateUserException("Username is already registered");
        if (users.existsByEmailIgnoreCase(email)) throw new DuplicateUserException("Email is already registered");
        User entity = new User(username, email, encoder.encode(request.password()));
        entity.setDisplayName(request.displayName());
        User user = users.saveAndFlush(entity);
        UserPrincipal principal = UserPrincipal.from(user);
        return new AuthResponse(jwtService.generateAccessToken(principal), "Bearer", jwtService.getExpirationSeconds(), UserResponse.from(user));
    }

    public AuthResponse login(LoginRequest request) {
        Authentication auth = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(request.identifier().trim(), request.password()));
        UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
        return new AuthResponse(jwtService.generateAccessToken(principal), "Bearer", jwtService.getExpirationSeconds(),
                users.findById(principal.getId()).map(UserResponse::from).orElseThrow());
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(Long id) {
        return users.findById(id).map(UserResponse::from).orElseThrow();
    }
}
