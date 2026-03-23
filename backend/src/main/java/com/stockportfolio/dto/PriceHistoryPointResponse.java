package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PriceHistoryPointResponse(
        LocalDate tradeDate,
        BigDecimal closePrice
) {
}
