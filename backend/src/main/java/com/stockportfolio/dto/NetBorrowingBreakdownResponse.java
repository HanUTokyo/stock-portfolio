package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.util.List;

public record NetBorrowingBreakdownResponse(
        String status,
        BigDecimal totalNetBorrowing,
        BigDecimal commercialPaperNetBorrowing,
        BigDecimal otherShortTermNetBorrowing,
        BigDecimal longTermNetBorrowing,
        List<String> missingInputs, String coverageStatus, String selectedRoute, java.time.LocalDate periodEnd,
        List<DebtComponentResponse> components, List<String> sourceConcepts, List<String> accessionNumbers,
        String quarterizationMethod, List<String> warnings
) { public NetBorrowingBreakdownResponse(String status, BigDecimal totalNetBorrowing, BigDecimal commercialPaperNetBorrowing, BigDecimal otherShortTermNetBorrowing, BigDecimal longTermNetBorrowing, List<String> missingInputs) { this(status,totalNetBorrowing,commercialPaperNetBorrowing,otherShortTermNetBorrowing,longTermNetBorrowing,missingInputs,status,null,null,List.of(),List.of(),List.of(),null,List.of()); } }
