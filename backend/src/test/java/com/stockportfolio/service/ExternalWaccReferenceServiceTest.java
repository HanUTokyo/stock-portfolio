package com.stockportfolio.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class ExternalWaccReferenceServiceTest {
    @Test void extractsOnlyAnExplicitPublicWaccPercentage() {
        assertThat(ExternalWaccReferenceService.parseRate("<h2>WACC</h2><strong>9.30%</strong>"))
                .contains(new BigDecimal("9.3000"));
        assertThat(ExternalWaccReferenceService.parseRate("Weighted Average Cost of Capital is 8.75%"))
                .contains(new BigDecimal("8.7500"));
    }

    @Test void rejectsBlockedOrAmbiguousPages() {
        assertThat(ExternalWaccReferenceService.parseRate("Please sign in to view WACC 8.2%")).isEmpty();
        assertThat(ExternalWaccReferenceService.parseRate("Discount rate: 8.2%")).isEmpty();
        assertThat(ExternalWaccReferenceService.parseRate("WACC 45%")).isEmpty();
    }
}
