package com.stockportfolio.dto;

import java.time.OffsetDateTime;

public record DataReviewAuditLogResponse(
        Long id,
        String sourceName,
        String recordId,
        String fieldName,
        String oldValue,
        String newValue,
        String action,
        String reviewStatus,
        String reviewer,
        String note,
        String reasonCode,
        OffsetDateTime createdAt
) {
    public DataReviewAuditLogResponse(
            Long id, String sourceName, String recordId, String fieldName, String oldValue, String newValue,
            String action, String reviewStatus, String reviewer, String note, OffsetDateTime createdAt
    ) {
        this(id, sourceName, recordId, fieldName, oldValue, newValue, action, reviewStatus, reviewer, note, null, createdAt);
    }
}
