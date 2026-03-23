package com.stockportfolio.dto;

public record TransactionCsvFailedRow(
        int rowNumber,
        String rawLine,
        String reason
) {
}
