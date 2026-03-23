package com.stockportfolio.dto;

public record PriceRefreshResponse(
        int scannedSymbols,
        int updatedSymbols,
        String trigger
) {
}
