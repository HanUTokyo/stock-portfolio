package com.stockportfolio.dto;

import java.time.LocalDate;

public record MarketCloseSyncResponse(
        LocalDate tradeDate,
        int scannedSymbols,
        int successfulSymbols,
        int failedSymbols,
        int skippedSymbols,
        int priceHistoryWrites,
        int peHistoryWrites,
        String trigger
) {
}
