package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Auditable reconciliation between reported indirect CFO and the economic FCFF
 * definition.  A non-COMPLETE bridge must never be treated as a verified
 * operating FCFF input.
 */
public record CashFlowBridgeResponse(
        String coverageStatus,
        String primaryStatus,
        BigDecimal economicFcff,
        BigDecimal cashFcffReferenceOnly,
        /** NOPAT reconstruction using incomplete or aggregate operating-NWC inputs; never a verified economic FCFF. */
        BigDecimal provisionalOperatingFcff,
        BigDecimal residual,
        BigDecimal reconciliationDifferencePct,
        List<String> sourceAccessions,
        List<LedgerEntry> ledger,
        List<String> missingInputs,
        List<String> warnings
) {
    public record LedgerEntry(String bucket, String label, BigDecimal amount,
                              String signConvention, String sourceConcept,
                              String accession, String status) { }
}
