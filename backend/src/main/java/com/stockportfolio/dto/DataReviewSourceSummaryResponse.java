package com.stockportfolio.dto;

public record DataReviewSourceSummaryResponse(
        String name,
        String label,
        String rawTable,
        long total,
        long pending,
        long approved,
        long corrected,
        long rejected,
        long uncertain,
        long anomalies,
        long attention,
        long completed
) {
    public DataReviewSourceSummaryResponse(
            String name, String label, String rawTable, long total, long pending, long approved, long corrected,
            long rejected, long uncertain, long anomalies
    ) {
        this(name, label, rawTable, total, pending, approved, corrected, rejected, uncertain, anomalies, anomalies, approved + corrected + rejected);
    }
}
