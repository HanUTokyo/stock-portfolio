package com.stockportfolio.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves SEC debt facts without adding overlapping XBRL concepts together.
 *
 * <p>The resolver intentionally keeps coverage and route metadata internal. The existing quarterly
 * fundamentals API remains compatible; {@link SecCompanyFactsService} publishes only COMPLETE
 * results and lets the existing provider fallback handle incomplete SEC coverage.</p>
 */
final class SecDebtResolver {
    enum Coverage { COMPLETE, INCOMPLETE }

    enum Route {
        COMBINED_DEBT,
        CURRENT_DEBT_PLUS_NONCURRENT,
        SHORT_TERM_PLUS_TERM_COMPONENTS,
        SHORT_TERM_PLUS_LONG_TERM,
        PARTIAL_DEBT,
        AGGREGATE_NET_BORROWING,
        AGGREGATE_GROSS_BORROWING,
        COMPONENT_NET_BORROWING
    }

    record Metric(
            LocalDate asOfDate,
            BigDecimal value,
            Coverage coverage,
            Route route,
            LocalDate filed,
            Integer fiscalYear,
            String fiscalPeriod,
            String accessionNumber,
            String form,
            Set<String> concepts,
            List<Evidence> evidence,
            LocalDate sourceStart,
            LocalDate sourceEnd,
            String quarterizationMethod
    ) { }
    record Evidence(String componentType, BigDecimal amount, Set<String> concepts, String accessions, LocalDate sourceStart, LocalDate sourceEnd) { }

    record Resolution(List<Metric> totalDebt, List<Metric> netBorrowing) { }

    private static final List<String> COMBINED_DEBT = List.of(
            "DebtLongtermAndShorttermCombinedAmount"
    );
    private static final List<String> CURRENT_DEBT = List.of("DebtCurrent");
    private static final List<String> BROAD_SHORT_TERM_DEBT = List.of("ShortTermBorrowings");
    private static final List<String> COMMERCIAL_PAPER = List.of("CommercialPaper");
    private static final List<String> OTHER_SHORT_TERM_DEBT = List.of("OtherShortTermBorrowings");
    private static final List<String> SHORT_TERM_DEBT = orderedList(
            BROAD_SHORT_TERM_DEBT, COMMERCIAL_PAPER, OTHER_SHORT_TERM_DEBT
    );
    private static final List<String> CURRENT_TERM_DEBT = List.of(
            "LongTermDebtAndFinanceLeaseObligationsCurrent",
            "LongTermDebtAndCapitalLeaseObligationsCurrent",
            "LongTermDebtCurrent"
    );
    private static final List<String> NONCURRENT_TERM_DEBT = List.of(
            "LongTermDebtAndFinanceLeaseObligationsNoncurrent",
            "LongTermDebtNoncurrent"
    );
    private static final List<String> LONG_TERM_DEBT = List.of(
            "LongTermDebtAndFinanceLeaseObligations",
            "LongTermDebtAndCapitalLeaseObligationsIncludingCurrentMaturities",
            "LongTermDebt"
    );

    private static final List<String> AGGREGATE_SIGNED_NET = List.of(
            "ProceedsFromRepaymentsOfDebt"
    );
    private static final List<String> AGGREGATE_ISSUANCE = List.of(
            "ProceedsFromIssuanceOfDebt"
    );
    private static final List<String> AGGREGATE_REPAYMENT = List.of(
            "RepaymentsOfDebt"
    );
    private static final List<String> LONG_TERM_SIGNED_NET = List.of(
            "ProceedsFromRepaymentsOfLongTermDebtAndCapitalSecurities",
            "ProceedsFromRepaymentsOfOtherLongTermDebt"
    );
    private static final List<String> LONG_TERM_ISSUANCE = List.of(
            "ProceedsFromIssuanceOfLongTermDebt",
            "ProceedsFromIssuanceOfOtherLongTermDebt",
            "ProceedsFromIssuanceOfSeniorLongTermDebt",
            "ProceedsFromIssuanceOfUnsecuredDebt"
    );
    private static final List<String> LONG_TERM_REPAYMENT = List.of(
            "RepaymentsOfLongTermDebt",
            "RepaymentsOfLongTermDebtAndCapitalSecurities"
    );
    private static final List<String> BROAD_SHORT_TERM_SIGNED_NET = List.of(
            "ProceedsFromRepaymentsOfShortTermDebt"
    );
    private static final List<String> BROAD_SHORT_TERM_ISSUANCE = List.of("ProceedsFromShortTermDebt");
    private static final List<String> BROAD_SHORT_TERM_REPAYMENT = List.of("RepaymentsOfShortTermDebt");
    private static final List<String> COMMERCIAL_PAPER_SIGNED_NET = List.of(
            "ProceedsFromRepaymentsOfCommercialPaper"
    );
    private static final List<String> COMMERCIAL_PAPER_ISSUANCE = List.of(
            "ProceedsFromIssuanceOfCommercialPaper"
    );
    private static final List<String> COMMERCIAL_PAPER_REPAYMENT = List.of("RepaymentsOfCommercialPaper");
    private static final List<String> OTHER_SHORT_TERM_ISSUANCE = List.of("ProceedsFromOtherShortTermDebt");
    private static final List<String> OTHER_SHORT_TERM_REPAYMENT = List.of("RepaymentsOfOtherShortTermDebt");

    private static final Set<String> ALL_BALANCE_CONCEPTS = orderedSet(
            COMBINED_DEBT, CURRENT_DEBT, SHORT_TERM_DEBT, CURRENT_TERM_DEBT,
            NONCURRENT_TERM_DEBT, LONG_TERM_DEBT
    );
    private static final Set<String> ALL_FLOW_CONCEPTS = orderedSet(
            AGGREGATE_SIGNED_NET, AGGREGATE_ISSUANCE, AGGREGATE_REPAYMENT,
            LONG_TERM_SIGNED_NET, LONG_TERM_ISSUANCE, LONG_TERM_REPAYMENT,
            BROAD_SHORT_TERM_SIGNED_NET, BROAD_SHORT_TERM_ISSUANCE, BROAD_SHORT_TERM_REPAYMENT,
            COMMERCIAL_PAPER_SIGNED_NET, COMMERCIAL_PAPER_ISSUANCE, COMMERCIAL_PAPER_REPAYMENT,
            OTHER_SHORT_TERM_ISSUANCE, OTHER_SHORT_TERM_REPAYMENT
    );

    private SecDebtResolver() { }

    static Resolution resolve(JsonNode usGaap, LocalDate from, LocalDate to) {
        Map<String, Map<LocalDate, Fact>> instantFacts = new LinkedHashMap<>();
        for (String concept : ALL_BALANCE_CONCEPTS) {
            instantFacts.put(concept, readInstantFacts(usGaap, concept));
        }

        Map<String, Map<Span, Fact>> flowFacts = new LinkedHashMap<>();
        for (String concept : ALL_FLOW_CONCEPTS) {
            flowFacts.put(concept, readFlowFacts(usGaap, concept));
        }

        return new Resolution(
                resolveBalances(instantFacts, from, to),
                quarterize(resolveFlowSpans(flowFacts, instantFacts), from, to)
        );
    }

    private static List<Metric> resolveBalances(Map<String, Map<LocalDate, Fact>> facts,
                                                LocalDate from,
                                                LocalDate to) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        facts.values().forEach(byDate -> dates.addAll(byDate.keySet()));
        boolean issuerHasShortTermDebt = hasAny(facts, SHORT_TERM_DEBT);
        boolean issuerHasLongTermDebt = hasAny(facts, CURRENT_TERM_DEBT)
                || hasAny(facts, NONCURRENT_TERM_DEBT) || hasAny(facts, LONG_TERM_DEBT);

        List<Metric> result = new ArrayList<>();
        dates.stream().filter(date -> !date.isBefore(from) && !date.isAfter(to)).sorted().forEach(date -> {
            Resolved resolved = resolveBalanceAt(facts, date, issuerHasShortTermDebt, issuerHasLongTermDebt);
            if (resolved != null) result.add(toMetric(date, resolved));
        });
        return List.copyOf(result);
    }

    private static Resolved resolveBalanceAt(Map<String, Map<LocalDate, Fact>> facts,
                                             LocalDate date,
                                             boolean issuerHasShortTermDebt,
                                             boolean issuerHasLongTermDebt) {
        Fact combined = firstAt(facts, date, COMBINED_DEBT);
        if (combined != null) {
            return resolved(combined.value(), Coverage.COMPLETE, Route.COMBINED_DEBT, List.of(combined));
        }

        Fact debtCurrent = firstAt(facts, date, CURRENT_DEBT);
        Component shortTerm = resolveShortTermBalance(facts, date);
        Fact currentTerm = firstAt(facts, date, CURRENT_TERM_DEBT);
        Fact noncurrentTerm = firstAt(facts, date, NONCURRENT_TERM_DEBT);
        Fact longTerm = firstAt(facts, date, LONG_TERM_DEBT);

        if (debtCurrent != null && noncurrentTerm != null) {
            return resolved(debtCurrent.value().add(noncurrentTerm.value()), Coverage.COMPLETE,
                    Route.CURRENT_DEBT_PLUS_NONCURRENT, List.of(debtCurrent, noncurrentTerm));
        }
        if (shortTerm != null && currentTerm != null && noncurrentTerm != null) {
            List<Fact> used = new ArrayList<>(shortTerm.facts());
            used.add(currentTerm);
            used.add(noncurrentTerm);
            return resolved(shortTerm.value().add(currentTerm.value()).add(noncurrentTerm.value()), Coverage.COMPLETE,
                    Route.SHORT_TERM_PLUS_TERM_COMPONENTS, used);
        }
        if (shortTerm != null && longTerm != null) {
            List<Fact> used = new ArrayList<>(shortTerm.facts());
            used.add(longTerm);
            return resolved(shortTerm.value().add(longTerm.value()), Coverage.COMPLETE,
                    Route.SHORT_TERM_PLUS_LONG_TERM, used);
        }
        if (currentTerm != null && noncurrentTerm != null) {
            Coverage coverage = issuerHasShortTermDebt ? Coverage.INCOMPLETE : Coverage.COMPLETE;
            return resolved(currentTerm.value().add(noncurrentTerm.value()), coverage,
                    Route.PARTIAL_DEBT, List.of(currentTerm, noncurrentTerm));
        }
        if (longTerm != null) {
            Coverage coverage = issuerHasShortTermDebt ? Coverage.INCOMPLETE : Coverage.COMPLETE;
            return resolved(longTerm.value(), coverage, Route.PARTIAL_DEBT, List.of(longTerm));
        }
        if (debtCurrent != null) {
            Coverage coverage = issuerHasLongTermDebt ? Coverage.INCOMPLETE : Coverage.COMPLETE;
            return resolved(debtCurrent.value(), coverage, Route.PARTIAL_DEBT, List.of(debtCurrent));
        }
        if (shortTerm != null) {
            Coverage coverage = issuerHasLongTermDebt ? Coverage.INCOMPLETE : Coverage.COMPLETE;
            return resolved(shortTerm.value(), coverage, Route.PARTIAL_DEBT, shortTerm.facts());
        }
        if (currentTerm != null || noncurrentTerm != null) {
            Fact available = currentTerm == null ? noncurrentTerm : currentTerm;
            return resolved(available.value(), Coverage.INCOMPLETE, Route.PARTIAL_DEBT, List.of(available));
        }
        return null;
    }

    private static Component resolveShortTermBalance(Map<String, Map<LocalDate, Fact>> facts, LocalDate date) {
        Fact broad = firstAt(facts, date, BROAD_SHORT_TERM_DEBT);
        if (broad != null) return new Component(broad.value(), Coverage.COMPLETE, List.of(broad));

        Fact commercialPaper = firstAt(facts, date, COMMERCIAL_PAPER);
        Fact other = firstAt(facts, date, OTHER_SHORT_TERM_DEBT);
        if (commercialPaper == null && other == null) return null;
        BigDecimal value = (commercialPaper == null ? BigDecimal.ZERO : commercialPaper.value())
                .add(other == null ? BigDecimal.ZERO : other.value());
        return new Component(value, Coverage.COMPLETE, present(commercialPaper, other));
    }

    private static List<FlowObservation> resolveFlowSpans(Map<String, Map<Span, Fact>> facts,
                                                          Map<String, Map<LocalDate, Fact>> instantFacts) {
        Set<Span> spans = new LinkedHashSet<>();
        facts.values().forEach(bySpan -> spans.addAll(bySpan.keySet()));
        boolean issuerHasShortTermDebt = hasAny(instantFacts, SHORT_TERM_DEBT);
        boolean issuerHasLongTermDebt = hasAny(instantFacts, CURRENT_TERM_DEBT)
                || hasAny(instantFacts, NONCURRENT_TERM_DEBT) || hasAny(instantFacts, LONG_TERM_DEBT);

        List<FlowObservation> result = new ArrayList<>();
        spans.stream().sorted(Comparator.comparing(Span::end).thenComparing(Span::start)).forEach(span -> {
            Resolved resolved = resolveFlowAt(facts, span, issuerHasShortTermDebt, issuerHasLongTermDebt);
            if (resolved != null) result.add(new FlowObservation(span.start(), span.end(), resolved));
        });
        return result;
    }

    private static Resolved resolveFlowAt(Map<String, Map<Span, Fact>> facts,
                                          Span span,
                                          boolean issuerHasShortTermDebt,
                                          boolean issuerHasLongTermDebt) {
        Fact aggregateNet = firstAt(facts, span, AGGREGATE_SIGNED_NET);
        if (aggregateNet != null) {
            return resolved(aggregateNet.value(), Coverage.COMPLETE,
                    Route.AGGREGATE_NET_BORROWING, List.of(aggregateNet));
        }

        Fact aggregateIssuance = firstAt(facts, span, AGGREGATE_ISSUANCE);
        Fact aggregateRepayment = firstAt(facts, span, AGGREGATE_REPAYMENT);
        if (aggregateIssuance != null || aggregateRepayment != null) {
            Coverage coverage = aggregateIssuance != null && aggregateRepayment != null
                    ? Coverage.COMPLETE : Coverage.INCOMPLETE;
            return resolved(grossNet(aggregateIssuance, aggregateRepayment), coverage,
                    Route.AGGREGATE_GROSS_BORROWING, present(aggregateIssuance, aggregateRepayment));
        }

        Component longTerm = signedOrGross(facts, span, LONG_TERM_SIGNED_NET,
                LONG_TERM_ISSUANCE, LONG_TERM_REPAYMENT);
        Component shortTerm = resolveShortTermFlow(facts, span);
        if (longTerm == null && shortTerm == null) return null;

        boolean complete = (!issuerHasLongTermDebt || longTerm != null && longTerm.coverage() == Coverage.COMPLETE)
                && (!issuerHasShortTermDebt || shortTerm != null && shortTerm.coverage() == Coverage.COMPLETE);
        BigDecimal value = (longTerm == null ? BigDecimal.ZERO : longTerm.value())
                .add(shortTerm == null ? BigDecimal.ZERO : shortTerm.value());
        List<Fact> used = new ArrayList<>();
        if (longTerm != null) used.addAll(longTerm.facts());
        if (shortTerm != null) used.addAll(shortTerm.facts());
        return resolved(value, complete ? Coverage.COMPLETE : Coverage.INCOMPLETE,
                Route.COMPONENT_NET_BORROWING, used);
    }

    private static Component resolveShortTermFlow(Map<String, Map<Span, Fact>> facts, Span span) {
        Fact broadSigned = firstAt(facts, span, BROAD_SHORT_TERM_SIGNED_NET);
        if (broadSigned != null) {
            return new Component(broadSigned.value(), Coverage.COMPLETE, List.of(broadSigned));
        }

        Fact broadIssuance = firstAt(facts, span, BROAD_SHORT_TERM_ISSUANCE);
        Fact broadRepayment = firstAt(facts, span, BROAD_SHORT_TERM_REPAYMENT);
        if (broadIssuance != null || broadRepayment != null) {
            Coverage coverage = broadIssuance != null && broadRepayment != null
                    ? Coverage.COMPLETE : Coverage.INCOMPLETE;
            return new Component(grossNet(broadIssuance, broadRepayment), coverage,
                    present(broadIssuance, broadRepayment));
        }

        Component commercialPaper = signedOrGross(facts, span, COMMERCIAL_PAPER_SIGNED_NET,
                COMMERCIAL_PAPER_ISSUANCE, COMMERCIAL_PAPER_REPAYMENT);
        Component other = signedOrGross(facts, span, List.of(),
                OTHER_SHORT_TERM_ISSUANCE, OTHER_SHORT_TERM_REPAYMENT);
        if (commercialPaper == null && other == null) return null;
        BigDecimal value = (commercialPaper == null ? BigDecimal.ZERO : commercialPaper.value())
                .add(other == null ? BigDecimal.ZERO : other.value());
        List<Fact> used = new ArrayList<>();
        if (commercialPaper != null) used.addAll(commercialPaper.facts());
        if (other != null) used.addAll(other.facts());
        Coverage coverage = (commercialPaper == null || commercialPaper.coverage() == Coverage.COMPLETE)
                && (other == null || other.coverage() == Coverage.COMPLETE)
                ? Coverage.COMPLETE : Coverage.INCOMPLETE;
        return new Component(value, coverage, used);
    }

    private static Component signedOrGross(Map<String, Map<Span, Fact>> facts,
                                           Span span,
                                           List<String> signedConcepts,
                                           List<String> issuanceConcepts,
                                           List<String> repaymentConcepts) {
        Fact signed = firstAt(facts, span, signedConcepts);
        if (signed != null) return new Component(signed.value(), Coverage.COMPLETE, List.of(signed));
        Fact issuance = firstAtOrProvenZero(facts, span, issuanceConcepts);
        Fact repayment = firstAt(facts, span, repaymentConcepts);
        if (issuance == null && repayment == null) return null;
        Coverage coverage = issuance != null && repayment != null ? Coverage.COMPLETE : Coverage.INCOMPLETE;
        return new Component(grossNet(issuance, repayment), coverage, present(issuance, repayment));
    }

    /**
     * A missing gross issuance fact is normally incomplete. The sole safe exception is a later,
     * explicitly reported zero cumulative issuance fact with the same fiscal-year start that
     * covers the requested period. Issuance proceeds cannot be negative, so a reported YTD zero
     * proves every included quarter's issuance is zero; it is evidence, not a missing-value default.
     */
    private static Fact firstAtOrProvenZero(Map<String, Map<Span, Fact>> facts, Span span, List<String> concepts) {
        Fact exact = firstAt(facts, span, concepts);
        if (exact != null) return exact;
        for (String concept : concepts) {
            Fact proof = facts.getOrDefault(concept, Map.of()).values().stream()
                    .filter(candidate -> candidate.value().signum() == 0)
                    .filter(candidate -> candidate.start().equals(span.start()))
                    .filter(candidate -> !candidate.end().isBefore(span.end()))
                    .min(Comparator.comparing(Fact::end).thenComparing(Fact::filed))
                    .orElse(null);
            if (proof != null) return proof;
        }
        return null;
    }

    private static BigDecimal grossNet(Fact issuance, Fact repayment) {
        BigDecimal proceeds = issuance == null ? BigDecimal.ZERO : issuance.value().abs();
        BigDecimal payments = repayment == null ? BigDecimal.ZERO : repayment.value().abs();
        return proceeds.subtract(payments);
    }

    private static List<Metric> quarterize(List<FlowObservation> observations,
                                           LocalDate from,
                                           LocalDate to) {
        Map<LocalDate, FlowObservation> standaloneByEnd = new HashMap<>();
        for (FlowObservation observation : observations) {
            long days = ChronoUnit.DAYS.between(observation.start(), observation.end()) + 1;
            if (days >= 70 && days <= 110) {
                standaloneByEnd.merge(observation.end(), observation, SecDebtResolver::preferredObservation);
            }
        }

        Map<LocalDate, Map<String, FlowObservation>> byFiscalStart = new LinkedHashMap<>();
        for (FlowObservation observation : observations) {
            String fp = observation.resolved().fiscalPeriod();
            if (!List.of("Q1", "Q2", "Q3", "FY").contains(fp)) continue;
            byFiscalStart.computeIfAbsent(observation.start(), ignored -> new HashMap<>())
                    .merge(fp, observation, SecDebtResolver::preferredObservation);
        }

        Map<LocalDate, Metric> byEnd = new HashMap<>();
        for (Map<String, FlowObservation> byPeriod : byFiscalStart.values()) {
            FlowObservation previous = null;
            String previousPeriod = null;
            for (String fp : List.of("Q1", "Q2", "Q3", "FY")) {
                FlowObservation current = byPeriod.get(fp);
                if (current == null) continue;
                FlowObservation predecessor = previous;
                String predecessorPeriod = previousPeriod;

                Resolved quarter = null;
                if ("Q1".equals(fp) && isStandalone(current)) {
                    quarter = current.resolved();
                } else if (previous != null && isImmediatePredecessor(previousPeriod, fp)) {
                    quarter = subtract(current.resolved(), previous.resolved());
                } else {
                    FlowObservation standalone = standaloneByEnd.get(current.end());
                    if (standalone != null && standalone.start().isAfter(current.start())) {
                        quarter = standalone.resolved();
                    }
                }

                previous = current;
                previousPeriod = fp;
                if (quarter == null || current.end().isBefore(from) || current.end().isAfter(to)) continue;
                Metric metric = predecessor != null && isImmediatePredecessor(predecessorPeriod, fp)
                        ? toQuarterizedMetric(current.end(), quarter, current.resolved(), predecessor.resolved(), current.start(), current.end())
                        : toMetric(current.end(), quarter);
                byEnd.merge(metric.asOfDate(), metric, SecDebtResolver::preferredMetric);
            }
        }
        return byEnd.values().stream().sorted(Comparator.comparing(Metric::asOfDate)).toList();
    }

    private static boolean isImmediatePredecessor(String previous, String current) {
        return ("Q1".equals(previous) && "Q2".equals(current))
                || ("Q2".equals(previous) && "Q3".equals(current))
                || ("Q3".equals(previous) && "FY".equals(current));
    }

    private static boolean isStandalone(FlowObservation observation) {
        long days = ChronoUnit.DAYS.between(observation.start(), observation.end()) + 1;
        return days >= 70 && days <= 110;
    }

    private static Resolved subtract(Resolved current, Resolved previous) {
        List<Fact> facts = new ArrayList<>(current.facts());
        facts.addAll(previous.facts());
        Coverage coverage = current.coverage() == Coverage.COMPLETE && previous.coverage() == Coverage.COMPLETE
                ? Coverage.COMPLETE : Coverage.INCOMPLETE;
        Resolved combined = resolved(current.value().subtract(previous.value()), coverage, current.route(), facts);
        return new Resolved(combined.value(), combined.coverage(), combined.route(), combined.filed(),
                current.fiscalYear(), current.fiscalPeriod(), current.accessionNumber(), current.form(),
                combined.concepts(), combined.facts());
    }

    private static Map<LocalDate, Fact> readInstantFacts(JsonNode usGaap, String concept) {
        Map<LocalDate, Fact> result = new HashMap<>();
        JsonNode values = usGaap.path(concept).path("units").path("USD");
        if (!values.isArray()) return result;
        for (JsonNode item : values) {
            Fact fact = fact(concept, item, true);
            if (fact != null) result.merge(fact.end(), fact, SecDebtResolver::preferredFact);
        }
        return result;
    }

    private static Map<Span, Fact> readFlowFacts(JsonNode usGaap, String concept) {
        Map<Span, Fact> result = new HashMap<>();
        JsonNode values = usGaap.path(concept).path("units").path("USD");
        if (!values.isArray()) return result;
        for (JsonNode item : values) {
            Fact fact = fact(concept, item, false);
            if (fact != null) result.merge(new Span(fact.start(), fact.end()), fact, SecDebtResolver::preferredFact);
        }
        return result;
    }

    private static Fact fact(String concept, JsonNode item, boolean instant) {
        String form = item.path("form").asText("");
        if (!isQuarterlyOrAnnualForm(form)) return null;
        JsonNode valueNode = item.path("val");
        String endRaw = item.path("end").asText("");
        String startRaw = item.path("start").asText("");
        if (endRaw.isBlank() || valueNode.isMissingNode() || valueNode.isNull()) return null;
        if (!instant && startRaw.isBlank()) return null;
        try {
            return new Fact(concept, instant ? null : LocalDate.parse(startRaw), LocalDate.parse(endRaw),
                    valueNode.decimalValue(), parseDate(item.path("filed").asText("")),
                    item.path("fy").isInt() ? item.path("fy").asInt() : null,
                    item.path("fp").asText(null), item.path("accn").asText(null), form);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isQuarterlyOrAnnualForm(String form) {
        return "10-Q".equals(form) || "10-Q/A".equals(form)
                || "10-K".equals(form) || "10-K/A".equals(form);
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return LocalDate.parse(raw); } catch (RuntimeException ignored) { return null; }
    }

    private static Fact preferredFact(Fact first, Fact second) {
        return factComparator().compare(first, second) <= 0 ? first : second;
    }

    private static FlowObservation preferredObservation(FlowObservation first, FlowObservation second) {
        return resolvedComparator().compare(first.resolved(), second.resolved()) <= 0 ? first : second;
    }

    private static Metric preferredMetric(Metric first, Metric second) {
        if (first.coverage() != second.coverage()) return first.coverage() == Coverage.COMPLETE ? first : second;
        return metricComparator().compare(first, second) <= 0 ? first : second;
    }

    private static Comparator<Fact> factComparator() {
        return Comparator.comparing(Fact::filed, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Fact::accessionNumber, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Fact::form, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static Comparator<Resolved> resolvedComparator() {
        return Comparator.comparing(Resolved::filed, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Resolved::accessionNumber, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static Comparator<Metric> metricComparator() {
        return Comparator.comparing(Metric::filed, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Metric::accessionNumber, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private static Resolved resolved(BigDecimal value, Coverage coverage, Route route, List<Fact> facts) {
        List<Fact> ordered = facts.stream().distinct().sorted(factComparator()).toList();
        Fact metadata = ordered.isEmpty() ? null : ordered.getFirst();
        LocalDate availableAt = ordered.stream().map(Fact::filed).filter(date -> date != null)
                .max(LocalDate::compareTo).orElse(null);
        Set<String> concepts = new LinkedHashSet<>();
        Set<String> accessions = new LinkedHashSet<>();
        ordered.forEach(fact -> {
            concepts.add(fact.concept());
            if (fact.accessionNumber() != null && !fact.accessionNumber().isBlank()) accessions.add(fact.accessionNumber());
        });
        return new Resolved(value, coverage, route, availableAt,
                metadata == null ? null : metadata.fiscalYear(),
                metadata == null ? null : metadata.fiscalPeriod(),
                accessions.isEmpty() ? null : String.join(",", accessions),
                metadata == null ? null : metadata.form(), Set.copyOf(concepts), ordered);
    }

    private static Metric toMetric(LocalDate date, Resolved resolved) {
        // Balance facts are reported as positive balance-sheet amounts. Financing-flow facts,
        // however, need the same issuance/repayment sign normalization used by grossNet().
        // Keeping this distinction here guarantees persisted evidence reconciles to Metric.value().
        boolean financingFlow = switch (resolved.route()) {
            case AGGREGATE_NET_BORROWING, AGGREGATE_GROSS_BORROWING, COMPONENT_NET_BORROWING -> true;
            default -> false;
        };
        return new Metric(date, resolved.value(), resolved.coverage(), resolved.route(), resolved.filed(),
                resolved.fiscalYear(), resolved.fiscalPeriod(), resolved.accessionNumber(), resolved.form(),
                resolved.concepts(), financingFlow ? flowEvidence(resolved) : evidence(resolved),
                null, date, "REPORTED_OR_RESOLVED");
    }

    private static Metric toQuarterizedMetric(LocalDate date, Resolved quarter, Resolved current, Resolved previous,
                                               LocalDate sourceStart, LocalDate sourceEnd) {
        List<Evidence> currentEvidence = flowEvidence(current), previousEvidence = flowEvidence(previous);
        Map<String, BigDecimal> currentByComponent = currentEvidence.stream().collect(java.util.stream.Collectors.toMap(Evidence::componentType, Evidence::amount, BigDecimal::add));
        Map<String, BigDecimal> previousByComponent = previousEvidence.stream().collect(java.util.stream.Collectors.toMap(Evidence::componentType, Evidence::amount, BigDecimal::add));
        Set<String> keys = new LinkedHashSet<>(); keys.addAll(currentByComponent.keySet()); keys.addAll(previousByComponent.keySet());
        List<Evidence> components = keys.stream().map(key -> {
            Evidence c = currentEvidence.stream().filter(e -> key.equals(e.componentType())).findFirst().orElse(null);
            Evidence p = previousEvidence.stream().filter(e -> key.equals(e.componentType())).findFirst().orElse(null);
            BigDecimal amount = currentByComponent.getOrDefault(key, BigDecimal.ZERO).subtract(previousByComponent.getOrDefault(key, BigDecimal.ZERO));
            Set<String> concepts = new LinkedHashSet<>(); if(c!=null) concepts.addAll(c.concepts()); if(p!=null) concepts.addAll(p.concepts());
            String accns = String.join(",", java.util.stream.Stream.of(c,p).filter(java.util.Objects::nonNull).map(Evidence::accessions).filter(v->v!=null&&!v.isBlank()).distinct().toList());
            return new Evidence(key, amount, Set.copyOf(concepts), accns, sourceStart, sourceEnd);
        }).toList();
        return new Metric(date, quarter.value(), quarter.coverage(), quarter.route(), quarter.filed(), quarter.fiscalYear(), quarter.fiscalPeriod(), quarter.accessionNumber(), quarter.form(), quarter.concepts(), components, sourceStart, sourceEnd, "YTD_DIFFERENCE");
    }
    private static List<Evidence> flowEvidence(Resolved resolved) {
        Map<String,List<Fact>> groups = new LinkedHashMap<>();
        for (Fact fact : resolved.facts()) groups.computeIfAbsent(componentType(fact.concept()), ignored -> new ArrayList<>()).add(fact);
        return groups.entrySet().stream().map(entry -> {
            BigDecimal amount = entry.getValue().stream().map(f -> flowAmount(f.concept(), f.value())).reduce(BigDecimal.ZERO, BigDecimal::add);
            Set<String> concepts = entry.getValue().stream().map(Fact::concept).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            String accns = entry.getValue().stream().map(Fact::accessionNumber).filter(java.util.Objects::nonNull).distinct().collect(java.util.stream.Collectors.joining(","));
            LocalDate start = entry.getValue().stream().map(Fact::start).filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null);
            LocalDate end = entry.getValue().stream().map(Fact::end).filter(java.util.Objects::nonNull).max(LocalDate::compareTo).orElse(null);
            return new Evidence(entry.getKey(), amount, Set.copyOf(concepts), accns, start, end);
        }).toList();
    }
    private static BigDecimal flowAmount(String concept, BigDecimal amount) {
        if (concept.startsWith("RepaymentsOf")) return amount.abs().negate();
        if (concept.startsWith("ProceedsFromIssuance") || concept.startsWith("ProceedsFromShortTerm")) return amount.abs();
        return amount;
    }

    private static List<Evidence> evidence(Resolved resolved) {
        Map<String,List<Fact>> groups = new LinkedHashMap<>();
        for (Fact fact : resolved.facts()) groups.computeIfAbsent(componentType(fact.concept()), ignored -> new ArrayList<>()).add(fact);
        return groups.entrySet().stream().map(entry -> {
            BigDecimal amount = entry.getValue().stream().map(Fact::value).reduce(BigDecimal.ZERO, BigDecimal::add);
            Set<String> concepts = entry.getValue().stream().map(Fact::concept).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            String accns = entry.getValue().stream().map(Fact::accessionNumber).filter(java.util.Objects::nonNull).distinct().collect(java.util.stream.Collectors.joining(","));
            LocalDate start = entry.getValue().stream().map(Fact::start).filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null);
            LocalDate end = entry.getValue().stream().map(Fact::end).filter(java.util.Objects::nonNull).max(LocalDate::compareTo).orElse(null);
            return new Evidence(entry.getKey(), amount, Set.copyOf(concepts), accns, start, end);
        }).toList();
    }
    private static String componentType(String concept) {
        if (COMMERCIAL_PAPER.contains(concept) || concept.contains("CommercialPaper")) return "COMMERCIAL_PAPER";
        if (CURRENT_TERM_DEBT.contains(concept)) return "CURRENT_TERM_DEBT";
        if (NONCURRENT_TERM_DEBT.contains(concept)) return "NONCURRENT_TERM_DEBT";
        if (BROAD_SHORT_TERM_DEBT.contains(concept) || concept.contains("ShortTermDebt")) return "OTHER_SHORT_TERM";
        if (LONG_TERM_DEBT.contains(concept) || concept.contains("LongTermDebt")) return "LONG_TERM";
        if (CURRENT_DEBT.contains(concept)) return "CURRENT_DEBT_AGGREGATE";
        if (COMBINED_DEBT.contains(concept)) return "COMBINED_DEBT_AGGREGATE";
        if (concept.contains("ProceedsFromRepayments")) return "SIGNED_NET_BORROWING";
        return "OTHER_SELECTED_COMPONENT";
    }

    private static <K> Fact firstAt(Map<String, Map<K, Fact>> facts, K key, List<String> concepts) {
        for (String concept : concepts) {
            Fact fact = facts.getOrDefault(concept, Map.of()).get(key);
            if (fact != null) return fact;
        }
        return null;
    }

    private static boolean hasAny(Map<String, ? extends Map<?, Fact>> facts, List<String> concepts) {
        return concepts.stream().anyMatch(concept -> {
            Map<?, Fact> byPeriod = facts.get(concept);
            return byPeriod != null && !byPeriod.isEmpty();
        });
    }

    @SafeVarargs
    private static Set<String> orderedSet(List<String>... groups) {
        Set<String> result = new LinkedHashSet<>();
        for (List<String> group : groups) result.addAll(group);
        return Set.copyOf(result);
    }

    @SafeVarargs
    private static List<String> orderedList(List<String>... groups) {
        List<String> result = new ArrayList<>();
        for (List<String> group : groups) result.addAll(group);
        return List.copyOf(result);
    }

    private static List<Fact> present(Fact... facts) {
        List<Fact> result = new ArrayList<>();
        for (Fact fact : facts) if (fact != null) result.add(fact);
        return result;
    }

    private record Span(LocalDate start, LocalDate end) { }

    private record Fact(String concept, LocalDate start, LocalDate end, BigDecimal value, LocalDate filed,
                        Integer fiscalYear, String fiscalPeriod, String accessionNumber, String form) { }

    private record Component(BigDecimal value, Coverage coverage, List<Fact> facts) { }

    private record FlowObservation(LocalDate start, LocalDate end, Resolved resolved) { }

    private record Resolved(BigDecimal value, Coverage coverage, Route route, LocalDate filed,
                            Integer fiscalYear, String fiscalPeriod, String accessionNumber, String form,
                            Set<String> concepts, List<Fact> facts) { }
}
