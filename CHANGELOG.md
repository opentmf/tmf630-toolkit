# Changelog

All notable changes to `tmf630-toolkit` are documented in this file.

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

