package com.stockportfolio.dto;

import com.stockportfolio.model.CashAdjustmentType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record CashAdjustmentResponse(
        Long id,
        CashAdjustmentType type,
        BigDecimal amount,
        BigDecimal signedAmount,
        OffsetDateTime occurredAt,
        OffsetDateTime createdAt
) {
}
