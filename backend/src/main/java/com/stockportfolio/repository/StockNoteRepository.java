package com.stockportfolio.repository;

import com.stockportfolio.model.StockNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StockNoteRepository extends JpaRepository<StockNote, Long> {
    Optional<StockNote> findBySymbolIgnoreCase(String symbol);
    List<StockNote> findAllByOrderBySymbolAsc();
}
