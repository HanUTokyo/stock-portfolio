package com.stockportfolio.dto;

import java.time.OffsetDateTime;

public record StockNoteResponse(
        String symbol,
        String note,
        OffsetDateTime updatedAt
) {
}
