package com.stockportfolio.repository;

import com.stockportfolio.model.FundamentalFactObservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface FundamentalFactObservationRepository extends JpaRepository<FundamentalFactObservation, Long> {
    List<FundamentalFactObservation> findBySymbolAndFieldNameAndSourceDateLessThanEqualOrderByPeriodEndAscSourceDateAsc(
            String symbol, String fieldName, LocalDate sourceDate);
    List<FundamentalFactObservation> findBySymbolAndFieldNameAndPeriodEndBetweenOrderByPeriodEndAscSourceDateDesc(
            String symbol, String fieldName, LocalDate from, LocalDate to);
    List<FundamentalFactObservation> findBySymbolAndFieldNameStartingWithAndPeriodEndBetweenOrderByPeriodEndAscSourceDateDesc(
            String symbol, String fieldNamePrefix, LocalDate from, LocalDate to);
    boolean existsBySymbolAndPeriodEndAndFieldNameAndSourceDateAndAccessionNumberAndUnit(
            String symbol, LocalDate periodEnd, String fieldName, LocalDate sourceDate,
            String accessionNumber, String unit);
}
