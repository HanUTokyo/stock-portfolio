package com.stockportfolio.repository;

import com.stockportfolio.model.EarningsEstimate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EarningsEstimateRepository extends JpaRepository<EarningsEstimate, Long> {
    Optional<EarningsEstimate> findBySymbolAndPeriodTypeAndPeriodCode(String symbol, String periodType, String periodCode);
    List<EarningsEstimate> findBySymbolAndPeriodTypeOrderByPeriodEndDateAsc(String symbol, String periodType);
    void deleteBySymbol(String symbol);
}
