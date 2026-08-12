package com.stockportfolio.service;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A deliberately small, single-worker queue for on-demand SEC filing graph rebuilds.
 * SEC downloads can be slow; they must never occupy a user-facing servlet thread.
 * Job state is intentionally in-memory: a restart cancels queued work and callers can
 * safely submit a new idempotent rebuild after the service is healthy again.
 */
@Service
public class SecFilingGraphJobService {
    private final SecCompanyFactsService companyFacts;
    private final SecDebtRebuildService debtRebuildService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sec-filing-graph-rebuild");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentMap<String, MutableJob> jobs = new ConcurrentHashMap<>();

    public SecFilingGraphJobService(SecCompanyFactsService companyFacts, SecDebtRebuildService debtRebuildService) {
        this.companyFacts = companyFacts;
        this.debtRebuildService = debtRebuildService;
    }

    public JobResponse submit(String rawSymbol, int years) { return submit(rawSymbol, years, "FILING_GRAPH_REBUILD"); }

    public JobResponse submitDebtEvidence(String rawSymbol, int years) { return submit(rawSymbol, years, "DEBT_EVIDENCE_REBUILD"); }

    private JobResponse submit(String rawSymbol, int years, String type) {
        String symbol = rawSymbol == null ? "" : rawSymbol.trim().toUpperCase(Locale.ROOT);
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusYears(years);
        Optional<MutableJob> active = jobs.values().stream()
                .filter(job -> job.symbol.equals(symbol) && job.jobType.equals(type) && ("QUEUED".equals(job.status) || "RUNNING".equals(job.status)))
                .min(Comparator.comparing(job -> job.createdAt));
        if (active.isPresent()) return active.get().snapshot();

        MutableJob job = new MutableJob(UUID.randomUUID().toString(), symbol, from, to, years, type);
        jobs.put(job.id, job);
        executor.submit(() -> run(job));
        return job.snapshot();
    }

    public Optional<JobResponse> get(String id) {
        MutableJob job = jobs.get(id);
        return job == null ? Optional.empty() : Optional.of(job.snapshot());
    }

    private void run(MutableJob job) {
        job.status = "RUNNING";
        job.startedAt = Instant.now();
        try {
            if ("DEBT_EVIDENCE_REBUILD".equals(job.jobType)) {
                job.totalFilings = 1;
                debtRebuildService.rebuild(job.symbol, job.years, false, "ASYNC_SEC_DEBT_EVIDENCE_REBUILD");
                job.completedFilings = 1;
            } else companyFacts.rebuildFilingCashFlowGraph(job.symbol, job.from, job.to, progress -> {
                    job.totalFilings = progress.totalFilings();
                    job.completedFilings = progress.completedFilings();
                    job.currentAccessionNumber = progress.accessionNumber();
                    if (progress.message() != null && !progress.message().isBlank()) job.lastMessage = progress.message();
                });
            job.status = "COMPLETED";
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            job.status = "CANCELLED";
            job.lastMessage = "SEC rebuild interrupted";
        } catch (IOException | RuntimeException ex) {
            job.status = "FAILED";
            job.lastMessage = safeMessage(ex);
        } finally {
            job.finishedAt = Instant.now();
        }
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) return ex.getClass().getSimpleName();
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public record JobResponse(String jobId, String jobType, String symbol, LocalDate from, LocalDate to, int years,
                              String status, int totalFilings, int completedFilings,
                              String currentAccessionNumber, String lastMessage,
                              Instant createdAt, Instant startedAt, Instant finishedAt) { }

    private static final class MutableJob {
        private final String id;
        private final String symbol;
        private final LocalDate from;
        private final LocalDate to;
        private final int years;
        private final String jobType;
        private final Instant createdAt = Instant.now();
        private volatile String status = "QUEUED";
        private volatile int totalFilings;
        private volatile int completedFilings;
        private volatile String currentAccessionNumber;
        private volatile String lastMessage;
        private volatile Instant startedAt;
        private volatile Instant finishedAt;

        private MutableJob(String id, String symbol, LocalDate from, LocalDate to, int years, String jobType) {
            this.id = id; this.symbol = symbol; this.from = from; this.to = to; this.years = years; this.jobType = jobType;
        }

        private JobResponse snapshot() {
            return new JobResponse(id, jobType, symbol, from, to, years, status, totalFilings, completedFilings,
                    currentAccessionNumber, lastMessage, createdAt, startedAt, finishedAt);
        }
    }
}
