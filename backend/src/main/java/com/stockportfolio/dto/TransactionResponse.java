package com.stockportfolio.dto;

import com.stockportfolio.model.TransactionType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TransactionResponse(
        Long id,
        String symbol,
        TransactionType type,
        BigDecimal quantity,
        BigDecimal price,
        OffsetDateTime executedAt
) {
}
