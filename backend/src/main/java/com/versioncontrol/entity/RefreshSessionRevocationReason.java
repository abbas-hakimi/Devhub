package com.versioncontrol.entity;

public enum RefreshSessionRevocationReason {
    USER_LOGOUT,
    LOGOUT_ALL,
    USER_REVOKED,
    TOKEN_REPLAY,
    EXPIRED
}
