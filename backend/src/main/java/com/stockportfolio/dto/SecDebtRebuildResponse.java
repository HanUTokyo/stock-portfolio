package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SecDebtRebuildResponse(
        String runId,
        boolean dryRun,
        LocalDate fromDate,
        LocalDate toDate,
        int scannedSymbols,
        int scannedSecRows,
        int matchedRows,
        int changedRows,
        int changedFields,
        int unmatchedRows,
        int duplicateFiscalPeriods,
        List<FieldChange> changes,
        String trigger
) {
    public record FieldChange(
            String symbol,
            LocalDate asOfDate,
            String fiscalPeriodKey,
            String field,
            BigDecimal beforeValue,
            BigDecimal afterValue
    ) {
    }
}
