package com.stockportfolio.repository;

import com.stockportfolio.model.Position;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PositionRepository extends JpaRepository<Position, Long> {
    Optional<Position> findBySymbolIgnoreCase(String symbol);
    List<Position> findBySymbolIn(List<String> symbols);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Position p order by p.id")
    List<Position> findAllForMarketSync();
}
