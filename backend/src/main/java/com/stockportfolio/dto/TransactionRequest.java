package com.stockportfolio.dto;

import com.stockportfolio.model.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record TransactionRequest(
        @NotBlank String symbol,
        @NotNull TransactionType type,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal quantity,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal price,
        OffsetDateTime executedAt
) {
}
