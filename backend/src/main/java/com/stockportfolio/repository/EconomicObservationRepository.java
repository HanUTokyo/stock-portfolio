package com.stockportfolio.repository;

import com.stockportfolio.model.EconomicObservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface EconomicObservationRepository extends JpaRepository<EconomicObservation, Long> {
    Optional<EconomicObservation> findBySeriesIdAndObservationDate(String seriesId, LocalDate observationDate);
    Optional<EconomicObservation> findTopBySeriesIdOrderByObservationDateDesc(String seriesId);
    List<EconomicObservation> findBySeriesIdAndObservationDateBetweenOrderByObservationDateAsc(
            String seriesId, LocalDate from, LocalDate to);
}
