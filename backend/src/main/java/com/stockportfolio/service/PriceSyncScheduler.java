package com.stockportfolio.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PriceSyncScheduler {

    private final PortfolioService portfolioService;

    public PriceSyncScheduler(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @Scheduled(cron = "${app.pricing.open-cron:0 35 9 * * MON-FRI}", zone = "${app.pricing.timezone:America/New_York}")
    public void refreshAtOpen() {
        portfolioService.refreshPrices("MARKET_OPEN");
    }

    @Scheduled(cron = "${app.pricing.close-cron:0 5 16 * * MON-FRI}", zone = "${app.pricing.timezone:America/New_York}")
    public void refreshAtClose() {
        portfolioService.syncMarketClose("MARKET_CLOSE");
    }
}
