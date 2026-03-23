package com.stockportfolio.dto;

import java.time.LocalDate;

public record PriceHistoryBackfillResponse(
        LocalDate fromDate,
        LocalDate toDate,
        int requestedYears,
        int scannedSymbols,
        int successfulSymbols,
        int failedSymbols,
        int skippedSymbols,
        int historyPointsWritten,
        String trigger
) {
}
