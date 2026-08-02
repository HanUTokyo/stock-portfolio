package com.stockportfolio.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PriceSyncScheduler {

    private final PortfolioService portfolioService;
    private final boolean fundamentalsBackfillEnabled;
    private final int fundamentalsBackfillYears;

    public PriceSyncScheduler(PortfolioService portfolioService,
                              @Value("${app.fundamentals.backfill-missing-enabled:true}") boolean fundamentalsBackfillEnabled,
                              @Value("${app.fundamentals.backfill-missing-years:15}") int fundamentalsBackfillYears) {
        this.portfolioService = portfolioService;
        this.fundamentalsBackfillEnabled = fundamentalsBackfillEnabled;
        this.fundamentalsBackfillYears = fundamentalsBackfillYears;
    }

    @Scheduled(cron = "${app.pricing.open-cron:0 35 9 * * MON-FRI}", zone = "${app.pricing.timezone:America/New_York}")
    public void refreshAtOpen() {
        portfolioService.refreshPrices("MARKET_OPEN");
    }

    @Scheduled(cron = "${app.pricing.close-cron:0 5 16 * * MON-FRI}", zone = "${app.pricing.timezone:America/New_York}")
    public void refreshAtClose() {
        portfolioService.syncMarketClose("MARKET_CLOSE");
    }

    @Scheduled(cron = "${app.fundamentals.backfill-missing-cron:0 30 3 * * *}", zone = "${app.pricing.timezone:America/New_York}")
    public void backfillMissingFundamentals() {
        if (!fundamentalsBackfillEnabled) {
            return;
        }
        portfolioService.backfillMissingFundamentals(
                null,
                fundamentalsBackfillYears,
                "SCHEDULED_FUNDAMENTALS_BACKFILL_MISSING"
        );
    }
}
