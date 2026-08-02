package com.stockportfolio.dto;

public record SyncMutationResult(
        String mutationId,
        String status,
        String entityId,
        Long serverVersion,
        String serverRevision,
        Object serverValue,
        String message
) {
    public static SyncMutationResult applied(String mutationId, String entityId, Long version, String revision, Object value) {
        return new SyncMutationResult(mutationId, "APPLIED", entityId, version, revision, value, null);
    }

    public static SyncMutationResult conflict(String mutationId, String entityId, Long version, String revision, Object value, String message) {
        return new SyncMutationResult(mutationId, "CONFLICT", entityId, version, revision, value, message);
    }

    public static SyncMutationResult rejected(String mutationId, String message) {
        return new SyncMutationResult(mutationId, "REJECTED", null, null, null, null, message);
    }
}
