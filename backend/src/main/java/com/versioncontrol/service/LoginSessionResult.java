package com.versioncontrol.service;

import com.versioncontrol.dto.auth.AuthResponse;
import java.time.LocalDateTime;

public record LoginSessionResult(AuthResponse response, String refreshToken, LocalDateTime expiresAt) { }
