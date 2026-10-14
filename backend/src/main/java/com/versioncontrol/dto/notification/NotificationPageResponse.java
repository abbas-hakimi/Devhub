package com.versioncontrol.dto.notification;

import java.util.List;
public record NotificationPageResponse(int page, int size, long totalElements, int totalPages, boolean hasNext,
        List<NotificationResponse> items) { }
