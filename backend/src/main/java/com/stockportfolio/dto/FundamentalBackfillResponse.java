package com.stockportfolio.dto;

import java.time.LocalDate;

public record FundamentalBackfillResponse(
        LocalDate fromDate,
        LocalDate toDate,
        int requestedYears,
        int scannedSymbols,
        int successfulSymbols,
        int failedSymbols,
        int skippedSymbols,
        int rowsInserted,
        int rowsUpdated,
        int fieldsFilled,
        String trigger
) {
}
