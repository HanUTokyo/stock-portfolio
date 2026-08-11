package com.stockportfolio.valuation.explicit;

import java.math.BigDecimal;
import java.util.List;

/**
 * Explicit financing rule for converting the shared FCFF forecast into FCFE.
 * Historical net borrowing is deliberately not an input to either policy.
 */
public record DebtFinancingPolicy(
        Type type,
        BigDecimal targetDebtFinancingRatio,
        List<BigDecimal> customAnnualNetBorrowing
) {
    public enum Type {
        TARGET_DEBT_FINANCING_RATIO,
        CUSTOM_ANNUAL_NET_BORROWING
    }

    public DebtFinancingPolicy {
        customAnnualNetBorrowing = customAnnualNetBorrowing == null
                ? List.of()
                : List.copyOf(customAnnualNetBorrowing);
    }

    public static DebtFinancingPolicy targetDebtFinancingRatio(BigDecimal ratio) {
        return new DebtFinancingPolicy(Type.TARGET_DEBT_FINANCING_RATIO, ratio, List.of());
    }

    public static DebtFinancingPolicy customAnnualNetBorrowing(List<BigDecimal> annualNetBorrowing) {
        return new DebtFinancingPolicy(Type.CUSTOM_ANNUAL_NET_BORROWING, null, annualNetBorrowing);
    }
}
