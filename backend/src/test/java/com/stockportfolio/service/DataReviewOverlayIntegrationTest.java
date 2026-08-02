package com.stockportfolio.service;

import com.stockportfolio.dto.DataReviewBatchStatusRequest;
import com.stockportfolio.dto.DataReviewBatchPreviewResponse;
import com.stockportfolio.dto.DataReviewPageResponse;
import com.stockportfolio.dto.DataReviewPatchRequest;
import com.stockportfolio.dto.PriceHistoryPointResponse;
import com.stockportfolio.model.PriceHistory;
import com.stockportfolio.repository.PriceHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class DataReviewOverlayIntegrationTest {

    @Autowired
    private DataReviewService dataReviewService;

    @Autowired
    private PortfolioService portfolioService;

    @Autowired
    private PriceHistoryRepository priceHistoryRepository;

    @Test
    void correctedMarketValueFlowsThroughNormalPriceHistoryReadsWithoutChangingRawRow() {
        PriceHistory price = price("AAPL", LocalDate.of(2026, 1, 2), "100.0000");
        price = priceHistoryRepository.save(price);

        dataReviewService.patchRow(
                "market_data",
                String.valueOf(price.getId()),
                new DataReviewPatchRequest(Map.of("closePrice", "123.4500"), "corrected", "Verified against primary source.")
        );

        List<PriceHistoryPointResponse> reviewed = portfolioService.getPriceHistory(
                "AAPL",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 3)
        );

        assertThat(reviewed).singleElement().satisfies(point ->
                assertThat(point.closePrice()).isEqualByComparingTo("123.4500")
        );
        assertThat(priceHistoryRepository.findById(price.getId()).orElseThrow().getClosePrice())
                .isEqualByComparingTo("100.0000");
    }

    @Test
    void rejectedMarketRowIsExcludedAndBatchStatusProducesAuditReadyRows() {
        PriceHistory first = priceHistoryRepository.save(price("MSFT", LocalDate.of(2026, 1, 2), "90.0000"));
        PriceHistory second = priceHistoryRepository.save(price("MSFT", LocalDate.of(2026, 1, 3), "91.0000"));

        dataReviewService.batchUpdateStatus(
                "market_data",
                new DataReviewBatchStatusRequest(
                        List.of(String.valueOf(first.getId()), String.valueOf(second.getId())),
                        "approved",
                        "Validated together."
                )
        );
        dataReviewService.updateStatus("market_data", String.valueOf(first.getId()), "rejected", "Bad vendor tick.");

        List<PriceHistoryPointResponse> visible = portfolioService.getPriceHistory(
                "MSFT",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 4)
        );

        assertThat(visible).singleElement()
                .extracting(PriceHistoryPointResponse::tradeDate)
                .isEqualTo(LocalDate.of(2026, 1, 3));
        assertThat(dataReviewService.getSummary().sources())
                .anySatisfy(source -> {
                    if ("market_data".equals(source.name())) {
                        assertThat(source.approved()).isGreaterThanOrEqualTo(1);
                        assertThat(source.rejected()).isGreaterThanOrEqualTo(1);
                    }
                });
    }

    @Test
    void reviewQueuePaginatesInTheDatabaseAndKeepsCorrectedAnomalyResultsAccurate() {
        PriceHistory resolvedAnomaly = priceHistoryRepository.save(price("NVDA", LocalDate.of(2026, 1, 1), "0.0000"));
        priceHistoryRepository.save(price("NVDA", LocalDate.of(2026, 1, 2), "101.0000"));
        priceHistoryRepository.save(price("NVDA", LocalDate.of(2026, 1, 3), "102.0000"));
        priceHistoryRepository.save(price("NVDA", LocalDate.of(2026, 1, 4), "103.0000"));
        priceHistoryRepository.save(price("NVDA", LocalDate.of(2026, 1, 5), "104.0000"));

        DataReviewPageResponse secondPage = dataReviewService.getRows(
                "market_data", 1, 2, null, "pending", "id", "asc", false
        );

        assertThat(secondPage.totalElements()).isEqualTo(5);
        assertThat(secondPage.totalPages()).isEqualTo(3);
        assertThat(secondPage.rows()).hasSize(2);

        DataReviewPageResponse rawAnomalies = dataReviewService.getRows(
                "market_data", 0, 25, null, "all", "id", "asc", true
        );
        assertThat(rawAnomalies.totalElements()).isEqualTo(1);
        assertThat(rawAnomalies.rows()).singleElement()
                .extracting((row) -> row.recordId())
                .isEqualTo(String.valueOf(resolvedAnomaly.getId()));

        dataReviewService.patchRow(
                "market_data",
                String.valueOf(resolvedAnomaly.getId()),
                new DataReviewPatchRequest(Map.of("closePrice", "100.0000"), "corrected", "Corrected vendor zero.")
        );

        DataReviewPageResponse effectiveAnomalies = dataReviewService.getRows(
                "market_data", 0, 25, null, "all", "id", "asc", true
        );
        assertThat(effectiveAnomalies.totalElements()).isZero();
        assertThat(effectiveAnomalies.rows()).isEmpty();
        assertThat(dataReviewService.getSummary().sources())
                .filteredOn((source) -> "market_data".equals(source.name()))
                .singleElement()
                .satisfies((source) -> assertThat(source.anomalies()).isZero());
    }

    @Test
    void batchPreviewIsReadOnlyAndRejectsStaleRevisionsBeforeAnyWrite() {
        PriceHistory price = priceHistoryRepository.save(price("PREVIEW", LocalDate.of(2026, 1, 2), "0.0000"));
        String id = String.valueOf(price.getId());

        DataReviewBatchPreviewResponse preview = dataReviewService.previewBatchStatus(
                "market_data",
                new DataReviewBatchStatusRequest(List.of(id), "uncertain", "Needs source confirmation.", "missing_or_stale", Map.of(id, "0"))
        );

        assertThat(preview.affectedCount()).isEqualTo(1);
        assertThat(preview.riskCounts()).containsEntry("urgent", 1L);
        assertThat(priceHistoryRepository.findById(price.getId()).orElseThrow().getClosePrice()).isEqualByComparingTo("0.0000");

        assertThatThrownBy(() -> dataReviewService.batchUpdateStatus(
                "market_data",
                new DataReviewBatchStatusRequest(List.of(id), "uncertain", "Needs source confirmation.", "missing_or_stale", Map.of(id, "999"))
        )).hasMessageContaining("changed");
    }

    private PriceHistory price(String symbol, LocalDate date, String close) {
        PriceHistory row = new PriceHistory();
        row.setSymbol(symbol);
        row.setTradeDate(date);
        row.setClosePrice(new BigDecimal(close));
        return row;
    }
}
