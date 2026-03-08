package com.stockportfolio.repository;

import com.stockportfolio.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findAllByOrderByExecutedAtAscIdAsc();
}
