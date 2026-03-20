# Changelog

All notable changes to `tmf630-toolkit` are documented in this file.

## [1.0.7] - 2026-03-20

### Changed
- Parent POM: `spring-boot.version` (imported BOM) bumped to `3.5.12`.
- `tmf630ResolverOrderingPostProcessor` no longer eagerly injects `Tmf630PredicateArgumentResolver`.
  - The `BeanPostProcessor` now accepts `ObjectProvider<Tmf630PredicateArgumentResolver>` and lazily resolves the bean inside `postProcessAfterInitialization`. This eliminates the "not eligible for getting processed by all BeanPostProcessors" warnings for all library beans (`OperatorRegistry`, `ParamKeyParser`, `ValueConverter`, `FieldPathResolver`, `PredicateFactory`, `JsonPathFilterPredicateBuilder`, `FieldAllowlistProvider`, `Tmf630FilteringExceptionHandler`).

### Fixed
- `TmfFilteringException` now propagates directly from `Tmf630PredicateArgumentResolver` instead of being wrapped in `ResponseStatusException`.
  - **Root cause of 500 in consuming services**: Spring's `ResponseStatusException` wrapping was being intercepted by consuming applications that had a catch-all `@ExceptionHandler(Exception.class)`, which returned a generic `500 Internal Server Error` to the client.
  - **Solution**: A new `Tmf630FilteringExceptionHandler` (`@RestControllerAdvice` at `@Order(Ordered.HIGHEST_PRECEDENCE)`) is auto-configured as part of the attribute-filtering feature. It catches `TmfFilteringException` before any catch-all handler in the consuming application and returns a structured `400 Bad Request` response:
    ```json
    {
      "code": "400",
      "status": "Bad Request",
      "reason": "Invalid filter parameter.",
      "message": "Field \"createdOn\" (Instant) could not be parsed from value \"2025-01-01\". Expected format: yyyy-MM-dd'T'HH:mm:ssX (ISO-8601 UTC), example: 1990-06-15T11:30:00Z"
    }
    ```
  - The `@Order(HIGHEST_PRECEDENCE)` annotation ensures the library handler wins over any consuming service's `@ExceptionHandler(Exception.class)`. The handler only intercepts `TmfFilteringException` and leaves all other exception types to the consuming service's own handlers.
- `ValueConverter` now returns `400 Bad Request` with a descriptive error message when a query parameter value cannot be parsed for `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime`, and `Instant` fields.
  - Previously, conversion failures produced either a generic `500 Internal Server Error` or an unhelpful `400` message such as `"Failed to convert value 'blabla' to LocalDate"`.
  - The new message includes the field name, the Java type, the expected ISO-8601 format, and a concrete example:
    ```
    Field "birthdate" (LocalDate) could not be parsed from value "15/06/1990".
    Expected format: yyyy-MM-dd, example: 1990-06-15
    ```
  - Enum conversion errors now follow the same pattern, naming the field and the enum type.
  - The field name is now propagated from all three call sites (`Tmf630PredicateArgumentResolver` single-value, multi-value, and `JsonPathFilterPredicateBuilder`).

### Added
- `Tmf630FilteringExceptionHandler` — a new `@RestControllerAdvice` at `@Order(Ordered.HIGHEST_PRECEDENCE)` that handles `TmfFilteringException` and returns a structured `400` response. Auto-configured via `Tmf630AttributeFilteringAutoConfiguration` with `@ConditionalOnMissingBean`, so it can be replaced by a custom bean if needed.
- `ValueConverter.TYPE_FORMAT_HINTS` — a static `Map<Class<?>, FormatHint>` that maps known `java.time` types to their expected format string and a concrete example value. Public to allow introspection and extension.
- `ValueConverter.convert(String rawValue, Class<?> targetType, String fieldName)` overload — produces richer error messages when the field name is known at the call site. The original two-argument overload delegates to this with `fieldName = null`.

### Docs
- New README section **6) Date and datetime field formats** documenting:
  - Accepted ISO-8601 formats for all six supported `java.time` types with example query strings.
  - Clarification that `@DateTimeFormat` annotations on entity fields are **not** respected (conversion is type-based).
  - How to override the `ValueConverter` bean to accept a custom format or to share the application's own `ConversionService`.
- Renumbered developer handbook sections 7–9 to 8–10 to accommodate the new section 6.

### Tests
- 2 new IT tests in `Tmf630PredicateArgumentResolverIT`:
  - `filteringExceptionHandlerReturns400WithStructuredBodyEvenWhenAppHasCatchAllHandler` — verifies that our handler at `HIGHEST_PRECEDENCE` wins over a `@ExceptionHandler(Exception.class)` defined in the same application context.
  - `filteringExceptionBodyContainsFieldNameAndFormatHintForTemporalType` — verifies the field name and ISO format hint appear in the response body for a temporal-type parse failure.
- 2 new unit tests in `Tmf630FilteringExceptionHandlerTest` covering the response status, body structure, and message content.
- Updated 6 unit tests in `Tmf630PredicateArgumentResolverTest` to assert `TmfFilteringException` instead of `ResponseStatusException`.
- `Tmf630ResolverOrderingPostProcessorTest` updated to supply `ObjectProvider<Tmf630PredicateArgumentResolver>` (anonymous provider) to match the auto-configuration signature.
- 5 new unit tests in `ValueConverterTest`:
  - `includesFieldNameAndFormatHintInErrorMessageForTemporalTypes` — verifies rich message for `LocalDate`, `LocalDateTime`, `OffsetDateTime`.
  - `includesFieldNameInEnumErrorMessage` — verifies field name and enum type appear in enum parse failure.
  - `fallsBackToGenericMessageWhenFieldNameIsNull` — verifies format hint still included even without a field name.
  - `allKnownTemporalTypesHaveFormatHints` — asserts all six `java.time` types are registered in `TYPE_FORMAT_HINTS`.

## [1.0.6] - 2026-03-11

### Added
- `@Tmf630Response` annotation for fully transparent TMF630 response handling.
  - When placed on a controller method (or class) returning `Page<T>`, the library automatically resolves the HTTP status (`200`, `206`, or `416`), adds `Content-Range` / `X-Total-Count` / `X-Result-Count` headers, serializes the page content as a JSON array, and applies `fields=` query parameter selection — all without any boilerplate code.
  - Works at method level or class level (applies to every handler method in the controller).
  - Three usage layers: (1) fully transparent `@Tmf630Response` + `Page<T>` return, (2) manual `tmfPage()` + annotation for auto field selection, (3) fully manual with no annotation.
  - Optional `depth` attribute (`@Tmf630Response(depth = N)`) overrides the global `default-depth` for a specific endpoint. `depth=1` (the default) maps only the scalar sub-fields of any explicitly-named complex field, preventing lazy-load cascades into deeper associations; `depth=0` passes the raw object to Jackson (all getters called); `depth=2` expands one additional level. Explicit dot-paths always resolve regardless of depth. Resolution order: method-level `depth` → class-level `depth` → `opentmf.tmf630.field-selection.default-depth`.
- `Tmf630ResponseBodyAdvice` — a `ResponseBodyAdvice` that powers the `@Tmf630Response` annotation. Auto-configured via Spring Boot; non-Boot users can register it as a bean.
- `Tmf630Util.tmfPage(Page<T>, String fields)` — convenience overload that combines TMF630 page response construction with field selection in a single call.
- New configuration namespace `opentmf.tmf630.field-selection`:
  - `enabled` (default: `true`) — enables/disables the `@Tmf630Response` advice.
  - `default-depth` (default: `1`) — controls how deep nested objects are auto-expanded during field selection. Defaults to `1` so that when a client sends `fields=address`, the library maps only the scalar properties of the named field into an explicit sub-map; Jackson never receives the raw POJO and therefore never triggers lazy-load cascades into deeper associations (e.g. a `@OneToMany states` on `Address`).
- Java record support in `FieldSelectionUtil`.
  - Records are detected via `clazz.isRecord()` and their components discovered through `RecordComponent` API.
  - Both property discovery and value reading bypass `Introspector` for records, since Java 17's `Introspector` does not reliably recognize record accessor methods (`name()` vs `getName()`).
  - Flat records, nested records, record-inside-bean, and bean-inside-record combinations are all supported.
- New public overloads for depth-aware field selection with explicit field lists:
  - `fieldsToMap(Object obj, String fields, int depth)`
  - `fieldsToMapList(List<?> objects, String fields, int depth)`
  - The `depth` parameter controls how deep complex fields are auto-expanded when selected by name (e.g., `fields=child` with `depth=2` expands one nested level into child's complex sub-properties). Explicit dot-paths (e.g., `child.address.city`) always resolve regardless of depth.

### Changed
- **Breaking**: `FieldSelectionUtil` default depth changed from `1` to `0`. Overloads without an explicit `depth` parameter (`fieldsToMap(obj)`, `fieldsToMapList(list)`, `fieldsToMap(obj, fields)`, `fieldsToMapList(list, fields)`) now emit scalar properties only; complex sub-objects are not auto-expanded. Use the `depth` overloads (e.g., `fieldsToMap(obj, 1)`) to restore previous behavior.
- `Tmf630ResponseBodyAdvice` / `@Tmf630Response` default depth changed from `0` to `1`. Scalar sub-fields of any explicitly-named complex field are now included automatically; deeper associations (e.g. `@OneToMany`) remain excluded, preventing JPA lazy-load cascades. Use `@Tmf630Response(depth = 0)` or set `opentmf.tmf630.field-selection.default-depth=0` to opt out.
- `ErrorMessage` converted from a mutable POJO (with setters) to a Java record. JSON serialization is identical; constructor replaces setters.
- `Tmf630PagingSettings` converted from a manual immutable class to a Java record for consistency with `Tmf630FilterSettings`.
- `Tmf630Util.applyRangeHeaders` parameter `returned` widened from `int` to `long` for consistency with `total` and `offset`.
- `OffsetLimitPageRequest` now implements `equals`, `hashCode`, and `toString`.

### Fixed
- `FieldSelectionUtil`: broad field selector overwritten by narrow dot-path.
  - When both `child` and `child.name` appeared in `fields`, the dot-path recursion overwrote the broader auto-expansion. Changed `if` to `else if` in `parseFieldsRecursive` so the two branches are mutually exclusive.
- `FieldSelectionUtil`: `ClassCastException` with wildcard or bounded generic types.
  - `getType` now safely unwraps `WildcardType` (returns upper bound), `TypeVariable` (returns bound), and nested `ParameterizedType` (returns raw type) instead of performing an unchecked cast.
- `FieldSelectionUtil`: `java.sql.Timestamp`, `java.sql.Date`, and `java.sql.Time` treated as scalar values.
  - Added `java.sql` to the excluded-packages set so these types are no longer expanded into their bean properties (e.g., `nanos`).
- `FieldSelectionUtil`: `parseFields` is now depth-aware, delegating to `resolveProperties` for auto-expansion of matched complex fields instead of a hardcoded scalar-only loop.

### Docs
- New README section: **"Avoiding JPA lazy-load cascades — recommended patterns"**, placed directly after the `depth` semantics explanation.
  - **Pattern A (strongly recommended):** use a DTO/record as the repository return type. Because the DTO carries no JPA associations, serialization is always safe with or without `fields=`. Includes a complete code example (record DTO + `@Query` repository + `@Tmf630Response` controller).
  - **Pattern B:** restrict `fields=` to scalar field names; the library's `depth=1` default ensures the raw entity POJO is never handed to Jackson, so lazy association getters are never called.
  - **Pattern C:** when a nested association is genuinely needed, use `@EntityGraph` or `JOIN FETCH` to load it eagerly in a single query, then let `depth=1` prevent cascading into deeper levels.
  - Patterns-to-avoid table: OSIV, `@Transactional` on controller, and `depth=0` without `fields=`.
  - Recommendation callout: set `spring.jpa.open-in-view=false` for new projects so lazy-load problems surface as hard errors at development time rather than silent N+1 queries in production.
- Clarified `depth` semantics: `depth=0` passes the raw POJO to Jackson (all getters called, including lazy associations); `depth=1` maps only scalar sub-fields into an explicit `Map`, preventing any getter call on the named complex object.
- Clarified that `depth` is only consulted when a `fields=` query parameter is present; without `fields=`, raw entity objects are returned to Jackson unconditionally regardless of the `depth` setting.

### Tests
- Add 21 unit tests for `Tmf630ResponseBodyAdvice`: Page→200/206/416 handling, field selection on Page/List/single-object, null body, empty page/list, ResponseEntity-wrapped Page (skips status override), class-level annotation detection, default constructor, depth resolution (method-level, class-level, method-overrides-class, zero depth, global fallback), and depth-aware field selection.
- Add 14 integration tests for `@Tmf630Response`: fully transparent Page, partial content, 416, field selection, class-level annotation, empty page, manual `tmfPage()` + annotation, non-annotated endpoint isolation, method-level depth (depth=1 excludes second-level complex fields, depth=2 includes them), class-level depth inheritance, and method depth overriding class depth.
- Add 2 tests for `OffsetLimitPageRequest` equals/hashCode/toString.
- Add 3 tests for `Tmf630PagingSettings` record: accessors, defensive copy, equals/hashCode.
- Add 2 tests for `Tmf630Util.tmfPage(Page, String fields)` overload.
- Add tests for `Tmf630FieldSelectionProperties` defaults and setters.
- Add 6 tests for record field selection: flat record, explicit field selection, nested records with depth, record-inside-bean, bean-inside-record, and list of records.
- Add 6 tests for depth-aware field selection with explicit fields: depth 1/2/3, list mapping, explicit dot-path unaffected by depth, and backward compatibility with existing overload.
- Add regression tests for the three `FieldSelectionUtil` bug fixes: broad-vs-narrow selector, wildcard/TypeVariable/nested-ParameterizedType generics, and `java.sql.Timestamp` as scalar.

## [1.0.5] - 2026-03-03

### Fixed
- Add `offset`, `limit`, and `fields` to the reserved parameter set in `Tmf630PredicateArgumentResolver`.
  - Previously only `page`, `size`, and `sort` were reserved, causing TMF630 paging and field-selection parameters to be misinterpreted as attribute filter fields.
  - With `onUnknownField=REJECT` (default), requests combining attribute filtering with `offset`/`limit` would fail with `400 Bad Request`.

### Documentation
- Add "Reserved parameter names" section to the README explaining which query parameter names are skipped by attribute filtering, and how to filter by entity fields that share a reserved name using explicit operator suffixes (e.g., `offset.eq=5`).

### Tests
- Add unit test verifying `offset`, `limit`, `fields`, and `sort` are ignored by the predicate resolver.
- Add controller-level integration test combining attribute filtering with `offset`, `limit`, `fields`, and `sort` to prevent regression.

## [1.0.4] - 2026-03-03

### Added
- Enum-aware value conversion with automatic factory method discovery.
  - Before falling back to `Enum.valueOf`, the converter scans for `public static` methods that accept a single `String` and return the enum type.
  - Multiple factory methods are tried in order; exceptions are silently ignored.
  - Factory methods are discovered once per enum type and cached.

### Changed
- Bump Spring Boot BOM from `3.5.10` to `3.5.11`.
- Bump Maven Surefire and Failsafe plugins from `3.5.4` to `3.5.5`.

### Documentation
- Add "Prerequisites for attribute filtering" section covering required peer dependencies (QueryDSL JPA/MongoDB bindings, `querydsl-apt`, Spring Data starters) and what the toolkit provides transitively.
- Add "Combining predicates with path variables" section with a controller example for sub-resource endpoints (`GET /master/{id}/children`).
- Add "Enum field resolution" section explaining the factory method discovery chain with plain, case-insensitive, and multi-factory examples.

### Tests
- Add unit tests for enum conversion: plain enum, single factory, null-returning factory, multiple factories, and full-chain failure.

## [1.0.3] - 2026-02-17

### Fixed
- Parse signed TMF sort values consistently for both `Pageable` and `Sort` arguments.
- Handle plus-prefixed ascending sort when `+` is URL-decoded as whitespace (for example `sort=+transformationId` -> `sort= transformationId`).
- Prevent Spring Data property lookup errors for signed/whitespace sort tokens (for example `-createdOn` and ` transformationId` being treated as raw property names).
- Restore QueryDSL-generated Mongo test Q-types (for example `QMongoSearchEntity`) to prevent integration-test context startup failures.

### Tests
- Add unit test coverage for `TmfSortHandlerMethodArgumentResolver`.
- Add resolver test coverage for sort-only pageable requests (without `offset`/`limit`).
- Add parser test coverage for whitespace/plus sort normalization.
- Add controller-level auto-configuration integration tests for signed sort requests without TMF paging parameters.
- Add test-compile QueryDSL annotation processing path to keep generated test Q-classes stable.


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

