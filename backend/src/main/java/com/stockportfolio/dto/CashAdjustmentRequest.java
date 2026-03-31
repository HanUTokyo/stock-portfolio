package com.stockportfolio.dto;

import com.stockportfolio.model.CashAdjustmentType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record CashAdjustmentRequest(
        @NotNull CashAdjustmentType type,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal amount,
        OffsetDateTime occurredAt
) {
}
