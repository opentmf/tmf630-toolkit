# tmf630-toolkit

> **This is a server-side library.** It runs inside your Spring Boot backend, translates
> incoming HTTP query parameters into database predicates, and provides helper annotations
> and utilities to serve TMF-630 compliant responses (paging headers, range status codes,
> field selection). It is not a client SDK and has no relationship to any client-side
> filtering or JavaScript runtime.

`tmf630-toolkit` is the adaptation of TMF-630 REST API Design Guidelines for Spring Web MVC.

When a Spring Boot–based microservice references this library, it automatically gains
TMF630-style paging/sorting and advanced filtering capabilities through configuration —
no custom query parsing code required. Using the library from other Spring-based projects
is also possible but requires explicit bean wiring.

Clients can request *"only records 51–100"*, *"sort by surname descending"*, or *"find
people born after 1990 whose surname starts with D"* using standard query parameters. In
addition to the query parameters, clients can send a JSONPath expression in the optional
`filter=` parameter to express grouped conditions (parentheses, `&&`/`||`, comparison
operators) in a single expression. When the backend is a document database (MongoDB), the
filter grammar also allows array correlation via `$elemMatch`.

Release notes and version history are in [`CHANGELOG.md`](./CHANGELOG.md). A section-by-
section TMF-630 v4.x compliance summary is in
[TMF-630 compliance summary](#tmf-630-compliance-summary) below.

## Table of contents

- [What you get](#what-you-get)
- [Requirements](#requirements)
- [Prerequisites for attribute filtering](#prerequisites-for-attribute-filtering)
- [Dependency management](#dependency-management)
- [Developer handbook](#developer-handbook)
  - [1) Example domain and sample data](#1-example-domain-and-sample-data)
  - [2) Full QueryDSL operator reference](#2-full-querydsl-operator-reference)
  - [3) `filter=` JSONPath grammar](#3-filter-jsonpath-grammar)
  - [4) Paging, sorting, and response headers](#4-paging-sorting-and-response-headers)
  - [5) Field selection with `@Tmf630Response`](#5-field-selection-with-tmf630response)
  - [6) Controller usage — three levels](#6-controller-usage--three-levels)
  - [7) Combining predicates with path variables](#7-combining-predicates-with-path-variables)
  - [8) Value types — dates, datetimes, enums](#8-value-types--dates-datetimes-enums)
  - [9) Error response shapes](#9-error-response-shapes)
  - [10) Avoiding JPA lazy-load cascades](#10-avoiding-jpa-lazy-load-cascades)
  - [11) Full combined scenario](#11-full-combined-scenario)
  - [12) Correlated sort (MongoDB)](#12-correlated-sort-mongodb)
- [Reference](#reference)
  - [Module layout](#module-layout)
  - [Configuration prefixes](#configuration-prefixes)
  - [Configuration scenarios](#configuration-scenarios)
  - [Backend-specific nested-path guidance (JPA vs Mongo)](#backend-specific-nested-path-guidance-jpa-vs-mongo)
  - [`filter=` JSONPath syntax (Jayway 3.x)](#filter-jsonpath-syntax-jayway-3x)
  - [Mongo `filter=` support scope](#mongo-filter-support-scope)
  - [JPA `filter=` support scope](#jpa-filter-support-scope)
  - [When `filter=` returns 400](#when-filter-returns-400)
  - [When sort / paging / fields parameters return 400](#when-sort--paging--fields-parameters-return-400)
  - [Spring Data Mongo entity-mapping gotchas](#spring-data-mongo-entity-mapping-gotchas)
  - [TMF-630 compliance summary](#tmf-630-compliance-summary)
- [POC databases used](#poc-databases-used)
- [Build](#build)
- [License](#license)

## What you get

- TMF-630 attribute filtering — 26 operators mapped to QueryDSL `Predicate` (see
  [operator reference](#2-full-querydsl-operator-reference)).
- TMF-630 JSONPath `filter=` support merged into the same QueryDSL pipeline. On MongoDB,
  strict same-element array correlation via `$elemMatch`. Positional index `[N]` and
  `length() == N` on collections since 2.1.5.
- TMF-630 paging (`offset` / `limit`) and sorting (`sort=` with `+`/`-` direction, comma-
  separated multi-field, dotted nested paths).
- TMF-630 response envelope — `Content-Range`, `X-Total-Count`, `X-Result-Count`,
  `Link` (2.1.5), and `200` / `206` / `416` status differentiation.
- TMF-630 `fields=` partial representation via `@Tmf630Response`. `fields=none` returns
  only `id` / `href`; identity fields are always present.
- Uniform TMF-630 error body (`code`, `status`, `reason`, `message`, plus optional
  `referenceError`, `@type`, `@schemaLocation`) across `filter=` / `sort=` / paging /
  range errors.
- Correlated sort (MongoDB) — JSONPath (`$.arr[?(@.name=='k')].value`) and simple-rich
  (`arr[name=k].value`) grammars, both compiled to a single `Aggregation` pipeline. Ships
  in the optional `tmf630-toolkit-mongo-aggregation` module.
- Works in both Spring Boot and plain Spring projects.

## Requirements

| Requirement    | Version                                     | Enforced by                                     |
| -------------- | ------------------------------------------- | ----------------------------------------------- |
| Java           | 17 (exact major; no 18+)                    | `maven-enforcer-plugin` — `[17,18)`             |
| Maven          | 3.9.x                                       | `maven-enforcer-plugin` — `[3.9,3.10)`          |
| Spring Boot    | 4.1.x (BOM-aligned)                         | Parent `pom.xml` `spring-boot.version=4.1.0`    |
| Java persistence backend | JPA (any Hibernate-compatible dialect) **or** MongoDB 4.0+ | See [Prerequisites for attribute filtering](#prerequisites-for-attribute-filtering) |

Consuming applications don't inherit the enforcer constraints — only the toolkit's own
build is bounded to Java 17 and Maven 3.9.x. If you consume the released artifacts you
just need Spring Boot 4.1.x on your classpath.

## Prerequisites for attribute filtering

The toolkit provides query-string-to-predicate translation but does **not** ship a
specific persistence backend. Your service must add the QueryDSL binding for the backend
it uses, plus a compile-time annotation processor to generate Q-classes from your entity
models.

### JPA backend

```xml
<!-- QueryDSL JPA binding (runtime) -->
<dependency>
  <groupId>com.querydsl</groupId>
  <artifactId>querydsl-jpa</artifactId>
  <classifier>jakarta</classifier>
</dependency>

<!-- Q-class generation (compile-time only) -->
<dependency>
  <groupId>com.querydsl</groupId>
  <artifactId>querydsl-apt</artifactId>
  <classifier>jakarta</classifier>
  <scope>provided</scope>
</dependency>
```

Your repository must extend `QuerydslPredicateExecutor`:

```java
public interface PersonRepository
    extends JpaRepository<Person, Long>, QuerydslPredicateExecutor<Person> {}
```

### MongoDB backend

```xml
<!-- QueryDSL MongoDB binding (runtime) -->
<dependency>
  <groupId>com.querydsl</groupId>
  <artifactId>querydsl-mongodb</artifactId>
</dependency>

<!-- Q-class generation (compile-time only) -->
<dependency>
  <groupId>com.querydsl</groupId>
  <artifactId>querydsl-apt</artifactId>
  <classifier>jakarta</classifier>
  <scope>provided</scope>
</dependency>
```

Mongo entities must be annotated with `@QueryEntity` (from `com.querydsl.core.annotations`)
in addition to `@Document`:

```java
@QueryEntity
@Document("persons")
public class Person { ... }
```

Your repository must extend `QuerydslPredicateExecutor`:

```java
public interface PersonRepository
    extends MongoRepository<Person, String>, QuerydslPredicateExecutor<Person> {}
```

### Spring Data starter

Whichever backend you use, the corresponding Spring Data starter must be on the classpath:

- **JPA**: `spring-boot-starter-data-jpa`
- **MongoDB**: `spring-boot-starter-data-mongodb`

These are typically already present in your service. The toolkit does not pull them
transitively because it is backend-agnostic.

### What the toolkit provides transitively (no action needed)

- `querydsl-core` — the predicate API used internally
- `spring-data-commons` — shared Spring Data types (`Pageable`, `Page`, `Sort`,
  `QuerydslPredicateExecutor`)
- `json-path` — used for `filter=` JSONPath parsing

## Dependency management

### First: import opentmf dependency versions

This manages the versions of all opentmf libraries so they stay compatible:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.opentmf</groupId>
      <artifactId>opentmf-versions</artifactId>
      <version>RELEASE</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

### Then choose your dependencies

#### Spring Boot (recommended one-liner)

```xml
<dependency>
  <groupId>org.opentmf.query</groupId>
  <artifactId>tmf630-toolkit-all</artifactId>
</dependency>
```

#### Spring Boot (pick only what you need)

```xml
<!-- Paging/sorting -->
<dependency>
  <groupId>org.opentmf.query</groupId>
  <artifactId>tmf630-toolkit-paging-sorting-autoconfigure</artifactId>
</dependency>

<!-- QueryDSL filtering -->
<dependency>
  <groupId>org.opentmf.query</groupId>
  <artifactId>tmf630-toolkit-attribute-filtering-autoconfigure</artifactId>
</dependency>
```

#### Spring Boot — MongoDB-backed services adding correlated sort

If your service is MongoDB-backed and you want to accept correlated sort terms (e.g. sort
by the value of an array element identified by a predicate), add the optional Mongo
aggregation module **in addition to** the autoconfigure modules above:

```xml
<!-- Optional: correlated-sort Aggregation executor for Mongo backends -->
<dependency>
  <groupId>org.opentmf.query</groupId>
  <artifactId>tmf630-toolkit-mongo-aggregation</artifactId>
</dependency>
```

Adding this dependency:
- Pulls `spring-data-mongodb` and `querydsl-mongodb` (with the legacy `mongo-java-driver`
  excluded — Spring Boot 4's `mongodb-driver-core` is used).
- Auto-registers a `Tmf630MongoCorrelatedSortExecutor` bean when a `MongoTemplate` is on
  the classpath.
- Adds the `TmfRichPageable` controller parameter binding — the recommended
  two-parameter shape (see [section 12](#12-correlated-sort-mongodb)). `TmfSort` is also
  bound for the alternative three-parameter `(Predicate, TmfSort, Pageable)` shape.
  Both are wired by the rich resolvers in `paging-sorting-autoconfigure`, registered
  alongside the existing `Sort` and plain `Pageable` resolvers — plain-only controllers
  stay unchanged.

JPA-only and other non-Mongo services should **not** add this dependency. The base
toolkit's plain `Sort` resolver continues to 400 on correlated terms, which is the
correct behaviour for a JPA backend.

### Plain Spring (manual wiring)

```xml
<dependency>
  <groupId>org.opentmf.query</groupId>
  <artifactId>tmf630-toolkit-paging-sorting-core</artifactId>
</dependency>

<dependency>
  <groupId>org.opentmf.query</groupId>
  <artifactId>tmf630-toolkit-attribute-filtering-core</artifactId>
</dependency>
```

Dependency versions are aligned via Spring Boot BOM `4.1.0`.

## Developer handbook

### 1) Example domain and sample data

Every handbook example below uses the same tiny domain:

- `id` (Long)
- `name` (String)
- `surname` (String)
- `birthdate` (LocalDate)
- `sex` (String, e.g. `female` / `male`)

Endpoint: `GET /api/persons`

| id  | name   | surname | birthdate  | sex    |
| --- | ------ | ------- | ---------- | ------ |
| 1   | Alice  | Doe     | 1992-05-10 | female |
| 2   | Bob    | Smith   | 1988-11-23 | male   |
| 3   | Carol  | Doe     | 2001-01-17 | female |
| 4   | David  | Brown   | 1995-09-02 | male   |
| 5   | Eva    | Stone   | 1998-04-05 | female |
| 6   | Fiona  | Blake   | 1994-12-21 | female |
| 7   | Grace  | Miller  | 2003-07-30 | female |
| 8   | Hannah | Wilson  | 1991-02-14 | female |
| 9   | Irene  | Moore   | 1985-10-01 | female |
| 10  | Julia  | Clark   | 1996-06-11 | female |

### 2) Full QueryDSL operator reference

**This section is the toolkit's API surface.** Every operator listed here is available
through the standard query-string form `?field.operator=value` — and, when
`implicit-eq-enabled=true` (the default), also through the bare form `?field=value`
(which is treated as `?field.eq=value`).

With `implicit-eq-csv-or=true` (the default), an implicit-eq value is a TMF-630 value
list: commas express OR. So `field=a,b` matches `a` OR `b`, exactly like
`field=a&field=b` or `field.in=a,b`. Use explicit `field.eq=a,b` (or the `\,` escape) to
match a literal value containing a comma; explicit single-value operators never split.

With `implicit-eq-semicolon-or=true` (the default), TMF-630 Part 1 §4.4 explicit `;`
ORing is also supported: `field=a;b` and the repeated-pair form `field=a;field=b` (the
redundant `field=` prefix inside the value is stripped). Semicolons compose with commas —
`field=a,b;c` matches `a` OR `b` OR `c`. Same escape hatches apply: explicit operators
never split, and `\;` embeds a literal semicolon. A `;`-segment prefixed with a
*different* key (`?a=x;b=y`) is kept as a literal element — cross-attribute `;` pairs
are out of scope. Servlet containers URL-decode `%3B` to `;` before the toolkit sees the
value, so an encoded semicolon is indistinguishable from a raw one; use `\;` (or an
explicit operator) to transmit a literal semicolon.

#### Operator table

| Operator suffix | Meaning                        | Example                                                                |
| --------------- | ------------------------------ | ---------------------------------------------------------------------- |
| `eq`            | equals                         | `name.eq=Alice`                                                        |
| `ne`            | not equals                     | `surname.ne=Smith`                                                     |
| `eqi`           | equals ignore case             | `name.eqi=alice`                                                       |
| `nei`           | not equals ignore case         | `surname.nei=smith`                                                    |
| `gt`            | greater than                   | `birthdate.gt=1990-01-01`                                              |
| `gte`           | greater than or equal          | `birthdate.gte=1990-01-01`                                             |
| `lt`            | less than                      | `birthdate.lt=2000-01-01`                                              |
| `lte`           | less than or equal             | `birthdate.lte=2000-01-01`                                             |
| `between`       | value range (2 values)         | `birthdate.between=1990-01-01&birthdate.between=1999-12-31`            |
| `in`            | in set (multi-value)           | `surname.in=Doe&surname.in=Brown` _or_ `surname.in=Doe,Brown`          |
| `nin`           | not in set (multi-value)       | `surname.nin=Smith&surname.nin=Jones` _or_ `surname.nin=Smith,Jones`   |
| `isnull`        | is null (no value)             | `name.isnull`                                                          |
| `isnotnull`     | is not null (no value)         | `surname.isnotnull`                                                    |
| `like`          | SQL LIKE (with `%`/`_`)        | `name.like=A%`                                                         |
| `likei`         | SQL LIKE ignore case           | `name.likei=a%`                                                        |
| `contains`      | substring match                | `surname.contains=ow`                                                  |
| `containsi`     | substring match ignore case    | `surname.containsi=OW`                                                 |
| `startswith`    | starts with                    | `name.startswith=Da`                                                   |
| `startswithi`   | starts with ignore case        | `name.startswithi=da`                                                  |
| `endswith`      | ends with                      | `surname.endswith=oe`                                                  |
| `endswithi`     | ends with ignore case          | `surname.endswithi=OE`                                                 |
| `regex`         | regular expression match       | `name.regex=^A.*`                                                      |
| `regexi`        | regex match ignore case        | `name.regexi=^a.*`                                                     |

**26 operators total**, when the case-insensitive variants and multi-value operators are
counted separately.

#### URL-encoded operator literal forms (TMF-630 Part 1 §4.4)

Per TMF-630 Part 1 §4.4, the operator can also be embedded in the parameter *name*
(URL-encoded) rather than the suffix. Both forms map onto the same operators:

| Encoded          | Decoded name          | Equivalent suffix form                                    |
| ---------------- | --------------------- | --------------------------------------------------------- |
| `%3E` / `%3E%3D` | `field>v` / `field>=v` | `field.gt=v` / `field.gte=v`                              |
| `%3C` / `%3C%3D` | `field<v` / `field<=v` | `field.lt=v` / `field.lte=v`                              |
| `%3D%3D`         | `field==v`             | `field.eq=v` (explicit — never value-list split)          |
| `%3D~`           | `field=~pattern`       | `field.regex=pattern`                                     |

The spec's ORING example `?dateTime%3C2013-04-20;dateTime%3C2017-04-20` (one decoded name
carrying two expressions) folds the same way as repeated parameters.

#### Notes

- `regex` / `regexi` require `opentmf.tmf630.attribute-filtering.regex.enabled=true`
  (default `false`). Max pattern length is bounded by
  `opentmf.tmf630.attribute-filtering.regex.max-length` (default `256`).
- **3.0.0**: `regex` / `regexi` on `@Entity` (pure JPA) rooted queries return
  `400` at parse time — querydsl-jpa renders them as SQL `LIKE` (not real regex),
  silently differing from Mongo/JSONB semantics. Escape hatch (deprecated):
  set `opentmf.tmf630.attribute-filtering.regex.allow-jpa-like-semantics=true`
  to preserve the pre-3.0.0 `LIKE`-based behavior; a one-time `WARN` log is
  emitted on first use. For real regex on Postgres, use the JSONB backend
  (native `~` / `~*` operators) — see the JSONB backend handbook.
- `between`, `in`, `nin` are multi-value operators. Values can be provided either as
  repeated query parameters (`?key.in=A&key.in=B`) or as a single comma-separated list
  (`?key.in=A,B`). A literal comma inside a value can be escaped with `\,`. The two
  forms can be mixed in one request.
- Unknown fields/operators are validated by `on-unknown-field` and
  `on-unknown-operator` (both default `REJECT`).
- JSONPath `filter=` uses a separate policy `on-unknown-json-path-field` (default
  `IGNORE`) so that unmatched paths yield an empty result per TMF-630 Part 6.

#### Null-check widening (`MISSING_ONLY` vs `NULLISH`)

TMF-630 defines no null-test operator; Part 6's `[?(!@.field)]` glosses as *"items that
do not have the property"*. The toolkit's default (`isnull-semantics: MISSING_ONLY`)
mirrors that: `?attr.isnull=true` (and `filter=` forms `!@.field` / `== null`) matches
only documents where the field is missing (Mongo `{$exists: false}`) or SQL `NULL`.
This is the back-compatible behaviour and the one Part 6 arguably specifies.

Downstream consumers whose data model treats *missing*, *explicit `null`*, and *empty
array* as equivalent "no value" states can opt into `isnull-semantics: NULLISH`. Under
NULLISH:

- On Mongo `@Document` roots, `?attr.isnull=true` widens to match documents where the
  field is **missing OR explicitly `null`**. `?attr.isnotnull=true` is the exact
  complement. Empty-array matching (`[]`) is intentionally not applied on the plain
  find path — Spring Data's `QueryMapper` strips `$size` / typed-empty-list clauses
  during its post-serialisation pass — so callers that need the third state can add an
  explicit `filter=$[?(@.arr.length()==0)]` clause (see
  [`filter=` JSONPath grammar](#3-filter-jsonpath-grammar) below) or compose a
  repository-level `.size().eq(0)` predicate.
- On JPA `@Entity` roots the widening is skipped: SQL `IS NULL` already captures the
  only "no value" state for scalar columns, and applying the `NOT IN (NULL)` complement
  would poison `IS_NOT_NULL` to zero rows via SQL trilean UNKNOWN. NULLISH is therefore
  a functional no-op on JPA — the same SQL as `MISSING_ONLY`.

Set the mode application-wide via the property; there is no per-request override —
callers that need mixed semantics run the two `?attr.isnull=` styles as separate
requests or compose repository-level predicates directly.

#### Reserved parameter names

The following query parameter names are reserved for paging, sorting, field selection,
and filter control. The attribute filtering engine skips them automatically — they are
never treated as entity field filters:

`page`, `size`, `sort`, `offset`, `limit`, `fields`, `filter`,
`filter.combineWithAttributes`, `depth`, `expand`

`depth` and `expand` are the TMF-630 Part 2 dereferencing directives. The toolkit does
not implement reference expansion (that requires application-level data access), but it
reserves the names so a spec-compliant request is never misread as an attribute filter.

If your entity happens to have a field with one of these names (for example, a column
called `offset`), you can still filter by it using the explicit operator suffix form:

| Query           | Behaviour                                                                          |
| --------------- | ---------------------------------------------------------------------------------- |
| `offset=5`      | Reserved — interpreted as TMF-630 pagination offset, **not** as a filter           |
| `offset.eq=5`   | Attribute filter — matches records where the `offset` field equals `5`             |
| `offset.gte=3`  | Attribute filter — matches records where `offset >= 3`                             |

The bare form (`field=value`) is ambiguous for reserved names, and the library resolves
it in favour of the framework parameter. The explicit operator form (`field.op=value`)
always bypasses the reservation.

### 3) `filter=` JSONPath grammar

TMF-630 Part 6 defines a JSONPath grammar for a `filter=` query parameter that expresses
richer grouped conditions than the attribute-side operator suffixes allow. The toolkit
implements a **restricted subset** of Jayway JSONPath and translates it to the same
QueryDSL `Predicate` pipeline that attribute-side filters produce.

Jayway is used **solely** as a syntax validator (`JsonPath.compile()`); it is never
invoked to evaluate the expression against a result array at runtime.

#### Basic examples

```http
# All active persons
GET /api/persons?filter=$[?(@.sex == 'female')]

# Combined comparison
GET /api/persons?filter=$[?(@.birthdate > '1990-01-01' && @.surname != 'Smith')]

# Grouped OR
GET /api/persons?filter=$[?((@.sex == 'female') || (@.surname == 'Brown'))]

# Null check via unary negation (`!@.field`) — matches missing or null values
GET /api/persons?filter=$[?(!@.middleName)]
```

Supported operators inside `[?(...)]`: `==`, `!=`, `>`, `>=`, `<`, `<=`, `=~` (regex —
requires `regex.enabled=true`), `&&`, `||`, `!` (only as prefix on `@.field`).

String literals accept both `'...'` and `"..."` — the opening quote is the close
sentinel, so `'X"` does not terminate at the `"`. Number, boolean, and `null` literals
are also accepted.

#### Combining attribute filtering with `filter=`

Attribute query parameters and `filter=` are merged into a single predicate:

```http
GET /api/persons?sex.eq=female&filter=$[?(@.birthdate > '1990-01-01')]
```

Default merge is `AND`:

```
(sex == female) AND (birthdate > 1990-01-01)
```

Per-request override to `OR` via the reserved parameter `filter.combineWithAttributes`:

```http
GET /api/persons?sex.eq=female&filter=$[?(@.surname == 'Brown')]&filter.combineWithAttributes=OR
```

Effective logic:

```
(sex == female) OR (surname == Brown)
```

#### Sub-array correlation

Given a document with a nested array (typical TMF resource shape):

```json
{
  "externalReference": [
    { "name": "MARKET_ACCOUNT_ID", "id": "OPCO-ID-012" },
    { "name": "ORDER_REFERENCE",    "id": "OPCO-ORDER-012" }
  ]
}
```

To require the same array element to satisfy multiple conditions (Mongo `$elemMatch`
semantics), nest a `[?(...)]` on the array:

```http
GET /api/orders?filter=$[?(@.externalReference[?(@.name == 'ORDER_REFERENCE' && @.id == 'OPCO-ORDER-012')])]
```

The negative case — cross-element mismatch does **not** match:

```http
GET /api/orders?filter=$[?(@.externalReference[?(@.name == 'MARKET_ACCOUNT_ID' && @.id == 'OPCO-ORDER-012')])]
```

- **Mongo**: translated to `$elemMatch`, strict same-element semantics.
- **JPA (3.0.0)**: array-correlation works when the collection field is
  JOIN-mapped (`@OneToMany`, `@ManyToMany`, or `@ElementCollection`). The
  toolkit emits a correlated `EXISTS` subquery with same-element semantics
  matching Mongo's `$elemMatch`. Collections NOT annotated with one of those
  three (for example, `@JdbcTypeCode(SqlTypes.JSON)`-mapped lists stored as
  JSON columns) return `400 Bad Request` with a clear message naming the
  escape hatch — use a JSONB-backed entity or repository-level QueryDSL for
  the correlated predicate.

> **Warning — uncorrelated attribute-side matching:** using separate attribute
> parameters like `externalReference.name.eq=ORDER_REFERENCE&externalReference.id.eq=OPCO-ORDER-012`
> does **not** guarantee the two values come from the same array element. An object
> with `name=ORDER_REFERENCE` on one reference and `id=OPCO-ORDER-012` on a *different*
> reference would false-positive. Always use the nested `[?(...)]` form for correlated
> conditions on Mongo backends.

#### TMF-630 shorthand forms

Per TMF-630, the leading `$` / `$.` may be omitted from `filter=`. All three of these
match identically:

```http
GET /api/orders?filter=$[?(@.status == 'Pending')]
GET /api/orders?filter=[?(@.status == 'Pending')]
GET /api/orders?filter=$.[?(@.status == 'Pending')]
```

Sub-array correlation also supports the shorthand:

```http
GET /api/orders?filter=statusChange[?(@.status == 'Pending')]
GET /api/orders?filter=$.statusChange[?(@.status == 'Pending')]
```

Both rewrite internally to the canonical `$[?(@.statusChange[?(@.status == 'Pending')])]`.

#### Wildcard `[*]` as transparent projection

Canonical JSONPath uses `[*]` to project across array elements. Mongo's BSON
path-equality auto-projects implicitly, so the toolkit accepts both forms equivalently:

```http
GET /api/orders?filter=$[?(@.externalReference[*].name == 'ORDER_REFERENCE')]
GET /api/orders?filter=$[?(@.externalReference.name == 'ORDER_REFERENCE')]
```

Both match orders whose `externalReference` array has at least one element with
`name == 'ORDER_REFERENCE'`. The `[*]` is stripped at parse time; quoted string literals
containing `[*]` are preserved verbatim.

#### Positional index `[N]` (2.1.5, Mongo only)

TMF-630 Part 6 lists `[n]` — *"Selects the nth element from an array. Indexes are
0-based."* — as a JSONPath path operator. `filter=` accepts positional `[N]` inside
`@`-paths and resolves it as a dotted numeric hop (`productOrderItem[2].state` →
`productOrderItem.2.state`), which MongoDB resolves natively:

```http
# The 3rd (index 2) productOrderItem must be in 'completed' state
GET /api/productOrder?filter=$[?(@.productOrderItem[2].state == 'completed')]

# Multi-hop — a[0].b[1].c
GET /api/nested?filter=$[?(@.a[0].b[1].c == 'x')]
```

- **Mongo**: dotted numeric path resolved natively — no `$expr`, no aggregation stage.
  Out-of-range indices simply match nothing (Mongo-native no-match, not an error).
- **JPA**: rejected with `400 Bad Request`. Element-N indexing is not portable JPQL,
  and the toolkit does not silently fake it via a subquery.
- Allowlist is authored by JavaBean field name; `[N]` narrows the element, not the
  field, so an allowlist entry `externalReference.id` covers `externalReference[N].id`
  too.

#### `length()` on collection fields (2.1.5, both backends)

TMF-630 Part 6's Functions table lists `length()` returning Integer for an array.
`filter=` accepts `length() == N` on collection fields; both backends serialise it
through the standard QueryDSL `Ops.COL_SIZE` fast path (`SIZE(coll)=N` on JPA,
`{field: {$size: N}}` on Mongo). This closes the *"exists but is empty"* gap named in
the 2.1.4 CHANGELOG for `isnull-semantics: NULLISH`, which deliberately excludes empty
arrays:

```http
# "tags exists but is empty"
GET /api/persons?filter=$[?(@.tags.length() == 0)]

# "externalReference has exactly 3 elements"
GET /api/persons?filter=$[?(@.externalReference.length() == 3)]

# Combining length with attribute filter
GET /api/persons?filter=$[?(@.externalReference.length() == 0 || @.name == 'x')]
```

Deliberately narrow scope in 2.1.5 — all rejections return `400 Bad Request` with a
specific message:

- **`==` only.** `length() > 0` and every other comparator are rejected
  (*"length() supports only == comparison; use [?(...)] for non-empty checks"*).
  Cross-backend consistency: Mongo cannot express `>` / `<` on `$size` without raw
  `$expr` emission through package-private Spring Data internals, and shipping a URL
  grammar that works on JPA but 400s on Mongo would be a bug factory.
- **Collections only.** `length()` on a String, object, or scalar field is rejected
  (*"length() is supported only on collection fields"*). TMF-630 Part 6 defines
  `length()` for arrays; string/object length are out of the normative spec.
- **Non-negative integer literal.** `length() == 'x'`, `length() == null`,
  `length() == -1` all return `400`.

Escape hatches for the excluded cases:

- Non-empty check: existing array-match `$[?(@.arr[?(@.id)])]`.
- String-length approximation: existing regex `$[?(@.name =~ /^.{5,}$/)]`.
- Repository-level QueryDSL for anything else: `entity.tags.size().gt(5)` on JPA;
  `Criteria.where(...)` on Mongo.

#### What `filter=` does NOT support (rejected with 400)

- `IN` / `NIN` (use attribute-level `.in` / `.nin` operators instead)
- `SIZE`, `EMPTY`, `CONTAINS` (Jayway-specific — use `length()==N` for empty checks,
  array-match for containment)
- Bare exists check (`$[?(@.field)]`)
- Recursive descent operator (`..`)
- Functions other than `length()` (e.g. `count()`, `min()`, `max()`)
- `length()` outside the `== N` on collection-field scope (see above)
- Script expressions `[(expression)]`

For anything the restricted subset doesn't cover, either use the attribute-side operators
(`.regex`, `.in`, `.isnull`, `.between`, …) or write repository-level QueryDSL directly.

### 4) Paging, sorting, and response headers

Every list endpoint served through the toolkit accepts three families of query
parameters:

| Family        | Parameters                                                                     |
| ------------- | ------------------------------------------------------------------------------ |
| **Paging**    | `offset` (default `0`) and `limit` (default `50`, capped at `500`)             |
| **Sorting**   | `sort=<field>` or `sort=+<field>` (asc) / `sort=-<field>` (desc); comma-separated multi-field |
| **Fields**    | `fields=id,name,...` — see [section 5](#5-field-selection-with-tmf630response)  |

#### Paging examples

```http
GET /api/persons?offset=0&limit=2
GET /api/persons?offset=2&limit=2
GET /api/persons?offset=0&limit=10&sort=-surname,+name
```

If `offset` is missing it defaults to `0`. If `limit` is missing the server applies
`opentmf.tmf630.paging.default-limit` (default `50`, cap `500`).

#### Sorting grammar

- `+field` or `field` — ascending
- `-field` — descending
- Multi-field via commas: `sort=-surname,+name` sorts by surname desc, then name asc
- Dotted nested paths: `sort=address.city` (subject to `allow-nested-sort-properties`)

For services that need to sort by a value taken from a specific element of an embedded
array (TMF "characteristics" pattern), the optional `tmf630-toolkit-mongo-aggregation`
module adds two additional sort grammars on top of the plain form. See
[**section 12) Correlated sort (MongoDB)**](#12-correlated-sort-mongodb).

#### Response headers

Every paged response emitted through `@Tmf630Response` (or `Tmf630Util.tmfPage(Page)`)
carries these headers:

| Header                    | Purpose                                                                          | Example                                     |
| ------------------------- | -------------------------------------------------------------------------------- | ------------------------------------------- |
| `X-Total-Count`           | Total number of matching resources (TMF-630 Part 1 §1.9 / §4.5)                  | `X-Total-Count: 200`                        |
| `X-Result-Count`          | Number of resources in this response body                                        | `X-Result-Count: 10`                        |
| `Content-Range`           | RFC 7233 `items` unit range, or `items */N` when unsatisfiable                   | `Content-Range: items 1-10/200`             |
| `Link` (**2.1.5**)        | RFC 8288 pagination navigation — `first`, `prev`, `next`, `last`                 | `Link: <...offset=0...>; rel="first", <...>; rel="next", <...>; rel="last"` |

`Link` header details:

- Always includes `first` and `last` when the result set is non-empty.
- Omits `prev` when `offset == 0`.
- Omits `next` when `offset + limit >= total` (i.e. on the last page).
- All URIs preserve unrelated query parameters (`sort=`, `filter=`, `status=` etc.); only
  `offset` is rewritten.
- Silent no-op when called outside a request context (unit tests calling
  `Tmf630Util.tmfPage(page)` directly).

Example of the full paged-response header set:

```http
GET /api/persons?offset=20&limit=10&status=active

HTTP/1.1 206 Partial Content
Content-Type: application/json
X-Total-Count: 50
X-Result-Count: 10
Content-Range: items 21-30/50
Link: <https://host/api/persons?status=active&offset=0&limit=10>; rel="first",
      <https://host/api/persons?status=active&offset=10&limit=10>; rel="prev",
      <https://host/api/persons?status=active&offset=30&limit=10>; rel="next",
      <https://host/api/persons?status=active&offset=40&limit=10>; rel="last"
```

#### Status codes: 200 vs 206 vs 416

Per TMF-630 Part 1 §4.5.1:

| Status                          | When                                                                                     |
| ------------------------------- | ---------------------------------------------------------------------------------------- |
| `200 OK`                        | Full result set fits in the requested page (or the collection is empty)                  |
| `206 Partial Content`           | Requested page is a proper subset — pagination is in effect                              |
| `416 Requested Range Not Satisfiable` | `offset` is past the end of the result set (i.e. `offset >= total` and `total > 0`) |

The `416` response also carries `Content-Range: items */N` (unsatisfied-range convention
per RFC 7233) and a TMF error body — see [section 9](#9-error-response-shapes).

### 5) Field selection with `@Tmf630Response`

`@Tmf630Response` is a class-level or method-level annotation on Spring MVC handlers.
When present, the toolkit's `ResponseBodyAdvice`:

1. Reads the `fields=` query parameter and, if present, projects the response body down
   to the requested fields (JavaBean-getter introspection, no Jackson dependency).
2. For handlers returning `Page<T>`, unwraps the page content, sets the correct status
   (`200`/`206`/`416`), and emits `X-Total-Count`, `X-Result-Count`, `Content-Range`,
   and `Link` headers.
3. Always includes `id` and `href` in partial representations, per TMF-630 Part 1 §4.3,
   whether requested or not. Types without `id`/`href` are unaffected.
4. Supports the special value `fields=none` — returns only `id` and `href` (per §4.3).

Minimal example:

```java
@RestController
@RequestMapping("/api/persons")
class PersonController {

  private final PersonRepository repository;

  PersonController(PersonRepository repository) {
    this.repository = repository;
  }

  @GetMapping
  @Tmf630Response
  Page<Person> search(
      @QuerydslPredicate(root = Person.class) Predicate predicate,
      Pageable pageable) {
    return repository.findAll(predicate, pageable);
  }
}
```

That's all the code required. Filtering, paging, sorting, `fields=` selection, headers,
and status differentiation are all handled automatically.

#### The `depth` attribute

`@Tmf630Response(depth = N)` overrides the global default for how aggressively named
complex fields are expanded:

| depth   | Meaning                                                                                     |
| ------- | ------------------------------------------------------------------------------------------- |
| `0`     | Named field is passed to Jackson as-is (all getters called — **risk of JPA lazy-load**)     |
| `1` (default) | Only direct scalar sub-fields of the named complex field are included                 |
| `2`     | First-level complex sub-fields are also expanded; their sub-fields are not                  |

Resolution order: **method-level `depth`** → **class-level `depth`** →
**`opentmf.tmf630.field-selection.default-depth`** (defaults to `1`).

```java
@GetMapping("/persons")
@Tmf630Response(depth = 2)    // expand complex fields 2 levels deep for this endpoint
Page<Person> list(Pageable pageable) { ... }

@GetMapping("/orders")
@Tmf630Response(depth = 1)    // only top-level scalar fields of any selected complex field
Page<Order> orders(Pageable pageable) { ... }

@GetMapping("/simple")
@Tmf630Response               // inherits opentmf.tmf630.field-selection.default-depth
Page<Simple> simple(Pageable pageable) { ... }
```

Explicit dot-paths in `fields=` (e.g. `fields=address.country.code`) always resolve
regardless of `depth`.

**Cyclic type graphs** — types with self-referential fields (`Person.friend: Person`)
are safe at any `depth`. The recursive walk tracks visited types on the current stack
and stops expanding when it would re-enter a type it's already resolving. The
cyclically-referenced field still appears in the output as a scalar placeholder rather
than being dropped entirely.

#### Examples

`fields=none` — identity fields only:

```http
GET /api/persons/1?fields=none

HTTP/1.1 200 OK
{ "id": 1, "href": "/api/persons/1" }
```

`fields=id,name,birthdate` — explicit scalar selection:

```http
GET /api/persons/1?fields=id,name,birthdate

HTTP/1.1 200 OK
{ "id": 1, "href": "/api/persons/1", "name": "Alice", "birthdate": "1992-05-10" }
```

Nested dot-path — always resolves regardless of `depth`:

```http
GET /api/persons/1?fields=id,address.city,address.country.code
```

#### Manual field selection (`FieldSelectionUtil`)

For single-object endpoints or when you need manual control over the response shape,
`FieldSelectionUtil` (package `org.opentmf.query.commons.fieldselection`) can be called
directly:

```java
@GetMapping("/{id}")
ResponseEntity<Map<String, Object>> getById(
    @PathVariable Long id,
    @RequestParam(required = false) String fields) {
  Person person = repository.findById(id).orElseThrow();
  Map<String, Object> payload =
      (fields == null || fields.isBlank())
          ? FieldSelectionUtil.fieldsToMap(person)
          : FieldSelectionUtil.fieldsToMap(person, fields);
  return ResponseEntity.ok(payload);
}
```

### 6) Controller usage — three levels

There are three ways to wire TMF-630 responses, from most transparent to most manual.

#### Level 1: Fully transparent — `@Tmf630Response` on a `Page<T>` return (recommended)

```java
@RestController
@RequestMapping("/api/persons")
class PersonController {

  private final PersonRepository repository;

  PersonController(PersonRepository repository) {
    this.repository = repository;
  }

  @GetMapping
  @Tmf630Response
  Page<Person> search(
      @QuerydslPredicate(root = Person.class) Predicate predicate,
      Pageable pageable) {
    return repository.findAll(predicate, pageable);
  }
}
```

The library does everything: resolves the HTTP status (`200`/`206`/`416`), adds
`Content-Range` / `X-Total-Count` / `X-Result-Count` / `Link` headers, serialises page
content as a JSON array, and transparently applies `fields=` selection when the query
parameter is present.

#### Level 2: Manual paging, transparent field selection

```java
@GetMapping
@Tmf630Response
ResponseEntity<List<Person>> search(
    @QuerydslPredicate(root = Person.class) Predicate predicate,
    Pageable pageable) {
  Page<Person> page = repository.findAll(predicate, pageable);
  return Tmf630Util.tmfPage(page);
}
```

`Tmf630Util.tmfPage(page)` handles status and headers (including `Link`). The
`@Tmf630Response` annotation enables automatic `fields=` selection without any extra
parameter or code.

#### Level 3: Fully manual (no annotation needed)

```java
@GetMapping
ResponseEntity<List<Person>> search(
    @QuerydslPredicate(root = Person.class) Predicate predicate,
    Pageable pageable,
    @RequestParam(required = false) String fields) {
  Page<Person> page = repository.findAll(predicate, pageable);
  if (fields != null) {
    return Tmf630Util.tmfPage(page, fields);
  }
  return Tmf630Util.tmfPage(page);
}
```

Full control over every step. `Tmf630Util.tmfPage(page, fields)` returns
`ResponseEntity<List<Map<String, Object>>>` with field selection pre-applied.

### 7) Combining predicates with path variables

The toolkit builds a `Predicate` exclusively from query parameters. Path variables (like
`{id}`) are **not** included — combining them with the generated predicate is the
developer's responsibility.

This is common for sub-resource endpoints such as `GET /master/{id}/children`:

```java
@RestController
@RequestMapping("/api/masters/{masterId}/children")
class ChildController {

  private final ChildRepository repository;

  ChildController(ChildRepository repository) {
    this.repository = repository;
  }

  @GetMapping
  @Tmf630Response
  Page<Child> search(
      @PathVariable Long masterId,
      @QuerydslPredicate(root = Child.class) Predicate predicate,
      Pageable pageable) {
    Predicate combined = QChild.child.master.id.eq(masterId).and(predicate);
    return repository.findAll(combined, pageable);
  }
}
```

- `predicate` contains everything parsed from query parameters (attribute filters,
  `filter=`, etc.).
- The developer wraps it with the parent-scoping condition using standard QueryDSL.
- The final where clause is: `master.id = :masterId AND (query-param filters)`.

This separation is intentional — the library stays focused on query-string parsing and
does not make assumptions about URL structure or entity relationships.

### 8) Value types — dates, datetimes, enums

The library uses Spring's `DefaultFormattingConversionService` to parse query parameter
values into their Java target types.

#### Date and datetime formats

All `java.time` types use strict ISO-8601 format by default:

| Java type       | Accepted format                     | Example query                                                     |
| --------------- | ----------------------------------- | ----------------------------------------------------------------- |
| `LocalDate`     | `yyyy-MM-dd`                        | `birthdate.gt=1990-06-15`                                         |
| `LocalTime`     | `HH:mm:ss[.SSS]`                    | `startTime.gte=14:30:00`                                          |
| `LocalDateTime` | `yyyy-MM-dd'T'HH:mm:ss[.SSS]`       | `createdAt.lt=2024-01-15T14:30:00`                                |
| `OffsetDateTime`| `yyyy-MM-dd'T'HH:mm:ssXXX`          | `updatedAt.gte=2024-01-15T14:30:00+03:00`                         |
| `ZonedDateTime` | `yyyy-MM-dd'T'HH:mm:ssXXX'['VV']'`  | `scheduledAt.lt=2024-01-15T14:30:00+03:00[Europe/Istanbul]`       |
| `Instant`       | `yyyy-MM-dd'T'HH:mm:ssX` (UTC)      | `timestamp.gte=2024-01-15T11:30:00Z`                              |

> Fractional seconds (`[.SSS]`) are optional for `LocalTime` and `LocalDateTime`.

**`@DateTimeFormat` annotations on the entity are NOT respected.** The library converts
by Java type, not by inspecting field annotations. If your field is annotated
`@DateTimeFormat(pattern = "dd/MM/yyyy") LocalDate birthdate`, the library still expects
`yyyy-MM-dd` in the query string. The `@DateTimeFormat` pattern only affects Spring
MVC's own binding (form fields, `@RequestParam`).

**Customising the accepted format.** The `ValueConverter` bean is registered with
`@ConditionalOnMissingBean`, so you can override it in a `@Configuration` class:

```java
@Configuration
public class MyConversionConfig {

  // Option A: share the application ConversionService
  @Bean
  public ValueConverter tmf630ValueConverter(ConversionService conversionService) {
    return new ValueConverter(conversionService);
  }

  // Option B: register an additional formatter for a specific type
  @Bean
  public ValueConverter tmf630ValueConverter() {
    DefaultFormattingConversionService svc = new DefaultFormattingConversionService();
    svc.addConverter(String.class, LocalDate.class,
        s -> LocalDate.parse(s, DateTimeFormatter.ofPattern("dd/MM/yyyy")));
    return new ValueConverter(svc);
  }
}
```

#### Enum resolution

When a query parameter targets an enum field, the library resolves the string value in
this order:

1. **Custom factory methods** — the library scans the enum class for `public static`
   methods that accept a single `String` parameter and return the enum type itself
   (excluding `valueOf`). The first non-null result wins. Exceptions from factory
   methods are silently swallowed so a partial match doesn't block the fallback.
2. **Standard `Enum.valueOf`** — exact, case-sensitive.
3. **Error** — if all attempts fail, `400 Bad Request`.

**Plain enum (no factory method):**

```java
public enum Status { NEW, DONE, FAILED }
```

| Query                             | Result                                        |
| --------------------------------- | --------------------------------------------- |
| `status=NEW`                      | matches `Status.NEW`                          |
| `status=new`                      | `400 Bad Request` (case-sensitive)            |
| `status.in=NEW&status.in=DONE`    | matches `Status.NEW` or `Status.DONE`         |

**Enum with a case-insensitive factory:**

```java
public enum Status {
  NEW, DONE, FAILED;

  public static Status initFrom(String value) {
    return valueOf(value.toUpperCase());
  }
}
```

| Query           | Result                                                  |
| --------------- | ------------------------------------------------------- |
| `status=new`    | matches `Status.NEW` via `initFrom`                     |
| `status=Done`   | matches `Status.DONE` via `initFrom`                    |
| `status=FAILED` | matches `Status.FAILED` (either factory or `valueOf`)   |

**Enum with multiple factory methods:**

```java
public enum Status {
  NEW, DONE, FAILED;

  public static Status fromAlias(String value) {
    if ("nuevo".equalsIgnoreCase(value)) return NEW;
    throw new IllegalArgumentException("Unknown alias: " + value);
  }

  public static Status fromLower(String value) {
    return valueOf(value.toUpperCase());
  }
}
```

| Query           | Result                                                          |
| --------------- | --------------------------------------------------------------- |
| `status=nuevo`  | matches `Status.NEW` via `fromAlias`                            |
| `status=done`   | matches `Status.DONE` via `fromLower`                           |
| `status=FAILED` | matches `Status.FAILED` via `fromLower` or `valueOf` fallback   |
| `status=bogus`  | `400 Bad Request` (all methods and `valueOf` fail)              |

Factory methods are discovered once per enum type and cached.

### 9) Error response shapes

The toolkit produces a **single, uniform TMF-630 error body** for every query-parameter
validation failure it owns. Consumers can deserialise all four error paths (`filter=`,
`sort=`/`offset=`/`limit=`, `fields=`, range) into the same `ErrorMessage` type.

#### The TMF `ErrorMessage` body (TMF-630 Part 1 §3.4)

```json
{
  "code": "400",
  "status": "Bad Request",
  "reason": "Invalid filter parameter.",
  "message": "Field \"birthdate\" (LocalDate) could not be parsed from value \"15/06/1990\". Expected format: yyyy-MM-dd, example: 1990-06-15"
}
```

- **Mandatory**: `code`, `reason`.
- **Optional (2.1.5)**: `status`, `message`, `referenceError`, `@type`,
  `@schemaLocation`. All optional fields are omitted from the JSON body when `null`
  (Jackson `@JsonInclude(NON_NULL)`).

#### `filter=` errors → 400

`Tmf630FilteringExceptionHandler` catches `TmfFilteringException` from the JSONPath
parser, the attribute-side operator parser, and value coercion. Every such error surfaces
with:

```json
{
  "code": "400",
  "status": "Bad Request",
  "reason": "Invalid filter parameter.",
  "message": "<parser-specific detail>"
}
```

The handler is registered at `@Order(Ordered.HIGHEST_PRECEDENCE)` so it takes precedence
over any catch-all `@ExceptionHandler(Exception.class)` in the consuming application —
this prevents the common integration problem where a generic 500 handler intercepts the
error.

> **Consuming service note:** if you still see `500` after upgrading, verify that no
> framework-level component (API gateway, Sentry integration, custom
> `HandlerExceptionResolver`) strips the response before it reaches the client.

#### `sort=` / `offset=` / `limit=` errors → 400 (added in 2.1.5)

Before 2.1.5, sort/paging parameter errors fell through to Spring's default 400
translator, producing a non-TMF body. Since 2.1.5, `TmfPagingException` (extends
`IllegalArgumentException` for backward compatibility) is thrown by `TmfSortParser` and
the two `TmfPageableHandlerMethodArgumentResolver` variants, and the new
`Tmf630PagingExceptionHandler` maps it to:

```json
{
  "code": "400",
  "status": "Bad Request",
  "reason": "Invalid sort or paging parameter.",
  "message": "Sort property is not allowed: secret"
}
```

Examples that produce this body:

- `?sort=someRandomField` with an allowlist that doesn't include it →
  *"Sort property is not allowed: someRandomField"*
- `?sort=nested.field` with `allow-nested-sort-properties=false` →
  *"Nested sort properties are not allowed: nested.field"*
- `?offset=-1` → *"offset must be >= 0"*
- `?offset=notANumber` → *"offset must be numeric"*
- `?limit=0` or `?limit=-5` → *"limit must be > 0"*

#### Range not satisfiable → 416

When `offset` is past the end of the result set, `Tmf630RangeExceptionHandler` returns:

```http
HTTP/1.1 416 Requested Range Not Satisfiable
Content-Range: items */7
X-Total-Count: 7
X-Result-Count: 0
```

```json
{
  "code": "416",
  "status": "Requested Range Not Satisfiable",
  "reason": "Requested offset is outside the available range.",
  "message": "Requested offset 99 does not overlap with existing items. Valid offsets are between 0 and 6."
}
```

The `Content-Range: items */N` header follows RFC 7233 (unsatisfied range convention).
Consumers that want to skip trial-and-error can pre-check `X-Total-Count` from a prior
response.

#### `fields=` errors

Two distinct paths, deliberately treated differently:

- **Unknown field name in the request** (`fields=nonExistentProperty` on a type that
  doesn't expose that property) — silently skipped. No error. The response contains
  whatever fields DID resolve, plus the mandatory `id`/`href` per TMF-630 Part 1 §4.3.
  This is the spec-defensible behaviour ("unknown fields don't error"); the request
  isn't malformed, the field just doesn't exist on this type.
- **Reflection catastrophe inside `FieldSelectionUtil`** (`Introspector.getBeanInfo`
  refuses a class, a `PropertyDescriptor` can't be constructed for a record component,
  a getter blows up in `invoke`) — surfaced as `TmfFieldSelectionInternalException`
  and mapped to `500 Internal Server Error` with the same TMF `ErrorMessage` body
  shape as the other handlers. These are genuine server-side type-integrity failures,
  not bad client input, so a 500 is semantically correct.

Example 500 body:

```json
{
  "code": "500",
  "status": "Internal Server Error",
  "reason": "Field selection failed due to an internal reflection error.",
  "message": "Error getting properties of class com.example.Broken"
}
```

If you see this in production, the fix is almost always on the entity side — a broken
getter, a JavaBean spec violation, or a class that fails introspection. Log the full
stack from the `TmfFieldSelectionInternalException` cause for details.

### 10) Avoiding JPA lazy-load cascades

When no `fields=` parameter is present, the toolkit returns `page.getContent()` as-is.
Jackson then serialises the raw JPA entity objects and calls **every getter**, including
any `@OneToMany` / `@ManyToOne` lazy associations. Depending on whether the JPA session
is still open, this either triggers N+1 queries or throws `LazyInitializationException`.

The following patterns prevent this, ordered from best to most pragmatic.

**Pattern A — Return a DTO from the repository (strongly recommended)**

The cleanest solution: your Spring Data query returns a flat DTO that contains only the
fields you need. Lazy associations do not exist on the DTO, so there is nothing to load.

```java
// DTO — no JPA associations, no lazy fields
public record PersonDto(Long id, String name, String birthdate) {}

// Repository — only fetch what you need
@Query("SELECT new com.example.PersonDto(p.id, p.name, p.birthdate) FROM Person p")
Page<PersonDto> search(Predicate predicate, Pageable pageable);

// Controller — safe to use with or without fields=
@GetMapping("/persons")
@Tmf630Response
Page<PersonDto> list(
    @QuerydslPredicate(root = Person.class) Predicate predicate,
    Pageable pageable) {
  return repo.search(predicate, pageable);
}
```

Because `PersonDto` is a plain record with no associations, serialisation is always
safe. The `@Tmf630Response` advice still applies `fields=` selection on top if the
client requests it.

**Pattern B — Use `fields=` with only scalar field names**

When the client sends `fields=id,name,birthdate` and none of those names resolve to a
lazy association, `FieldSelectionUtil` at `depth=1` (the default) maps only scalar
properties. The raw entity POJO is never passed to Jackson — instead Jackson receives
a plain `Map` — so `getAddress()`, `getChildren()`, etc. are never called.

> **Rule of thumb:** only lazy-load risk arises when a field name in `fields=` refers to
> a complex JPA association. Scalar fields (`String`, `Long`, `LocalDate`, enums, …) are
> always safe.

**Pattern C — Fetch the association explicitly when you need it**

If you genuinely need a nested object in the response, use `@EntityGraph` or `JOIN FETCH`
so the association is loaded in a single query:

```java
@EntityGraph(attributePaths = {"address"})
Page<Person> findAll(Predicate predicate, Pageable pageable);
```

Then the client sends `fields=id,name,address`. With `depth=1` (the default), the
library maps only the scalar sub-fields of `address` (e.g. `city`, `zip`), never
touching `address.states` or other deeper associations. To go one level deeper for a
specific endpoint, annotate it with `@Tmf630Response(depth = 2)`.

**Patterns to avoid**

| Pattern | Problem |
| --- | --- |
| OSIV enabled (Spring Boot default `spring.jpa.open-in-view=true`) | Session stays open for the entire HTTP request; lazy loads silently succeed but produce hidden N+1 queries |
| `@Transactional` on a controller method | Same hidden N+1 risk, slightly narrower scope |
| `@Tmf630Response(depth = 0)` with no `fields=` | Raw entity handed to Jackson; every getter called; full object graph loaded |

> **Recommendation for new projects:** set `spring.jpa.open-in-view=false` in
> `application.yml` and use Pattern A (DTOs) for all list endpoints. This makes
> lazy-load issues surface at development time as `LazyInitializationException` rather
> than silently degrading performance in production.

### 11) Full combined scenario

All major capabilities in one flow — filter + paging + sorting + field selection +
range statuses + Link header:

```java
@RestController
@RequestMapping("/api/persons")
class PersonCombinedController {

  private final PersonRepository repository;

  PersonCombinedController(PersonRepository repository) {
    this.repository = repository;
  }

  @GetMapping
  @Tmf630Response
  Page<Person> search(
      @QuerydslPredicate(root = Person.class) Predicate predicate,
      Pageable pageable) {
    return repository.findAll(predicate, pageable);
  }
}
```

#### Example A: second page + sort by birthdate desc + selected fields + filters

```http
GET /api/persons?birthdate.gt=1990-01-01&sex.eq=female&sort=-birthdate&offset=2&limit=2&fields=id,name,birthdate
```

- filters to `birthdate > 1990-01-01 AND sex == female`
- sorts by `birthdate` descending
- returns the second page slice (`offset=2`, `limit=2`)
- returns only `id`, `name`, `birthdate` (plus `href` if the type exposes it)

```http
HTTP/1.1 206 Partial Content
Content-Type: application/json
Content-Range: items 3-4/7
X-Total-Count: 7
X-Result-Count: 2
Link: <.../persons?birthdate.gt=1990-01-01&sex.eq=female&sort=-birthdate&offset=0&limit=2&fields=id,name,birthdate>; rel="first",
      <.../persons?birthdate.gt=1990-01-01&sex.eq=female&sort=-birthdate&offset=0&limit=2&fields=id,name,birthdate>; rel="prev",
      <.../persons?birthdate.gt=1990-01-01&sex.eq=female&sort=-birthdate&offset=4&limit=2&fields=id,name,birthdate>; rel="next",
      <.../persons?birthdate.gt=1990-01-01&sex.eq=female&sort=-birthdate&offset=6&limit=2&fields=id,name,birthdate>; rel="last"
```

```json
[
  { "id": 5, "name": "Eva",   "birthdate": "1998-04-05" },
  { "id": 10, "name": "Julia", "birthdate": "1996-06-11" }
]
```

#### Example B: requested range is not satisfiable (416)

```http
GET /api/persons?birthdate.gt=1990-01-01&sex.eq=female&sort=-birthdate&offset=99&limit=2

HTTP/1.1 416 Requested Range Not Satisfiable
Content-Type: application/json
Content-Range: items */7
X-Total-Count: 7
X-Result-Count: 0
```

```json
{
  "code": "416",
  "status": "Requested Range Not Satisfiable",
  "reason": "Requested offset is outside the available range.",
  "message": "Requested offset 99 does not overlap with existing items. Valid offsets are between 0 and 6."
}
```

#### Example C: 206 Partial Content (partial page, not an error)

```http
GET /api/persons?sort=-birthdate&offset=0&limit=3

HTTP/1.1 206 Partial Content
Content-Type: application/json
Content-Range: items 1-3/10
X-Total-Count: 10
X-Result-Count: 3
Link: <.../persons?sort=-birthdate&offset=0&limit=3>; rel="first",
      <.../persons?sort=-birthdate&offset=3&limit=3>; rel="next",
      <.../persons?sort=-birthdate&offset=9&limit=3>; rel="last"
```

```json
[
  { "id": 7, "name": "Grace", "surname": "Miller", "birthdate": "2003-07-30", "sex": "female" },
  { "id": 3, "name": "Carol", "surname": "Doe",    "birthdate": "2001-01-17", "sex": "female" },
  { "id": 5, "name": "Eva",   "surname": "Stone",  "birthdate": "1998-04-05", "sex": "female" }
]
```

`206` here means "the request is successful and returns only part of the full result
set." Note the absence of `rel="prev"` in the `Link` header on this first page.

#### Example D: combined attribute + JSONPath filter

```http
GET /api/persons?sex.eq=female&filter=$[?(@.birthdate > '1995-01-01' && @.surname != 'Doe')]&sort=-birthdate&limit=5
```

Effective SQL/BSON:

```
sex == 'female' AND (birthdate > '1995-01-01' AND surname != 'Doe')
ORDER BY birthdate DESC
LIMIT 5
```

### 12) Correlated sort (MongoDB)

> **Module required**: `tmf630-toolkit-mongo-aggregation`. JPA-only services skip this
> section; the plain `Sort` resolver continues to 400 on correlated terms. See
> [`docs/correlated-sort.md`](./docs/correlated-sort.md) for the full design note (URL
> grammar, semantics, capability matrices for both grammars, module layout).

TMF Open API resources frequently encode attributes as arrays of `{name, value}`
objects (Characteristics, ExternalReferences, RelatedParty, etc.). Sorting by, say, the
`value` of the `characteristic` whose `name` equals `'price'` is **not expressible in a
plain `find()` query** — sorting on `characteristic.value` picks the min/max value
across the whole array, not the value of a specific element. The `mongo-aggregation`
module addresses this with an `Aggregation`-based execution path triggered by two new
sort grammars.

#### Two grammars, one IR, one executor

Both grammars produce the same internal AST and run through the same aggregation
pipeline emitter. Pick whichever reads better at the call site.

| Grammar         | Example sort term                                        | Notes                                                                                                                    |
| --------------- | -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| **JSONPath**    | `$.characteristic[?(@.name == 'price')].value`           | Rich predicates (`&&`, `\|\|`, `==`, `!=`, `>`, `<`, `>=`, `<=`); the same syntax used by `filter=`.                     |
| **Simple-rich** | `characteristic[name=price].value`                       | Equality only, terse, default-key inference (`arr[X]` → `arr[id=X]`); supports `min` / `max` / `str` / `num` / `date` functions. |

Plain dotted sort terms (`-createdOn,+id`) keep the existing cheap `find()` path —
there is no regression for non-correlated requests. The branch happens at the
controller and is one `if`.

#### Consumer pattern

**Recommended shape** — controllers declare `TmfRichPageable` as the single
sort+paging parameter. `TmfRichPageable` extends Spring Data `Pageable` and additionally
exposes `tmfSort()` carrying both plain and correlated terms:

```java
@RestController
class ProductController {

  private final ProductRepository repository;
  private final Tmf630MongoCorrelatedSortExecutor correlatedExecutor;

  ProductController(
      ProductRepository repository,
      Tmf630MongoCorrelatedSortExecutor correlatedExecutor) {
    this.repository = repository;
    this.correlatedExecutor = correlatedExecutor;
  }

  @GetMapping("/products")
  @Tmf630Response
  Page<Product> list(
      @QuerydslPredicate(root = Product.class) Predicate filter,
      TmfRichPageable pageable) {

    if (pageable.tmfSort().requiresAggregation()) {
      return correlatedExecutor.findAll(Product.class, filter, pageable.tmfSort(), pageable);
    }
    return repository.findAll(filter, pageable);
  }
}
```

`pageable.getSort()` returns the **plain subset** of the sort (or `Sort.unsorted()`
when every term is correlated). When the correlated-sort branch fires, the executor
reads sort via `pageable.tmfSort()`. The find()-path call simply passes `pageable` to
Spring Data — its `getSort()` handles plain terms correctly and returns unsorted when
correlated terms are present (the correlated-sort branch handles those itself, so the
find()-path case never sees correlated terms).

**Three-parameter shape** — also supported, identical end-state semantics. Useful if
you have an existing controller using Spring Data `Pageable` and want to add
correlated-sort awareness incrementally:

```java
@GetMapping("/products")
@Tmf630Response
Page<Product> list(
    @QuerydslPredicate(root = Product.class) Predicate filter,
    TmfSort sort,
    Pageable pageable) {

  if (sort.requiresAggregation()) {
    return correlatedExecutor.findAll(Product.class, filter, sort, pageable);
  }
  return repository.findAll(filter, pageable.withSort(sort.toPlainSort()));
}
```

`@Tmf630Response` works identically across both branches — the advice operates on the
returned `Page<T>` shape, not on how the page was built. The correlated-sort executor
returns a standard `PageImpl<T>`, so `Content-Range` / `X-Total-Count` /
`X-Result-Count` / `Link` headers and `200` / `206` / `416` status differentiation all
apply to correlated-sort responses with no extra wiring. `@Tmf630Response(depth = N)`
also works as documented in section 5.

`Tmf630MongoCorrelatedSortExecutor` is auto-wired from the aggregation module.
Plain-only controllers using `Sort` as the parameter type are unchanged — they continue
to 400 on correlated terms exactly as before.

#### Sample resource

The examples below assume a typical TMF Product Offering resource:

```json
[
  { "id": "1",
    "characteristic": [
      { "name": "price", "value": 20.5 },
      { "name": "color", "value": "blue" },
      { "name": "size",  "value": "large" }
    ]
  },
  { "id": "2",
    "characteristic": [
      { "name": "price", "value": 18 },
      { "name": "color", "value": "blue" },
      { "name": "size",  "value": "small" }
    ]
  },
  { "id": "3",
    "characteristic": [
      { "name": "stock", "value": "none" },
      { "name": "color", "value": "blue" },
      { "name": "size",  "value": "small" }
    ]
  }
]
```

#### Example A: sort by the value of a specific characteristic — JSONPath

Sort ascending by the `value` of the `price` characteristic:

```http
GET /api/products?sort=$.characteristic[?(@.name == 'price')].value
```

For the dataset above the result order is:

```
2 (price 18), 1 (price 20.5), 3 (no price → null sorts last regardless of direction)
```

To exclude documents that don't have a `price` characteristic, pair the sort with an
explicit filter:

```http
GET /api/products
  ?filter=$[?(@.characteristic[?(@.name == 'price')])]
  &sort=$.characteristic[?(@.name == 'price')].value
```

Result: `2, 1`.

#### Example A.1: filter and sort that both target an auto-promoted `id` field

A common TMF pattern is to filter `productOffering` documents whose `prodSpecCharValueUse`
array contains an entry with a specific `id`, and sort by a value taken from that same
entry. Realistic shape:

```json
{
  "id": "PO-1",
  "prodSpecCharValueUse": [
    { "id": "RC_OFFER_TYPE", "productSpecCharacteristicValue": [{ "value": "Bronze" }] },
    { "id": "RC_DURATION",   "productSpecCharacteristicValue": [{ "value": "12M" }] }
  ]
}
```

```http
GET /api/productOffering
  ?filter=prodSpecCharValueUse[?(@.id == 'RC_OFFER_TYPE')]
  &sort=prodSpecCharValueUse[id=RC_OFFER_TYPE].productSpecCharacteristicValue.value
  &limit=5
```

Returns documents whose array contains a `RC_OFFER_TYPE` entry, ordered by that entry's
nested `value`. Both the filter and the sort target the nested `id` field; Spring Data
Mongo auto-promotes nested `id` properties to BSON `_id` on write, and the toolkit
applies the same mapping on both code paths so the request shape works whether or not
a correlated sort is present.

#### Example B: same sort, simple-rich form

```http
GET /api/products?sort=characteristic[name=price].value
```

Identical end state to Example A. Both lower to the same `Aggregation` pipeline.

#### Example C: default-key shorthand

When `name` is the bracket key (or whatever you've configured via
`opentmf.tmf630.mongo-aggregation.simple-rich.default-key`), you can drop the `name=`
prefix:

```yaml
opentmf:
  tmf630:
    mongo-aggregation:
      simple-rich:
        default-key: name
```

```http
GET /api/products?sort=characteristic[price].value
```

Same result as Example B.

#### Example D: descending with paging

```http
GET /api/products?sort=-characteristic[name=price].value&offset=0&limit=10
```

Returns documents ordered by `price` descending. Documents without a `price`
characteristic sort **last** in descending (mirrors ascending-puts-null-first).

#### Example E: tie-breaking with a plain term

When multiple documents resolve to the same correlated key, MongoDB does not guarantee
a stable order between them. Append a stable plain term to make it deterministic:

```http
GET /api/products?sort=-characteristic[name=price].value,+id
```

#### Example E.1: multi-term sort with multiple correlated keys

A single sort string may carry **N correlated terms** plus optional plain terms. Each
term is comma-separated, takes the usual `+` / `-` direction prefix, and is honoured in
declaration order. The executor emits one synthetic key per correlated term
(`_sortKey0`, `_sortKey1`, …) inside `$addFields` and the final `$sort` stage orders by
all of them in the order the user wrote.

Two JSONPath terms — primary descending by `price` value, secondary ascending by
`stock` value:

```http
GET /api/products?sort=-$.characteristic[?(@.name == 'price')].value,+$.characteristic[?(@.name == 'stock')].value
```

The same two-correlated-key pattern in simple-rich:

```http
GET /api/products?sort=-characteristic[name=price].value,+characteristic[name=stock].value
```

Grammars can mix in a single sort — JSONPath + simple-rich + plain tiebreaker, in any
order:

```http
GET /api/products?sort=-$.characteristic[?(@.name == 'price')].value,+characteristic[name=stock].value,+id
```

The `+id` tail keeps the result deterministic when the two correlated keys also tie.
Plain terms in the mix don't force a different execution shape — the presence of any
correlated term is what routes the request through the aggregation pipeline; plain
terms then ride along inside the same `$sort` stage.

#### Example F: nested correlation (two levels deep)

For TMF `ServiceOrder`-shaped resources where the array hop chains through another
array, both grammars allow multi-level chaining.

JSONPath:

```http
GET /api/serviceOrder
  ?sort=$.serviceOrderItem[?(@.id == 'A100')]
         .service.serviceCharacteristic[?(@.name == 'kafkaEventId')]
         .value
```

Simple-rich:

```http
GET /api/serviceOrder
  ?sort=serviceOrderItem[id=A100].service.serviceCharacteristic[name=kafkaEventId].value
```

The translator emits one `$let` per array level, defensively wrapping each `input` in
`$ifNull: [..., []]` so a missing intermediate array resolves cleanly to `null` at the
leaf instead of erroring at runtime.

#### Example F.1: trailing dotted path crossing an object intermediate

When the trailing path (after the last `[...]`) crosses an object intermediate before
reaching a nested array, simple-rich treats the whole trailing portion as a Mongo path
expression rather than a chain of naked hops. This works because Mongo auto-traverses
objects and auto-projects fields across arrays.

```http
GET /api/serviceOrder?sort=serviceOrderItem[A100].service.serviceCharacteristic.value
```

Lowering: one explicit hop on `serviceOrderItem` with predicate `id == 'A100'`
(default-key shorthand), and the trailing `service.serviceCharacteristic.value` becomes
the leaf path. The translator emits:

```js
$let: {
  vars: {
    m0: { $first: { $filter: {
      input: { $ifNull: [ "$serviceOrderItem", [] ] },
      as: "c",
      cond: { $eq: [ "$$c.id", "A100" ] }
    }}}
  },
  in: { $min: "$$m0.service.serviceCharacteristic.value" }
}
```

Mongo evaluates `$$m0.service.serviceCharacteristic.value` by traversing through the
`service` sub-document and projecting `.value` across `serviceCharacteristic`'s
elements. For single-element arrays (the common TMF case where each
`prodSpecCharValueUse` has one `productSpecCharacteristicValue`), the result is
effectively that element's `value`. For multi-element arrays, the executor folds the
auto-projected array with `$min` (ASC) or `$max` (DESC) so each synthetic sort key
stays scalar AND the ordering matches MongoDB's pre-2.1.2 native array-key sort
semantics — write `.min(value)` / `.max(value)` explicitly when you want the aggregator
at the leaf rather than the implicit direction-aware reduction.

> History: 2.1.2 fixed a "parallel arrays" crash by wrapping such leaves in
> `$arrayElemAt: [path, 0]`, which silently switched ordering to "first matching element
> regardless of direction". 2.1.3 restored the direction-aware reduction (`$min` ASC /
> `$max` DESC) so the pre-2.1.2 behaviour is back without re-introducing the crash. See
> CHANGELOG [2.1.3] *"Restored MongoDB's native array-key sort semantics"*.

**JSONPath equivalents.** Plain trailing dotted paths
(`serviceOrderItem[?(@.id=='A100')].service.serviceCharacteristic.value`) work in
JSONPath too — the path traversal is identical. The **simple-rich only** limitation
applied to multiple inner-array hops without predicates; JSONPath requires a `[?(...)]`
predicate at every explicit array hop. For a coercion-only function leaf (e.g.
`.service.serviceCharacteristic.num(value)`) both grammars now produce the same
translation since 2.1.3 — pre-function dotted segments stay inside the FieldRef path
rather than being promoted to naked hops, so object intermediates like `service` are
no longer a footgun.

#### Example G: aggregator functions (simple-rich AND JSONPath)

For arrays where multiple elements match the bracket predicate (or where you don't want
predicate-based selection at all), both grammars expose `min` and `max` aggregator
functions:

```http
GET /api/products?sort=-characteristic[name=score].max(value)
GET /api/products?sort=-$.characteristic[?(@.name == 'score')].max(value)
```

For each document, take the **maximum** `value` across all characteristics whose `name`
is `score`, and order documents by that.

```http
GET /api/products?sort=+characteristic[name=score].min(value)
GET /api/products?sort=+$.characteristic[?(@.name == 'score')].min(value)
```

Same shape, ascending by minimum. JSONPath gained this in 2.1.3.

#### Example H: type coercion (simple-rich AND JSONPath)

When the leaf field is `Object`-typed across documents (a number in some, a string in
others), MongoDB's BSON sort order groups by **type first** then value — strings
cluster after numbers regardless of intuitive ordering. Coerce to a uniform type:

```http
GET /api/products?sort=characteristic[name=value].str(value)
GET /api/products?sort=$.characteristic[?(@.name == 'value')].str(value)
```

All values are coerced to string before sorting. Failures degrade to `null` (sort order
applies to the null per the asc-first / desc-last rule). Available coercions: `str`
(string), `num` (double), `date` (BSON Date — accepts ISO-8601, numeric ms-since-epoch,
ObjectId).

Per TMF-630 §4.7, the coercion wrapper may also enclose the entire sort term. All four
forms below produce the same aggregation pipeline:

```http
# Simple-rich, inner-wrapper form
GET /api/products?sort=characteristic[name=value].num(value)

# Simple-rich, outer-wrapper form (equivalent)
GET /api/products?sort=num(characteristic[name=value].value)

# JSONPath, inner-wrapper form (added in 2.1.3)
GET /api/products?sort=$.characteristic[?(@.name == 'value')].num(value)

# JSONPath, outer-wrapper form (added in 2.1.3)
GET /api/products?sort=num($.characteristic[?(@.name == 'value')].value)
```

Outer wrappers compose recursively, so `num(str(arr[X].leaf))` and
`num(str($.arr[?(@.id=='X')].leaf))` are both accepted.

**Multi-value array semantics** (2.1.3). When the leaf path crosses an inner array
intermediate AFTER the predicate-filtered hop — e.g.
`prodSpecCharValueUse[id=X].productSpecCharacteristicValue.num(value)` where
`productSpecCharacteristicValue` is a list — the coercion runs **per element**, then
the converted values are reduced direction-aware (ASC: `$min`; DESC: `$max`). So
`["105.34", "12.2", "4.31"]` under ASC gives `4.31`, not the lex-min `"105.34"`
converted. This matches the documented "coerce each value … numeric order" semantics.
Single-element inner arrays are unaffected. Before 2.1.3 the coercion ran on the
lex-extreme element, giving subtle wrong-order results for string-stored numerics
(DNext convention).

#### Example I: composing aggregator and coercion

Both orderings parse and run, but produce different results on mixed-type input:

```http
# Recommended: coerce each element first, then aggregate
GET /api/products?sort=characteristic[name=value].max(str(value))

# Possible but rarely intended: BSON-aggregate first, then coerce result
GET /api/products?sort=characteristic[name=value].str(max(value))
```

For mixed-type fields, prefer the inner-coercion form — `max(str(...))`. The outer
form's BSON ordering ranks strings above numbers regardless of intuitive ordering and
is rarely what users mean.

#### Example J: TMF-630 shorthand and `[*]` wildcard for sort

Per TMF-630, `$.` may be omitted from a JSONPath sort term. Canonical JSONPath users
can also include `[*]` as the explicit projection sigil. All four of the following
parse to the same `SortPath` and produce the same Mongo aggregation pipeline:

```http
GET /api/products?sort=$.characteristic[?(@.name == 'price')].value
GET /api/products?sort=characteristic[?(@.name == 'price')].value
GET /api/products?sort=$.characteristic[?(@.name == 'price')][*].value
GET /api/products?sort=characteristic[?(@.name == 'price')][*].value
```

The classifier identifies any sort term containing `[?(...)]` or `[*]` as JSONPath even
when `$.` is absent, and the parser strips `[*]` outside quoted strings before lowering
to the IR. This keeps URLs interoperable with external JSONPath tooling (jsonpath.com,
Jayway evaluation) without the toolkit having to choose between strict-prefix-required
and tolerant.

**Quote styles.** Both `'...'` and `"..."` are accepted as string-literal delimiters in
sort predicates, matching the filter parser and canonical Jayway evaluation. The two
forms produce identical AST:

```http
GET /api/products?sort=$.characteristic[?(@.name == 'price')].value
GET /api/products?sort=$.characteristic[?(@.name == "price")].value
```

Added in 2.1.3 — earlier the sort parser only accepted `'`, making the toolkit
internally inconsistent with the filter parser.

#### Example K: positional-index sort `[N]` (JSONPath only)

TMF-630 Part 6 defines JSONPath index access (`[n]`, 0-based) and allows any JSON Path
expression as a Sort-Field. The JSONPath sort grammar accepts `[N]` as a hop, selecting
the **literal Nth element** — never the min/max direction fold used for `[*]` / plain
array paths:

```http
# Sort by the first element's value
GET /api/products?sort=$.characteristic[0].value

# Predicate narrows the outer element, [1] picks the literal second inner element
GET /api/orders?sort=$.serviceOrderItem[?(@.id == 'X')].service.serviceCharacteristic[1].value

# Composes with coercion — converts the picked element only
GET /api/products?sort=$.characteristic[0].num(value)
```

An out-of-bounds index yields a `null` sort key, which lands last regardless of
direction per the standard missing-key contract.

**Positional requires the JSONPath form** (`$.arr[0].value`). The bare simple-rich
spelling `arr[0].value` keeps its default-key meaning (`arr[id=0].value`) for backward
compatibility with numeric-id consumers.

#### Capability cheat-sheet — what each grammar accepts

| Construct                                                                                | Plain              | Simple-rich                                                                              | JSONPath                                                             |
| ---------------------------------------------------------------------------------------- | ------------------ | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| Equality match                                                                           | (n/a — single field) | yes                                                                                    | yes                                                                  |
| Comparison operators (`>`, `<`, `>=`, `<=`, `!=`)                                        | n/a                | drop to JSONPath                                                                         | yes                                                                  |
| Logical `&&` / `\|\|` in predicate                                                       | n/a                | drop to JSONPath                                                                         | yes                                                                  |
| Multi-level chained correlation                                                          | n/a                | yes                                                                                      | yes                                                                  |
| Predicate on a sub-array of the matched element                                          | n/a                | drop to JSONPath                                                                         | yes                                                                  |
| Aggregator functions (`min`, `max`)                                                      | n/a                | yes                                                                                      | yes (added in 2.1.3 — leaf-call and outer-wrap forms)                |
| Coercion wrappers (`str`, `num`, `date`)                                                 | n/a                | yes                                                                                      | yes (added in 2.1.3 — leaf-call and outer-wrap forms)                |
| Default-key bracket shorthand `arr[X]`                                                   | n/a                | yes                                                                                      | n/a                                                                  |
| Trailing dotted path crossing object/array intermediates (e.g. `arr[X].deep.path.value`) | n/a                | yes — Mongo path auto-traversal (min/max element of multi-element arrays per `$sort` direction) | rejected (400 — JSONPath requires a predicate at every array hop)  |
| JSONPath wildcard `[*]` (transparent projection)                                         | n/a                | n/a                                                                                      | accepted — stripped at parse time                                    |
| Positional index `[N]` (literal Nth element, 0-based)                                    | n/a                | n/a — `arr[0]` means `arr[id=0]` (default-key shorthand)                                 | yes (added in 2.1.4 — `$arrayElemAt`, out-of-bounds → nulls-last)    |
| Recursive descent (`..`), array slices (`[0:5]`), JSONPath functions (`length()`)        | rejected           | n/a                                                                                      | rejected (HTTP 400)                                                  |
| Trailing predicate without leaf field                                                    | n/a                | n/a                                                                                      | rejected (HTTP 400)                                                  |
| Mixed with plain terms in one comma-separated sort                                       | yes                | yes                                                                                      | yes                                                                  |

#### Runtime requirements

- **MongoDB 4.0 or newer.** The aggregation path uses `$convert ... onError`,
  introduced in 4.0. The cheap `find()` path (plain sorts only) has no version
  requirement beyond what the base toolkit already supports.
- The new module's auto-configuration registers `Tmf630MongoCorrelatedSortExecutor`
  only when a `MongoTemplate` bean is on the classpath; without one, the bean is not
  created and no behaviour changes for non-Mongo services that happen to pull the
  dependency transitively.

## Reference

### Module layout

| Module                                             | Description                                                                          |
| -------------------------------------------------- | ------------------------------------------------------------------------------------ |
| `tmf630-toolkit-paging-sorting-core`               | Paging, sorting, field selection (no Boot dependency)                                |
| `tmf630-toolkit-paging-sorting-autoconfigure`      | Spring Boot auto-configuration for paging/sorting                                    |
| `tmf630-toolkit-attribute-filtering-core`          | Attribute filtering to QueryDSL `Predicate` (no Boot dependency)                     |
| `tmf630-toolkit-attribute-filtering-autoconfigure` | Spring Boot auto-configuration for filtering                                         |
| `tmf630-toolkit-mongo-aggregation`                 | **Optional.** Correlated-sort `Aggregation` executor for MongoDB-backed services     |
| `tmf630-toolkit-all`                               | Convenience artifact depending on both filtering autoconfigure modules               |

The four core+autoconfigure modules are intentionally **DB-agnostic in production
scope** — they declare only `querydsl-core`, `spring-web`, `spring-data-commons`, and
`json-path`. JPA-only and other non-Mongo consumers pay no Mongo dependency cost.

`tmf630-toolkit-mongo-aggregation` is an opt-in module that ships `spring-data-mongodb`
and `querydsl-mongodb` as production dependencies. Add it only when your service uses
MongoDB and wants the correlated-sort features (JSONPath / simple-rich grammar with
`$let` / `$filter` / `$first` aggregation pipelines).

### Configuration prefixes

| Prefix                               | Purpose                                                    |
| ------------------------------------ | ---------------------------------------------------------- |
| `opentmf.tmf630.paging`              | Paging/sorting behaviour                                   |
| `opentmf.tmf630.attribute-filtering` | Query filter parsing and rules                             |
| `opentmf.tmf630.field-selection`     | `@Tmf630Response` field selection behaviour                |
| `opentmf.tmf630.mongo-aggregation`   | Correlated-sort behaviour (mongo-aggregation module only)  |

#### Common paging properties

| Property                                                    | Default | Purpose                                                                 |
| ----------------------------------------------------------- | ------- | ----------------------------------------------------------------------- |
| `opentmf.tmf630.paging.enabled`                             | `true`  | Master switch                                                           |
| `opentmf.tmf630.paging.default-limit`                       | `50`    | Applied when the request omits `limit`                                  |
| `opentmf.tmf630.paging.max-limit`                           | `500`   | Upper bound on `limit`; requests above are clipped                      |
| `opentmf.tmf630.paging.strict-mode`                         | `true`  | If `true`, out-of-range offsets 416; if `false`, they return an empty page |
| `opentmf.tmf630.paging.allow-nested-sort-properties`        | `false` | Whether `sort=parent.child` is allowed                                  |
| `opentmf.tmf630.paging.sort-allowlist`                      | empty   | List of allowed sort field names; empty means unrestricted              |
| `opentmf.tmf630.paging.nulls-last`                          | `false` | If `true`, every plain sort order is decorated with `Sort.Order.nullsLast()` — null-valued rows sort last regardless of ASC/DESC direction. Cross-backend parity with Mongo's 2.1.1 nulls-last handling. Hibernate emits explicit `NULLS LAST` SQL only when it differs from the dialect default (elided on Postgres ASC, emitted on Postgres DESC); non-native dialects fall back to a synthetic `CASE WHEN` sort key (index-scan cost implications on large tables) |

#### Common filtering properties

| Property                                                                     | Default        | Purpose                                                                 |
| ---------------------------------------------------------------------------- | -------------- | ----------------------------------------------------------------------- |
| `opentmf.tmf630.attribute-filtering.enabled`                                 | `true`         | Master switch                                                           |
| `opentmf.tmf630.attribute-filtering.implicit-eq-enabled`                     | `true`         | Whether `?name=Alice` is treated as `?name.eq=Alice`                    |
| `opentmf.tmf630.attribute-filtering.implicit-eq-csv-or`                      | `true`         | Whether `?name=a,b` splits on commas                                    |
| `opentmf.tmf630.attribute-filtering.implicit-eq-semicolon-or`                | `true`         | Whether `?name=a;b` splits on semicolons                                |
| `opentmf.tmf630.attribute-filtering.combine-repeated-values`                 | `OR`           | `OR` or `AND` — how to combine repeated attribute keys                  |
| `opentmf.tmf630.attribute-filtering.allow-nested-paths-jpa`                  | `false`        | Whether `?address.city=X` is allowed on JPA-backed entities             |
| `opentmf.tmf630.attribute-filtering.allow-nested-paths-docdb`                | `true`         | Whether `?address.city=X` is allowed on Mongo-backed entities           |
| `opentmf.tmf630.attribute-filtering.regex.enabled`                           | `false`        | Whether `.regex` / `.regexi` operators are accepted                     |
| `opentmf.tmf630.attribute-filtering.regex.max-length`                        | `256`          | Max regex pattern length                                                |
| `opentmf.tmf630.attribute-filtering.limits.max-clauses`                      | `50`           | Max total attribute clauses in a single request                         |
| `opentmf.tmf630.attribute-filtering.limits.max-values-per-key`               | `20`           | Max values for a single attribute key                                   |
| `opentmf.tmf630.attribute-filtering.allowlist.mode`                          | `ALLOW_ALL`    | `ALLOW_ALL` or `DENY_ALL`                                               |
| `opentmf.tmf630.attribute-filtering.allowlist.entities.<EntityName>`         | —              | List of allowed fields per entity; used when mode is `DENY_ALL`         |
| `opentmf.tmf630.attribute-filtering.on-unknown-field`                        | `REJECT`       | `REJECT` (400) or `IGNORE` for unknown attribute keys                   |
| `opentmf.tmf630.attribute-filtering.on-unknown-operator`                     | `REJECT`       | `REJECT` (400) or `IGNORE` for unknown operator suffixes                |
| `opentmf.tmf630.attribute-filtering.on-unknown-json-path-field`              | `IGNORE`       | `REJECT` or `IGNORE` — Part 6 says unmatched JSONPath yields empty result |
| `opentmf.tmf630.attribute-filtering.json-path-filter.enabled`                | `true`         | Whether `?filter=$[?(...)]` is accepted                                 |
| `opentmf.tmf630.attribute-filtering.json-path-filter.max-length`             | `2048`         | Max `filter=` expression length                                         |
| `opentmf.tmf630.attribute-filtering.isnull-semantics`                        | `MISSING_ONLY` | `MISSING_ONLY` or `NULLISH` — see [null-check widening](#null-check-widening-missing_only-vs-nullish) |

#### Field selection properties

| Property                                                    | Default | Purpose                                                                 |
| ----------------------------------------------------------- | ------- | ----------------------------------------------------------------------- |
| `opentmf.tmf630.field-selection.enabled`                    | `true`  | Enables the `@Tmf630Response` auto-advice                               |
| `opentmf.tmf630.field-selection.default-depth`              | `1`     | How deep nested objects are auto-expanded when selected by name         |

#### Mongo aggregation properties (`tmf630-toolkit-mongo-aggregation` only)

| Property                                                            | Default | Purpose                                                                   |
| ------------------------------------------------------------------- | ------- | ------------------------------------------------------------------------- |
| `opentmf.tmf630.mongo-aggregation.simple-rich.default-key`          | `id`    | The field name used for the bare-value bracket form `arr[X]` in simple-rich sort terms. `arr[X]` is shorthand for `arr[<defaultKey>=X]`. Projects whose convention uses `name`, `code`, etc. flip this once globally without code changes. |

### Configuration scenarios

**Scenario 1: default behaviour (no custom config)** — safe defaults out of the box:

- Paging/sorting enabled, `default-limit=50`, `max-limit=500`, strict range checks,
  unrestricted sort fields.
- Attribute filtering enabled with implicit `eq` support (`name=alice`).
- Repeated values combined with `OR`.
- Nested paths disabled for JPA, enabled for document backends.
- Allowlist mode `ALLOW_ALL`.
- Unknown fields/operators return `400 Bad Request`.
- JSONPath `filter=` enabled with 2048-character max length.

**Scenario 2: minimal override — change only a few items:**

```yaml
opentmf:
  tmf630:
    paging:
      max-limit: 200
    attribute-filtering:
      allow-nested-paths-docdb: true
      on-unknown-operator: IGNORE
```

**Scenario 3: full non-default example:**

```yaml
opentmf:
  tmf630:
    paging:
      enabled: true
      default-limit: 25
      max-limit: 100
      strict-mode: false
      allow-nested-sort-properties: true
      sort-allowlist:
        - id
        - name
        - birthdate
    attribute-filtering:
      enabled: true
      implicit-eq-enabled: false
      combine-repeated-values: AND
      allow-nested-paths-jpa: false
      allow-nested-paths-docdb: true
      regex:
        enabled: true
        max-length: 128
      limits:
        max-clauses: 30
        max-values-per-key: 10
      allowlist:
        mode: DENY_ALL
        entities:
          Person:
            - id
            - name
            - surname
            - birthdate
            - sex
      on-unknown-field: IGNORE
      on-unknown-operator: IGNORE
      on-unknown-json-path-field: IGNORE
      json-path-filter:
        enabled: true
        max-length: 1024
```

How this behaves:

- Query parsing is strict about explicit operators (`implicit-eq-enabled=false`), so
  clients should send `field.eq=value`.
- Repeated values are treated as `AND` constraints, making filtering narrower.
- Nested-path behaviour is backend-aware (`false` for JPA, `true` for document
  entities).
- Regex operators are enabled with tighter safety limits.
- Filtering and sorting are constrained to explicit allowlists to protect exposed query
  surface.
- Unknown fields/operators are ignored to avoid hard failures when older/newer clients
  send extra parameters.
- JSONPath filtering remains available but with a tighter max expression length.

**Note:** merge mode between attribute filters and `filter=` is controlled per request
via `filter.combineWithAttributes=AND|OR` (query parameter), not via YAML.

### Backend-specific nested-path guidance (JPA vs Mongo)

Recommended operational policy:

- For JPA-backed services, keep `allow-nested-paths-jpa` disabled.
- For Mongo/document-backed services, enable `allow-nested-paths-docdb`.

Minimal examples:

```yaml
# JPA-oriented service
opentmf:
  tmf630:
    attribute-filtering:
      allow-nested-paths-jpa: false
```

```yaml
# Mongo-oriented service
opentmf:
  tmf630:
    attribute-filtering:
      allow-nested-paths-docdb: true
```

Nested-path policy is backend-aware through separate properties
(`allow-nested-paths-jpa` and `allow-nested-paths-docdb`). In mixed JPA+Mongo
applications, use explicit allowlists for tighter control.

### `filter=` JSONPath syntax (Jayway 3.x)

[Jayway JsonPath 3.x](https://github.com/json-path/JsonPath) is used **solely** as a
syntax validator: the library calls `JsonPath.compile()` to verify that the incoming
`filter=` expression is syntactically valid JSONPath. Jayway is **never** used to
evaluate the expression against a result array at runtime. Once the expression passes
Jayway's syntax check, the library's own parser takes over, translates the expression
into a QueryDSL `Predicate` (or a MongoDB `$elemMatch` for array-correlation patterns),
and the database executes the query natively. Only the **restricted subset** listed
below is accepted by the library's parser — anything not listed here returns
`400 Bad Request`.

All examples below assume a JSON array of objects such as:

```json
[
  {
    "name": "Fiber 100Mbps",
    "status": "active",
    "price": 49,
    "category": "broadband",
    "externalReference": [
      {"name": "MARKET_ACCOUNT_ID", "id": "OPCO-ID-012"},
      {"name": "ORDER_REFERENCE",   "id": "OPCO-ORDER-012"}
    ]
  }
]
```

#### Simple field equality

```
$[?(@.status == 'active')]
$[?(@.status != 'active')]
$[?(@.status == "active")]
```

String literals accept both `'...'` and `"..."`.

#### Comparison operators

```
$[?(@.price > 10)]
$[?(@.price >= 10)]
$[?(@.price < 100)]
$[?(@.price <= 100)]
```

#### Unary negation `!@.field`

```
$[?(!@.optionalField)]
```

Matches rows where the named field is missing or `null`. Translates to `IS_NULL`.
Negation of array-match subforms — e.g. `!@.externalReference[?(...)]` — is not
supported.

#### Logical AND / OR

```
$[?(@.status == 'active' && @.name == 'Fiber 100Mbps')]
$[?(@.status == 'active' || @.status == 'suspended')]
$[?((@.status == 'active' || @.status == 'suspended') && @.category == 'broadband')]
```

Parentheses control grouping. Arbitrary nesting depth is supported.

#### Nested field access

```
$[?(@.externalReference.name == 'ORDER_REFERENCE')]
```

Subject to allowlist and nested-path configuration
(`allow-nested-paths-jpa`, `allow-nested-paths-docdb`).

#### Sub-array correlation shorthand (`<arrayPath>[?(...)]`)

Compact form for *"filter docs where some element of `<arrayPath>` satisfies the
predicate."* All three forms below produce the same QueryDSL predicate and (on Mongo)
the same `$elemMatch` query:

```http
# Canonical wrapper form
GET /api/products?filter=$[?(@.prodSpecCharValueUse[?(@.id == 'RC_OFFER_TYPE')])]

# Sub-array shorthand — `$.` prefix optional per TMF-630
GET /api/products?filter=$.prodSpecCharValueUse[?(@.id == 'RC_OFFER_TYPE')]
GET /api/products?filter=prodSpecCharValueUse[?(@.id == 'RC_OFFER_TYPE')]
```

The shorthand is parsed by `JsonPathFilterPredicateBuilder.trySubArrayShorthand` and
rewritten to the canonical correlated form internally. The wrapper form is wordier but
unambiguous when a single filter expression combines multiple sub-array conditions
under `&&` / `||`.

**Trailing projection suffix is tolerated and discarded (2.1.3).** DPC-style consumers
build `?filter=` URLs by reusing their `?sort=` templates, which leaves a trailing
projection like `.productSpecCharacteristicValue[*].value` after the filter's `[?(...)]`.
The projection has no effect on the matched row set — that's fully determined by the
predicate — so the parser strips it:

```http
# All five forms produce the same filter (`prodSpecCharValueUse[?(@.id == 'X')]`)
GET /api/products?filter=$.prodSpecCharValueUse[?(@.id == 'RC_OFFER_TYPE')]
GET /api/products?filter=$.prodSpecCharValueUse[?(@.id == 'RC_OFFER_TYPE')].value
GET /api/products?filter=$.prodSpecCharValueUse[?(@.id == 'RC_OFFER_TYPE')].productSpecCharacteristicValue.value
GET /api/products?filter=$.prodSpecCharValueUse[?(@.id == 'RC_OFFER_TYPE')].productSpecCharacteristicValue[*].value
GET /api/products?filter=$.prodSpecCharValueUse[?(@.id == "RC_OFFER_TYPE")].productSpecCharacteristicValue[*].value
```

A suffix that contains another `[?(...)]` predicate, a positional index access
(`[0]`), or a slice (`[0:5]`) is **rejected** with the standard "must be a filter
expression" 400.

#### What this library does NOT support (400)

- `IN` / `NIN` (use attribute-level `.in` / `.nin` operators instead)
- `SIZE`, `EMPTY`, `CONTAINS` (Jayway-specific — use `length()==N` for empty checks,
  array-match for containment)
- Bare exists check (`$[?(@.field)]`)
- Recursive descent operator (`..`)
- Functions other than `length()` (e.g. `count()`, `min()`, `max()`)
- `length()` outside the `== N` on collection-field scope
- Script expressions

### Mongo `filter=` support scope

Supported subset (Mongo/document backends):

- wrapper form: `$[?(...)]` (canonical), `[?(...)]` (bare wrapper, TMF-630 shorthand),
  or `<arrayPath>[?(...)]` / `$.<arrayPath>[?(...)]` (sub-array correlation shorthand,
  rewritten to the canonical correlated form)
- logical operators: `&&`, `||`
- grouping with parentheses
- comparisons: `==`, `!=`, `>`, `>=`, `<`, `<=`
- regex match: `=~` with a `/pattern/` or `/pattern/i` literal; requires
  `regex.enabled=true` and honours `regex.max-length`, exactly like the attribute-side
  `.regex` / `.regexi` operators. Only the `i` flag is supported — other flags are
  rejected.
- literals: string (single- or double-quoted), number, boolean, `null`
- field paths: `@.field`, `@.nested.field`
- array correlation: `@.arrayField[?(...)]` with strict same-element semantics via
  `$elemMatch`
- positional index `[N]` in field paths — dotted numeric hop resolved natively by Mongo
  (2.1.5)
- `length() == N` on collection fields via the standard `$size` fast path (2.1.5)
- JSONPath wildcard `[*]` is accepted as a transparent projection sigil (stripped at
  parse time)
- trailing projection suffix on the sub-array shorthand is tolerated and discarded (see
  above)
- merge with attribute filtering in the same request — default `AND`, per-request
  override via `filter.combineWithAttributes=OR`

Not supported yet:

- full JSONPath language / functions (only restricted subset above)
- nested array filters inside array filters in a single expression
- broader wildcard / function / script-style JSONPath constructs

The supported subset is intended for production use; behaviour is intentionally
constrained for predictable parsing and backend translation.

### JPA `filter=` support scope

Key capability: JPA clients can express complex grouped where-conditions using
`filter=` with parentheses, `&&`, and `||` for non-array-correlation scenarios.

Supported subset (JPA backends):

- wrapper form: `$[?(...)]` (canonical), `[?(...)]` (bare wrapper), or
  `<arrayPath>[?(...)]` / `$.<arrayPath>[?(...)]` (sub-array correlation shorthand)
- logical operators: `&&`, `||`
- grouping with parentheses
- comparisons: `==`, `!=`, `>`, `>=`, `<`, `<=`
- literals: string, number, boolean, `null`
- field paths: `@.field`, `@.nested.field` (subject to allowlist and nested-path
  configuration)
- `length() == N` on collection fields via standard JPQL `SIZE(coll)` (2.1.5)
- merge with attribute filtering — default `AND`, override via
  `filter.combineWithAttributes=OR`

Not supported on JPA:

- array-correlation patterns such as `@.arrayField[?(...)]`
- positional index `[N]` in field paths (Mongo-only per TMF-630 Part 6 dotted-numeric
  semantics; JPQL cannot express element-N indexing on a plain collection)

These are rejected with `400 Bad Request`.

### When `filter=` returns 400

The library throws a filtering exception (mapped to HTTP `400`) for unsupported or
invalid `filter=` usage, including:

- expression is not a filter wrapper (must be `$[?(...)]`)
- expression has invalid JSONPath syntax
- unsupported operators / tokens are used
- unsupported literal forms are used
- `null` is used with unsupported operators (e.g. `> null`)
- more than one `filter` parameter is sent
- `filter.combineWithAttributes` has invalid value (must be `AND` or `OR`) or appears
  multiple times
- unknown / disallowed field paths when `on-unknown-json-path-field=REJECT`
- nested path usage when nested paths are disabled
- array-correlation usage on JPA backends
- positional index `[N]` in filter paths on JPA backends
- `length()` used with any comparator other than `==`, or on a non-collection leaf
- `filter=` exceeds configured max length
- `filter=` expression nesting exceeds 32 levels (hardening cap against paren-bomb stack overflow — bounds both within-Parser recursion and across-Parser nested array-match)
- JSONPath filter feature is disabled by configuration

All these return the TMF `ErrorMessage` body — see
[section 9](#9-error-response-shapes).

### When sort / paging / fields parameters return 400

Since 2.1.5, sort/paging errors also return the TMF `ErrorMessage` body (via
`Tmf630PagingExceptionHandler`). The following inputs produce a `400`:

- **`sort=`**
  - `sort=<field>` where the field is not in `sort-allowlist` (when allowlist is
    non-empty) → *"Sort property is not allowed: `<field>`"*
  - `sort=parent.child` when `allow-nested-sort-properties=false` → *"Nested sort
    properties are not allowed: `parent.child`"*
- **`offset=`**
  - Negative value → *"offset must be >= 0"*
  - Non-numeric value → *"offset must be numeric"*
- **`limit=`**
  - Zero or negative value → *"limit must be > 0"*
  - Non-numeric value → *"limit must be numeric"*

**`fields=`** errors — unknown field names in the request are silently skipped (per
TMF-630 Part 1 §4.3 convention). Reflection catastrophes inside `FieldSelectionUtil`
surface as `500` with the TMF error body via `Tmf630FieldSelectionExceptionHandler` —
see [Error response shapes](#9-error-response-shapes) for the split.

### Spring Data Mongo entity-mapping gotchas

If you hand-write or generate entity classes for a Mongo collection (typical for a
read-only "proxy" service that filters/sorts data written by a different application),
two Spring Data idiosyncrasies bite specifically when you start using nested-array
predicates in correlated sort or filter.

#### Gotcha 1: name-based id-property auto-promotion on nested classes

Spring Data Mongo's id-property detection runs on **every persistent entity, root or
nested**:

1. If a property is annotated `@Id` → it's the id, mapped to `_id`.
2. Otherwise, if a property is literally named `id` → **promote it to id**, also mapped
   to `_id`.

Rule 2 historically caused JSONPath / simple-rich predicates referencing `@.id == 'X'` /
`[id=X]` to silently fail to match nested objects, since the BSON field had been
renamed to `_id`. **As of 2.0.1, this is handled transparently by the toolkit** —
`MongoFieldResolver` consults your `MongoMappingContext` at translation time and
rewrites the user-facing path to whatever BSON name your entity declares. You can write
predicates against `id` regardless of whether the nested field has `@Field("id")` or
relies on the default auto-promotion.

The mitigations below remain useful as **awareness items** — they explain *why* the
resolver does what it does — but for typical Spring Data Mongo entities, no
consumer-side configuration is required.

##### Diagnose first — what does your writer actually store?

Before picking a mitigation, **inspect a real document.** The fix depends on which
stack wrote the data:

```javascript
// In mongo shell, against the actual collection:
db.productOffering.findOne({}, { _id: 0 })
```

Look at one nested object. Is the inner key called `id` or `_id`?

| Writer stack | Nested field stored as | Reader's job |
| --- | --- | --- |
| Spring Data Mongo (Java/Kotlin) | `_id` | Either alias `_id` ↔ `id` on read, or write JSONPath/simple-rich predicates against `_id` directly |
| Anything else (Node, Python, Go, raw shell, vendor binary) | `id` | Tell Spring Data Mongo NOT to auto-remap, so `@.id` stays `id` end to end |

You will not pick the right mitigation by guessing. Run the diagnostic.

##### Mitigation A: surgical `@Field("id")` (writer doesn't remap)

When the BSON keeps nested `id` as `id`, annotate every nested class's `id` field on
the reader so Spring Data does not auto-promote it to `_id`:

```java
public class ProductSpecificationRef {
  @Field("id") String id;     // reference, not an entity identity
  String version;
  String href;
  String name;
}
```

Pros: locality — the exception is visible at the field. No global behaviour change.
Cons: easy to forget when adding a new entity class. Audit on every new model.

##### Mitigation B: global "only `@Id` counts" override (writer doesn't remap)

If you'd rather rule out name-based auto-promotion entirely, replace the default
`MongoMappingContext` with one that only honours `@Id`:

```java
@Configuration
class MongoMappingConfig {

  @Bean
  MongoMappingContext mongoMappingContext() {
    return new MongoMappingContext() {
      @Override
      protected <T> BasicMongoPersistentEntity<T> createPersistentEntity(
          TypeInformation<T> typeInformation) {
        return new BasicMongoPersistentEntity<T>(typeInformation) {
          @Override
          protected MongoPersistentProperty
              returnPropertyIfBetterIdPropertyCandidateOrNull(
                  MongoPersistentProperty property) {
            return property.isAnnotationPresent(Id.class) ? property : null;
          }
        };
      }
    };
  }
}
```

Pros: one bean, fixes everything. New entities need nothing.
Cons: now you must be **explicit about the top-level `_id`** — every root entity's
identity property has to be `@Id`-annotated (or `@Field("_id")`). For composite-key
resources like TMF620 ProductOffering (where `id + version` is the composite identity),
this is actually a feature: a bare `id` field stays as `id` and the composite is
whatever you choose to make `@Id`-annotated.

##### Mitigation C: alias `_id` → `id` on read (writer does remap)

When the writer is also Spring Data Mongo and the BSON has `_id` on nested objects,
neither `@Field("id")` nor a mapping-context override will help — the field truly isn't
`id` in storage. The cleanest fix is a custom read-side converter that aliases nested
`_id` back to `id` for non-root entities. This keeps every JSONPath / simple-rich
predicate referencing `@.id` portable across writer stacks. Sketch:

```java
@ReadingConverter
class NestedIdAliasingConverter
    implements GenericConverter {

  @Override
  public Set<ConvertiblePair> getConvertibleTypes() {
    return Set.of(new ConvertiblePair(Document.class, Document.class));
  }

  @Override
  public Object convert(@Nullable Object source, TypeDescriptor srcType, TypeDescriptor tgtType) {
    Document doc = (Document) source;
    if (doc != null && doc.containsKey("_id") && !doc.containsKey("id")) {
      doc.put("id", doc.remove("_id"));
    }
    return doc;
  }
}
```

Wire it into `MongoCustomConversions` and the `MappingMongoConverter`. The exact
registration depends on your read-path needs (you may want to scope this to nested-only,
leaving root `_id` intact). Treat the snippet as a starting point and verify with an
integration test against real data.

##### Mitigation D: bake `@Field("id")` into your code generator

If you generate models with `openapi-generator-maven-plugin`, the post-generation step
should add `@Field("id")` to every property named `id` automatically. A small Mustache
template override in `src/main/resources/openapi/templates/pojo.mustache` is enough —
flag the generator with a `vendorExtensions.x-is-id-field` rule and emit the annotation
conditionally. One-time template work, every nested id annotated forever.

#### Gotcha 2: the `_class` discriminator field

Spring Data writes the FQN of the Java class to `_class` by default. For a read-only
proxy that reads documents written by a different service, you can decouple from the
writer's package layout by overriding the `MappingMongoConverter`'s type mapper:

```java
@Bean
MappingMongoConverter mappingMongoConverter(
    MongoDatabaseFactory factory,
    MongoMappingContext ctx,
    MongoCustomConversions conv) {
  MappingMongoConverter c =
      new MappingMongoConverter(new DefaultDbRefResolver(factory), ctx);
  c.setCustomConversions(conv);
  c.setTypeMapper(new DefaultMongoTypeMapper(null));   // null typeKey → ignore _class
  return c;
}
```

This sidesteps polymorphic-type FQN matching entirely; Spring Data falls back to the
call-site class for every read. Safe for read-only proxies that don't have polymorphic
field types keyed on `_class`.

### TMF-630 compliance summary

The toolkit's stated scope is **the TMF-630 query contract**: attribute filtering,
JSONPath `filter=` / `fields=`, sorting, offset/limit paging with 200/206/416
semantics, and the shared error/exception mapping around those. Everything else in
TMF-630 (CRUD/PATCH, task resources, monitor pattern, notifications, event management,
JSON-Patch, JSON-LD hypermedia, JSON schemas) is intentionally left to the host
application.

The full section-by-section audit is in
[`docs/TMF630_COMPLIANCE_AUDIT.md`](./docs/TMF630_COMPLIANCE_AUDIT.md). Summary:

| Spec area                                              | Status                                                                                                                                                        |
| ------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Part 1 §3.3 status codes emitted by toolkit             | ✅ `200`, `206`, `400`, `416` (host app owns the rest)                                                                                                        |
| Part 1 §3.4 error body shape (`code`, `reason`, `message`, optional fields) | ✅ Uniform across filter / sort / paging / range handlers (2.1.5)                                                                                              |
| Part 1 §4.3 `fields=` partial representation           | ✅ Including `fields=none`, always-include `id`/`href`                                                                                                          |
| Part 1 §4.4 attribute filtering (`.gt=`, `.eq=`, etc.) | ✅ 26 operators, both suffix and URL-encoded literal forms                                                                                                    |
| Part 1 §4.5 pagination (`offset`, `limit`, `X-Total-Count`, `206`) | ✅ Including `Link` header for navigation (2.1.5) and `416` for out-of-range offsets                                                                          |
| Part 1 §4.7 sorting                                    | ✅ `+`/`-` direction, comma-separated multi-field, dotted nested paths                                                                                        |
| Part 6 JSONPath `filter=`                              | ✅ Restricted subset — see [Mongo](#mongo-filter-support-scope) / [JPA](#jpa-filter-support-scope) support scope. Includes `length()==N` and `[N]` (2.1.5). |
| Part 6 JSONPath `sort=`                                | ✅ End-to-end, both plain-find and correlated-sort executor paths                                                                                             |
| Part 1 §5 modify / §6 create / §7 delete / §8 task / §9 monitor / §10 notifications / §11 versioning / §12 event | 🚫 Out of toolkit scope — application-level                                                                                                                  |
| Part 2 polymorphism / extension / depth-expand / EntityRefOrValue | 🚫 Modelling patterns (out of scope) — `depth`/`expand` names are reserved so they aren't misread as attribute filters                                       |
| Part 3 hypermedia / JSON-LD                            | 🚫 Optional per spec; not implemented                                                                                                                        |
| Part 4 Export/Import Task / Entity Versioning / RBAC   | 🚫 Application-level                                                                                                                                          |
| Part 5 JSON Patch Query                                | 🚫 Toolkit doesn't do PATCH                                                                                                                                   |
| Part 6 `fields=` with JSONPath expression              | ❌ Known gap — `fields=` accepts only dotted names + `fields=none`. On the 2.2.x roadmap                                                                       |
| Part 7 JSON Schemas                                    | 🚫 Modelling patterns                                                                                                                                         |

## POC databases used

The current proof-of-concept and integration coverage has been verified with these
databases:

- PostgreSQL `18.1-alpine` (via Testcontainers `jdbc:tc:postgresql:18.1-alpine:///db`)
  for JPA/SQL predicate and SQL reflection tests
- H2 (in-memory) for lightweight JPA-based integration scenarios
- MongoDB `8.0.5` (via Testcontainers `mongo:8.0.5`) for document-oriented filtering,
  including JSONPath array-correlation behaviour
- MariaDB `11.4.4` (via Testcontainers profile `it-mariadb`) for SQL/JPA compatibility
  validation
- Microsoft SQL Server `2022-CU14-ubuntu-22.04` (via Testcontainers profile `it-mssql`)
  for SQL/JPA compatibility validation
- Oracle XE `21-slim-faststart` (via Testcontainers profile `it-oracle`) for dedicated
  SQL/JPA validation
- IBM DB2 `11.5.0.0a` (via Testcontainers profile `it-db2`) for dedicated SQL/JPA
  validation

Notes:

- JSONPath array-correlation in `filter=` is supported for Mongo/document backends.
- The same array-correlation pattern is intentionally rejected for JPA backends (HTTP
  `400`).
- MariaDB coverage is used as a practical compatibility indicator for the MySQL family
  due to shared lineage and behaviour.
- Microsoft SQL Server coverage is used as a practical compatibility indicator for
  Sybase-family behaviour due to shared historical lineage.

## Build

```bash
mvn clean install
```

`install` (rather than `verify`) is recommended because the toolkit is a multi-module
project and the modules depend on each other transitively in the local Maven
repository. `install` writes the built `-SNAPSHOT` artifacts to `~/.m2/repository`,
making them resolvable from downstream projects on the same machine. `verify` runs all
tests and integration tests but stops before installation, so a downstream project
building against the current `-SNAPSHOT` would not find the artifacts.

### Local Sonar analysis (opt-in)

An opt-in `sonar` Maven profile runs the SonarScanner against a locally-running
SonarQube and blocks the build on Quality Gate failure. Authentication is not baked
into the pom — pass a token via env var or CLI:

```bash
# Against local SonarQube (http://localhost:9000) — the profile default:
SONAR_TOKEN=<your-local-token> mvn -Psonar clean verify

# Against a non-default host:
mvn -Psonar -Dsonar.host.url=https://sonar.example -Dsonar.token=<token> clean verify
```

The scanner binds to the `verify` phase in every module; the plugin's own
reactor-detection defers the actual scan to fire exactly once at the end of the
build (watch for `Delaying SonarQube Scanner to the end of multi-module project`
in the log — that's the deferral confirmation). `sonar.qualitygate.wait=true`
blocks the build until Sonar returns the Quality Gate verdict; a red gate fails
the build. Without `-Psonar`, no Sonar plugin is activated and the build behaves
exactly as before.

## License

Apache License 2.0.
