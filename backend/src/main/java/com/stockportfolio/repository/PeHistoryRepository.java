package com.stockportfolio.repository;

import com.stockportfolio.model.PeHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PeHistoryRepository extends JpaRepository<PeHistory, Long> {
    Optional<PeHistory> findBySymbolAndTradeDate(String symbol, LocalDate tradeDate);
    List<PeHistory> findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(String symbol, LocalDate from, LocalDate to);
}
