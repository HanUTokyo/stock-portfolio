package com.stockportfolio.repository;

import com.stockportfolio.model.Position;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PositionRepository extends JpaRepository<Position, Long> {
    Optional<Position> findBySymbolIgnoreCase(String symbol);
    List<Position> findBySymbolIn(List<String> symbols);
}
