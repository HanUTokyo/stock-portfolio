package com.stockportfolio.repository;

import com.stockportfolio.model.ValuationNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ValuationNoteRepository extends JpaRepository<ValuationNote, Long> {
    Optional<ValuationNote> findBySymbolIgnoreCase(String symbol);
    List<ValuationNote> findAllByOrderBySymbolAsc();
}
