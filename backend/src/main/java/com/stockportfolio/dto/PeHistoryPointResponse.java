package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PeHistoryPointResponse(
        LocalDate tradeDate,
        BigDecimal ttmPe,
        BigDecimal nonGaapTtmPe,
        BigDecimal quarterlyPe,
        BigDecimal forwardPe,
        String ttmPeStatus,
        String nonGaapTtmPeStatus,
        String quarterlyPeStatus,
        String forwardPeStatus,
        LocalDate earningsAsOf
) {
    public PeHistoryPointResponse(LocalDate tradeDate, BigDecimal ttmPe, BigDecimal nonGaapTtmPe,
                                  BigDecimal quarterlyPe, BigDecimal forwardPe) {
        this(tradeDate, ttmPe, nonGaapTtmPe, quarterlyPe, forwardPe,
                status(ttmPe), status(nonGaapTtmPe), status(quarterlyPe), status(forwardPe), null);
    }
    private static String status(BigDecimal value) { return value == null ? "UNAVAILABLE" : "AVAILABLE"; }
}
