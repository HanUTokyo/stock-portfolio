package com.stockportfolio.dto;
import java.math.BigDecimal; import java.util.List;
public record DebtComponentResponse(String componentType, BigDecimal amount, List<String> concepts, List<String> accessions) { }
