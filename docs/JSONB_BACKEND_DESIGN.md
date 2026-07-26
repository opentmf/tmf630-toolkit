# PostgreSQL + JSONB backend — design proposal

**Status:** design proposal, not a spec
**Written against:** `2.1.6-SNAPSHOT`
**Companion doc:** [`JPA_BACKEND_GAP_ANALYSIS.md`](./JPA_BACKEND_GAP_ANALYSIS.md) — many
of the JPA-relational limitations this backend would close are catalogued there.
**Origin:** the JSONB backend was originally parked after a 2026-04-16 discussion;
upgraded 2026-07-20 to "planned in the near future" during the 2.1.5 `length()`
design session, where it began actively shaping decisions (see the "avoid
per-backend raw-emission surface" principle in the length() CHANGELOG entry).

This document proposes the shape of a **third attribute-filtering backend** —
PostgreSQL with a `jsonb` payload column — sitting alongside the existing JPA and
MongoDB backends. It answers:

1. **Table structure** — one table per TMF entity, with which columns and why
2. **Joins** — what is supported (intra-payload traversal), what is not (cross-table
   joins), and where the escape hatch lives
3. **Indexing** — how to make GIN pay off in practice and what expression indexes
   downstream services will need
4. **QueryDSL integration** — which of three approaches keeps the URL grammar
   backend-neutral without pulling in a maintenance burden
5. **Operator translation** — a concrete `TmfOperator`-to-SQL table for the 24
   operators the toolkit currently ships
6. **Sort and paging** — including the correlated-sort story that closes the biggest
   JPA gap
7. **Entity model, migration story, testing, and effort estimate**

Framing (per memory): **not "replace MongoDB."** This is *"TMF payload filtering on
JSONB with GIN, as a cheaper Postgres-based alternative for services whose data is
document-shaped and don't need cross-entity JOIN queries."* Every scope decision below
respects that framing — feature-parity claims against Mongo balloon the project;
feature-parity claims against JPA-relational miss the point.

---

## 0. Three backends, per-entity opt-in

Adding JSONB brings the toolkit to **three distinct storage backends**, and it's
important these are not conflated:

| Backend | Query path | Write path | When to pick it |
|---|---|---|---|
| **MongoDB** | `querydsl-mongodb` → BSON | spring-data-mongodb | Document-shaped payloads on Mongo; existing services; correlated-sort via `tmf630-toolkit-mongo-aggregation` |
| **Pure JPA** | `querydsl-jpa` → JPQL | spring-data-jpa (Hibernate) | Normalized relational schemas; any Hibernate-compatible DB (Postgres, Oracle, MySQL, H2, SQL Server); cross-entity JOINs |
| **PostgreSQL-JSONB** | Toolkit's `JsonbPredicateSerializer` → native JSONB SQL (`->>`, `@>`, `jsonb_path_exists`, ...) | spring-data-jpa (Hibernate) for entity metadata and audit fields; `JdbcClient` for the actual JSONB query SQL | Document-shaped payloads on Postgres for cost/ops reasons; no cross-entity JOINs needed on the payload path |

Two things to notice:

1. **PostgreSQL is present in TWO backends.** "Pure JPA on Postgres" and
   "PostgreSQL-JSONB" are different modes even though the database is the same. Pure
   JPA on Postgres uses normalized tables with FK relationships, queried via
   Hibernate-generated SQL. PostgreSQL-JSONB uses `jsonb` payload columns queried via
   toolkit-generated JSONB SQL. They can even coexist in one deployment on one
   database — different tables, different modes.
2. **Pure JPA and PostgreSQL-JSONB share the write path.** Both use `@Entity`,
   `spring-data-jpa`, Hibernate, and the audit annotations from §1.3. The divergence
   is entirely on the read/query path — how a `Predicate` (or a `TmfSort`) is
   translated into SQL. This is why the recommended entity model in §7 uses `@Entity`
   for both.

### 0.1 Per-entity opt-in via annotation, default is pure JPA

Backend mode is selected **per entity, via a toolkit annotation on the row entity
class.** The default (no annotation) is pure JPA, preserving today's behavior. Opt
individual entities into PostgreSQL-JSONB by adding `@Tmf630JsonbBacked`:

```java
@Entity
@Table(name = "product_order")
@Tmf630JsonbBacked          // <-- opt into JSONB query path for this entity
@EntityListeners(AuditingEntityListener.class)
public class ProductOrderRow {

  @Id
  private String id;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode payload;

  // ... audit fields per §1.3
}
```

Without `@Tmf630JsonbBacked`, the same entity would be treated as pure JPA — the
toolkit would try to filter/sort on entity fields (not on `payload->>'...'`) via
`querydsl-jpa`. Mongo detection stays as it is today (annotation-name string match
on `@Document`, see `PredicateFactory.isMongoRoot` in the JPA gap doc §2).

**Why an annotation rather than a configuration property listing class FQNs:**

- Refactor-safe. A property listing `com.example.ProductOrderRow` breaks silently if
  the class is renamed. An annotation moves with the class.
- Colocated with the code that needs to know. A reader of `ProductOrderRow` sees the
  storage decision immediately; a reader of `application.yml` has to know to look for
  it.
- Consistent with how the toolkit's other backend cues work — `@Document` for Mongo,
  `@Entity` for JPA, both annotation-based.

**Why the default is pure JPA, not JSONB:**

- Existing services (pure JPA) keep working unchanged. Adding the toolkit's JSONB
  module to the classpath doesn't change behavior for entities that don't opt in.
- JSONB is a specialized mode with real trade-offs (§2 GIN indexing, §6 type coercion
  strictness, §3.2 no cross-table JOINs). Making it explicit is safer than making it
  the default.

### 0.2 Mixed-mode services

A service can have some entities on pure JPA and others on PostgreSQL-JSONB in the
same deployment, sharing one database. Example: a service that keeps `Customer` as a
normalized relational entity (many FK-linked address/contact rows, cross-entity
JOIN queries in dashboards) but stores `ProductOrder` as a JSONB payload
(document-shaped, no JOINs needed). Two row classes, one has `@Tmf630JsonbBacked`,
the other doesn't. Two repositories, each routed to its own predicate serializer.
One `spring-data-jpa` bean set, one Hibernate `EntityManagerFactory`, one Postgres
connection pool.

The autoconfiguration hooks (see §7.2) inspect each `@RequestMapping` handler's
bound `@QuerydslPredicate root` type, walk to its associated row entity, and route
to the JSONB path or the pure-JPA path based on the annotation.

### 0.3 Naming clarification for the companion JPA doc

The sibling [`JPA_BACKEND_GAP_ANALYSIS.md`](./JPA_BACKEND_GAP_ANALYSIS.md) uses
"JPA" throughout to mean **pure JPA** as defined in this table's row 2 — the current
`querydsl-jpa`-driven query path against normalized relational tables. Every gap
that document catalogues (correlated sort, array correlation, positional `[N]`,
regex-as-LIKE) is a **pure-JPA gap**. Most of them do not apply to PostgreSQL-JSONB
because JSONB has native operators for those cases (§3.1 in this document
enumerates which). If you're reading the JPA doc and the JSONB doc side by side,
mentally substitute "pure JPA" wherever the JPA doc says "JPA".

---

## 1. The table shape

### 1.1 Two shapes: non-versioned vs versioned entities

TMF distinguishes **non-versioned entities** (a single logical `Customer`, `Alarm`,
`Party` — one row per `id`, updates mutate in place) from **versioned entities**
(`ProductSpecification`, `ProductOfferingPrice`, `ServiceCatalog` — a business-level
`version` string like `"1.0"`, `"1.1"`, `"2.0"` identifies each catalog revision, and
multiple versions of the same `id` coexist). Different PKs, one table per entity in
both cases.

**Non-versioned entity — single-column PK:**

```sql
CREATE TABLE customer (
  id           VARCHAR(50)  NOT NULL,
  payload      JSONB        NOT NULL,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  opt_lock     INTEGER      NOT NULL DEFAULT 0,
  PRIMARY KEY (id)
);
```

**Versioned entity — composite PK on `(id, version)`:**

```sql
CREATE TABLE product_specification (
  id           VARCHAR(50)  NOT NULL,
  version      VARCHAR(20)  NOT NULL,
  payload      JSONB        NOT NULL,
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  opt_lock     INTEGER      NOT NULL DEFAULT 0,
  PRIMARY KEY (id, version)
);
```

The `version` column here is the **TMF business version** materialized from the
payload (`payload->>'version'`) so it can participate in the PK and index scans. It is
NOT Hibernate's optimistic-lock counter — that lives in a separate column (§1.3).

**Column rationale:**

- **`id VARCHAR(50)`** — TMF entity id, matches the Mongo `_id` string shape. Not
  SERIAL/UUID because TMF ids are external strings assigned by the producing service.
  Length 50 is comfortable for standard TMF id conventions; adjust per entity if a
  specific TMF resource type documents a longer id.
- **`version VARCHAR(20)`** (versioned only) — TMF business version. Kept
  human-readable (`"1.0"`, `"2.0-draft"`) rather than an integer because that's how
  TMF resources natively express version.
- **`payload JSONB`** — the full TMF resource, exactly as it would appear in a Mongo
  document (or in an HTTP response body). This is the filterable/sortable surface.
- **`created_at` / `updated_at` / `opt_lock`** — audit and locking columns outside
  the payload so they don't compete with payload-side timestamps and are always
  queryable with plain SQL operators (no `->>` cast needed). §1.3 explains why these
  columns' names are arbitrary and how the toolkit discovers them.

**Detection.** The toolkit reads the row entity's `@Id` / `@IdClass` / `@EmbeddedId`
structure to decide non-versioned vs versioned automatically. No new toolkit-specific
annotation needed on the entity to declare which shape it is.

**What deliberately isn't there:**

- No discriminator column for entity type. Each TMF resource type gets its own table —
  see §1.2 for why not one shared table.
- No `tenant_id` in the base shape. Multi-tenancy is a per-deployment decision; the
  toolkit shouldn't be opinionated. Services that need it add `tenant_id VARCHAR NOT
  NULL` to the composite PK and to every WHERE clause via a base-predicate hook.
- No indexed extracted scalar columns. Those come as **expression indexes** in §2,
  not as denormalized columns — expression indexes cost less to maintain and don't
  require a write-time projection layer.
- No full-history variant (multiple revisions of a non-versioned entity's row kept as
  audit trail). That's a service-level concern outside the toolkit's scope; if a
  service wants an audit table, it lives alongside the primary table via a Postgres
  trigger or an outbox pattern.

### 1.2 Why one table per entity, not one shared table with a discriminator

The single-shared-table pattern (`documents(id, entity_type, payload)`) looks
attractive — one migration, one repository, one autoconfigure bean. It falls over on
two counts:

1. **Indexing is per-shape, not per-table.** A GIN index on `payload jsonb_path_ops`
   covers containment queries but each expression index (`ON t ((payload->>'state'))`)
   is field-specific. Different TMF resource types have different hot query paths
   (`ProductOrder.state` vs `Customer.name` vs `Alarm.severity`) and the union of hot
   fields across all types produces an index sprawl on the shared table. Per-entity
   tables let each service pay for the indexes it actually queries.
2. **PK collisions across types.** TMF `id` uniqueness is per-resource-type, not
   global. A shared table would need `(entity_type, id)` PK, adding a discriminator to
   every read/write. Combined with the versioned-entity shape (`id + version`), the
   shared table would need `(entity_type, id, version)` — three-column PK on every
   row.

The counterargument — "we don't want dozens of migrations" — is real but modest;
Postgres migration tooling (Flyway, Liquibase) handles per-table migrations well.

### 1.3 Audit columns and optimistic locking — annotation-driven, column names flexible

The `created_at` / `updated_at` / `opt_lock` columns in §1.1's DDLs are **examples,
not fixed names**. The toolkit discovers them by scanning the row entity for Spring
Data JPA's audit annotations and JPA's `@Version` annotation. Whatever the service
names the columns, the toolkit uses the annotation to know what each field is for.

**The five annotations the toolkit recognizes:**

| Annotation | Purpose | Managed by |
|---|---|---|
| `@CreatedDate` | Timestamp when the row was first persisted | Spring Data JPA (`AuditingEntityListener`) |
| `@LastModifiedDate` | Timestamp of the most recent update | Spring Data JPA |
| `@CreatedBy` | Principal identifier at creation time | Spring Data JPA + `AuditorAware<T>` bean |
| `@LastModifiedBy` | Principal identifier at last update | Spring Data JPA + `AuditorAware<T>` bean |
| `@Version` (JPA) | Optimistic-lock counter | Hibernate / JPA provider |

The service enables auditing the standard Spring way — `@EnableJpaAuditing` on a
`@Configuration` class, `@EntityListeners(AuditingEntityListener.class)` on the row
entity, and (if using `@CreatedBy` / `@LastModifiedBy`) an `AuditorAware<T>` bean that
returns the current principal.

**Example row entity with all five annotations:**

```java
@Entity
@Table(name = "product_specification")
@EntityListeners(AuditingEntityListener.class)
public class ProductSpecificationRow {

  @EmbeddedId
  private ProductSpecificationKey key;   // id + version composite

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode payload;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @CreatedBy
  @Column(name = "created_by", updatable = false)
  private String createdBy;

  @LastModifiedBy
  @Column(name = "updated_by")
  private String updatedBy;

  @Version
  @Column(name = "opt_lock", nullable = false)
  private Integer optLock;

  // getters/setters
}
```

Column names above (`created_at`, `updated_at`, `created_by`, `updated_by`, `opt_lock`)
are chosen by the service. The toolkit reads the annotation, not the column name.

**Why this matters for the toolkit.** The audit and optimistic-lock columns are
**server-managed** — Hibernate and Spring Data write to them, never the client. When
the toolkit generates the SELECT for a filter/sort query, it doesn't need to know
these columns exist; when the toolkit deserializes the result row into a domain
model (§7), it uses the `payload` column alone. The audit columns exist for the
service's own bookkeeping (audit logs, "when was this last touched?", "who edited
this?") and are visible via the row entity if the service wants to expose them, but
they don't flow into the query pipeline.

**Two "versions" to keep straight.** For versioned entities, there are now two
distinct version-like columns and reading the code needs both to be labeled:

- **`version VARCHAR(20)`** in the PK — the **TMF business version** (a
  human-meaningful string that identifies which revision of a catalog resource the
  row represents). Part of the resource identity.
- **`opt_lock INTEGER`** annotated `@Version` — Hibernate's **optimistic-lock
  counter** (an integer bumped on every UPDATE to detect concurrent writes). Not part
  of the resource identity; not visible to clients.

Naming the second column `opt_lock` or `optimistic_lock_version` (rather than
`version`) in the DDL is deliberate to avoid the visual clash with the TMF `version`
column.

### 1.4 Out of scope: POST/PATCH payload validation for server-managed fields

Preventing clients from setting audit-managed fields (e.g. rejecting a POST that
includes `{"createdDate": "..."}` where `createdDate` is server-computed) is a
**write-side** concern — validating incoming request bodies. The tmf630-toolkit is
currently **query-side only**: it parses query parameters into predicates and shapes
response bodies via `@Tmf630Response`. It does not touch POST/PATCH request-body
validation.

Adding write-side validation is a legitimate future direction but explicitly
**not part of this JSONB backend design**. Services that need it should validate at
the controller / mapper layer with their own logic (or a small utility) until the
toolkit takes on write-side scope as a separate initiative. Doing it in the JSONB
module only would grow per-backend feature surface — the exact anti-pattern from
[[feedback_scoped_rejection_over_architectural_change]]. When we do this, it should
apply to Mongo and JPA-relational too, in one cross-backend feature.

---

## 2. Indexing

### 2.1 GIN index on the payload for containment queries

```sql
CREATE INDEX product_order_payload_gin
  ON product_order
  USING GIN (payload jsonb_path_ops);
```

`jsonb_path_ops` (rather than the default `jsonb_ops`) is the right choice for TMF
workloads — it's smaller, faster to build, and covers exactly the three operators the
JSONB backend will emit for containment tests:
- `@>` — payload contains value
- `@?` — payload has a path
- `@@` — payload matches a jsonpath predicate

**What GIN does NOT accelerate.** `->>` scalar extraction in WHERE (`payload->>'state'
= 'Pending'`) does not use the GIN index — that's a functional scan unless there is an
expression index on `(payload->>'state')`. This is the single most-important
performance caveat of the JSONB backend and the reason for §2.2.

### 2.2 Expression indexes for hot query paths

Every field that appears in a common filter or sort URL needs its own expression index
if the query volume warrants:

```sql
CREATE INDEX product_order_state_idx
  ON product_order ((payload->>'state'));

CREATE INDEX product_order_created_at_idx
  ON product_order (((payload->>'creationDate')::timestamptz));

CREATE INDEX product_order_customer_id_idx
  ON product_order ((payload->'relatedParty'->0->>'id'));
```

**Design implications:**

- These are per-deployment decisions, not toolkit-provided. The toolkit generates the
  SQL; the service DBA/ops team declares the indexes.
- We should provide **a script or documented recipe** in the JSONB-backend module's
  README for deriving expression indexes from an actual query log (Postgres
  `pg_stat_statements` extension makes this straightforward).
- Expression indexes MUST match the exact expression form the toolkit emits, including
  the cast — `(payload->>'creationDate')` and `((payload->>'creationDate')::timestamptz)`
  are different indexes. §5's operator translation table pins the exact SQL shapes so
  the toolkit and the DBA speak the same syntax.

### 2.3 Write-cost trade-off

GIN indexes are 2–5× more expensive to maintain on writes than B-tree. For read-heavy
TMF workloads (typical: 90%+ read, 10%- write) this is fine. For write-heavy paths
(event ingestion, high-frequency status updates), the recommendation is:
- Keep the GIN index on the payload
- Skip expression indexes on frequently-updated fields
- Or use partial indexes: `... WHERE payload->>'status' != 'archived'` to shrink
  the write cost on the hot subset

---

## 3. Joins — the key architectural question

**Short answer:**

> The JSONB backend supports **intra-payload traversal** natively (nested arrays,
> correlated array-element matches, positional indexes). It does **not** support
> cross-table joins for filter/sort. That is a deliberate scope constraint, not a
> limitation to fix later.

### 3.1 What's supported natively — the JPA-relational gaps this closes

Every one of these is a first-class Postgres/JSONB operation with no JOIN required:

| Feature (URL grammar) | JSONB SQL | JPA-relational status |
|---|---|---|
| Nested array traversal `@.a.b.c` | `payload#>'{a,b,c}'` or `jsonb_path_query` | Requires JOIN on entity model |
| Array correlation `@.arr[?(@.name=='X' && @.id=='Y')]` | `jsonb_path_exists(payload, '$.arr[*] ? (@.name == "X" && @.id == "Y")')` | Currently 400; feasible under JOIN-mapping constraint (per JPA doc §3.3) but requires the field to be `@OneToMany`/`@ManyToMany`/`@ElementCollection` |
| Positional `[N]` in filter | `payload#>'{arr,N,field}'` | Currently 400; impractical portably on JPA (needs `@OrderColumn`) |
| Positional `[N]` in sort | `payload->'arr'->N->>'field'` | Currently 400 / silent no-op on JPA |
| `[*]` wildcards in filter | Native to JSONB path — `jsonb_path_query(payload, '$.arr[*].field')` | Silent projection on Mongo (native BSON); rejected on JPA |
| Correlated sort `arr[key=X].value` | `ORDER BY (jsonb_path_query_first(payload, '$.arr[*] ? (@.id == "X").value')::text)` | Absent on JPA (whole `Tmf630MongoCorrelatedSortExecutor` has no analogue) |
| `min()` / `max()` aggregators in sort | `ORDER BY jsonb_path_query_first(...)` with subselect over `jsonb_path_query(...)` folded via SQL `MIN`/`MAX` | Impractical portably on JPA (dialect-specific SQL) |
| `length() == N` on arrays | `jsonb_array_length(payload->'arr') = N` | Works on JPA and Mongo (2.1.5, via `Ops.COL_SIZE`) |
| Real regex (`.regex` / `=~`) | `payload->>'field' ~ ?` (case-sensitive) / `~* ?` (case-insensitive) | Works on JPA as `LIKE` (semantic drift — see JPA doc §3.6) |

**Read this table carefully.** The JSONB backend closes **7 of the 11 divergences**
listed in the JPA gap analysis, in most cases *more cleanly* than a JPA-relational
extension would. This is the strongest single argument for prioritising JSONB over the
JPA-array-correlation extension proposed at the end of the prior conversation.

### 3.2 What's NOT supported — cross-table joins

The JSONB backend explicitly does **not** support:

- Filter or sort on JSONB payloads via a JOIN to another table
  (e.g. *"sort orders by their customer's name"* where customer is in a different
  table)
- Aggregations across payloads (e.g. *"top 10 customers by total order value"*)
- Payload-to-relational-table foreign-key constraints

**Why this is a deliberate scope decision:**

- The JPA-relational backend exists for services with a normalized relational data
  model. Services that need cross-entity JOINs should use JPA, not JSONB.
- A JSONB-backed service should keep each entity's payload self-contained. If a
  ProductOrder needs customer info displayed inline, that info is copied into
  `payload->'relatedParty'` at write time (denormalization is the whole point).
- Supporting cross-table JOINs would require the toolkit to know the relationships
  between tables — which pushes it back into being an ORM, which JPA already is.

**Escape hatch:** any service that has a genuine JOIN need can hand-roll the SQL in
its own `JdbcClient` / `NamedParameterJdbcTemplate` query at the repository level, the
way the existing JPA and Mongo services hand-roll repository-level QueryDSL for cases
the URL grammar doesn't cover. The toolkit's URL-parameter-to-predicate pipeline is
still useful even when the final query is hand-written — it can produce the WHERE
fragment.

### 3.3 What about `@OneToMany` associations that happen to exist alongside a JSONB column?

Some services model a hybrid: an `@Entity ProductOrder` with a `payload jsonb` column
*and* an `@OneToMany List<OrderAudit> audits` association to a side table. The JSONB
backend module does **not** try to help with the JOIN to the side table. That's the
JPA-relational backend's problem; use it alongside if you need it, and let each backend
handle its own predicates.

The toolkit's URL grammar has no way to express "this parameter targets the JSONB
payload, this other parameter targets the JOIN'd table" cleanly. Trying to mix backends
per parameter within one query breeds ambiguity and lock-in.

### 3.4 Split-and-merge for oversized child collections — approved architecture

Some TMF resources have a child collection that can grow to a size that makes
storing it inline impractical — canonically `ProductOrder.productOrderItem`, where a
single order can hold 500+ items in production and each item is a substantial
payload. Inline storage hits three walls at that scale:

1. **Row size.** Postgres tuples soft-cap at ~8KB before TOAST; JSONB compresses well
   but very large orders still stress the storage system and slow full-row rewrites.
2. **Rewrite cost on every update.** Modifying one item requires the whole `payload`
   JSONB to be rewritten by the SQL UPDATE — expensive at scale even with only a
   single-field logical change.
3. **Item-side filter cost.** Filters like `?productOrderItem.state=Pending` cannot
   use per-item indexes when items live inside the parent's `payload` — the planner
   has no way to skip parents whose items don't match without scanning the whole
   payload.

For these cases the JSONB backend supports splitting the child collection into its
own table. **This is a first-class supported pattern**, not a cross-entity JOIN
(§3.2). The child table represents *this one entity's* array extracted for storage
reasons, not a separate entity.

**Table shape (approved 2026-07-26):**

```sql
CREATE TABLE product_order (
  id          VARCHAR(50)  PRIMARY KEY,
  payload     JSONB        NOT NULL,   -- no productOrderItem field, no id list, no count
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  opt_lock    INTEGER      NOT NULL DEFAULT 0
);

CREATE TABLE product_order_item (
  parent_id   VARCHAR(50)  NOT NULL REFERENCES product_order(id) ON DELETE CASCADE,
  item_id     VARCHAR(50)  NOT NULL,
  item_order  INTEGER      NOT NULL,
  payload     JSONB        NOT NULL,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  opt_lock    INTEGER      NOT NULL DEFAULT 0,
  PRIMARY KEY (parent_id, item_id)
);
CREATE INDEX product_order_item_order_idx ON product_order_item (parent_id, item_order);
```

**Load-bearing decisions:**

- **No parent-side child metadata.** The parent stores nothing about its children —
  no id list, no count, no summary. All child state lives on the child table.
  Enables append/modify/delete on child without touching the parent row.
- **`item_order INTEGER` on the child** preserves TMF ordering semantics. Query is
  `SELECT ... WHERE parent_id = ? ORDER BY item_order` — one index scan on the
  compound `(parent_id, item_order)` index.
- **Composite PK `(parent_id, item_id)`** enforces per-parent item-id uniqueness at
  the DB level. `item_id` is the TMF-visible id (e.g. `"1"`, `"2"`, `"3"` within the
  order); it is unique within its parent, not globally.
- **`ON DELETE CASCADE`** handles parent deletion; no application-level cascade
  logic needed.
- **Deferred:** a denormalized `item_count INTEGER` on the parent for
  item-count-based filters (`?productOrderItem.length() > 10`). Add only if such
  filters become a real hot path.

**Domain-model declaration:**

```java
public class ProductOrder {
  @Tmf630JsonbSplitCollection(
      childEntity = ProductOrderItem.class,
      childTable = "product_order_item",
      maxInlineItems = 100)
  private List<ProductOrderItem> productOrderItem;
  // ... other fields
}
```

Annotation semantics: "this field is not in the parent's payload; toolkit populates
it at read time from `child_table` scoped to this parent's id, capped at
`maxInlineItems`; toolkit routes filter/sort predicates targeting this field's paths
to the child table."

**Sub-endpoint pattern for over-cap orders.** Developer writes a thin controller
extending a toolkit-provided base:

```java
@RestController
@RequestMapping("/productOrder/{parentId}/productOrderItem")
public class ProductOrderItemSubResourceController
    extends Tmf630JsonbSubResourceController<ProductOrderItem, ProductOrder> {
  // empty body — base class provides GET /, GET /{itemId}, PATCH /{itemId},
  // POST, DELETE /{itemId} — all scoped to {parentId}, with normal
  // filter/sort/paging on the child collection
}
```

Five lines of developer boilerplate per split, in exchange for standard Spring MVC
handler resolution — greppable, debuggable, testable. No bytecode generation. This
is the pattern Spring Data uses for repositories.

**Response contract:**

- Parent `GET /productOrder/{id}` for an order with ≤100 items → merged JSON with
  `productOrderItem: [...]` inline, no header.
- Parent `GET /productOrder/{id}` for an order with >100 items → merged JSON with
  the first 100 items inline (ordered by `item_order`) plus header
  `X-Total-Count-ProductOrderItem: <true count>`. Client uses the sub-endpoint for
  the tail.
- List `GET /productOrder` → each parent in the page gets its first 100 items
  inline, `X-Total-Count-ProductOrderItem` header per parent (or a single header
  naming the max; TBD in implementation).
- Sub-endpoint `GET /productOrder/{id}/productOrderItem?offset=&limit=&sort=&filter=`
  → normal paginated child response, standard toolkit paging headers.

**Query planning:**

- Parent-only filter → single-table SQL on `product_order`.
- Item-only filter → drive from `product_order_item` (indexed), select DISTINCT
  `parent_id`, JOIN back to `product_order`.
- Mixed AND(parent, item) → item-first plan with post-JOIN parent predicate.
- Mixed OR(parent, item) → fallback to parent-drive with EXISTS subquery into
  child (slower but correct).
- The router picks per clause shape; comment the decision in generated SQL for
  debuggability.

**Response merge SQL shape:**

```sql
SELECT
  po.payload || jsonb_build_object(
    'productOrderItem',
    COALESCE(
      (SELECT jsonb_agg(item.payload ORDER BY item.item_order)
       FROM (SELECT payload, item_order
             FROM product_order_item
             WHERE parent_id = po.id
             ORDER BY item_order
             LIMIT 100) item),
      '[]'::jsonb
    )
  ) AS payload
FROM product_order po
WHERE ...
```

Item cap enforced at DB level via `LIMIT 100` inside the aggregate — never
materialize all items and truncate in Java.

**Write handling:**

- POST includes items inline in the payload; persistence hook splits — parent to
  `product_order` (with items removed from `payload`), each item to
  `product_order_item` with an auto-assigned `item_order` (max+1).
- PATCH `op: add, path: /productOrderItem/-, value: ...` → INSERT into child, no
  parent rewrite.
- PATCH `op: replace, path: /productOrderItem/2/state, value: ...` → UPDATE on
  child row with matching `item_id`, no parent rewrite.
- PATCH `op: remove, path: /productOrderItem/2` → DELETE on child row, no parent
  rewrite.
- PATCH parent-only → UPDATE on parent, no touch to child table.
- All four operations run in one Spring `@Transactional` boundary; Postgres
  transaction isolation handles atomicity.

**JPA-relational is explicitly out of scope for this pattern.** Services on
pure-JPA that need split-and-merge are already doing it through normalized
tables — that's what JPA is for. The split-and-merge feature is a JSONB-only
extension (and, potentially, Mongo — under evaluation as a separate question).

---

## 4. QueryDSL integration — three options, one recommendation

Per memory, there is **no maintained QueryDSL-JSONB library**:
`alexliesenfeld/querydsl-jpa-postgres-json` was archived June 2023, last release March
2020. We DIY.

The core question: how do URL parameters become executable SQL against a JSONB column?
Three options, in ascending order of divergence from the current architecture.

### 4.1 Option A — `Expressions.booleanTemplate` inside a JSONB-aware `PredicateFactory`

**Shape.** A new `JsonbPredicateFactory` (companion to the existing `PredicateFactory`)
that emits `com.querydsl.core.types.Predicate` objects backed by SQL fragment
templates:

```java
// EQ operator on JSONB backend
Expressions.booleanTemplate(
    "{0} @> {1}::jsonb",
    payloadPath,
    Expressions.constant("{\"" + fieldPath + "\": \"" + escaped(value) + "\"}"));
```

**Pros:**
- Fits the existing `QuerydslPredicateExecutor` extension pattern — controllers keep
  binding `@QuerydslPredicate(root = ProductOrder.class) Predicate` unchanged.
- Reuses `Tmf630PredicateArgumentResolver` end-to-end. The resolver hands out
  `Predicate` objects; the executor runs them.
- Backend detection stays at the same layer as the existing `isMongoRoot` / `isJpaEntity`
  probes (see JPA doc §2). Add a third: `isJsonbEntity(root)` matches a
  toolkit-provided `@JsonbBacked` annotation or a Hibernate `@JdbcTypeCode(SqlTypes.JSON)`
  presence check.
- `Predicate` composition (AND/OR/NOT) uses the standard QueryDSL builder — nothing
  new to write.

**Cons:**
- Every operator needs a hand-written SQL template. §5 lists them all.
- SQL injection risk if the field-path isn't sanitized at template build time
  (the value is parameter-bound, but the JSONB path is inline). Mitigated by the
  existing field allowlist (`Tmf630FilterSettings.allowNestedPathsFor`), which
  already validates paths before they reach the predicate factory.
- Cast handling for numeric/date comparisons is per-operator (§6).

**This is the recommendation.** Everything else in this document assumes Option A.

### 4.2 Option B — new `Tmf630JsonbExecutor` bean, bypasses QueryDSL entirely

**Shape.** Parallel to `Tmf630MongoCorrelatedSortExecutor` — a new executor bean that
takes a parsed AST (attribute filters + JsonPath filter + TmfSort + Pageable) and
emits SQL via `JdbcClient` (Spring Framework 6.1+) or `NamedParameterJdbcTemplate`.
Skips `Predicate` construction entirely.

**Pros:**
- Full control over SQL. Complex patterns (correlated subqueries with
  `jsonb_path_query_first`, CTE-based sorts) are easier to write imperatively than as
  QueryDSL templates.
- No `Expressions.booleanTemplate` at all — no SQL injection surface from path
  interpolation into templates.

**Cons:**
- Requires a **new controller binding shape** — `@QuerydslPredicate Predicate` doesn't
  make sense here. Controllers would need to bind `TmfJsonbQuery` or similar.
- **Breaks URL-grammar backend neutrality claim.** A service that ports from JPA
  → JSONB would need to change its controller signature. The whole point of the
  neutral filter pipeline is *the same controller shape works on any backend*.
- Doesn't reuse `PredicateFactory` — every operator gets reimplemented in the
  executor.

**Not recommended** unless Option A hits a wall on a specific complex pattern.

### 4.3 Option C — a full custom serializer (like Mongo's `MongodbDocumentSerializer`)

**Shape.** Write a `JsonbDocumentSerializer` that walks a `Predicate` tree and emits
SQL text + parameter bindings, mirroring how `querydsl-mongodb`'s
`MongodbDocumentSerializer` walks a Predicate and emits a BSON Document.

**Pros:**
- Cleanest separation of concerns — predicate composition stays pure QueryDSL, one
  central serializer handles the SQL emission.
- Would give a plausible upstream contribution path to querydsl-sql or a new
  querydsl-jsonb library.

**Cons:**
- Substantial engineering effort — a real serializer for all 24 `TmfOperator` values
  plus AND/OR/NOT composition plus subquery emission (for array correlation) plus
  sort keys is a **month+ of work** on its own.
- The archived `alexliesenfeld/querydsl-jpa-postgres-json` project is evidence that
  the maintenance burden is real.

**Not recommended for the first cut.** Revisit if the Option A template approach
becomes unmanageable.

---

## 5. Operator translation table — the concrete SQL contract

Each of the 24 `TmfOperator` values maps to a specific SQL template. `payload` is the
column name (assumed via the entity annotation); `<field>` is the toolkit-resolved
JSONB path (single-hop or dotted); `?` is a JDBC parameter binding.

| `TmfOperator` | Primary template | Alt (GIN-accelerated where possible) | Notes |
|---|---|---|---|
| `EQ` | `payload->>'<field>' = ?` | `payload @> ?::jsonb` (containment; GIN-indexed via `jsonb_path_ops`) | Alt only usable when `<field>` is a top-level scalar — for nested paths the containment shape gets complex |
| `NE` | `payload->>'<field>' != ?` | — | No GIN acceleration; consider expression index |
| `EQI` | `LOWER(payload->>'<field>') = LOWER(?)` | — | |
| `NEI` | `LOWER(payload->>'<field>') != LOWER(?)` | — | |
| `GT` | `(payload->>'<field>')::<cast> > ?` | — | `<cast>` from Java type (§6) |
| `GTE` | `(payload->>'<field>')::<cast> >= ?` | — | |
| `LT` | `(payload->>'<field>')::<cast> < ?` | — | |
| `LTE` | `(payload->>'<field>')::<cast> <= ?` | — | |
| `BETWEEN` | `(payload->>'<field>')::<cast> BETWEEN ? AND ?` | — | |
| `IN` | `payload->>'<field>' = ANY(?)` | — | Postgres array binding; nicer than IN(?, ?, ?) |
| `NIN` | `payload->>'<field>' != ALL(?)` | — | |
| `IS_NULL` | `NOT (payload ? '<field>')` (missing) OR `payload->'<field>' IS NULL` (explicit null) | — | Widening decision: NULLISH semantics widen to `NOT (payload ? '<field>') OR payload->'<field>' = 'null'::jsonb` (see §5.1) |
| `IS_NOT_NULL` | `payload ? '<field>' AND payload->'<field>' IS NOT NULL` | — | |
| `LIKE` | `payload->>'<field>' LIKE ?` | — | |
| `LIKEI` | `payload->>'<field>' ILIKE ?` | — | Postgres `ILIKE` is native; no `LOWER(...)` wrap |
| `CONTAINS` | `payload->>'<field>' LIKE '%' \|\| ? \|\| '%'` | — | Escape `%` / `_` from the value at parameter-binding time |
| `CONTAINSI` | `payload->>'<field>' ILIKE '%' \|\| ? \|\| '%'` | — | |
| `STARTS_WITH` | `payload->>'<field>' LIKE ? \|\| '%'` | — | |
| `STARTS_WITHI` | `payload->>'<field>' ILIKE ? \|\| '%'` | — | |
| `ENDS_WITH` | `payload->>'<field>' LIKE '%' \|\| ?` | — | |
| `ENDS_WITHI` | `payload->>'<field>' ILIKE '%' \|\| ?` | — | |
| `REGEX` | `payload->>'<field>' ~ ?` | — | **Native regex** — closes JPA gap #11 |
| `REGEXI` | `payload->>'<field>' ~* ?` | — | **Native regex** |

### 5.1 NULLISH widening on JSONB

The NULLISH widening semantic (`isnull-semantics: NULLISH`) needs a different
translation than either Mongo or JPA. On JSONB there are **three** "no-value" states,
not two:

1. Field is absent from the payload (`NOT (payload ? '<field>')`)
2. Field is present but with JSON `null` value (`payload->'<field>' = 'null'::jsonb`,
   or `payload->>'<field>' IS NULL` — the two are equivalent but the `#>>` form is
   what most template engines emit)
3. Field is an empty array (`jsonb_array_length(payload->'<field>') = 0`)

**Under `MISSING_ONLY`** (default): state 1 only. `NOT (payload ? '<field>')`.

**Under `NULLISH`** (Mongo-parity): states 1 + 2. `NOT (payload ? '<field>') OR
payload->'<field>' = 'null'::jsonb`. Note this does NOT include state 3, matching
Mongo's decision — empty-array widening should stay opt-in via `length() == 0`.

**Complement (`.isnotnull` under NULLISH):** the same trilean concern as JPA applies,
but Postgres's `?` operator (checks key presence) is always definite (true/false, never
UNKNOWN), so the complement can be built as a plain AND:
`payload ? '<field>' AND payload->'<field>' != 'null'::jsonb`. This is
straightforwardly the boolean opposite. Unlike SQL trilean IN(NULL), this composition
is safe.

**Verdict:** JSONB gets its own `nullish()` branch in `JsonbPredicateFactory`, using
the shapes above. Backend-detect via `isJsonbEntity(root)` (see §7).

### 5.2 Array correlation via `[?(...)]`

The JsonPath filter grammar `filter=$[?(@.arr[?(@.name=='X' && @.id=='Y')])]` maps
directly to a single `jsonb_path_exists` call:

```sql
jsonb_path_exists(
  payload,
  '$.arr[*] ? (@.name == "X" && @.id == "Y")'
)
```

- Same-element semantics guaranteed by JSONB path expression semantics
- Multiple predicates naturally correlate to the same array element
- Nested `[?(...)]` (correlation inside correlation) composes: `$.a[*] ? (@.b[*] ? (@.c == "X"))`
- Multi-hop `$.a[*].b[*].c` composes without correlation (transparent wildcard)

**This is the largest single win.** No JOIN mapping constraint, no `@OrderColumn`,
no `@OneToMany` annotation required — because there is no JOIN at all. The full
Jayway-subset JsonPath grammar the toolkit accepts maps into `jsonb_path_exists`
almost 1:1. There will be edge cases (regex inside `[?(...)]` needs `like_regex`
inside the path expression rather than the `~` operator — different syntax; deferred
to implementation notes).

### 5.3 Positional `[N]` in filter and sort

**Filter:** `filter=$[?(@.arr[N].field == 'X')]` maps to
`payload#>'{arr,N,field}' = '"X"'::jsonb` (or equivalently the `jsonb_path_exists`
form with `?` operator). Out-of-range indices yield SQL NULL → the equality is false
→ no match. Same semantics as Mongo's dotted numeric path.

**Sort:** `sort=arr[N].field` maps to
`ORDER BY payload->'arr'->N->>'field'` (with an appropriate cast if the sort key is
non-string).

Both are first-class Postgres operations. **Closes JPA gap #9 cleanly.**

### 5.4 `length()` function

`filter=$[?(@.arr.length() == N)]` maps to
`jsonb_array_length(payload->'arr') = N`. Postgres-native, no cast needed. Same-URL
parity with JPA and Mongo (both of which already support this via `Ops.COL_SIZE` in
2.1.5).

Extension question — should JSONB relax the *"only `==` comparator, only on
collection fields"* rejection that JPA and Mongo enforce (per 2.1.5 CHANGELOG)? On
JSONB the non-`==` forms are trivially supported (`jsonb_array_length(...) > N`), and
string length is `char_length(payload->>'field')`. Doing so would violate the
cross-backend consistency principle
([[feedback_scoped_rejection_over_architectural_change]]). **Recommendation: keep
the 2.1.5 scope constraint identically on JSONB** — same URL, same result, same
rejections. If we later loosen it, we loosen on all three backends together or not at
all.

---

## 6. Type coercion — Postgres is strict, and that's the design constraint

The JSONB `->>` operator always returns `text`. Comparisons against typed values
(dates, numbers, booleans) require explicit casts. The toolkit already resolves the
target Java type via `FieldPathResolver` and coerces the input value via
`ValueConverter`, so the mapping to Postgres casts is mechanical:

| Java type | Postgres cast | Example |
|---|---|---|
| `Integer`, `int`, `Long`, `long`, `Short`, `Byte` | `::bigint` | `(payload->>'priority')::bigint > 5` |
| `Double`, `double`, `Float`, `float`, `BigDecimal` | `::numeric` | `(payload->>'amount')::numeric >= 100.50` |
| `Boolean`, `boolean` | `::boolean` | `(payload->>'active')::boolean = true` |
| `LocalDate` | `::date` | `(payload->>'birthDate')::date > '1990-01-01'` |
| `LocalTime` | `::time` | `(payload->>'startTime')::time >= '14:30:00'` |
| `LocalDateTime` | `::timestamp` | |
| `OffsetDateTime`, `ZonedDateTime`, `Instant` | `::timestamptz` | `(payload->>'createdAt')::timestamptz >= '2024-01-15T00:00:00Z'` |
| `String` | (no cast) | `payload->>'name' = 'Alice'` |
| Enum | (no cast, compared as string) | `payload->>'status' = 'PENDING'` |

**Failure mode.** If the stored value doesn't cast (e.g. `payload->>'priority'` is
`"abc"` when the query expects a numeric comparison), Postgres throws
`invalid input syntax for type integer`. Three options:

- **(a) Fail fast** — the SQL error propagates as a 500. TMF payloads should have
  consistent types per field; misconfigured data is a data problem, not a filter
  problem. **Recommended default.**
- **(b) Silent skip** — wrap the cast in `NULLIF` + `CASE WHEN`, so uncastable rows
  are treated as non-matches. Verbose SQL, hides real bugs.
- **(c) `TRY_CAST`-style** — Postgres has no first-class `try_cast` until 16+ but the
  same effect is `(CASE WHEN payload->>'field' ~ '^-?[0-9]+$' THEN
  (payload->>'field')::bigint ELSE NULL END)`. Regex-guarded, works everywhere,
  verbose.

**Recommendation:** ship (a) as default in the first cut; expose (b) or (c) later as
an opt-in `tmf630.jsonb.cast-mode: strict|lenient` property if downstream services
need it. This is exactly the "scope down, add later if asked" pattern from
[[feedback_scoped_rejection_over_architectural_change]].

---

## 7. Entity model — what the consuming service writes

### 7.1 Recommended approach: a thin `@Entity` row class + a domain-model facade

```java
@Entity
@Table(name = "product_order")
@QueryEntity
@Tmf630JsonbBacked                                    // <-- opt into JSONB (§0.1)
@EntityListeners(AuditingEntityListener.class)
public class ProductOrderRow {

  @Id
  private String id;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private JsonNode payload;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private OffsetDateTime createdAt;

  @LastModifiedDate
  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Version
  @Column(name = "opt_lock", nullable = false)
  private Integer optLock;

  // getters/setters
}
```

Removing `@Tmf630JsonbBacked` would turn this exact class into a **pure JPA** row —
the toolkit would try to filter on the row's own fields (which for this class would
mean the URL parameter surface would degrade to `id`, `createdAt`, `updatedAt`, and
whatever else Hibernate can see — not the payload's TMF fields). That's why the
annotation is load-bearing: it tells the toolkit *"filter this entity's URL
parameters as JSONB payload paths, not as JPA columns."*

For a **versioned** entity (`ProductSpecification`, `ServiceCatalog`), the shape is
the same except `@Id` becomes `@EmbeddedId` on an `(id, version)` composite key
class — see §1.1 for the DDL and §1.3 for the audit-annotation reference.

The service also has a domain model `ProductOrder` (the TMF resource) — a plain POJO
that Jackson serializes to/from `payload`. This is the class the controller binds:

```java
@RestController
@RequestMapping("/api/productOrder")
class ProductOrderController {

  private final ProductOrderRepository repository;

  @GetMapping
  @Tmf630Response
  Page<ProductOrder> search(
      @QuerydslPredicate(root = ProductOrder.class) Predicate predicate,
      Pageable pageable) {
    return repository.findAllByPayload(predicate, pageable);
  }
}
```

Two important design points:

1. **`@QuerydslPredicate(root = ProductOrder.class)`** — the domain model, NOT
   `ProductOrderRow`. URL parameters (`?status.eq=Pending`) name domain fields, not
   row-table columns. The toolkit's `Tmf630PredicateArgumentResolver` resolves paths
   against `ProductOrder`'s bean introspection, and the JSONB predicate factory
   translates each resolved path into `payload->>'status'` etc. against the row
   table.
2. **`findAllByPayload(Predicate, Pageable)`** — a repository fragment (see §7.2)
   that runs the JSONB SQL and returns `Page<ProductOrder>` by deserializing the
   `payload` column of each matching row.

This keeps the URL grammar and the controller shape identical across backends. A
service migrating from Mongo to JSONB changes only the repository and the
`@Document`/`@Entity` annotation; the controller code doesn't change.

### 7.2 Repository shape

A JSONB backend can't extend `QuerydslPredicateExecutor` directly because the
predicate is against a *different type* (domain model) than the row type. Two options:

**Custom fragment**:

```java
public interface ProductOrderRepository
    extends JpaRepository<ProductOrderRow, String>, JsonbFilterFragment<ProductOrder> {}

// Fragment provided by the toolkit:
public interface JsonbFilterFragment<T> {
  Page<T> findAllByPayload(Predicate predicate, Pageable pageable);
}
```

The toolkit ships a default `JsonbFilterFragmentImpl` (autoconfigured) that:
1. Serializes the `Predicate` into a JSONB WHERE clause via `JsonbPredicateSerializer`
2. Adds ORDER BY / OFFSET / LIMIT
3. Runs the SQL via `JdbcClient` (or `NamedParameterJdbcTemplate`)
4. Deserializes each `payload` column into `T` via the auto-configured
   `ObjectMapper`
5. Returns a `PageImpl<T>` with a separate `COUNT(*)` for the total

**Standalone bean** (mirror of `Tmf630MongoCorrelatedSortExecutor`):

```java
public interface Tmf630JsonbExecutor {
  <T> Page<T> findAll(Class<T> domainType, Class<?> rowType,
                      Predicate predicate, Pageable pageable);
}
```

Called from the controller directly. Doesn't use Spring Data JPA repositories at all;
still uses Hibernate for the DDL but not for queries.

**Recommendation: the fragment approach.** It composes with existing Spring Data
patterns and mirrors how `Tmf630MongoCorrelatedSortExecutor` composes with
`QuerydslPredicateExecutor` on the Mongo side.

### 7.3 Do we still need Q-classes?

For the `ProductOrder` domain type: **yes, exactly as today**. The APT processor
generates `QProductOrder` from the domain-model POJO; the toolkit's field resolver
uses that for path validation.

For the `ProductOrderRow` entity: **no**. The row table is never bound as a QueryDSL
root by the toolkit — it's only accessed via the JSONB fragment's hand-written SQL.
If the service wants Q-class-based repository queries against the row for other
reasons (audit lookups, raw SQL manipulation), it can add `@QueryEntity`; the toolkit
doesn't need it.

---

## 8. Sort and paging

### 8.1 Plain dotted sort

`sort=name,-birthDate` on a `ProductOrder`-backed row:

```sql
ORDER BY
  payload->>'name' ASC NULLS LAST,
  (payload->>'birthDate')::date DESC NULLS LAST
```

- Type-aware cast applied per §6 based on the domain field's Java type
- `NULLS LAST` is Postgres-native — the JPA nulls-last gap (#7 in the JPA doc) does
  not exist here at all

### 8.2 Correlated sort (closes JPA gap #2)

`sort=serviceOrderItem[type=install].service.serviceCharacteristic[name=price].value`:

```sql
ORDER BY (
  jsonb_path_query_first(
    payload,
    '$.serviceOrderItem[*] ? (@.type == "install").service.serviceCharacteristic[*] ? (@.name == "price").value'
  )::text
)::numeric ASC NULLS LAST
```

- One SQL expression per sort term
- Cast to text first (defensive: `jsonb_path_query_first` returns `jsonb`), then to
  the domain type
- `NULLS LAST` handled by the standard SQL modifier — no `_hasKey` companion needed
  like the Mongo pipeline requires
- **JsonPath grammar (`sort=$.arr[?(...)].leaf`) and simple-rich grammar
  (`sort=arr[key=X].leaf`) both compile to the same shape**, exactly like the Mongo
  implementation shares one AST via `JsonPathSortAst`. The toolkit can reuse that
  AST directly.

### 8.3 Aggregators and coercions

`sort=-num(min(prices[*].value))` on JSONB:

```sql
ORDER BY (
  SELECT MIN(v::numeric)
  FROM jsonb_array_elements_text(payload->'prices') AS v
) DESC NULLS LAST
```

- `min()` / `max()` map to SQL aggregates over `jsonb_array_elements(...)` /
  `jsonb_array_elements_text(...)`
- `num()` / `str()` / `date()` map to `::numeric` / `(no cast)` / `::timestamptz`
- Each is a scalar subquery in the ORDER BY

**This IS supported on JSONB**, unlike JPA-relational where it hit the dialect wall.
The subquery pattern is portable *because we're only targeting one dialect* (Postgres).
The JPA-relational multi-dialect story doesn't apply here.

### 8.4 Paging

Trivial: `LIMIT ? OFFSET ?`. Total count via a companion `SELECT COUNT(*) FROM ...
WHERE <same predicate>`. Postgres-native, nothing custom.

---

## 9. Migration story — existing services adopting JSONB

For a service currently on Mongo migrating to Postgres+JSONB:

1. **Schema:** run the DDL from §1.1 for each TMF entity type. Pick the non-versioned
   or versioned shape per entity based on whether TMF treats it as versioned (see the
   TMF entity's spec).
2. **Data migration:** `mongoexport --db=x --collection=orders --out=orders.json`,
   then a small loader script that INSERTs each JSON doc into the corresponding
   Postgres table's `payload` column. Preserve `id` (from `_id`); for versioned
   entities extract `version` from `payload->>'version'` into the composite-key
   column. The audit columns (`created_at`, `updated_at`, `opt_lock` — whatever
   the service names them per §1.3) either backfill from the payload's own
   `creationDate` / `lastUpdate` fields, or default to `NOW()` / `0` for a
   greenfield load.
3. **Application changes:**
   - Remove `spring-boot-starter-data-mongodb`, `querydsl-mongodb`,
     `tmf630-toolkit-mongo-aggregation`.
   - Add `spring-boot-starter-data-jpa` (only for entity management),
     `postgresql`, and the new `tmf630-toolkit-jsonb` module.
   - Replace `@Document` with the row-entity + fragment pattern from §7.
   - Controllers unchanged.
4. **URL contracts:** unchanged. Every URL that worked on Mongo works on JSONB;
   most work with *better* semantics (real regex, native `NULLS LAST`).

For a service currently on JPA-relational migrating to JSONB:

- This is a bigger conceptual shift — you're going from a normalized schema to a
  denormalized document store. The URL contracts stay the same, but the underlying
  data model changes significantly. Migration should be entity-by-entity, not
  wholesale.

---

## 10. Testing strategy

- **Testcontainers Postgres 15+**. Postgres 15 is required for full JSONB path
  expression support (`?` predicate operator, `jsonb_path_query_first`, etc.).
- **Parity IT** — `Tmf630PredicateJsonbIT`, mirroring the existing
  `Tmf630PredicateMongoIT` and `Tmf630PredicateJpaParityIT`. Same URL fixtures,
  same expected result sets. Verifies the URL-grammar parity claim.
- **Regression seed:** at least one entity with nested arrays (Order →
  serviceOrderItem → serviceCharacteristic) so the correlated-sort and array-correlation
  ITs have a meaningful shape to query against. Reuse the SDWAN fixtures from the
  existing Mongo IT.
- **EXPLAIN ANALYZE tests** — per the memory's "realistic GIN index, EXPLAIN ANALYZE
  before committing to full backend" note. Add a `Tmf630PredicateJsonbPerfIT` that
  asserts, for the hot query paths, that the generated query plan uses the intended
  index (GIN scan, expression index scan) rather than a sequential scan.
- **Optimistic-lock IT** — for both PK shapes, verify that a concurrent-update race
  causes exactly one writer to succeed and the loser to receive an
  `ObjectOptimisticLockingFailureException` (Hibernate manages `@Version`; the
  toolkit just needs to confirm the column is wired correctly on the row entity).
- **Versioned-entity IT** — for the composite-PK shape, verify that two rows with the
  same `id` but different `version` values coexist, and that filter/sort URLs
  correctly scope to a single version when the payload's `version` field is filtered
  on.

---

## 11. Effort estimate — consolidated into v3.0.0

**Decision (2026-07-26):** the JSONB backend ships as part of **v3.0.0**, together with
the JPA gap closure and both split-and-merge annotations. See
[`V3_ROADMAP.md`](./V3_ROADMAP.md) for the full sequenced plan across all four
workstreams. This section previously proposed a three-phase incremental release
schedule (filter-first → correlated-sort → positional/NULLISH) but has been
superseded — everything in this document is v3.0.0 scope.

**JSONB work maps to roadmap Phase (b) and Phase (c):**

### Phase (b) — JSONB base backend + correlated sort (~7–10 weeks)

| Roadmap ID | This document reference | Scope |
|---|---|---|
| **b.1** | §1, §7 | Row entity + `@Tmf630JsonbBacked` + audit-annotation detection + autoconfigure (both PK shapes) |
| **b.2** | §5, §6 | `JsonbPredicateFactory`: 24 operators + type-aware casts + NULLISH widening JSONB flavor |
| **b.3** | §5.2, §5.3, §5.4 | JsonPath filter integration: `jsonb_path_exists`, positional `[N]`, `length()` |
| **b.4** | §8.1, §8.4 | Plain dotted sort with casts + native `NULLS LAST` + paging |
| **b.5** | §7.2 | `JsonbFilterFragment` + `JdbcClient` integration + `Tmf630PredicateJsonbIT` parity vs Mongo |
| **b.6** | §8.2 | Correlated sort (JsonPath + simple-rich, reusing `JsonPathSortAst`) |
| **b.7** | §8.3 | Sort aggregators (`min()` / `max()`) + coercions (`num()`/`str()`/`date()`) + parity ITs |

### Phase (c) — JSONB split-and-merge (~6–7 weeks)

| Roadmap ID | This document reference | Scope |
|---|---|---|
| **c.1** | §3.4 | `@Tmf630JsonbSplitCollection` + DDL + persistence hooks (POST split) |
| **c.2** | §3.4 | Predicate-splitting walker (backend-neutral, in `attribute-filtering-core`, reused in Phase d) |
| **c.3** | §3.4 | Query planner (parent-only / item-only / mixed AND / mixed OR fallback) |
| **c.4** | §3.4 | Response merge SQL with `jsonb_agg` + `LIMIT` inside the aggregate |
| **c.5** | §3.4 | `Tmf630JsonbSubResourceController<C, P>` base class + sub-endpoint routes |
| **c.6** | §3.4 | PATCH-append/modify/delete optimization (no parent rewrite) |
| **c.7** | §10 | `Tmf630JsonbSplitIT` parity ITs (SDWAN-scale fixtures) |

**Total Phase (b) + Phase (c) effort:** ~13–17 weeks. See V3_ROADMAP.md §3 and §4
for sub-milestone detail and the full total-effort accounting including Phases (a)
and (d).

---

## 12. Interaction with the JPA gap closure

The v3.0.0 release brings JPA-array-correlation and JPA correlated-sort (both under
Phase (a) — see [`JPA_BACKEND_GAP_ANALYSIS.md`](./JPA_BACKEND_GAP_ANALYSIS.md) and
[`V3_ROADMAP.md`](./V3_ROADMAP.md) §2) into the toolkit alongside the JSONB backend
of this document. Services choose which backend fits their data shape:

- **Pure JPA** (normalized relational schema, cross-entity JOINs) — JPA gap closure
  in Phase (a) covers correlated sort and array correlation on JOIN-mapped
  associations. `@Entity` + repositories, no annotation change.
- **PostgreSQL-JSONB** (document-shaped payloads on Postgres for cost/ops reasons) —
  full JSONB backend from this document, opt-in per entity via `@Tmf630JsonbBacked`.
- **MongoDB** (document-shaped payloads on Mongo, existing) — unchanged surface,
  gains `@Tmf630MongoSplitCollection` in Phase (d).
- **Mixed** — per JSONB doc §0.2, one service can host pure-JPA and JSONB-backed
  entities in the same Postgres database. Per-entity annotation decides the query
  path.

The scope decision between pure JPA and JSONB happens at the entity level, not the
service level.

---

## 13. Open questions for maintainer decision

Decision log — all resolved during the design discussion that produced V3_ROADMAP.md:

| # | Question | Resolution |
|---|---|---|
| 1 | Which TMF entities are versioned vs non-versioned? | Auto-detected from `@Id` / `@EmbeddedId` structure per §1.1; module docs will spell out common TMF resource classifications during b.1 |
| 2 | Multi-tenancy shape (column vs schema vs deferred)? | **Deferred entirely to deployment** — toolkit provides no opinion; see V3_ROADMAP.md §8 open Q3 |
| 3 | Type-cast failure mode | **Fail-fast** as default; opt-in `TRY_CAST` mode a later addition (V3_ROADMAP.md §8 open Q4) |
| 4 | QueryDSL integration approach | **Option A** — `Expressions.booleanTemplate` inside a `JsonbPredicateFactory`; see §4.1 |
| 5 | Repository shape | **Fragment on top of Spring Data JPA repo** — see §7.2 |
| 6 | First-cut phase scope | **Everything ships in v3.0.0**, including correlated sort — see V3_ROADMAP.md §3 |
| 7 | Annotation name | **`@Tmf630JsonbBacked`** — confirmed in V3_ROADMAP.md §7 |
| 8 | Which TMF entity to spike first? | **ProductOrder** — matches PIA use case, validates split-and-merge (Phase c) with the base backend (Phase b); see V3_ROADMAP.md §8 open Q2 |
| 9 | JPA-array-correlation vs JSONB — either/or? | **Both** — JPA gets it via Phase (a.3), JSONB gets it natively via `jsonb_path_exists` (§5.2) — see V3_ROADMAP.md §2 |

Remaining unresolved items are all implementation-time decisions (module split for
Mongo, exact spike entity, etc.) — see V3_ROADMAP.md §8 for the current list.
