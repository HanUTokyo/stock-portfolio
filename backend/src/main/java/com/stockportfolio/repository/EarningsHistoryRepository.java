package com.stockportfolio.repository;

import com.stockportfolio.model.EarningsHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface EarningsHistoryRepository extends JpaRepository<EarningsHistory, Long> {
    Optional<EarningsHistory> findBySymbolAndAsOfDate(String symbol, LocalDate asOfDate);
    List<EarningsHistory> findBySymbolAndAsOfDateBetweenOrderByAsOfDateAsc(String symbol, LocalDate from, LocalDate to);
    List<EarningsHistory> findBySymbolAndAsOfDateLessThanEqualOrderByAsOfDateAsc(String symbol, LocalDate to);
    Optional<EarningsHistory> findTopBySymbolAndAsOfDateLessThanEqualOrderByAsOfDateDesc(String symbol, LocalDate asOfDate);
}
