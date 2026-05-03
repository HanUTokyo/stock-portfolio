package com.stockportfolio.controller;

import com.stockportfolio.dto.AssetCurvePointResponse;
import com.stockportfolio.dto.HoldingResponse;
import com.stockportfolio.dto.MarketCloseSyncResponse;
import com.stockportfolio.dto.PeHistoryPointResponse;
import com.stockportfolio.dto.PriceHistoryBackfillResponse;
import com.stockportfolio.dto.PriceHistoryPointResponse;
import com.stockportfolio.dto.PriceRefreshResponse;
import com.stockportfolio.dto.PortfolioSummaryResponse;
import com.stockportfolio.dto.PortfolioExportResponse;
import com.stockportfolio.service.PortfolioService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping("/holdings")
    public List<HoldingResponse> holdings() {
        return portfolioService.getHoldings();
    }

    @GetMapping("/summary")
    public PortfolioSummaryResponse summary() {
        return portfolioService.getSummary();
    }

    @GetMapping("/export")
    public PortfolioExportResponse export() {
        return portfolioService.exportPortfolio();
    }

    @GetMapping("/asset-curve")
    public List<AssetCurvePointResponse> assetCurve() {
        return portfolioService.getAssetCurve();
    }

    @PostMapping("/prices/refresh")
    public PriceRefreshResponse refreshPrices() {
        return portfolioService.refreshPrices("MANUAL");
    }

    @PostMapping("/market-close/sync")
    public MarketCloseSyncResponse syncMarketClose() {
        return portfolioService.syncMarketClose("MANUAL_CLOSE");
    }

    @PostMapping("/history/prices/backfill")
    public PriceHistoryBackfillResponse backfillPriceHistory(
            @RequestParam(required = false) String symbols,
            @RequestParam(defaultValue = "10") int years
    ) {
        return portfolioService.backfillPriceHistory(symbols, years, "MANUAL_BACKFILL");
    }

    @PostMapping("/history/pe/backfill")
    public PriceHistoryBackfillResponse backfillPeHistory(
            @RequestParam(required = false) String symbols,
            @RequestParam(defaultValue = "10") int years
    ) {
        return portfolioService.backfillPeHistory(symbols, years, "MANUAL_PE_BACKFILL");
    }

    @GetMapping("/history/prices")
    public List<PriceHistoryPointResponse> priceHistory(
            @RequestParam String symbol,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to
    ) {
        return portfolioService.getPriceHistory(symbol, from, to);
    }

    @GetMapping("/history/pe")
    public List<PeHistoryPointResponse> peHistory(
            @RequestParam String symbol,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to
    ) {
        return portfolioService.getPeHistory(symbol, from, to);
    }
}
