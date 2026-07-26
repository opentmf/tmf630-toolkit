# tmf630-toolkit v3.0.0 — sequenced roadmap

**Target version:** 3.0.0 (bump `pom.xml` from `2.1.6-SNAPSHOT` to `3.0.0-SNAPSHOT` when
work starts; create the `## [3.0.0]` CHANGELOG heading immediately per the
SNAPSHOT-heading rule)
**Written:** 2026-07-26
**Status:** approved plan, single release cut
**Related design docs:**
- [`JPA_BACKEND_GAP_ANALYSIS.md`](./JPA_BACKEND_GAP_ANALYSIS.md) — (a) rationale and per-gap analysis
- [`JSONB_BACKEND_DESIGN.md`](./JSONB_BACKEND_DESIGN.md) — (b), (c), (d) architecture; JSONB
  split-and-merge shape approved in §3.4
- [`correlated-sort.md`](./correlated-sort.md) — the Mongo sort feature that (a.4) and (b.6) are analogues of

The v3.0.0 release consolidates four workstreams into one release cut:

- **(a)** Close the JPA vs Mongo capability gap
- **(b)** Introduce PostgreSQL_with_JSONB as a document-DB alternative backend
- **(c)** Support split-and-merge for oversized child collections on JSONB
  (`@Tmf630JsonbSplitCollection`)
- **(d)** Support split-and-merge for oversized child collections on Mongo
  (`@Tmf630MongoSplitCollection`)

This document is the sequencing plan; it does not repeat design decisions — those live
in the sibling docs above.

---

## 1. Sequencing

**Order: (a) → (b) → (c) → (d)**

Rationale by principle:

- **Dependency.** (c) hard-depends on (b); (b) and (d) are independent of (a); (d)
  benefits from (c) being designed first so the split-and-merge abstractions can be
  shaped in the greenfield backend before being ported to Mongo.
- **Risk.** (a) is well-understood extension work on an existing surface — low risk,
  first. (b) is the biggest architectural bet — do it while the existing surface is
  stable and CI-green. (c) inherits from (b). (d) has a proven production reference
  implementation (PIA/Orbitant's product-order-management service) — lowest surprise
  risk despite being large.
- **Value delivery.** (a) improves today's users at the first shippable milestone,
  even before (b) lands. Each intermediate sub-milestone is independently useful,
  though the release cut is one.
- **Learning transfer.** Design split-and-merge abstractions once in JSONB (c) where
  there is no legacy behavior to preserve, then port to Mongo (d) with confidence.
  Reuse the predicate-splitting walker across the two.

---

## 2. Phase (a) — JPA gap closure

**Effort:** ~5–6 weeks. **References:** JPA doc §3.1–§3.6.

| # | Sub-milestone | Effort | Reference |
|---|---|---|---|
| a.1 | Nulls-last-regardless-of-direction property (`opentmf.tmf630.paging.nulls-last`), `TmfSort.toPlainSort` decorates orders with `.nullsLast()` when set, IT parity assertion on emitted SQL | 1 week | JPA doc §3.2 |
| a.2 | `.regex` / `.regexi` on JPA: strict-backend rejection with one-release deprecation-warning cycle (Option A from the JPA doc), gated by `opentmf.tmf630.attribute-filtering.regex.allow-jpa-like-semantics` for back-compat | ~3 days | JPA doc §3.6 |
| a.3 | Filter array correlation `[?(...)]` for JOIN-mapped associations (`@OneToMany` / `@ManyToMany` / `@ElementCollection`): annotation-based JOIN detection via reflection, `JPAExpressions` reflective loading (mirrors existing Mongo `ELEM_MATCH` reflective pattern), correlated `EXISTS` subquery emission, unique aliasing for nested subqueries | 1–2 weeks | JPA doc §3.3 |
| a.4 | New `tmf630-toolkit-jpa-correlated-sort` module. Scope: simple-rich `arr[key=X].leaf`, JsonPath `$.arr[?(...)].leaf`, outer `min()`/`max()` only, nulls-last honored. **Explicit rejection** with clear messages for `[N]`, `[*]`, `num()`/`str()`/`date()` — scoped-rejection principle per [[feedback_scoped_rejection_over_architectural_change]] | 3–5 weeks | JPA doc §3.1 |

Deliverables at end of (a):
- Nulls-last cross-backend parity property.
- `.regex` semantic-drift closed via strict rejection.
- Filter-side array correlation works on JPA for JOIN-mapped associations.
- Sort-side correlated sort works on JPA for JOIN-mapped associations (the largest
  historical gap).
- Full `Tmf630PredicateSqlIT` extensions covering all above.

---

## 3. Phase (b) — PostgreSQL_with_JSONB base backend + correlated sort

**Effort:** ~7–10 weeks (up from initial 5–7 because correlated sort is in scope).
**References:** JSONB doc §0–§8.

| # | Sub-milestone | Effort | Reference |
|---|---|---|---|
| b.1 | Row entity model conventions + `@Tmf630JsonbBacked` annotation + audit-annotation detection (`@CreatedDate`, `@LastModifiedDate`, `@CreatedBy`, `@LastModifiedBy`, `@Version`) via reflection + autoconfiguration; both non-versioned (PK `id`) and versioned (PK `id + version`) table shapes | 2 weeks | JSONB doc §1, §7 |
| b.2 | `JsonbPredicateFactory` with all 24 `TmfOperator` values per §5's translation table; type-aware casts (`::bigint`, `::numeric`, `::timestamptz`, etc.) per §6; NULLISH widening JSONB flavor per §5.1 | 2–3 weeks | JSONB doc §5, §6 |
| b.3 | JsonPath filter integration: `jsonb_path_exists` for array correlation; positional `[N]` via dotted numeric path; `length()` via `jsonb_array_length`; consistent scope restrictions per §5.4 (== only, collection only) | 1 week | JSONB doc §5.2, §5.3, §5.4 |
| b.4 | Plain dotted sort with type-aware casts + native `NULLS LAST` + paging (`LIMIT`/`OFFSET`); total-count companion query | 1 week | JSONB doc §8.1, §8.4 |
| b.5 | `JsonbFilterFragment<T>` + `JdbcClient` integration + result-payload deserialization to domain model via configured `ObjectMapper` + `Tmf630PredicateJsonbIT` parity vs Mongo | 1–2 weeks | JSONB doc §7.2 |
| b.6 | **Correlated sort** for JSONB (both JsonPath and simple-rich grammars): reuse `JsonPathSortAst` from the mongo-aggregation module; emit `ORDER BY (jsonb_path_query_first(...)::text)::<cast>` per sort term; nulls-last companion natively via `NULLS LAST` | 3 weeks | JSONB doc §8.2 |
| b.7 | Sort aggregators (`min()` / `max()`) via scalar subquery over `jsonb_array_elements`; sort coercions (`num()` / `str()` / `date()`) per §6's cast table; parity ITs vs Mongo aggregation module | 2 weeks | JSONB doc §8.3 |

Deliverables at end of (b):
- PostgreSQL_with_JSONB as a fully-featured backend covering everything Mongo covers
  on filter and sort, plus the JSONB-native wins (native regex, native NULLS LAST,
  native `[N]`, native array correlation).
- Per-entity opt-in via `@Tmf630JsonbBacked`; pure-JPA services unchanged.
- Mixed-mode services supported (some entities pure JPA, others JSONB, one database).

---

## 4. Phase (c) — `@Tmf630JsonbSplitCollection` for JSONB

**Effort:** ~6–7 weeks. **References:** JSONB doc §3.4 (approved architecture).

| # | Sub-milestone | Effort | Reference |
|---|---|---|---|
| c.1 | `@Tmf630JsonbSplitCollection` annotation + DDL template (parent + child tables with `item_order`, composite child PK, `ON DELETE CASCADE`) + persistence-hook plumbing to split POST payload into parent + N child INSERTs in one `@Transactional` | 1 week | JSONB doc §3.4 |
| c.2 | **Predicate-splitting walker**: traverse the QueryDSL `Predicate` tree, separate parent-side vs child-side clauses; **design generically** so (d.2) can reuse — this walker lives in `tmf630-toolkit-attribute-filtering-core`, backend-neutral | 1 week | JSONB doc §3.4 "Query planning" |
| c.3 | Query planner: routes each request to parent-only SQL, item-only child-first SQL, mixed AND, or mixed OR fallback (parent-drive + EXISTS); emitted SQL includes comments describing the routing decision for debuggability | 1–2 weeks | JSONB doc §3.4 "Query planning" |
| c.4 | Response merge SQL: `payload \|\| jsonb_build_object('<child-field>', (SELECT jsonb_agg(item.payload ORDER BY item.item_order) FROM ... LIMIT N))`; cap enforced at DB level via `LIMIT`, not in Java | 1 week | JSONB doc §3.4 "Response merge SQL shape" |
| c.5 | `Tmf630JsonbSubResourceController<C, P>` base class + `RequestMapping` support for `/{parent}/{parentId}/{child}` sub-endpoint routes (developer extends with empty body, gets full CRUD scoped to `{parentId}`) | 1 week | JSONB doc §3.4 "Sub-endpoint pattern" |
| c.6 | PATCH optimizations: `op: add, path: /child/-` → INSERT into child; `op: replace, path: /child/N/field` → UPDATE on child row; `op: remove, path: /child/N` → DELETE on child row; parent-only patches don't touch child table; all in one `@Transactional` | 1 week | JSONB doc §3.4 "Write handling" |
| c.7 | `Tmf630JsonbSplitIT` parity ITs with SDWAN-scale fixtures (500+ items per order) validating truncation, `X-Total-Count-<childName>` header, sub-endpoint pagination, item-side filter routing to child table | 1 week | JSONB doc §10 |

Deliverables at end of (c):
- Services can declare `@Tmf630JsonbSplitCollection` on any oversized child collection
  and get transparent split-store + merged-read + sub-endpoint paging for free.
- Item-side filters at the parent endpoint are index-driven (child-first plan).
- Response payload is bounded regardless of item count.

---

## 5. Phase (d) — `@Tmf630MongoSplitCollection` for Mongo

**Effort:** ~5–6 weeks (with c.2 reuse benefit). **References:** Mongo-side eval from
the design conversation (not yet in a doc; PIA's product-order-management service is
the reference implementation for the pipeline shapes).

| # | Sub-milestone | Effort | Reference |
|---|---|---|---|
| d.1 | `@Tmf630MongoSplitCollection` annotation + compound-index conventions (`{parentId:1, itemId:1}` unique, `{parentId:1, itemOrder:1}` for ordering) + write hooks with Mongo multi-doc transaction wrapper (respecting `$unionWith`-in-transaction caveat per PIA's PERFORMANCE_PLAN.md) | 1 week | Mongo eval |
| d.2 | **Reuse the generic predicate-splitter from c.2** — integration into the Mongo aggregation path; this is the payoff for generalizing in c.2 | ~3 days | c.2 payoff |
| d.3 | Aggregation pipeline generator: item-first (`$match` → `$group parentId` → `$lookup` back to parent), parent-first (`$lookup` with inner pipeline), union-fallback with `$unionWith` outside transaction — three shapes, router picks per clause shape (mirrors PIA's `MongoAggregationBuilderForProductOrder`) | 2–3 weeks | Mongo eval |
| d.4 | `$lookup` inner pipeline emission: `[$match: {parentId: <parent>}, $sort: {itemOrder:1}, $limit: N, $project: {payload:1}]`; extended `$lookup` form (Mongo 3.6+) | 1 week | Mongo eval |
| d.5 | `Tmf630MongoSubResourceController<C, P>` base class (mirror of c.5 with `MongoTemplate`-based fragment) | ~3 days | c.5 mirror |
| d.6 | PATCH optimizations for Mongo + transaction handling (append/modify/delete on child collection, per-op INSERT/UPDATE/DELETE) | 1 week | mirrors c.6 |
| d.7 | `Tmf630MongoSplitIT` parity ITs vs `Tmf630JsonbSplitIT` — same URL fixtures, same expected result sets — proves the two backends behave identically for the split pattern | 1 week | JSONB doc §10 |

Deliverables at end of (d):
- Services on Mongo get the same `@Tmf630JsonbSplitCollection` semantics via
  `@Tmf630MongoSplitCollection`, with parity for URL grammar and response contract.
- PIA's bespoke ~700-line hand-rolled implementation becomes a five-line annotation
  on the domain model plus a five-line sub-endpoint controller.

---

## 6. Total effort

**Sequential (single developer):** ~25–29 weeks, ~6–7 months.

**Parallelizable if two developers available:**
- (a) can run in parallel with (b) — they touch different modules. Saves ~5–6 weeks
  of wall clock.
- (d) can start once (c.2)'s generic predicate-splitter is done (before c.7 lands),
  saving another ~1 week.
- With two developers: ~19–22 weeks (~4.5–5 months).

**Not parallelizable:** (c) depends on (b) end-to-end; (d) depends on c.1-c.5 for
abstraction shapes.

---

## 7. Cross-cutting decisions locked in

**Module layout:**
- **New module:** `tmf630-toolkit-jpa-correlated-sort` (for a.4). Autoconfigured on
  `QuerydslPredicateExecutor` + `EntityManager` presence, mirroring how the
  mongo-aggregation module autoconfigures on `MongoTemplate`.
- **New module:** `tmf630-toolkit-jsonb` (for b + c). Autoconfigured when the
  toolkit sees an entity annotated `@Tmf630JsonbBacked` on the classpath.
- **Extend existing module:** `tmf630-toolkit-mongo-aggregation` (for d). If
  split-and-merge feels distinct enough from correlated sort to warrant its own
  module, consider renaming to `tmf630-toolkit-mongo-extensions` and splitting into
  two subpackages — decision deferred to during (d.1).
- **Shared code:** the predicate-splitting walker (c.2) lives in
  `tmf630-toolkit-attribute-filtering-core` — backend-neutral, consumed by both the
  JSONB and Mongo split modules.

**Annotation names (final):**
- `@Tmf630JsonbBacked` — opts an entity into JSONB backend mode (JSONB doc §0.1).
- `@Tmf630JsonbSplitCollection` — declares a split collection on JSONB (JSONB doc §3.4).
- `@Tmf630MongoSplitCollection` — declares a split collection on Mongo (Mongo eval).
- (No new annotation for pure JPA — existing `@Entity` detection is unchanged.)

**Testing infrastructure:**
- Shared fixture module for split-and-merge ITs — one SDWAN-style dataset used by both
  `Tmf630JsonbSplitIT` and `Tmf630MongoSplitIT`.
- Testcontainers: Postgres 15+ for JSONB (required for full path expression support);
  Mongo 6+ with replica-set config for transaction-requiring ITs.
- Parity IT pattern: same URL fixtures asserted against both backends produce the same
  expected row sets. This enforces the URL-grammar-neutrality claim by test rather
  than by comment.

**CHANGELOG & release:**
- Bump `pom.xml` to `3.0.0-SNAPSHOT` at start of work.
- Create `## [3.0.0]` heading in CHANGELOG immediately, per your SNAPSHOT-heading rule.
- Per your intra-cycle-defect rule, only surviving-to-release changes get CHANGELOG
  entries; churn within the cycle stays in git history alone.
- README: extensive rewrite of §Backend-specific sections + new §JSONB backend
  handbook + new §Split-and-merge handbook. Coordinate at end of each phase.

---

## 8. Open questions

None of these block the start of work; all can be answered during their phase.

1. **Module split for Mongo (during d.1):** extend `tmf630-toolkit-mongo-aggregation`
   or split into `tmf630-toolkit-mongo-extensions` + subpackages?
2. **Which TMF entity to spike JSONB backend with (during b.1):** ProductOrder
   matches the PIA use case (most complex, validates split too), Customer or Alarm
   are simpler for faster feedback. Suggest ProductOrder because the whole
   split-and-merge story lives there.
3. **Multi-tenancy shape for JSONB (during b.1):** column vs schema vs deferred? Per
   memory, defer entirely to the deployment for the first cut.
4. **Type-cast failure mode for JSONB (during b.2):** fail-fast is the default;
   confirm no downstream service needs `TRY_CAST` regex-guarded semantics.
5. **JPA correlated-sort autoconfig gate (during a.4):** bean-transparent (add to
   controller by adding the dependency, like Mongo module) or require explicit
   `@EnableJpaCorrelatedSort`? Suggest bean-transparent for consistency; the
   JOIN-mapping constraints are enforced at parse time with clear rejection messages.

---

## 9. What NOT in scope for v3.0.0

Explicitly deferred to a later release:

- **JPA `[N]` positional in filter or sort** — impractical portably without `@OrderColumn`
  assumption; JSONB backend covers it natively for services that need it.
- **JPA `[*]` wildcard in sort** — same reason.
- **JPA `num()` / `str()` / `date()` coercion in sort** — dialect-specific SQL, not
  portable across the toolkit's supported dialects.
- **Portable real regex on JPA via `regexp_like` dialect templates** — not worth the
  dialect-detect surface until a downstream service actually asks. The (a.2) rejection
  makes the current silent-drift explicit; the extension to real regex can come later.
- **Write-side payload validation** (rejecting `@CreatedDate`/`@Version` fields from
  client POSTs) — a legitimate future direction but explicitly not tied to v3.0.0.
- **Cross-entity JOIN filter/sort** on any backend — remains scoped out per JSONB doc
  §3.2. Split-and-merge is intra-entity only.
- **Full audit-history variant** of JSONB (Model B from the earlier design draft) —
  services that need audit history layer it via triggers/outbox alongside the primary
  table.
