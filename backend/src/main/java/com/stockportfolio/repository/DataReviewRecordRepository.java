package com.stockportfolio.repository;

import com.stockportfolio.model.DataReviewRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DataReviewRecordRepository extends JpaRepository<DataReviewRecord, Long> {
    Optional<DataReviewRecord> findBySourceNameAndRecordId(String sourceName, String recordId);

    List<DataReviewRecord> findBySourceNameAndRecordIdIn(String sourceName, Collection<String> recordIds);

    List<DataReviewRecord> findBySourceNameAndRecordIdInAndReviewStatus(
            String sourceName,
            Collection<String> recordIds,
            String reviewStatus
    );

    List<DataReviewRecord> findBySourceNameAndReviewStatus(String sourceName, String reviewStatus);
}
