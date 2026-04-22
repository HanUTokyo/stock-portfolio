package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PositionResponse(
        Long id,
        String symbol,
        BigDecimal latestPrice,
        BigDecimal latestPe,
        OffsetDateTime priceUpdatedAt,
        OffsetDateTime updatedAt
) {
}
