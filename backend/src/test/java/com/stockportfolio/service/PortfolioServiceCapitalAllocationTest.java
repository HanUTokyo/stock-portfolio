package com.stockportfolio.service;

import com.stockportfolio.model.StockSplit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortfolioServiceCapitalAllocationTest {

    @Test
    void adjustsAReportedShareCountByLaterFourForOneSplitOnly() {
        StockSplit split = new StockSplit();
        split.setSplitDate(LocalDate.of(2020, 8, 31));
        split.setNumerator(new BigDecimal("4"));
        split.setDenominator(BigDecimal.ONE);

        assertEquals(new BigDecimal("4.000000000000"), PortfolioService.calculateCurrentSplitAdjustmentFactor(
                LocalDate.of(2020, 6, 27), List.of(split)));
        assertEquals(BigDecimal.ONE, PortfolioService.calculateCurrentSplitAdjustmentFactor(
                LocalDate.of(2020, 9, 26), List.of(split)));
    }
}
