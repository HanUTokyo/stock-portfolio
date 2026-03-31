package com.stockportfolio.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockportfolio.dto.PortfolioSummaryResponse;
import com.stockportfolio.model.Position;
import com.stockportfolio.model.Transaction;
import com.stockportfolio.model.TransactionType;
import com.stockportfolio.repository.DividendRepository;
import com.stockportfolio.repository.CashAdjustmentRepository;
import com.stockportfolio.repository.PeHistoryRepository;
import com.stockportfolio.repository.PositionRepository;
import com.stockportfolio.repository.PriceHistoryRepository;
import com.stockportfolio.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceSummaryTest {

    @Mock
    private PositionRepository positionRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private DividendRepository dividendRepository;
    @Mock
    private CashAdjustmentRepository cashAdjustmentRepository;
    @Mock
    private PriceHistoryRepository priceHistoryRepository;
    @Mock
    private PeHistoryRepository peHistoryRepository;

    @Test
    void getSummary_shouldCalculateRealizedAndUnrealizedAcrossPartialSellAndRebuy() {
        PortfolioService service = createService();

        Position aapl = position("AAPL", "5.0000", "102.5000", "140.0000");
        when(positionRepository.findAll()).thenReturn(List.of(aapl));
        when(transactionRepository.findAllByOrderByExecutedAtAscIdAsc()).thenReturn(List.of(
                transaction("AAPL", TransactionType.BUY, "10.0000", "100.0000", "2025-01-01T00:00:00Z"),
                transaction("AAPL", TransactionType.SELL, "4.0000", "120.0000", "2025-01-02T00:00:00Z"),
                transaction("AAPL", TransactionType.BUY, "2.0000", "110.0000", "2025-01-03T00:00:00Z"),
                transaction("AAPL", TransactionType.SELL, "3.0000", "130.0000", "2025-01-04T00:00:00Z")
        ));

        PortfolioSummaryResponse summary = service.getSummary();

        assertEquals(1, summary.totalPositions());
        assertEquals(1, summary.trackedSymbols());
        assertEquals(1, summary.currentHoldings());
        assertEquals(new BigDecimal("5.0000"), summary.totalUnits());
        assertEquals(new BigDecimal("512.5000"), summary.totalCostBasis());
        assertEquals(new BigDecimal("700.0000"), summary.totalMarketValue());
        assertEquals(new BigDecimal("187.5000"), summary.totalUnrealizedPnl());
        assertEquals(new BigDecimal("162.5000"), summary.totalRealizedGain());
    }

    @Test
    void getSummary_shouldIncludePositionWithoutTransactionHistoryForUnrealized() {
        PortfolioService service = createService();

        Position aapl = position("AAPL", "5.0000", "102.5000", "140.0000");
        Position msft = position("MSFT", "2.0000", "50.0000", "55.0000");

        when(positionRepository.findAll()).thenReturn(List.of(aapl, msft));
        when(transactionRepository.findAllByOrderByExecutedAtAscIdAsc()).thenReturn(List.of(
                transaction("AAPL", TransactionType.BUY, "10.0000", "100.0000", "2025-01-01T00:00:00Z"),
                transaction("AAPL", TransactionType.SELL, "5.0000", "120.0000", "2025-01-02T00:00:00Z")
        ));

        PortfolioSummaryResponse summary = service.getSummary();

        assertEquals(2, summary.totalPositions());
        assertEquals(2, summary.trackedSymbols());
        assertEquals(2, summary.currentHoldings());
        assertEquals(new BigDecimal("7.0000"), summary.totalUnits());
        assertEquals(new BigDecimal("600.0000"), summary.totalCostBasis());
        assertEquals(new BigDecimal("810.0000"), summary.totalMarketValue());
        assertEquals(new BigDecimal("210.0000"), summary.totalUnrealizedPnl());
        assertEquals(new BigDecimal("100.0000"), summary.totalRealizedGain());
    }

    @Test
    void getSummary_shouldFallbackToAverageCostWhenLatestPriceMissing() {
        PortfolioService service = createService();

        Position nvda = position("NVDA", "8.0000", "15.0000", null);

        when(positionRepository.findAll()).thenReturn(List.of(nvda));
        when(transactionRepository.findAllByOrderByExecutedAtAscIdAsc()).thenReturn(List.of(
                transaction("NVDA", TransactionType.BUY, "8.0000", "15.0000", "2025-02-01T00:00:00Z")
        ));

        PortfolioSummaryResponse summary = service.getSummary();

        assertEquals(1, summary.totalPositions());
        assertEquals(1, summary.trackedSymbols());
        assertEquals(1, summary.currentHoldings());
        assertEquals(new BigDecimal("120.0000"), summary.totalCostBasis());
        assertEquals(new BigDecimal("120.0000"), summary.totalMarketValue());
        assertEquals(new BigDecimal("0.0000"), summary.totalUnrealizedPnl());
        assertEquals(new BigDecimal("0.0000"), summary.totalRealizedGain());
    }

    private static Position position(String symbol, String quantity, String averageCost, String latestPrice) {
        Position position = new Position();
        position.setSymbol(symbol);
        position.setQuantity(new BigDecimal(quantity));
        position.setAverageCost(new BigDecimal(averageCost));
        position.setLatestPrice(latestPrice == null ? null : new BigDecimal(latestPrice));
        return position;
    }

    private static Transaction transaction(String symbol,
                                           TransactionType type,
                                           String quantity,
                                           String price,
                                           String executedAt) {
        Transaction transaction = new Transaction();
        transaction.setSymbol(symbol);
        transaction.setType(type);
        transaction.setQuantity(new BigDecimal(quantity));
        transaction.setPrice(new BigDecimal(price));
        transaction.setExecutedAt(OffsetDateTime.parse(executedAt));
        return transaction;
    }

    private PortfolioService createService() {
        return new PortfolioService(
                positionRepository,
                transactionRepository,
                dividendRepository,
                cashAdjustmentRepository,
                priceHistoryRepository,
                peHistoryRepository,
                new YahooFinancePriceService("https://query1.finance.yahoo.com", new ObjectMapper()),
                3,
                0,
                "America/New_York"
        );
    }
}
