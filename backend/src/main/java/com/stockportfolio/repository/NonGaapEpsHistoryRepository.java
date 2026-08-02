package com.stockportfolio.repository;

import com.stockportfolio.model.NonGaapEpsHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface NonGaapEpsHistoryRepository extends JpaRepository<NonGaapEpsHistory, Long> {
    List<NonGaapEpsHistory> findBySymbolAndAsOfDateLessThanEqualOrderByAsOfDateAsc(String symbol, LocalDate to);
}
