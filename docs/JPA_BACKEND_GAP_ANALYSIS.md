# Pure JPA backend — capability gap vs. MongoDB, and extension feasibility

**Status:** analysis / decision support, not a spec
**Written against:** `2.1.6-SNAPSHOT` (latest release `2.1.5`, 2026-07-24)
**Audience:** toolkit maintainer, deciding whether/how to close the pure-JPA gap

> **Post-v3.0.0 update (2026-07-28):** rows 2–5 of the executive-summary table
> below are partially superseded by the `tmf630-toolkit-jpa-correlated-sort`
> module and its v3.0.0 pragmatic-parity additions:
>
> - Row 2 (simple-rich `arr[id=X].leaf`) — **shipped** (v3.0.0-first).
> - Row 3 (JsonPath `$.arr[?(...)].leaf`) — still deferred; rejected with a
>   message pointing at the JSONB backend.
> - Row 4 (`[*]` / `[N]`) — still deferred; rejected. `[*]` reduces to
>   `min()`/`max()`; `[N]` is not portable across JPA dialects.
> - Row 5 (`min()`/`max()`) — **shipped**. This document called it "impractical
>   portably", but plain SQL aggregate subqueries (`SELECT MIN(x) …`) are
>   dialect-portable — the v3.0.0 executor uses them.
> - Row 7 (nulls-last parity) — **already worked** via `.nullsLast()` uniformly
>   applied; the memo predating v3.0.0 rich-sort work overstated the gap.
>
> Portability-driven rejections (positional, coercions, full JsonPath predicate
> DSL) remain deferred as **architectural** choices, not schedule items.
**Related design docs:**
- [`correlated-sort.md`](./correlated-sort.md) — the sort feature this document lists as
  the largest gap
- [`JSONB_BACKEND_DESIGN.md`](./JSONB_BACKEND_DESIGN.md) — the third planned backend
  (PostgreSQL-JSONB) that closes most of these same gaps via a different code path;
  worth reading alongside if you're weighing pure-JPA extensions vs. JSONB investment

**Terminology.** Throughout this document, **"JPA"** means **pure JPA** — the current
`querydsl-jpa`-driven query path against normalized relational tables, on any
Hibernate-compatible dialect (Postgres, Oracle, MySQL, H2, SQL Server). This is
distinct from the planned **PostgreSQL-JSONB** backend (see the sibling design doc),
which also uses `@Entity` and Hibernate for writes but has a completely different
query path (native JSONB SQL, not JPQL). A Postgres deployment can host both modes
in one service — see JSONB design doc §0 for the per-entity opt-in via
`@Tmf630JsonbBacked`. Nothing in this document applies to entities marked
JSONB-backed; those follow the JSONB doc's shape and are exempt from every gap
listed here.

The toolkit's filter/sort/paging pipeline claims backend neutrality: it produces standard
`com.querydsl.core.types.Predicate` objects that both `querydsl-mongodb` and `querydsl-jpa`
serialize by construction. That claim is **mostly true on the filter side**, and
**substantially false on the sort side**. This document inventories every current pure-JPA/Mongo
divergence in main-line code, explains why each divergence exists, and asks — for each one
— whether extending pure JPA to close the gap is architecturally feasible, mechanically
practical, or a deliberate scope decision that should stay in place.

---

## 1. Executive summary

| # | Capability | JPA today | Mongo today | Feasible on JPA? |
|---|---|---|---|---|
| 1 | Plain dotted sort (`sort=name,-createdAt`) | works | works | (no gap) |
| 2 | Correlated sort — simple-rich `arr[id=X].leaf` | 400 or silent no-op | full support via `Tmf630MongoCorrelatedSortExecutor` | **Feasible with caveats** — needs a whole new module |
| 3 | Correlated sort — JsonPath `$.arr[?(...)].leaf` | 400 or silent no-op | full support | Feasible; same module as #2 |
| 4 | Sort with `[*]` wildcards / `[N]` positional | 400 or silent no-op | supported | Feasible; same module as #2 |
| 5 | Sort with `min()`/`max()` aggregators | not available | supported | **Impractical portably** — dialect-specific SQL |
| 6 | Sort with `num()`/`str()`/`date()` coercions | not available | supported | Impractical portably — `TRY_CAST` non-uniform across dialects |
| 7 | Nulls-last-regardless-of-direction | inherits DB default | supported (`_hasKey` companion) | **Feasible now**, dialect-aware; small |
| 8 | Filter — array correlation `[?(...)]` | 400 with clear message | supported (Mongo `$elemMatch`) | Feasible on JPA but **not portably** — needs `EXISTS` subquery + JOIN, and only sensibly on JOIN-mapped associations |
| 9 | Filter — positional `[N]` in field paths | 400 with clear message | supported (Mongo dotted numeric path) | Impractical portably — no first-class element-N indexing in JPQL |
| 10 | Base `.isnull` / `.isnotnull` operators | works (`IS NULL` / `IS NOT NULL` via QueryDSL) | works (`$exists` via QueryDSL) | (no gap — QueryDSL maps both) |
| 10a | NULLISH **widening** on top of `.isnull` / `.isnotnull` | intentionally skipped (widening is a toolkit composition, not a QueryDSL op — its OR/AND form breaks under SQL trilean UNKNOWN) | widens to `missing OR null` | **Not needed on JPA** — SQL has only one null state; see §3.5 |
| 11 | `.regex` / `.regexi` / JsonPath `=~` | works, but querydsl-jpa's default templates render `Ops.MATCHES` / `Ops.MATCHES_IC` as `LIKE`, not as `regexp_like` — anchor and metacharacter semantics diverge | works, mapped to native `$regex` with `$options:"i"` | **Feasible per-dialect, not portably** — Hibernate has `regexp_like` for Postgres/Oracle/MySQL/H2 but not SQL Server; would need dialect-specific `Ops.MATCHES` template overrides |

**Bottom line.** Seven of eleven divergences (#2, #3, #4, #7, #8, #10, #11) are already
either fully mitigated or documented as one-backend-only in the README's capability
sections. The remaining gaps split cleanly into two piles: **rich sort** (#2–#6) which
would be the largest single extension the toolkit has taken on and is the primary subject
of this document, and **nulls-last parity** (#7) which is the smallest and highest-leverage
piece of work available. See §5 for recommended packaging.

---

## 2. Where the code decides "am I on JPA or Mongo?"

The toolkit has exactly **four** places where behavior depends on the entity's backend
annotation. All four are string-matched by annotation FQN to avoid a compile-time
dependency on the target framework in the neutral core modules.

| # | Site | What it does |
|---|------|-------------|
| A | `JsonPathFilterPredicateBuilder#isJpaEntity` (`tmf630-toolkit-attribute-filtering-core/.../filtering/JsonPathFilterPredicateBuilder.java:544-551`) | Matches `jakarta.persistence.Entity` on the root type; used by B, C |
| B | `JsonPathFilterPredicateBuilder#predicateForArrayMatch` (line 331-334) | Rejects `[?(...)]` on JPA — `Array correlation in jsonPath filter is supported only for document databases.` |
| C | `JsonPathFilterPredicateBuilder#predicateForComparison` (line 405-408) | Rejects positional `[N]` in `filter=` on JPA — `Positional index [N] in jsonPath filter is supported only for document databases.` |
| D | `PredicateFactory#isMongoRoot` + `#nullish` (`tmf630-toolkit-attribute-filtering-core/.../filtering/predicate/PredicateFactory.java:183-195` and `:153-175`) | Matches `org.springframework.data.mongodb.core.mapping.Document`; used to skip the NULLISH widening (widened OR-of-branches) on JPA because SQL trilean `NOT IN (NULL)` would poison the complement to zero rows |
| E | `Tmf630FilterSettings#allowNestedPathsFor` (`tmf630-toolkit-attribute-filtering-core/.../filtering/config/Tmf630FilterSettings.java:59-69`) | Picks per-backend value of `allow-nested-paths` (JPA vs docdb); not a rejection, just a config split |

There is **no backend-detect anywhere** in the paging/sorting or aggregation modules —
`TmfSortParser`, `TmfPageableHandlerMethodArgumentResolver`, and
`TmfRichPageableHandlerMethodArgumentResolver` are all backend-agnostic. The
Mongo-vs-JPA sort split is enforced entirely by **module presence**: adding the
`tmf630-toolkit-mongo-aggregation` dependency wires the rich-sort executor and the
`TmfRichPageable` binding; without it, controllers can only bind plain `Pageable`, which
400s on any correlated term.

---

## 3. The gaps in detail

### 3.1 Correlated / rich sort (gaps #2–#6)

**Missing on JPA:** the whole `tmf630-toolkit-mongo-aggregation` module. A JPA-backed
service that would like to accept URLs like

```
GET /orders?sort=serviceOrderItem[type=install].service.serviceCharacteristic[name=price].value
GET /orders?sort=$.serviceOrderItem[?(@.state=='completed')].updatedAt
GET /orders?sort=-num(min(serviceOrderItem[*].price.value))
```

has three options today, none good:

- Bind plain `Pageable`. The parser 400s on the correlated term at
  `TmfSortParser.parseInternal` (`.../paging/TmfSortParser.java:70-77`) with
  `Correlated sort terms (jsonpath|simple-rich) are not supported in this context: <expr>`.
- Bind `TmfRichPageable` / `TmfSort`. The parser accepts the term, but there is no JPA
  executor to route it to; the term is stripped from the effective `Sort` (silent no-op
  at `TmfRichPageableHandlerMethodArgumentResolver.java:51`, which forces
  `plainSort = Sort.unsorted()`). The controller receives the `TmfSort` and has to
  implement translation itself.
- Do not accept it at all. The current README documents this: *"JPA-only and other
  non-Mongo services should not add this dependency"* (README §Dependency management,
  Mongo aggregation subsection).

**Why the gap exists in current code.** `Tmf630MongoCorrelatedSortExecutor` is not a
predicate translator — it is a whole-of-request aggregation-pipeline rewrite. It
materializes one `$addFields` stage that computes a synthetic sort key per term
(the correlated array element's value with `$filter` + `$let` + `$first`/`$arrayElemAt`),
a second `$addFields` stage that computes a companion `_hasKey<N>` for nulls-last
handling (`.../mongo/Tmf630MongoCorrelatedSortExecutor.java:61-113`), then a `$sort`
that references both. The MQL operators involved — `$filter`, `$let`, `$map`, `$reduce`,
`$min`, `$max`, `$convert`, `$ifNull`, `$cond`, `$literal`, `$arrayElemAt`, `$slice` —
do not compile down to a standard QueryDSL `Predicate`, which is why the mongo-aggregation
module bypasses the neutral `PredicateFactory` entirely.

**Would a JPA analogue be architecturally similar?** Broadly yes, structurally no:

1. **Predicate correlation `arr[id=X].leaf`.** SQL-portable via an `EXISTS` subquery
   over the JOIN-mapped child collection: `EXISTS (SELECT 1 FROM order.serviceOrderItem
   soi WHERE soi.type = 'install')` — but this only picks *whether* to include the row,
   not *which* element's leaf value to sort by. For sort, the analogue is a **correlated
   subquery in the ORDER BY**: `ORDER BY (SELECT soi.updatedAt FROM order.serviceOrderItem
   soi WHERE soi.type = 'install' LIMIT 1)`. Hibernate 7 supports this via
   `CriteriaBuilder.subquery` inside a Spring Data `Sort` extension, but Spring Data's
   `Sort` doesn't have a first-class hook for correlated subquery expressions — a JPA
   analogue would have to override `findAll(Predicate, Pageable)` in a custom repository
   fragment, emitting JPQL/Criteria directly. This is a **new bean shape**, not a plug-in
   to the existing `QuerydslPredicateExecutor`.

2. **JsonPath predicate correlation `$.arr[?(@.type=='X')].leaf`.** Same story as (1) —
   the two grammars share `JsonPathSortAst`, so a JPA translator would consume the
   same AST.

3. **Positional `[N]`.** No first-class JPQL for element-N indexing on a JOIN-mapped
   collection. The workaround (a correlated subquery with `LIMIT 1 OFFSET N`) is
   Postgres/Oracle/MySQL/H2-portable but **loses stable ordering** unless the child
   entity has an `@OrderColumn` or the child table has a persisted index column. Silent
   ordering drift is worse than a 400.

4. **`[*]` wildcards.** For predicate JOINs, `[*]` is the trivial default — no
   correlation. For sort, `[*].leaf` on JPA would need `ARRAY_AGG` / `STRING_AGG` or a
   window function to fold the child rows into a single sort key per parent, and neither
   of those are portable across dialects that the toolkit's downstream applications
   actually deploy on (Oracle 19 vs Postgres 15 vs SQL Server 2022 vs H2 all differ).

5. **`min()` / `max()` aggregators.** `MIN(soi.updatedAt)` inside a correlated subquery
   is portable and clean. This is the *easiest* piece of the rich-sort feature on JPA —
   for sort by an aggregate the correlated subquery pattern is uniform.

6. **`num()` / `str()` / `date()` coercions.** `TRY_CAST` semantics diverge:
   - PostgreSQL 15+: `CAST(x AS numeric)` throws; the closest portable form is a
     `CASE WHEN x ~ '^-?[0-9]+(\.[0-9]+)?$' THEN CAST(x AS numeric) ELSE NULL END`
     — regex-based, breaks on non-Postgres.
   - Oracle: `CAST(x AS NUMBER DEFAULT NULL ON CONVERSION ERROR)` — clean but
     Oracle-only.
   - SQL Server: `TRY_CAST(x AS decimal)` — clean but SQL-Server-only.
   - MySQL 8: no `TRY_CAST` at all; needs `CASE WHEN` fallback.
   - H2: has `TRY_CAST` (2.x) but with quirks around numeric precision.
   
   The Mongo path is uniform because BSON has `$convert` with `onError: null`.
   Emitting a `Sort.Order` on JPA that does the equivalent means **per-dialect codegen**,
   which the toolkit has zero precedent for. This is where the "Feasible on JPA?"
   column in §1 says **Impractical portably**.

**Effort estimate for a JPA-side rich-sort module** (call it
`tmf630-toolkit-jpa-correlated-sort`):

| Feature | Complexity | Portable? |
|---|---|---|
| Simple-rich predicate correlation (single hop) | medium | Yes |
| Simple-rich predicate correlation (multi hop) | medium-high | Yes |
| JsonPath predicate correlation | (free — reuses AST) | Yes |
| Positional `[N]` | high | **No** — needs `@OrderColumn` on the child |
| `[*]` wildcards | high | **No** — dialect-specific aggregate |
| `min()`/`max()` outer wrapper | medium | Yes |
| `num()`/`str()`/`date()` coercions | high | **No** — per-dialect codegen |
| Nulls-last-regardless-of-direction | (bundled here, see §3.2) | Postgres/Oracle yes, MySQL/H2 needs synthetic column |

Best-case scope for a first cut: the "Yes" rows only. That's a **useful subset** — the
canonical TMF characteristics pattern `arr[key=X].value` sort — and it's plausibly a
**3–5 week** implementation for one engineer, versus 3–6 weeks for the JSONB backend
sketched in memory. Bulk of the work is the correlated-subquery builder and its
interaction with Spring Data's `Sort` (which does not natively accept subquery
expressions).

**Verdict.** A first-cut correlated-sort executor for JPA (aggregator-free,
positional-free, coercion-free) is architecturally feasible as a new
`tmf630-toolkit-jpa-correlated-sort` module. The `[*]`, `[N]`, and coercion features
should be **explicitly out of scope for JPA** and rejected at parse time with clear
messages naming the escape hatch (repository-level Criteria for anything the grammar
can't cover). This matches the existing pattern
([[feedback_scoped_rejection_over_architectural_change]] as codified in the 2.1.5
`length()` decision): drop capability to preserve grammar cross-backend consistency,
document escape hatches for the excluded cases.

### 3.2 Nulls-last-regardless-of-direction (gap #7)

**Missing on JPA:** any control over null position in `Sort.Order`. The toolkit
constructs orders at exactly one place: `TmfSort.toPlainSort`
(`tmf630-toolkit-paging-sorting-core/.../paging/TmfSort.java:40-45`):

```java
orders.add(new Sort.Order(term.direction(), term.expression()));
```

No `.nullsLast()` / `.nullsFirst()` / `.withNullHandling(...)` call anywhere in the
paging modules. The result: JPA inherits whatever the database defaults to —
Postgres/Oracle put NULLs last for ASC, MySQL/H2/SQL Server put them first.

**Why the gap exists.** The Mongo side gained nulls-last-regardless-of-direction in
2.1.1 via a whole-pipeline transformation (the `_hasKey<N>` companion field prepended
ascending to the `$sort` document at
`Tmf630MongoCorrelatedSortExecutor.java:61-113`). Because the JPA path uses a plain
`Sort.Order` handed to `QuerydslPredicateExecutor.findAll(Pageable)`, there is no
symmetric hook — you'd need to either (a) decorate the `Sort.Order` with
`NullHandling.NULLS_LAST` (Spring Data supports this, Hibernate renders it as native
`NULLS LAST` on Postgres/Oracle), or (b) emit a synthetic `CASE WHEN x IS NULL THEN 1
ELSE 0 END` companion column for dialects that don't support native `NULLS LAST`
(MySQL, H2, older SQL Server).

**Extension shape.** This is genuinely small:

- Add `NullHandling` to `TmfSort.SortTerm` (currently just direction + expression).
- Read `opentmf.tmf630.paging.nulls-last` (new property, default `false` to preserve
  back-compat).
- In `TmfSort.toPlainSort`, when the property is `true`, decorate every order with
  `.nullsLast()`.
- For dialects that don't render `NULLS LAST` natively, Hibernate 6/7 already emits
  the `CASE WHEN` fallback automatically — there's no toolkit work per dialect. Verified
  in Spring Data JPA + Hibernate 7 as of Spring Boot 4.1 (the parent BOM).
- Document the perf caveat: on MySQL/H2, `CASE WHEN` prevents index-only sort
  scans; the property should be opt-in per environment, not default-on.
- Test: extend `Tmf630PredicateSqlIT` with a null-position parity IT that asserts
  emitted SQL contains `nulls last` (Postgres) or the expected fallback (H2 default
  in tests).

**Verdict.** This is the highest-leverage extension in the document — one file changed
in the core module, one property, minimal risk of regression, closes a genuine
cross-backend inconsistency. **Recommend prioritizing this independently** of the
larger rich-sort decision. It could ship in 2.1.6 as a standalone item.

### 3.3 Filter — array correlation `[?(...)]` (gap #8)

**Current behavior.** 400 on JPA at `JsonPathFilterPredicateBuilder.java:331-334`. Mongo
translates via `MongodbOps.ELEM_MATCH` reflectively loaded (`resolveElemMatchOperator`,
line 623-637) — the QueryDSL `Ops.ELEM_MATCH` doesn't exist in the core, so the builder
uses a runtime probe to avoid a compile-time dep on querydsl-mongodb in the neutral
module.

**Would extension work on JPA?** For the common case of a JOIN-mapped one-to-many
association (`Order.serviceOrderItem` is a `@OneToMany`), yes — the semantic equivalent
is an `EXISTS` subquery: `EXISTS (SELECT 1 FROM order.externalReference er WHERE er.name
= 'ORDER_REFERENCE' AND er.id = 'OPCO-ORDER-012')`. This is portable JPQL and Hibernate
handles it uniformly across dialects.

**But** — the JsonPath `[?(...)]` grammar is expressive:
- Multi-condition per element: fine, translates 1:1 to the `AND` inside the subquery.
- Nested `[?(...)]` (correlation inside correlation): needs nested `EXISTS` subqueries.
  Doable; adds parser depth to the QueryDSL side.
- Field-negation `!@.field`: fine, becomes `IS NULL OR NOT ... ` in the subquery.
- Regex `=~`: has the same portability problem as gap #11 — Hibernate maps regex
  operators to `LIKE`, so `=~ /D.*/i` inside a correlated subquery would drift
  semantically for anchor/character-class patterns.
- `length() == N` on the correlated collection: JPA `SIZE(er) = N` — already supported
  as a top-level operator (2.1.5).

**Constraint that limits utility.** The JPA analogue requires the correlated collection
to be **JOIN-mapped** as an association (`@OneToMany` / `@ManyToMany` / `@ElementCollection`).
It does **not** work on:
- Embedded objects serialized as columns (JSON columns without `@JdbcTypeCode(SqlTypes.JSON)`).
- Denormalized fields that aren't relational children.
- Non-entity DTOs used as query projections.

Mongo has no such restriction: `$elemMatch` traverses any embedded array. If we lift
the JPA rejection, we'd have to detect the traversed path shape (is `externalReference`
a JOIN? or an embeddable? or a JSON column?) and either succeed or 400 based on
mapping — a substantial reflection-into-Hibernate move that the neutral core module
would need indirection to make.

**Verdict.** Feasible in a **future release** as part of a JPA-first-class effort, but
requires either Hibernate 7 metamodel introspection at parse time (adds a compile-time
dep to attribute-filtering-core — anti-pattern per the module's own contract) OR a
runtime probe (feels fragile). **Recommend deferring** until the JSONB backend decision
is made — a JSONB filter path via `jsonb_path_exists` supports `[?(...)]` natively on
Postgres, which would give us three-backend parity without extending JPA-relational.

### 3.4 Filter — positional `[N]` in field paths (gap #9)

**Current behavior.** 400 on JPA at `JsonPathFilterPredicateBuilder.java:405-408`. Mongo
resolves as a dotted numeric path (`productOrderItem[2].state` →
`productOrderItem.2.state`) that BSON walks natively.

**JPA extension analysis.** Same issue as sort positional `[N]` (§3.1 row 3): JPQL has
no first-class element-N indexing for JOIN-mapped collections. Correlated subquery
with `LIMIT 1 OFFSET N` works but requires stable ordering, i.e. `@OrderColumn` on the
association. That's an entity-model assumption the toolkit cannot make.

**Verdict.** **Do not extend.** Keep the 400 as-is; the message is clear
(`Positional index [N] in jsonPath filter is supported only for document databases.`).
The JSONB backend, if it materializes, will handle this via `jsonb_path_exists(payload,
'$.productOrderItem[2].state ? (@ == "completed")')` natively.

### 3.5 NULLISH widening (gap #10a)

**Important framing.** The **base** `.isnull` / `.isnotnull` operators are fully
supported on JPA via QueryDSL — `Ops.IS_NULL` / `Ops.IS_NOT_NULL` render as SQL
`IS NULL` / `IS NOT NULL` in the standard way. There is *no gap in the base operator*.

What differs between backends is the toolkit's opt-in **NULLISH widening semantic**
(configured via `opentmf.tmf630.attribute-filtering.isnull-semantics=NULLISH`, default
`MISSING_ONLY`). That widening is a *toolkit-added composition on top of the base
operator*, not a QueryDSL op itself:

- `path.isNull() OR path.in([null])` for IS_NULL
- `path.isNotNull() AND path.notIn([null])` for IS_NOT_NULL

On Mongo it serializes to `$or:[{$exists:false},{$in:[null]}]` and folds missing +
explicit-null matches into one predicate. On JPA the toolkit **skips the widening**
via `isMongoRoot(root)` at `PredicateFactory.java:156` for two reasons that are neither
about QueryDSL support nor about JPA capability:

1. **Redundant on SQL.** SQL scalar columns have only one null state. `IS NULL` already
   matches every row where the value is "no value" — there is nothing to widen to.
2. **The complement would break.** `NOT (IS NULL OR IN (NULL))` under De Morgan is
   `IS NOT NULL AND NOT IN (NULL)` → `IS NOT NULL AND UNKNOWN` → UNKNOWN → zero rows
   for every `.isnotnull` query. That's an SQL trilean-logic problem, not a QueryDSL
   or JPA limitation.

See `PredicateFactory.java:112-152` Javadoc for the full derivation and
[[feedback_cross_backend_predicate_traps]] for the incident history.

**Documentation status.** Already documented in README §Null-check widening.

**Verdict.** No action. This is a case where the same URL correctly produces different
serializations on the two backends because the two backends have different null-state
models — SQL has one null state, BSON has two (missing + explicit null). The JPA no-op
is semantically correct; skipping the widening on JPA is the right behavior, not a
shortfall. The base `.isnull` / `.isnotnull` operators themselves have full
cross-backend parity.

### 3.6 `.regex` / `.regexi` / `=~` semantic drift (gap #11)

**Important framing.** The operators are **supported by QueryDSL on JPA** — they map
`Ops.MATCHES` and `Ops.MATCHES_IC` and neither URL returns 400. The issue is *what
those ops render to*.

**What actually happens.** In querydsl-jpa's `HQLTemplates`, `Ops.MATCHES` /
`Ops.MATCHES_IC` render as `LIKE` (case-sensitive) / `LOWER(x) LIKE LOWER(?)`
(case-insensitive), **not** as `regexp_like` or the dialect's native regex operator.
Hibernate does not translate regex metacharacters (`^`, `$`, `.`, `*`, `?`, character
classes) into their `LIKE` equivalents (`%`, `_`) — they are passed through as literal
characters. Verified live by the current SQL IT at
`Tmf630PredicateSqlIT.java:126-151` (`patternOperandsAreReflected`) and at
`Tmf630PredicateSqlIT.java:268-287` (`regexEqualsTildeInFilterLandsOnJpa`):

```java
// Line 144: Querydsl StringPath.matches is rendered as LIKE for this stack/dialect
// Line 270: QueryDSL-JPA's MATCHES_IC serializer … converts them to LIKE. Use an
//           anchor-free pattern
```

So the same URL:
- `?name.regex=^A.*` on Mongo → `{name: {$regex: "^A.*"}}` → matches names starting
  with `A`.
- `?name.regex=^A.*` on JPA → `name LIKE '^A.*'` → matches names *literally* starting
  with `^` followed by `A`, then a literal `.` and literal `*`. In practice, nothing.

Both requests return 200; both go through a QueryDSL-supported operator; both hit the
database. But the row sets returned diverge silently because `LIKE` treats regex
metacharacters as literal text.

**On Mongo,** the same operator produces a real regex via `$regex` + `$options:"i"`
(comment at `PredicateFactory.java:227-228` confirms the intent). So this isn't a
JPA-doesn't-support-regex problem — it's a querydsl-jpa-renders-regex-as-LIKE choice
in the default templates.

**Extension analysis — what would real regex on JPA cost?**

Hibernate 6/7 has `regexp_like` in its dialect function registry for many databases:
- Postgres: `~` / `~*` operators (native regex).
- Oracle: `REGEXP_LIKE(x, pattern)` (native regex).
- MySQL 8: `REGEXP` / `RLIKE` (native regex).
- H2: `REGEXP` (native regex).
- SQL Server: no first-class regex until SQL Server 2025 (RTM date TBD).

The raw dialect capability is there for 4 of the 5 common backends. But wiring it into
QueryDSL requires **overriding the `Ops.MATCHES` / `Ops.MATCHES_IC` template**, e.g.
via `Expressions.booleanTemplate("regexp_like({0}, {1})", path, constant)` inside
`PredicateFactory.regex` / `regexIgnoreCase`. That:

1. Requires dialect detection at predicate-emit time (or a per-application config
   knob to declare "my dialect supports regexp_like"). The toolkit has no dialect-detect
   surface today.
2. Breaks on SQL Server pre-2025 — which means we couldn't turn it on by default even
   for JPA-only services without a per-deployment gate.
3. Needs different templates for case-insensitive: `regexp_like(x, ?, 'i')` on Oracle,
   `x ~* ?` on Postgres, `LOWER(x) REGEXP LOWER(?)` on MySQL, `REGEXP` (case-insensitive
   by default in some collations) on H2. Uniform QueryDSL surface across those requires
   per-dialect templates, not one.

That's what I meant by "impractical portably" — the JPA capability exists per-dialect
but there is no single QueryDSL template that fits all backends we might land on. This
is different from the sort-side coercion story (§3.1 row 6), which is also per-dialect
but can't even be shimmed uniformly because the *language* differs (`TRY_CAST` vs.
`CASE WHEN`).

**Options:**

- (a) **Reject `.regex` / `.regexi` on JPA at parse time** unless a new opt-in property
  (`opentmf.tmf630.attribute-filtering.regex.allow-jpa-like-semantics=true`) confirms
  the caller understands they get `LIKE` semantics. Small change. Breaks any consumer
  today relying on the (broken) drift, so this needs a deprecation cycle: warn-log for
  one release, hard-reject the following. Consistent with the scoped-rejection principle
  ([[feedback_scoped_rejection_over_architectural_change]]).
- (b) **Emit dialect-specific `regexp_like` on JPA** via a small `RegexTemplateFactory`
  wired at autoconfigure time (`opentmf.tmf630.attribute-filtering.regex.jpa-dialect=postgres|oracle|mysql|h2|sqlserver`,
  no default — service owner must declare). Bigger change; introduces the first
  dialect-aware knob in the toolkit; SQL Server pre-2025 must still fall back to `LIKE`
  or reject.
- (c) **Do nothing.** Keep the silent drift; document it prominently in the README's
  "JPA `filter=` support scope" section (which the compliance summary already gestures
  at but doesn't spell out the anchor/metacharacter divergence).

**Verdict.** Option (a) is the highest-integrity fix and the one I'd recommend for
2.1.6 or 2.2.0. Option (b) is a bigger investment that only pays off if enough
downstream services actually want portable regex on their JPA backends to justify the
dialect-detect surface. Option (c) leaves the trap in place; not great given the
[[feedback_filtering_both_backends_it_rule]] standing rule.

---

## 4. The JSONB backend factor

Per [[project_jsonb_backend_sketch]] (upgraded 2026-07-20 to "planned in the near
future"), a third backend — PostgreSQL with a `jsonb` payload column — is on the
roadmap. Every extension decision in this document should be weighed against what
JSONB does natively:

| Gap | JPA-relational fix effort | JSONB native support |
|---|---|---|
| Correlated sort (`arr[id=X].leaf`) | high (correlated subquery, new module) | `jsonb_path_query_first(payload, '$.arr[*] ? (@.id == "X").leaf')` → clean |
| Correlated filter `[?(...)]` | medium (only for JOIN-mapped) | `jsonb_path_exists(payload, '$.arr[*] ? (@.name == "X" && @.id == "Y")')` → **cleaner than Mongo** per JSONB memory |
| Positional `[N]` | impractical (needs `@OrderColumn`) | `jsonb_path_query_first(payload, '$.arr[N]')` → clean |
| `[*]` wildcards, `min`/`max`, coercions | impractical portably | `jsonb_path_query` + `jsonb_typeof` — mostly clean on Postgres 15+ |
| Nulls-last | already Postgres-native | already Postgres-native |
| `.regex` / `.regexi` | dialect-specific | `@?` with regex predicate — clean on Postgres |

**Read of this:** if the JSONB backend lands within the next 2–3 minor releases, it
will close **most** of the JPA-relational gaps in a way that JPA-relational cannot,
because JSONB is a document-shaped backend that happens to live in Postgres. The
JPA-relational extensions that make sense are the ones that **don't** duplicate what
JSONB will do:

- **Nulls-last parity** (§3.2) — worth doing for JPA-relational regardless, because
  it's small and independent.
- **Simple correlated sort with `arr[key=X].value`** (§3.1) — worth doing for
  JPA-relational because there's a large installed base of relational-only services
  that won't migrate to JSONB.
- **Everything else** — the case is thinner. Waiting for JSONB avoids per-backend
  raw-emission surface (per [[feedback_scoped_rejection_over_architectural_change]]).

---

## 5. Packaging — all in one release cut

**Decision (2026-07-26):** everything in this document ships as part of **v3.0.0**,
alongside the PostgreSQL_with_JSONB backend (`JSONB_BACKEND_DESIGN.md`) and the split-and-merge
patterns for both JSONB and Mongo. The sequenced roadmap lives in
[`V3_ROADMAP.md`](./V3_ROADMAP.md); this section previously listed three
alternative bundles (A/B/C) but has been superseded by the "one release, everything"
decision.

**JPA-side work maps to roadmap Phase (a):**

| Roadmap ID | This document reference | Scope |
|---|---|---|
| **a.1** | §3.2 | Nulls-last-regardless-of-direction property + IT parity |
| **a.2** | §3.6 | `.regex`/`.regexi` strict-backend rejection with deprecation cycle |
| **a.3** | §3.3 | Filter array correlation `[?(...)]` for JOIN-mapped associations |
| **a.4** | §3.1 | New `tmf630-toolkit-jpa-correlated-sort` module — simple-rich + JsonPath grammars, JOIN-mapped only, `min()`/`max()` aggregators, nulls-last honored; **explicit rejection** for `[N]`/`[*]`/`num()`/`str()`/`date()` |

Total Phase (a) effort: **~5–6 weeks** (see V3_ROADMAP.md §2 for sub-milestone
breakdown).

**What stays out of scope for v3.0.0** — dialect-portable `regexp_like` for JPA,
positional `[N]` in JPA sort/filter, `[*]` in JPA sort, `num()`/`str()`/`date()`
coercion in JPA sort. These remain "impractical portably" per this document's per-gap
analysis; consumers who need them should use the JSONB backend (Phase (b) in
V3_ROADMAP.md) where they are natively supported.

---

## 6. Open questions for maintainer decision

All questions previously listed have been resolved during the design discussion that
produced V3_ROADMAP.md. Kept here as a decision-log for future readers:

| # | Question | Resolution |
|---|---|---|
| 1 | Nulls-last (§3.2): independent item, or bundle with something bigger? | Bundled into v3.0.0 Phase (a.1) |
| 2 | Regex-on-JPA (§3.6): reject with deprecation, or leave silent drift? | Reject with deprecation cycle — v3.0.0 Phase (a.2) |
| 3 | Rich sort on JPA (§3.1): build it or wait for JSONB? | **Both** — Phase (a.4) and Phase (b) both ship in v3.0.0 |
| 4 | Public API shape for JPA correlated-sort module | Bean-transparent (add to controller by adding the dependency), consistent with existing Mongo aggregation module — confirmed in V3_ROADMAP.md §8 open Q5 |
| 5 | Version of the toolkit for JPA rich sort | **v3.0.0** (bump `pom.xml` from `2.1.6-SNAPSHOT` at start of work) |
