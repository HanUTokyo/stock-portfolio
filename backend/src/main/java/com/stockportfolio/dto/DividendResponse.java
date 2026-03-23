package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record DividendResponse(
        Long id,
        String symbol,
        BigDecimal amount,
        LocalDate paidDate,
        OffsetDateTime createdAt
) {
}
