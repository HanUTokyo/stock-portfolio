package com.stockportfolio.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortfolioServiceQuarterKeyTest {

    @Test
    void quarterKeyMapsFiscalQuarterEndNearCalendarQuarterEnd() {
        assertEquals("2023-Q2", PortfolioService.quarterKey(LocalDate.of(2023, 7, 1)));
        assertEquals("2017-Q2", PortfolioService.quarterKey(LocalDate.of(2017, 7, 1)));
        assertEquals("2011-Q1", PortfolioService.quarterKey(LocalDate.of(2011, 4, 2)));
    }

    @Test
    void quarterKeyFallsBackToCalendarQuarterAwayFromQuarterEnd() {
        assertEquals("2023-Q3", PortfolioService.quarterKey(LocalDate.of(2023, 8, 15)));
    }
}
