package com.stockportfolio.repository;

import com.stockportfolio.model.SecShareCountEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface SecShareCountEvidenceRepository extends JpaRepository<SecShareCountEvidence, Long> {
    List<SecShareCountEvidence> findBySymbolAndPeriodEndBetweenOrderByPeriodEndAsc(String symbol, LocalDate from, LocalDate to);
    void deleteBySymbolAndPeriodEndBetween(String symbol, LocalDate from, LocalDate to);
}
