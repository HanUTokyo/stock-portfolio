package com.stockportfolio.service;

import com.stockportfolio.dto.SecDebtRebuildResponse;
import com.stockportfolio.model.EarningsHistory;
import com.stockportfolio.repository.EarningsHistoryRepository;
import com.stockportfolio.repository.FundamentalRebuildAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecDebtRebuildServiceTest {
    private EarningsHistoryRepository historyRepository;
    private FundamentalRebuildAuditRepository auditRepository;
    private SecCompanyFactsService secCompanyFactsService;
    private SecDebtRebuildService service;

    @BeforeEach
    void setUp() {
        historyRepository = mock(EarningsHistoryRepository.class);
        auditRepository = mock(FundamentalRebuildAuditRepository.class);
        secCompanyFactsService = mock(SecCompanyFactsService.class);
        service = new SecDebtRebuildService(historyRepository, auditRepository, secCompanyFactsService);
    }

    @Test
    void dryRunReportsTargetedChangesWithoutWriting() throws Exception {
        EarningsHistory existing = existingRow();
        when(historyRepository.findBySymbolAndAsOfDateBetweenOrderByAsOfDateAsc(any(), any(), any()))
                .thenReturn(List.of(existing));
        when(secCompanyFactsService.fetchQuarterlyFundamentalsHistory(any(), any(), any()))
                .thenReturn(List.of(secPoint()));

        SecDebtRebuildResponse result = service.rebuild("AAPL", 5, true, "TEST");

        assertThat(result.changedRows()).isEqualTo(1);
        assertThat(result.changedFields()).isEqualTo(3);
        assertThat(existing.getTotalDebt()).isEqualByComparingTo("82347");
        verify(historyRepository, never()).save(any());
        verify(auditRepository, never()).saveAll(any());
    }

    @Test
    void rebuildIsIdempotentAndPersistsAnAuditTrail() throws Exception {
        EarningsHistory existing = existingRow();
        when(historyRepository.findBySymbolAndAsOfDateBetweenOrderByAsOfDateAsc(any(), any(), any()))
                .thenReturn(List.of(existing));
        when(secCompanyFactsService.fetchQuarterlyFundamentalsHistory(any(), any(), any()))
                .thenReturn(List.of(secPoint()));

        SecDebtRebuildResponse first = service.rebuild("AAPL", 5, false, "TEST");
        SecDebtRebuildResponse second = service.rebuild("AAPL", 5, false, "TEST");

        assertThat(first.changedFields()).isEqualTo(3);
        assertThat(second.changedFields()).isZero();
        assertThat(existing.getTotalDebt()).isEqualByComparingTo("84344.0000");
        assertThat(existing.getNetBorrowing()).isEqualByComparingTo("-5911.0000");
        verify(auditRepository).saveAll(any());
    }

    private EarningsHistory existingRow() {
        EarningsHistory row = new EarningsHistory();
        row.setSymbol("AAPL");
        row.setAsOfDate(LocalDate.now().minusMonths(1));
        row.setFiscalYear(2026);
        row.setFiscalPeriod("Q3");
        row.setTotalDebt(new BigDecimal("82347"));
        row.setNetBorrowing(BigDecimal.ZERO);
        row.setInvestedCapital(new BigDecimal("200000"));
        return row;
    }

    private YahooFinancePriceService.QuarterlyFundamentalPoint secPoint() {
        return new YahooFinancePriceService.QuarterlyFundamentalPoint(
                LocalDate.now().minusMonths(1), null, "USD", null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                new BigDecimal("84344"), null, null, null,
                null, null, new BigDecimal("197997"),
                null, null, null, null, new BigDecimal("-5911"),
                null, null, 2026, "Q3", LocalDate.now()
        );
    }
}
