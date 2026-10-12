# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). Before 1.0.0 a minor
version may change the public API.

## [Unreleased]

## [0.2.0] - 2026-10-12

### Changed

- `ObserverInstaller.install(...)` returns an `Installation`; callers that install directly must
  `guard.trust(installation)`. `RowRead` and `Observation` gained a kind / written-tenants
  component (old constructors kept).
- **Maven coordinates are now `io.github.yavuzhankursun:tenant-guard`** (were `dev.tenantguard:tenant-guard`),
  the namespace Maven Central verifies through the GitHub account. The Java packages stay
  `dev.tenantguard.*`; only the dependency declaration changes.
- Endpoint scanner: a 2xx with an empty body (`[]`, `{}`, `null`, blank, zero, `false`) on a by-id
  endpoint is no longer a LEAK; it is SUSPECT if the observer saw the victim's rows, CLEAN otherwise.
- Observer installation runs its DDL under a `lock_timeout` and, on timeout, names the sessions
  holding locks (pid, state, last query) instead of hanging.
- Setup failures say what to check: DataSource not guarded, missing or ambiguous; observer
  installed by a non-owner; self-check errors.

### Added

- **Write endpoint scanning** (POST, PUT, PATCH, DELETE), opt-in via
  `EndpointScanner.includeWrites(dataSource, tenantColumn)`: a write is a LEAK when the victim's rows
  change across the call (row snapshots), whatever the HTTP status; request bodies are user-supplied
  or generated from the `@RequestBody` type; optional spoof probe for tenant fields in POST bodies.
- **Write-side observation**: the observer policy's `WITH CHECK` reports INSERTs and UPDATEs that
  leave a row with another tenant's id as `Finding.Rule.WRITE_MISMATCH`.
- **Per-install NOTICE nonce** (`tg:<nonce>|r|table|tenant`): the application's own notices and
  forged strings can no longer produce observations.
- `ALTER DEFAULT PRIVILEGES` for the observed role, so tables created after installation do not fail
  with "permission denied" while the guard is active (they are not observed).
- Tables that already have RLS policies are observed instead of skipped or refused: the observer is
  created `AS RESTRICTIVE` there, so the application's own policy keeps filtering (a permissive one
  would be OR-ed with it). Tables whose policies never grant the observed role anything are refused.
- Any tenant column type whose `::text` form identifies the tenant (`text`, `integer`, `bigint`,
  `uuid`, ...), tables in non-public schemas (`schemas = {...}`), quoted and mixed-case identifiers.
- Guard placement under `LazyConnectionDataSourceProxy` / `TransactionAwareDataSourceProxy`,
  `AbstractRoutingDataSource` targets and several `DataSource` beans (`dataSources = {...}`).
- Endpoint exclusion: `EndpointScanner.exclude(antPatterns)` / `excludeIf(predicate)`, reported
  as EXCLUDED.
- Spring scheduling running under the test's actor is reported in `ObserverSetup.warnings()`;
  `tenantguard.scheduling=off` replaces the scheduler with a no-op one.
- 33-pattern leak corpus and PIT mutation testing of the JDBC core (`-Pmutation`).
- Environment matrix: Postgres 13-17, Spring Boot 3.2-3.5 (`-Dboot.version`), pgjdbc 42.6/42.7
  (`-Dpostgresql.version`), connection pools with and without statement caches
  (`-Dtenantguard.pool`, `-Dtenantguard.pool.cache`).
- Examples: Spring Data's Hibernate `@TenantId` sample on Postgres
  (`examples/spring-data-tenantid`) and a Gradle Kotlin DSL + Kotlin consumer
  (`examples/gradle-kotlin-consumer`), both built in CI against the installed snapshot.
- Release tooling: `release` Maven profile (sources and javadoc jars, GPG signatures, upload to
  the Maven Central Portal for manual publishing) and a tag-triggered release workflow.
- `CONTRIBUTING.md`, `SECURITY.md`, this changelog.

### Fixed

- Pool statement caches (tomcat-jdbc, DBCP2, c3p0, vibur) no longer hand the next borrower the
  previous borrower's warning chain, which double counted rows and charged tenant A's own read to
  tenant B; DBCP2's self-unwrapping `ResultSet` no longer gets two proxies; c3p0's closed results
  are handled.

## [0.1.0] - 2026-10-10

First tagged version (git tag only, not published to Maven Central).

### Added

- RLS observer policy that reports, through `NOTICE`s, every row the executor reads, and a JDBC
  proxy (`GuardedDataSource`) that matches those rows against the acting tenant:
  `ACTOR_MISMATCH` (active mode, `guard.runAs`) and `MULTI_TENANT_STATEMENT` (passive mode).
- Notices are collected from every access path: statements, prepared and callable statements,
  batches, later cursor fetches on a `ResultSet`, `unwrap`.
- Differential probe: baseline with the attacker's data only, then the victim's data seeded, and
  the responses compared (LEAK vs SUSPECT), with extreme-value seeding helpers (`Extremes`).
- `@TenantIsolationTest` JUnit 5 extension with a `TenantSeeder` hook and a self-check that fails
  when the guard would be blind (superuser, `BYPASSRLS`, filtered notices).
- GET endpoint scanner over Spring's handler mappings and MockMvc (`EndpointScanner`).
- Apache License 2.0, GitHub Actions CI, design notes.

[Unreleased]: https://github.com/yavuzhankursun/tenant-guard/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/yavuzhankursun/tenant-guard/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/yavuzhankursun/tenant-guard/releases/tag/v0.1.0
