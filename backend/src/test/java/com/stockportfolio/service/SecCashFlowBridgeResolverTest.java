package com.stockportfolio.service;

import com.stockportfolio.dto.CashFlowBridgeResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecCashFlowBridgeResolverTest {
    @Test
    void blocksEconomicFcffUntilNonOverlappingFilingBridgeExists() {
        List<ValuationEngine.Quarter> rows = List.of(1, 2, 3, 4).stream().map(index -> new ValuationEngine.Quarter(
                LocalDate.of(2025, index * 3, index == 4 ? 31 : 30), null, 2025, "Q" + index,
                null, bd("30"), bd("5"), bd("2"), bd("0"), bd("4"), bd("3"), bd("20"), bd("4"), bd("24"), bd("20"),
                null, bd("10"), bd("20"), null, null, null, bd("100"), bd("45"), bd("200"), "USD")).toList();

        CashFlowBridgeResponse result = new SecCashFlowBridgeResolver().resolve(rows, bd("20"));

        assertThat(result.coverageStatus()).isEqualTo("INCOMPLETE");
        assertThat(result.primaryStatus()).isEqualTo("CASH_FCFF_REFERENCE_ONLY");
        assertThat(result.economicFcff()).isNull();
        assertThat(result.provisionalOperatingFcff()).isEqualByComparingTo("48");
        assertThat(result.cashFcffReferenceOnly()).isEqualByComparingTo("106.4");
        assertThat(result.ledger()).anyMatch(entry -> "SBC".equals(entry.bucket()) && "MISSING".equals(entry.status()));
        assertThat(result.missingInputs()).contains("secFilingPresentationAndCalculationRelationships");
    }

    private static BigDecimal bd(String value) { return new BigDecimal(value); }
}
