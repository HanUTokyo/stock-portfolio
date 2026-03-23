package com.stockportfolio.dto;

import java.util.List;

public record TransactionCsvImportResponse(
        boolean dryRun,
        int totalRows,
        int importedRows,
        int skippedRows,
        int failedRows,
        List<String> sampleErrors,
        List<TransactionCsvFailedRow> failedRowsDetail
) {
}
