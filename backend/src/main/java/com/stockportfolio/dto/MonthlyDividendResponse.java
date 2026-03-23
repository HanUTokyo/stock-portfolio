package com.stockportfolio.dto;

import java.math.BigDecimal;

public record MonthlyDividendResponse(
        String month,
        BigDecimal totalAmount
) {
}
