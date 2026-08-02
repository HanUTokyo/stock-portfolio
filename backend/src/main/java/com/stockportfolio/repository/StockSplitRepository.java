package com.stockportfolio.repository;

import com.stockportfolio.model.StockSplit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StockSplitRepository extends JpaRepository<StockSplit, Long> {
    List<StockSplit> findBySymbolAndSplitDateBetweenOrderBySplitDateAsc(String symbol, LocalDate from, LocalDate to);
    Optional<StockSplit> findBySymbolAndSplitDate(String symbol, LocalDate splitDate);
}
