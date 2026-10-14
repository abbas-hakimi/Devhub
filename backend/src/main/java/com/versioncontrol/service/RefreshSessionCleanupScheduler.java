package com.versioncontrol.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RefreshSessionCleanupScheduler {
    private final RefreshSessionService sessions;

    public RefreshSessionCleanupScheduler(RefreshSessionService sessions) { this.sessions = sessions; }

    @Scheduled(fixedDelayString = "${auth.session-cleanup-delay-ms:21600000}")
    public void purgeExpiredHistory() { sessions.purgeExpiredHistory(); }
}
