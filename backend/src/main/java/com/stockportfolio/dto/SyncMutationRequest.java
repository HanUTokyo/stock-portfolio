package com.stockportfolio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.Map;

public record SyncMutationRequest(
        @NotBlank String mutationId,
        @NotBlank String deviceId,
        @NotBlank String entityType,
        @NotBlank String action,
        String entityId,
        Long baseVersion,
        String baseRevision,
        @NotNull Map<String, Object> payload,
        @NotNull OffsetDateTime clientOccurredAt
) {
}
