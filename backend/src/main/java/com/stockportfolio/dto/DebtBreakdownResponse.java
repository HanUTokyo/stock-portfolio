package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.util.List;

public record DebtBreakdownResponse(
        String status,
        BigDecimal totalDebt,
        BigDecimal commercialPaper,
        BigDecimal currentTermDebt,
        BigDecimal noncurrentTermDebt,
        BigDecimal otherDebtLikeItems,
        BigDecimal cashAndEquivalents,
        BigDecimal shortTermInvestments,
        BigDecimal noncurrentMarketableSecurities,
        BigDecimal netDebt,
        List<String> missingInputs,
        String coverageStatus, String selectedRoute, java.time.LocalDate periodEnd, java.time.LocalDate filedDate,
        List<DebtComponentResponse> components, List<String> sourceConcepts, List<String> accessionNumbers, List<String> warnings
) { public DebtBreakdownResponse(String status, BigDecimal totalDebt, BigDecimal commercialPaper, BigDecimal currentTermDebt, BigDecimal noncurrentTermDebt, BigDecimal otherDebtLikeItems, BigDecimal cashAndEquivalents, BigDecimal shortTermInvestments, BigDecimal noncurrentMarketableSecurities, BigDecimal netDebt, List<String> missingInputs) { this(status,totalDebt,commercialPaper,currentTermDebt,noncurrentTermDebt,otherDebtLikeItems,cashAndEquivalents,shortTermInvestments,noncurrentMarketableSecurities,netDebt,missingInputs,status,null,null,null,List.of(),List.of(),List.of(),List.of()); } }
