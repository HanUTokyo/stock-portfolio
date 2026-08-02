package com.stockportfolio.repository;

import com.stockportfolio.model.DataReviewAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DataReviewAuditLogRepository extends JpaRepository<DataReviewAuditLog, Long> {
    List<DataReviewAuditLog> findBySourceNameAndRecordIdOrderByCreatedAtDesc(String sourceName, String recordId);
}
