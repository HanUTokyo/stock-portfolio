package com.stockportfolio.dto;

import java.util.List;

public record CashAdjustmentCsvImportResponse(
        int totalRows,
        int importedRows,
        int skippedRows,
        int failedRows,
        List<String> sampleErrors
) {
}
