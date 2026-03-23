package com.stockportfolio.repository;

import com.stockportfolio.model.Dividend;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DividendRepository extends JpaRepository<Dividend, Long> {
    List<Dividend> findAllByOrderByPaidDateAscIdAsc();
}
