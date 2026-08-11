package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ShareCountBridgeResponse(
        LocalDate periodStart, LocalDate periodEnd,
        BigDecimal beginningShares, BigDecimal endingShares, BigDecimal netShareChange, BigDecimal residual,
        String coverageStatus, String alignmentStatus, String statementRole, String accessionNumber,
        LocalDate filedDate, BigDecimal splitAdjustmentFactor,
        List<Component> components, List<String> warnings
) {
    public record Component(String componentType, BigDecimal amount, List<String> concepts, String accessionNumber) { }
}
