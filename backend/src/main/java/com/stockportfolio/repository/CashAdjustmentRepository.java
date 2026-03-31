package com.stockportfolio.repository;

import com.stockportfolio.model.CashAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CashAdjustmentRepository extends JpaRepository<CashAdjustment, Long> {
    List<CashAdjustment> findAllByOrderByOccurredAtAscIdAsc();
    Optional<CashAdjustment> findByTransactionId(Long transactionId);
    List<CashAdjustment> findAllByTransactionIdIsNotNull();
    void deleteByTransactionId(Long transactionId);
}
