package com.stockportfolio.repository;

import com.stockportfolio.model.FundamentalNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FundamentalNoteRepository extends JpaRepository<FundamentalNote, Long> {
    Optional<FundamentalNote> findBySymbolIgnoreCase(String symbol);

    List<FundamentalNote> findAllByOrderBySymbolAsc();
}
