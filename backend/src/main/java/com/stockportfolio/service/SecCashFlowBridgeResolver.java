package com.stockportfolio.service;

import com.stockportfolio.dto.CashFlowBridgeResponse;
import com.stockportfolio.model.FundamentalFactObservation;
import com.stockportfolio.repository.FundamentalFactObservationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Produces the safe first stage of the indirect-CFO bridge. Company Facts does
 * not carry statement presentation/calculation relationships, therefore this
 * resolver deliberately returns INCOMPLETE until a filing-instance extractor
 * supplies a non-overlapping detailed ledger. This prevents a generic
 * IncreaseDecreaseInOperatingCapital tag from being represented as verified
 * economic FCFF.
 */
@Component
public class SecCashFlowBridgeResolver {
    private static final MathContext MC = ValuationEngine.MC;
    private FundamentalFactObservationRepository observations;

    @Autowired(required = false)
    void setObservations(FundamentalFactObservationRepository observations) { this.observations = observations; }

    public CashFlowBridgeResponse resolve(String symbol, List<ValuationEngine.Quarter> rows, BigDecimal taxRatePct) {
        CashFlowBridgeResponse baseline = resolve(rows, taxRatePct);
        if (observations == null || rows == null || rows.isEmpty()) return baseline;
        LocalDate from = rows.get(Math.max(0, rows.size() - 4)).periodEnd().minusMonths(10);
        LocalDate to = rows.get(rows.size() - 1).periodEnd();
        List<FundamentalFactObservation> facts = observations
                .findBySymbolAndFieldNameStartingWithAndPeriodEndBetweenOrderByPeriodEndAscSourceDateDesc(
                        symbol, "xbrlCashFlowLeaf.", from, to);
        if (facts.isEmpty()) return baseline;
        // Company filings often restate comparative periods. Never add two vintages
        // of the same concept/context into a bridge ledger.
        Map<String, FundamentalFactObservation> latestByConceptAndContext = new LinkedHashMap<>();
        facts.stream().filter(this::isLatestPublication).forEach(fact -> {
            String key = fact.getFieldName() + "|" + fact.getPeriodStart() + "|" + fact.getPeriodEnd();
            latestByConceptAndContext.merge(key, fact, (left, right) -> Comparator
                    .comparing(FundamentalFactObservation::getSourceDate)
                    .thenComparing(FundamentalFactObservation::getAccessionNumber, Comparator.nullsFirst(String::compareTo))
                    .compare(left, right) >= 0 ? left : right);
        });
        LinkedHashSet<String> accessions = new LinkedHashSet<>();
        ArrayList<CashFlowBridgeResponse.LedgerEntry> ledger = new ArrayList<>(baseline.ledger());
        latestByConceptAndContext.values().forEach(fact -> {
            if (fact.getAccessionNumber() != null) accessions.add(fact.getAccessionNumber());
            ledger.add(new CashFlowBridgeResponse.LedgerEntry(fact.getXbrlBucket(), "Filing leaf: " + fact.getFieldName().replace("xbrlCashFlowLeaf.", ""),
                    fact.getValue(), "instance fact sign", fact.getFieldName().replace("xbrlCashFlowLeaf.", ""),
                    fact.getAccessionNumber(), "PRESENTATION_LEAF"));
        });
        Map<String, BigDecimal> quarterlyBuckets = quarterizedBucketTotals(latestByConceptAndContext.values(), rows);
        for (String bucket : List.of("AR_OR_RECEIVABLES", "INVENTORY", "AP_OR_PAYABLES",
                "OTHER_OPERATING_ASSETS", "OTHER_OPERATING_LIABILITIES")) {
            BigDecimal amount = quarterlyBuckets.get(bucket);
            ledger.add(new CashFlowBridgeResponse.LedgerEntry(bucket,
                    "TTM quarterized cash-flow effect (non-overlapping presentation leaves)", amount,
                    "cash inflow positive; economic delta NWC is the inverse", "FILING_CONTEXT_QUARTERIZATION",
                    null, amount == null ? "MISSING_QUARTER_SPAN" : "QUARTERIZED"));
        }
        Map<String, BigDecimal> quarterlyConcepts = quarterizedConceptTotals(latestByConceptAndContext.values(), rows);
        BigDecimal cashTaxes = quarterlyConcepts.get("IncomeTaxesPaidNet");
        BigDecimal sbc = quarterlyConcepts.get("ShareBasedCompensation");
        BigDecimal deferredTax = quarterlyConcepts.get("DeferredIncomeTaxExpenseBenefit");
        BigDecimal taxProvision = quarterlyConcepts.get("IncomeTaxExpenseBenefit");
        BigDecimal pretaxIncome = firstPresent(quarterlyConcepts,
                "IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest",
                "IncomeLossFromContinuingOperationsBeforeIncomeTaxes");
        BigDecimal interestExpense = firstPresent(quarterlyConcepts, "InterestExpense", "InterestExpenseNonoperating", "InterestAndDebtExpense");
        BigDecimal interestIncome = firstMatching(quarterlyConcepts, "interestincome");
        BigDecimal operatingIncome = sum(rows.subList(Math.max(0, rows.size() - 4), rows.size()), ValuationEngine.Quarter::operatingIncome);
        BigDecimal operatingCashTaxCandidate = cashTaxes == null || pretaxIncome == null || pretaxIncome.signum() == 0 || operatingIncome == null
                ? null : cashTaxes.multiply(operatingIncome, MC).divide(pretaxIncome.abs(), MC);
        ledger.add(new CashFlowBridgeResponse.LedgerEntry("CASH_TAX", "TTM cash taxes paid", cashTaxes,
                "cash outflow positive in SEC presentation", "IncomeTaxesPaidNet", null,
                cashTaxes == null ? "MISSING_QUARTER_SPAN" : "QUARTERIZED"));
        ledger.add(new CashFlowBridgeResponse.LedgerEntry("DEFERRED_TAX", "TTM deferred-tax adjustment", deferredTax,
                "instance fact sign", "DeferredIncomeTaxExpenseBenefit", null,
                deferredTax == null ? "MISSING_OR_UNMAPPED" : "QUARTERIZED"));
        ledger.add(new CashFlowBridgeResponse.LedgerEntry("TAX_PROVISION", "TTM tax provision", taxProvision,
                "expense positive", "IncomeTaxExpenseBenefit", null, taxProvision == null ? "MISSING" : "QUARTERIZED"));
        ledger.add(new CashFlowBridgeResponse.LedgerEntry("PRETAX_INCOME", "TTM pretax income", pretaxIncome,
                "income positive", "IncomeLossFromContinuingOperationsBeforeIncomeTaxes", null, pretaxIncome == null ? "MISSING" : "QUARTERIZED"));
        ledger.add(new CashFlowBridgeResponse.LedgerEntry("INTEREST_EXPENSE", "TTM interest expense disclosure", interestExpense,
                "expense positive", "InterestExpense", null, interestExpense == null ? "MISSING" : "QUARTERIZED"));
        ledger.add(new CashFlowBridgeResponse.LedgerEntry("INTEREST_INCOME", "TTM interest income disclosure", interestIncome,
                "income positive", "InterestIncome", null, interestIncome == null ? "MISSING" : "QUARTERIZED"));
        ledger.add(new CashFlowBridgeResponse.LedgerEntry("OPERATING_CASH_TAX_CANDIDATE", "Cash tax allocated to EBIT by pretax-income ratio", operatingCashTaxCandidate,
                "cash tax outflow positive", "IncomeTaxesPaidNet", null, operatingCashTaxCandidate == null ? "MISSING" : "PROXY_UNVERIFIED"));
        ledger.add(new CashFlowBridgeResponse.LedgerEntry("SBC", "TTM share-based compensation", sbc,
                "economic expense retained in EBIT; never added back", "ShareBasedCompensation", null,
                sbc == null ? "MISSING_QUARTER_SPAN" : "QUARTERIZED"));
        ArrayList<String> warnings = new ArrayList<>(baseline.warnings());
        warnings.add("Filing-scoped presentation leaf facts are deduplicated by concept/context; YTD-to-quarter conversion and the tax-basis bridge must still pass before economic FCFF is COMPLETE.");
        ArrayList<String> missing = new ArrayList<>(baseline.missingInputs());
        missing.remove("secFilingPresentationAndCalculationRelationships");
        if (sbc != null) missing.remove("shareBasedCompensationBridge");
        return new CashFlowBridgeResponse(baseline.coverageStatus(), baseline.primaryStatus(), baseline.economicFcff(),
                baseline.cashFcffReferenceOnly(), baseline.provisionalOperatingFcff(), baseline.residual(), baseline.reconciliationDifferencePct(),
                List.copyOf(accessions), List.copyOf(ledger), List.copyOf(missing), List.copyOf(warnings));
    }

    /**
     * Converts direct-quarter or cumulative YTD filing facts into the four actual quarters
     * selected by valuation. A missing predecessor deliberately yields no value rather than a
     * multi-quarter amount masquerading as a quarter.
     */
    private Map<String, BigDecimal> quarterizedBucketTotals(Iterable<FundamentalFactObservation> facts,
                                                            List<ValuationEngine.Quarter> rows) {
        List<LocalDate> targetEnds = rows.subList(Math.max(0, rows.size() - 4), rows.size()).stream()
                .map(ValuationEngine.Quarter::periodEnd).toList();
        Map<String, List<FundamentalFactObservation>> byConcept = new HashMap<>();
        facts.forEach(fact -> byConcept.computeIfAbsent(fact.getFieldName(), ignored -> new ArrayList<>()).add(fact));
        Map<String, BigDecimal> result = new HashMap<>();
        for (List<FundamentalFactObservation> conceptFacts : byConcept.values()) {
            String bucket = conceptFacts.getFirst().getXbrlBucket();
            if (!List.of("AR_OR_RECEIVABLES", "INVENTORY", "AP_OR_PAYABLES", "OTHER_OPERATING_ASSETS", "OTHER_OPERATING_LIABILITIES").contains(bucket)) continue;
            BigDecimal ttm = BigDecimal.ZERO;
            boolean complete = true;
            for (LocalDate end : targetEnds) {
                FundamentalFactObservation current = conceptFacts.stream().filter(f -> end.equals(f.getPeriodEnd()))
                        .max(Comparator.comparing(FundamentalFactObservation::getSourceDate)).orElse(null);
                if (current == null || current.getPeriodStart() == null) { complete = false; break; }
                long days = ChronoUnit.DAYS.between(current.getPeriodStart(), current.getPeriodEnd()) + 1;
                BigDecimal quarter = current.getValue();
                if (days > 110) {
                    FundamentalFactObservation previous = conceptFacts.stream()
                            .filter(f -> current.getPeriodStart().equals(f.getPeriodStart()))
                            .filter(f -> f.getPeriodEnd().isBefore(end))
                            .max(Comparator.comparing(FundamentalFactObservation::getPeriodEnd)
                                    .thenComparing(FundamentalFactObservation::getSourceDate)).orElse(null);
                    if (previous == null) { complete = false; break; }
                    quarter = current.getValue().subtract(previous.getValue(), MC);
                }
                ttm = ttm.add(quarter, MC);
            }
            if (complete) result.merge(bucket, ttm, BigDecimal::add);
        }
        return result;
    }

    private Map<String, BigDecimal> quarterizedConceptTotals(Iterable<FundamentalFactObservation> facts,
                                                              List<ValuationEngine.Quarter> rows) {
        List<LocalDate> targetEnds = rows.subList(Math.max(0, rows.size() - 4), rows.size()).stream()
                .map(ValuationEngine.Quarter::periodEnd).toList();
        Map<String, List<FundamentalFactObservation>> byConcept = new HashMap<>();
        facts.forEach(fact -> byConcept.computeIfAbsent(fact.getFieldName().replace("xbrlCashFlowLeaf.", ""), ignored -> new ArrayList<>()).add(fact));
        Map<String, BigDecimal> result = new HashMap<>();
        for (Map.Entry<String, List<FundamentalFactObservation>> entry : byConcept.entrySet()) {
            BigDecimal ttm = quarterizedTtm(entry.getValue(), targetEnds);
            if (ttm != null) result.put(entry.getKey(), ttm);
        }
        return result;
    }

    private BigDecimal quarterizedTtm(List<FundamentalFactObservation> facts, List<LocalDate> targetEnds) {
        BigDecimal ttm = BigDecimal.ZERO;
        for (LocalDate end : targetEnds) {
            FundamentalFactObservation current = facts.stream().filter(f -> end.equals(f.getPeriodEnd()))
                    .max(Comparator.comparing(FundamentalFactObservation::getSourceDate)).orElse(null);
            if (current == null || current.getPeriodStart() == null) return null;
            BigDecimal quarter = current.getValue();
            if (ChronoUnit.DAYS.between(current.getPeriodStart(), current.getPeriodEnd()) + 1 > 110) {
                FundamentalFactObservation previous = facts.stream().filter(f -> current.getPeriodStart().equals(f.getPeriodStart()))
                        .filter(f -> f.getPeriodEnd().isBefore(end)).max(Comparator.comparing(FundamentalFactObservation::getPeriodEnd)
                                .thenComparing(FundamentalFactObservation::getSourceDate)).orElse(null);
                if (previous == null) return null;
                quarter = quarter.subtract(previous.getValue(), MC);
            }
            ttm = ttm.add(quarter, MC);
        }
        return ttm;
    }

    private BigDecimal firstPresent(Map<String, BigDecimal> values, String... concepts) {
        for (String concept : concepts) if (values.get(concept) != null) return values.get(concept);
        return null;
    }

    private BigDecimal firstMatching(Map<String, BigDecimal> values, String token) {
        return values.entrySet().stream().filter(entry -> entry.getKey().toLowerCase(java.util.Locale.ROOT).contains(token))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    public CashFlowBridgeResponse resolve(List<ValuationEngine.Quarter> rows, BigDecimal taxRatePct) {
        if (rows == null || rows.size() < 4) return unavailable(List.of("fourConsecutiveActualQuarters"));
        List<ValuationEngine.Quarter> latest = rows.subList(rows.size() - 4, rows.size());
        BigDecimal cfo = sum(latest, ValuationEngine.Quarter::cfo);
        BigDecimal ebit = sum(latest, ValuationEngine.Quarter::operatingIncome);
        BigDecimal da = sum(latest, ValuationEngine.Quarter::depreciationAmortization);
        BigDecimal capex = sum(latest, ValuationEngine.Quarter::capex);
        BigDecimal nwc = sum(latest, ValuationEngine.Quarter::changeInWorkingCapital);
        BigDecimal interest = sum(latest, ValuationEngine.Quarter::interestExpense);
        BigDecimal tax = taxRatePct == null ? null : taxRatePct.divide(BigDecimal.valueOf(100), MC);
        BigDecimal economic = ebit == null || tax == null || da == null || capex == null || nwc == null ? null
                : ebit.multiply(BigDecimal.ONE.subtract(tax), MC).add(da, MC).subtract(capex, MC).subtract(nwc, MC);
        BigDecimal cashReference = cfo == null || capex == null || interest == null || tax == null ? null
                : cfo.add(interest.multiply(BigDecimal.ONE.subtract(tax), MC), MC).subtract(capex, MC);
        BigDecimal residual = cashReference == null || economic == null ? null : cashReference.subtract(economic, MC);
        BigDecimal difference = cashReference == null || economic == null || cashReference.signum() == 0 ? null
                : residual.abs().divide(cashReference.abs(), MC).multiply(BigDecimal.valueOf(100));
        List<CashFlowBridgeResponse.LedgerEntry> ledger = new ArrayList<>();
        ledger.add(entry("CFO", "Reported cash from operations", cfo, "cash inflow positive", "NetCashProvidedByUsedInOperatingActivities", "REPORTED"));
        ledger.add(entry("UNLEVERING", "After-tax accrued interest adjustment (reference only)", interest == null || tax == null ? null : interest.multiply(BigDecimal.ONE.subtract(tax), MC), "add to CFO", "InterestExpense", "PROXY"));
        ledger.add(entry("EBIT", "Operating income", ebit, "income positive", "OperatingIncomeLoss", "REPORTED"));
        ledger.add(entry("TAX", "Normalised tax on EBIT", ebit == null || tax == null ? null : ebit.multiply(tax, MC).negate(), "tax outflow negative", "IncomeTaxExpenseBenefit", "PROXY"));
        ledger.add(entry("D_AND_A", "Depreciation and amortisation", da, "add back non-cash charge", "DepreciationDepletionAndAmortization", "REPORTED"));
        ledger.add(entry("SBC", "Share-based compensation", null, "economic expense retained in EBIT; not added back", "ShareBasedCompensation", "MISSING"));
        ledger.add(entry("OPERATING_NWC", "Accounting change in operating NWC", nwc == null ? null : nwc.negate(), "increase in NWC is a cash outflow", "IncreaseDecreaseInOperatingCapital", "UNVERIFIED_AGGREGATE"));
        ledger.add(entry("CAPEX", "Capital expenditure", capex == null ? null : capex.negate(), "cash outflow negative", "PaymentsToAcquirePropertyPlantAndEquipment", "REPORTED"));
        ledger.add(entry("RESIDUAL", "Unexplained CFO-to-economic-FCFF residual", residual, "cash reference minus economic FCFF", "SEC_STATEMENT_LINKBASE_REQUIRED", "UNEXPLAINED"));
        List<String> missing = new ArrayList<>();
        if (cfo == null) missing.add("operatingCashFlow");
        if (ebit == null) missing.add("operatingIncome");
        if (da == null) missing.add("depreciationAmortization");
        if (capex == null) missing.add("capex");
        if (nwc == null) missing.add("changeInWorkingCapital");
        if (tax == null) missing.add("cashOperatingTaxRate");
        missing.add("secFilingPresentationAndCalculationRelationships");
        missing.add("nonOverlappingArApContractLiabilityAndOtherOperatingWorkingCapital");
        missing.add("cashTaxesDeferredTaxAndNonOperatingTaxBridge");
        missing.add("shareBasedCompensationBridge");
        List<String> warnings = List.of(
                "CASH_FCFF_REFERENCE_ONLY: CFO + after-tax accrued interest - capex is not a verified economic FCFF.",
                "Economic FCFF is blocked until SEC filing statement relationships provide non-overlapping indirect-CFO and operating-NWC components.",
                "SBC remains an economic cost in EBIT and is not added back to FCFF.");
        // The aggregate company-facts NWC value is an audit aid only. Until the filing-level
        // bridge passes, it must not be exposed as economic FCFF.
        return new CashFlowBridgeResponse("INCOMPLETE", "CASH_FCFF_REFERENCE_ONLY", null, cashReference,
                economic, residual, difference, List.of(), List.copyOf(ledger), List.copyOf(missing), warnings);
    }

    private CashFlowBridgeResponse unavailable(List<String> missing) {
        return new CashFlowBridgeResponse("INCOMPLETE", "CASH_FCFF_REFERENCE_ONLY", null, null, null, null,
                null, List.of(), List.of(), missing, List.of("A four-quarter SEC indirect-CFO bridge is required."));
    }
    private CashFlowBridgeResponse.LedgerEntry entry(String bucket, String label, BigDecimal amount, String sign, String concept, String status) {
        return new CashFlowBridgeResponse.LedgerEntry(bucket, label, amount, sign, concept, null, status);
    }
    private BigDecimal sum(List<ValuationEngine.Quarter> rows, java.util.function.Function<ValuationEngine.Quarter, BigDecimal> getter) {
        BigDecimal total = BigDecimal.ZERO;
        for (ValuationEngine.Quarter row : rows) { BigDecimal value = getter.apply(row); if (value == null) return null; total = total.add(value, MC); }
        return total;
    }
    private boolean isLatestPublication(FundamentalFactObservation fact) {
        // Repository ordering is deterministic by period/filed date. Keep one value for
        // each concept/context/accession period; a later accession does not get summed.
        return fact.getPeriodStart() != null && fact.getXbrlBucket() != null;
    }
}
