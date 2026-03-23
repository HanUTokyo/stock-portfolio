package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PeHistoryPointResponse(
        LocalDate tradeDate,
        BigDecimal trailingPe
) {
}
