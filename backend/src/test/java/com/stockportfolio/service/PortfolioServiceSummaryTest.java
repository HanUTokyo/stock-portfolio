package com.stockportfolio.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockportfolio.dto.TransactionCsvImportResponse;
import com.stockportfolio.dto.PortfolioExportV2Response;
import com.stockportfolio.dto.PortfolioSummaryResponse;
import com.stockportfolio.dto.PositionMetadataRequest;
import com.stockportfolio.dto.PositionResponse;
import com.stockportfolio.model.OverviewNote;
import com.stockportfolio.model.OverviewNoteType;
import com.stockportfolio.model.Position;
import com.stockportfolio.model.Transaction;
import com.stockportfolio.model.TransactionType;
import com.stockportfolio.repository.DividendRepository;
import com.stockportfolio.repository.CashAdjustmentRepository;
import com.stockportfolio.repository.EarningsEstimateRepository;
import com.stockportfolio.repository.EarningsHistoryRepository;
import com.stockportfolio.repository.FundamentalNoteRepository;
import com.stockportfolio.repository.FundamentalFactObservationRepository;
import com.stockportfolio.repository.NonGaapEpsHistoryRepository;
import com.stockportfolio.repository.OverviewNoteRepository;
import com.stockportfolio.repository.PositionRepository;
import com.stockportfolio.repository.PriceHistoryRepository;
import com.stockportfolio.repository.PeHistoryRepository;
import com.stockportfolio.repository.StockNoteRepository;
import com.stockportfolio.repository.StockSplitRepository;
import com.stockportfolio.repository.SecShareCountEvidenceRepository;
import com.stockportfolio.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
    @Mock
    private EarningsEstimateRepository earningsEstimateRepository;
    @Mock
    private EarningsHistoryRepository earningsHistoryRepository;
    @Mock
    private StockSplitRepository stockSplitRepository;
    @Mock
    private SecShareCountEvidenceRepository shareCountEvidenceRepository;
    @Mock
    private NonGaapEpsHistoryRepository nonGaapEpsHistoryRepository;
    @Mock
    private FundamentalNoteRepository fundamentalNoteRepository;
    @Mock
    private FundamentalFactObservationRepository fundamentalFactObservationRepository;
    @Mock
    private StockNoteRepository stockNoteRepository;
    @Mock
    private OverviewNoteRepository overviewNoteRepository;
    @Mock
    private ReviewedDataResolver reviewedDataResolver;

    @Test
    void marketSyncOperations_shouldAcquireTheSharedPositionLock() {
        PortfolioService service = createService();
        when(transactionRepository.findAllByOrderByExecutedAtAscIdAsc()).thenReturn(List.of());

        service.refreshPrices("TEST_REFRESH");
        service.syncMarketClose("TEST_CLOSE");

        verify(positionRepository, times(2)).findAllForMarketSync();
    }

    @Test
    void getSummary_shouldCalculateRealizedAndUnrealizedAcrossPartialSellAndRebuy() {
        PortfolioService service = createService();

        Position aapl = position("AAPL", "140.0000");
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
    void getSummary_shouldIgnoreSymbolsWithoutTransactionHistory() {
        PortfolioService service = createService();

        Position aapl = position("AAPL", "140.0000");
        Position msft = position("MSFT", "55.0000");

        when(positionRepository.findAll()).thenReturn(List.of(aapl, msft));
        when(transactionRepository.findAllByOrderByExecutedAtAscIdAsc()).thenReturn(List.of(
                transaction("AAPL", TransactionType.BUY, "10.0000", "100.0000", "2025-01-01T00:00:00Z"),
                transaction("AAPL", TransactionType.SELL, "5.0000", "120.0000", "2025-01-02T00:00:00Z")
        ));

        PortfolioSummaryResponse summary = service.getSummary();

        assertEquals(1, summary.totalPositions());
        assertEquals(1, summary.trackedSymbols());
        assertEquals(1, summary.currentHoldings());
        assertEquals(new BigDecimal("5.0000"), summary.totalUnits());
        assertEquals(new BigDecimal("500.0000"), summary.totalCostBasis());
        assertEquals(new BigDecimal("700.0000"), summary.totalMarketValue());
        assertEquals(new BigDecimal("200.0000"), summary.totalUnrealizedPnl());
        assertEquals(new BigDecimal("100.0000"), summary.totalRealizedGain());
    }

    @Test
    void getSummary_shouldFallbackToAverageCostWhenLatestPriceMissing() {
        PortfolioService service = createService();

        Position nvda = position("NVDA", null);

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

    @Test
    void updatePositionMetadata_shouldSaveManualClassificationFields() {
        PortfolioService service = createService();
        Position nvda = position("NVDA", "196.5000");

        when(positionRepository.findBySymbolIgnoreCase("NVDA")).thenReturn(Optional.of(nvda));
        when(positionRepository.save(nvda)).thenReturn(nvda);

        PositionResponse response = service.updatePositionMetadata(
                "nvda",
                new PositionMetadataRequest("equity", "common_stock", "", "Information Technology", "US")
        );

        assertEquals("equity", response.assetClass());
        assertEquals("common_stock", response.instrumentType());
        assertEquals(null, response.underlying());
        assertEquals("Information Technology", response.sector());
        assertEquals("US", response.region());
    }

    @Test
    void importTransactionsFromCsv_shouldDryRunInChronologicalOrder() {
        PortfolioService service = createService();
        when(transactionRepository.findAllByOrderByExecutedAtAscIdAsc()).thenReturn(List.of());

        String csv = String.join("\n",
                "executedAt,symbol,type,quantity,price,note",
                "2026-01-02 0:00:00,TSM,SELL,1.00000000,200.0000,sell appears first",
                "2025-01-02 0:00:00,TSM,BUY,1.00000000,100.0000,buy appears later"
        ) + "\n";

        TransactionCsvImportResponse response = service.importTransactionsFromCsv(
                new MockMultipartFile("file", "transactions.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)),
                true
        );

        assertEquals(2, response.totalRows());
        assertEquals(2, response.importedRows());
        assertEquals(0, response.failedRows());
    }

    @Test
    void exportPortfolioV2_shouldStructureHoldingsAndParseOccOptionSymbols() {
        PortfolioService service = createService();

        Position option = position("NKE280121C00040000", "10.9100");
        Position nke = position("NKE", "43.0600");
        when(positionRepository.findAll()).thenReturn(List.of(option, nke));
        when(transactionRepository.findAllByOrderByExecutedAtAscIdAsc()).thenReturn(List.of(
                transaction("NKE280121C00040000", TransactionType.BUY, "100.0000", "10.3000", "2026-05-01T00:00:00Z")
        ));
        when(dividendRepository.findAllByOrderByPaidDateAscIdAsc()).thenReturn(List.of());
        when(cashAdjustmentRepository.findAllByOrderByOccurredAtAscIdAsc()).thenReturn(List.of());
        when(priceHistoryRepository.findAllBySymbolInAndTradeDateGreaterThanEqualOrderByTradeDateAsc(
                eq(List.of("NKE280121C00040000")),
                any(LocalDate.class)
        )).thenReturn(List.of());
        when(stockNoteRepository.findAllByOrderBySymbolAsc()).thenReturn(List.of());
        when(earningsHistoryRepository.findBySymbolAndAsOfDateBetweenOrderByAsOfDateAsc(
                eq("NKE280121C00040000"),
                any(LocalDate.class),
                any(LocalDate.class)
        )).thenReturn(List.of());
        when(earningsEstimateRepository.findBySymbolAndPeriodTypeOrderByPeriodEndDateAsc("NKE280121C00040000", "ANNUAL"))
                .thenReturn(List.of());
        when(overviewNoteRepository.findAllByOrderByNoteTypeAsc()).thenReturn(List.of(
                overviewNote(OverviewNoteType.USER, "Increase cash buffer before CPI."),
                overviewNote(OverviewNoteType.AI, "Previous AI: trim oversized winners.")
        ));

        PortfolioExportV2Response response = service.exportPortfolioV2();
        PortfolioExportV2Response.Holding holding = response.holdings().get(0);

        assertEquals("user", response.aiSuggestionContext().monthlyIdeas().role());
        assertEquals("Increase cash buffer before CPI.", response.aiSuggestionContext().monthlyIdeas().note());
        assertEquals("previous_ai", response.aiSuggestionContext().previousAiSuggestions().role());
        assertEquals("Previous AI: trim oversized winners.", response.aiSuggestionContext().previousAiSuggestions().note());
        assertEquals("NKE280121C00040000", holding.symbol());
        assertEquals(new BigDecimal("100.00000000"), holding.position().quantity());
        assertEquals(new BigDecimal("1030.0000"), holding.position().costBasis());
        assertEquals("equity_option", holding.classification().assetClass());
        assertEquals("call_option", holding.classification().instrumentType());
        assertEquals("NKE", holding.classification().underlying());
        assertEquals("call", holding.option().type());
        assertEquals(new BigDecimal("40.0000"), holding.option().strike());
        assertEquals(LocalDate.of(2028, 1, 21), holding.option().expiration());
        assertEquals(new BigDecimal("4306.0000"), holding.option().notionalExposure());
        assertEquals(new BigDecimal("306.0000"), holding.option().intrinsicValue());
        assertEquals(new BigDecimal("785.0000"), holding.option().timeValue());
        assertEquals(new BigDecimal("1030.0000"), holding.option().maxLoss());
        assertEquals(new BigDecimal("61.0000"), holding.performance().unrealizedPnl());
    }

    private static Position position(String symbol, String latestPrice) {
        Position position = new Position();
        position.setSymbol(symbol);
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

    private static OverviewNote overviewNote(OverviewNoteType noteType, String note) {
        OverviewNote overviewNote = new OverviewNote();
        overviewNote.setNoteType(noteType);
        overviewNote.setNote(note);
        overviewNote.setUpdatedAt(OffsetDateTime.parse("2026-06-14T00:00:00Z"));
        return overviewNote;
    }

    private PortfolioService createService() {
        org.mockito.Mockito.lenient().when(reviewedDataResolver.correctedValues(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(Long.class)))
                .thenReturn(Map.of());
        org.mockito.Mockito.lenient().when(reviewedDataResolver.correctedValues(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(Map.of());
        org.mockito.Mockito.lenient().when(reviewedDataResolver.rejectedRecordIds(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(Set.of());
        org.mockito.Mockito.lenient().when(reviewedDataResolver.decimal(org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        org.mockito.Mockito.lenient().when(reviewedDataResolver.text(org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        return new PortfolioService(
                positionRepository,
                transactionRepository,
                dividendRepository,
                cashAdjustmentRepository,
                priceHistoryRepository,
                peHistoryRepository,
                earningsEstimateRepository,
                earningsHistoryRepository,
                stockSplitRepository,
                shareCountEvidenceRepository,
                nonGaapEpsHistoryRepository,
                fundamentalNoteRepository,
                fundamentalFactObservationRepository,
                stockNoteRepository,
                overviewNoteRepository,
                reviewedDataResolver,
                new YahooFinancePriceService("https://query1.finance.yahoo.com", new ObjectMapper()),
                new SecCompanyFactsService(new ObjectMapper(), "stock-portfolio test@example.com"),
                3,
                0,
                "America/New_York"
        );
    }
}
