package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CapitalAllocationHistoryResponse(
        String symbol,
        List<ShareRepurchasePoint> shareRepurchases,
        List<SharesOutstandingPoint> sharesOutstanding
) {
    public record ShareRepurchasePoint(
            LocalDate fiscalPeriodEnd,
            BigDecimal amount,
            BigDecimal ttmAmount,
            FieldSourceResponse source
    ) { }

    public record SharesOutstandingPoint(
            LocalDate asOfDate,
            BigDecimal sharesOutstanding,
            BigDecimal rawSharesOutstanding,
            BigDecimal splitAdjustmentFactor,
            FieldSourceResponse source
    ) { }
}
