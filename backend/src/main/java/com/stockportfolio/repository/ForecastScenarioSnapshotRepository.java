package com.stockportfolio.repository;

import com.stockportfolio.model.ForecastScenarioSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ForecastScenarioSnapshotRepository extends JpaRepository<ForecastScenarioSnapshot, Long> {
    Optional<ForecastScenarioSnapshot> findBySymbol(String symbol);
}
