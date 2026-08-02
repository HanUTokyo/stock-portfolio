package com.stockportfolio.dto;

import java.time.OffsetDateTime;

public record FundamentalNoteResponse(
        String symbol,
        String note,
        OffsetDateTime updatedAt,
        Long version
) {
}
