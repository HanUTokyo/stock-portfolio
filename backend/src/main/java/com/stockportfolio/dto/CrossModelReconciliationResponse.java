package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Difference between independently valued FCFF and FCFE equity values. The
 * readiness threshold is <=10% READY, >10%-25% READY_WITH_CAVEATS, and >25%
 * NOT_READY. Scenario-level comparison is UNAVAILABLE when either method is
 * missing; overall readiness is READY_WITH_CAVEATS with one method and
 * NOT_READY with neither method.
 */
public record CrossModelReconciliationResponse(
        String readiness,
        String comparabilityStatus,
        BigDecimal baseDifferencePct,
        List<Scenario> scenarios,
        List<String> warnings
) {
    public record Scenario(
            String scenarioType,
            String primaryMethod,
            BigDecimal primaryIntrinsicValuePerShare,
            String crossCheckMethod,
            BigDecimal crossCheckIntrinsicValuePerShare,
            BigDecimal differencePct,
            String readiness,
            List<String> warnings
    ) { }
}
