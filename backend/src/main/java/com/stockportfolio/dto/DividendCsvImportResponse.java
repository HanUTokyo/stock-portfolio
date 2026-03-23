package com.stockportfolio.dto;

import java.util.List;

public record DividendCsvImportResponse(
        int totalRows,
        int importedRows,
        int skippedRows,
        int failedRows,
        List<String> sampleErrors
) {
}
