# Plan — JSONB URL-binding bridge (b.8) + Jackson 3 migration → 3.1.0

> Why now: dnms-681 (the first JSONB-backed DNMS service) needs transparent
> TMF-630 attribute filtering on `@Tmf630JsonbBacked` endpoints, and the DNMS
> engineering standard mandates Jackson 3 in all new code (combined-rules §9:
> never `com.fasterxml.jackson.databind` on a service classpath). Today the
> jsonb module forces BOTH problems onto every consumer: hand-parsed filter
> params, plus a Jackson-2 `ObjectMapper` bean. Both are toolkit gaps, fixed
> here. Target release: **3.1.0**.

## 1. The gap, precisely

Phase (b) of `V3_ROADMAP.md` delivered the JSONB *engine* — all 24
`TmfOperator` translations (`JsonbPredicateFactory`, b.2), JsonPath filter
translation (`JsonbJsonPathTranslator`, b.3), sort/paging/count and the
executors (b.4/b.5) — but **no sub-milestone delivered the HTTP binding**:

- `Tmf630PredicateArgumentResolver` (attribute-filtering-core) implements the
  FULL TMF-630 URL grammar: dot-suffix operators, §4.4 encoded operator
  literals (`?dateTime%3E2013-04-20`), comma/semicolon OR lists with escapes,
  allowlists, clause/value limits, `filter=` JsonPath, and
  `filter.combineWithAttributes`. Its terminal step, however, is hard-wired to
  QueryDSL: `PredicateFactory` + `PathBuilder` → `Predicate`, root detection
  via `@QuerydslPredicate(root=…)`.
- The design doc's option analysis (`JSONB_BACKEND_DESIGN.md`, "Reuses
  `Tmf630PredicateArgumentResolver` end-to-end") assumed the JSONB backend
  would emit QueryDSL `Predicate`s via `booleanTemplate`. The implementation
  deliberately went another way — its own `JsonbClause` record (SQL fragment +
  ordered params, self-parenthesized composition). The binding layer fell
  between the two decisions; `Tmf630JsonbFilterExecutor`'s javadoc marks it
  "lives in a later sub-milestone".
- Consequence: a JSONB-backed controller gets `@Tmf630Response`, `fields=`,
  paging headers and `TmfSort` for free, but every attribute-filter parameter
  must be hand-parsed into `JsonbPredicateFactory` calls — per service,
  drifting from the grammar the resolver already implements.

## 2. Work item 1 — backend-neutral parse core + JSONB argument resolver

> **Status: DONE — landed on `develop` 2026-08-10.** Delivered as planned:
> `Tmf630FilterParser` + `Tmf630FilterExpression`/`Tmf630AttributeClause` AST in
> attribute-filtering-core (existing resolver now a thin terminal, public constructor
> unchanged, pre-existing IT suite green as the no-behavior-change proof);
> `Tmf630JsonbClauseBuilder` + `@Tmf630JsonbFilter` +
> `Tmf630JsonbClauseArgumentResolver` in tmf630-toolkit-jsonb, auto-registered when
> Spring MVC and a `Tmf630FilterSettings` bean are present; parity battery
> `Tmf630JsonbUrlBindingParityIT` (30+ same-URL-same-results cases, rejection parity,
> pinned `.regex` divergence). Notable delta vs the sketch below: instead of the
> resolver taking parameter type `JsonbClause` bare, the binding also required exposing
> `Tmf630FilterSettings` as a bean from attribute-filtering-autoconfigure — which
> incidentally fixed the jsonb factories' settings lookup (regex flag now reaches the
> JSONB backend from properties).

The grammar is backend-neutral; only the terminal differs. Split accordingly.

1. **Extract `Tmf630FilterParser`** (attribute-filtering-core): everything in
   `Tmf630PredicateArgumentResolver` up to (but excluding) predicate
   construction moves into a parser producing a neutral AST —
   `Tmf630FilterExpression`: the list of attribute clauses
   `(fieldPath, TmfOperator, normalized values, implicitEq)` after
   reserved-param skipping, encoded-operator normalization, escape-aware
   splitting, allowlist filtering and limit enforcement; the raw `filter=`
   JsonPath string; the resolved `CombineMode`. Unknown-field/operator
   behaviors (REJECT/IGNORE) stay in the parser. **No behavior change** — the
   existing resolver becomes a thin terminal over the parser and the current
   IT suite is the proof.
2. **`Tmf630JsonbClauseBuilder`** (tmf630-toolkit-jsonb): AST →
   `JsonbClause`, delegating per clause to `JsonbPredicateFactory.build/
   buildMulti/buildNoValue` and for `filter=` to `JsonbJsonPathTranslator`,
   composing with `JsonbClause.and/or` per the same repeated-value and
   combine-mode semantics the QueryDSL terminal applies. Value coercion
   resolves field types against the **domain type** (the same
   `Function<String, Class<?>>` contract `Tmf630JsonbFilterExecutor.findAll`
   already takes, backed by `JsonbEntityRegistry` metadata) and reuses
   `ValueConverter`.
3. **`@Tmf630JsonbFilter(root = Domain.class)`** +
   `Tmf630JsonbClauseArgumentResolver` supporting parameter type
   `JsonbClause`, registered by `Tmf630JsonbAutoConfiguration` via a
   `WebMvcConfigurer` guarded with `@ConditionalOnClass` on spring-web (the
   dependency is already `optional` in the jsonb pom). Target controller
   shape:

   ```java
   @GetMapping
   @Tmf630Response
   Page<CommunicationMessage> search(
       @Tmf630JsonbFilter(root = CommunicationMessage.class) JsonbClause clause,
       TmfSort sort,
       Pageable pageable);
   ```

4. **Parity ITs**: run the QueryDSL resolver IT fixture URLs against a
   JSONB-backed endpoint and compare result sets — the same
   same-URLs-same-results pattern the roadmap prescribes for c.7/d.7.

## 3. Work item 2 — Jackson 3 migration (jsonb module)

> **Status: DONE — landed on `develop` 2026-08-10** (reactor bumped to
> `3.1.0-SNAPSHOT`; CHANGELOG `[3.1.0]` section). Delivered as planned, plus:
> the unused compile-scope Jackson 2 `jackson-databind` in
> `tmf630-toolkit-mongo-split-collection` (missed by the audit below) was
> removed; payload-failure wrapping moved from `UncheckedIOException` to a new
> `Tmf630JsonbSerializationException`; `ErrorMessage` audit confirmed
> annotation-only (nothing to migrate).

The jsonb module is the toolkit's only main-source Jackson-2 dependency
besides the legacy `ErrorMessage` (paging-sorting-core):

- Swap `com.fasterxml.jackson.databind.ObjectMapper` →
  `tools.jackson.databind.ObjectMapper` in the five classes
  (`Tmf630JsonbFilterExecutor`, `Tmf630JsonbWriteExecutor`,
  `Tmf630JsonbVersionResolver`, `Tmf630JsonbSubResourceController`,
  `Tmf630JsonbAutoConfiguration`); pom: replace
  `com.fasterxml.jackson.core:jackson-databind` with
  `tools.jackson.core:jackson-databind`.
- The auto-config takes the mapper via `ObjectProvider`; absent ⇒
  `Tmf630JsonbConfigurationException` naming the missing bean (Boot 4
  provides `JsonMapper` out of the box; Boot 3 consumers add `tools.jackson`
  explicitly — release-note it).
- Serialization-behavior note for the CHANGELOG: Jackson 3 defaults differ
  from 2 (e.g. `FAIL_ON_UNKNOWN_PROPERTIES` off by default, java.time written
  as ISO strings) — the payload write/read path must be covered by a
  round-trip IT asserting byte-stable payloads across the migration.
- **Guard**: an ImportTests-style rule in the module's test suite banning
  `com.fasterxml.jackson.databind`/`.core` imports going forward (annotations
  namespace `com.fasterxml.jackson.annotation` stays legal, per the shared
  convention).
- `ErrorMessage` (paging-sorting-core): audit; migrate in the same release if
  the change is annotation-only, otherwise leave and note — it predates this
  plan and does not force a mapper bean on consumers.

## 4. Release & downstream follow-through

- Version **3.1.0** (new API surface + dependency change), CHANGELOG per repo
  style, `maven-release-plugin`.
- Bump `tmf630-toolkit.version` → 3.1.0 in `opentmf-versions`; next BOM
  release carries it; DNMS floor moves with the BOM.
- Downstream (not this repo): dnms-service-template's `ApiContractTests`
  learns `@Tmf630JsonbFilter` as the jsonb-path equivalent of
  `@QuerydslPredicate` in the Page-GET rule; dnms-681 drops its planned
  hand-parsed curated-filter fallback and binds transparently.

Rough effort: parser extraction 3–5 d, jsonb builder + resolver 3–5 d, parity
ITs 2–3 d, Jackson 3 migration 2 d — ~2–3 weeks elapsed.
