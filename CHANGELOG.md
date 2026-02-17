# Changelog

All notable changes to `tmf630-toolkit` are documented in this file.

## [1.0.3] - 2026-02-17

- Fix sorting behavior for signed TMF sort tokens and strengthen integration coverage.
  - ensure signed sort values are parsed in both resolver paths:
    - `Pageable` arguments (including requests that only send `sort` without `offset`/`limit`)
    - `Sort` arguments via a dedicated TMF sort argument resolver
  - normalize plus-prefixed ascending sort values even when `+` is URL-decoded as whitespace
    (for example `sort=+transformationId` becoming `sort= transformationId`)
  - prevent Spring Data property lookup errors such as:
    - `No property '-createdOn' found for type ...`
    - `No property ' transformationId' found for type ...`
  - add focused tests to lock behavior:
    - unit test for `TmfSortHandlerMethodArgumentResolver`
    - resolver test for sort-only pageable fallback parsing
    - parser test for whitespace/plus handling
    - controller-level auto-configuration ITs for signed sort requests without TMF paging params

- Restore stable QueryDSL test model generation for attribute-filtering autoconfigure tests.
  - add a test-compile QueryDSL annotation-processor execution to generate missing Mongo Q-types (for example `QMongoSearchEntity`)
  - keep Spring configuration metadata generation intact while restoring QueryDSL test class generation
  - fix integration test context startup failures caused by missing generated Q classes


## [1.0.2] - 2026-02-13
- Improve TMF630 filtering defaults, backend-aware nested-path handling, and docs.
  - add implicit-eq fallback for dotted attribute keys with unknown suffixes when implicit eq is enabled
  - fix deep nested keys (for example `a.b.c=...`) to parse as field-path `eq` instead of unknown-operator errors
  - split nested-path configuration into backend-specific settings:
    - `opentmf.tmf630.attribute-filtering.allow-nested-paths-jpa`
    - `opentmf.tmf630.attribute-filtering.allow-nested-paths-docdb`
  - resolve nested-path behavior per root type in runtime settings (`@Entity` -> JPA setting, `@Document` -> docdb setting)
  - propagate backend-aware nested-path resolution through attribute filtering and JsonPath filter translation
  - update JsonPath predicate builder internals to use resolved nested-path policy consistently, including array-correlation flow
  - change default allowlist mode from `DENY_ALL` to `ALLOW_ALL` for out-of-the-box filtering usability
  - remove deprecated legacy nested-path property bridge (`allowNestedPaths`) from autoconfigure properties
  - update and extend tests across core and autoconfigure modules for:
    - new parser fallback behavior
    - backend-specific nested-path settings
    - removed deprecated property bridge
    - Mongo integration test context stability
  - refactor Mongo IT to use an inline test controller in the test app for reliable context loading under verify/failsafe
  - refresh README configuration documentation with:
    - default behavior scenario
    - minimal override YAML scenario
    - full non-default YAML scenario
    - backend-specific nested-path guidance (JPA vs docdb)
    - explicit safer production recommendation (`DENY_ALL` + allowlisted fields per entity)

## [1.0.1] - 2026-02-11

### Added
- `filter=` JsonPath support (restricted subset) merged into the same predicate flow.
- Request-level merge override between attribute filters and `filter=` via `filter.combineWithAttributes=AND|OR`.
- Mongo-oriented array-correlation support in `filter=` with strict same-element behavior (`$elemMatch` translation).
- Dedicated integration profiles for SQL database validation using Testcontainers (PostgreSQL, MariaDB, Microsoft SQL Server, Oracle XE, IBM DB2).

### Documentation
- Expanded root README with end-to-end usage, operator reference, backend support scope, and tested database matrix.
- Added explicit Mongo and JPA `filter=` support boundaries and `400` error conditions.

## [1.0.0] - 2026-02-10
- Initial release.

