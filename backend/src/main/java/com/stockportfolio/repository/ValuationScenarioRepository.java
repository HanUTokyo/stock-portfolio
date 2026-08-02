package com.stockportfolio.repository;

import com.stockportfolio.model.ValuationScenario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ValuationScenarioRepository extends JpaRepository<ValuationScenario, Long> {
    List<ValuationScenario> findBySymbolOrderByScenarioTypeAsc(String symbol);
    Optional<ValuationScenario> findBySymbolAndScenarioType(String symbol, String scenarioType);
    void deleteBySymbolAndScenarioType(String symbol, String scenarioType);
}
