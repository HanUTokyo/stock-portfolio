# Changelog

All notable changes to this project are documented here.

## [0.1.0-beta.2] - 2026-08-02

### Added

- Server-authoritative valuation workspace with DCF, Reverse DCF, sensitivity, real CAPE, and Bear/Base/Bull scenarios
- Data Review queues, corrections, batch decisions, audit history, and rollback
- Quarterly fundamentals, cash-flow, margin, capital-efficiency, and capital-allocation analysis
- Structured portfolio JSON v2 export and position classification metadata
- Fundamental and valuation notes
- Optional bearer-token API protection, configurable CORS, OpenAPI, and Swagger UI
- Additive backend synchronization API with idempotent mutations
- Responsive multilingual Web experience and expanded automated tests

### Changed

- Backend runtime upgraded from Java 17 to Java 21
- Release scope is explicitly the Spring Boot backend and React Web client
- Web API access defaults to the same-origin `/api` path with a localhost development proxy
- Existing beta1 databases now have an explicit, ordered migration and backup procedure

### Security

- Removed private deployment addresses from tracked configuration and documentation
- Expanded ignore rules for credentials, database dumps, portfolio exports, logs, caches, and local automation artifacts

## [0.1.0-beta.1] - 2026-05-04

- Initial public beta with positions, transactions, portfolio summaries, dividends, cash adjustments, notes, history, and JSON export
