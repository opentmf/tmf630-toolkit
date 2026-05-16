# tmf630-toolkit

> **This is a server-side library.** It runs inside your Spring Boot backend, translates
> incoming HTTP query parameters into database predicates, and provides helper annotations
> and utilities to serve TMF-630 compliant responses (paging headers, range status codes,
> field selection). It is not a client SDK and has no relationship to any client-side
> filtering or JavaScript runtime.

`tmf630-toolkit` is the adaptation of TMF-630 REST API Design Guidelines for Spring Web MVC.

When a Spring Boot based microservice references this library, it automatically gains TMF630-style paging/sorting and advanced filtering capabilities through configuration, without writing custom query parsing logic. Using the library from other Spring-based projects is also possible but requires explicit bean wiring.

Clients can request "only records 51-100", "sort by surname descending", or "find people born after 1990 whose surname starts with D" using standard query parameters.
In addition to the query parameters, clients can also use a JsonPath in the optional `filter=` parameter to express richer grouped conditions (parentheses, AND/OR, comparison operators) in a single query expression. When the backend is a document database (like MongoDB), this filter further allows array correlation.

Release notes and version history are available in [`CHANGELOG.md`](./CHANGELOG.md).

## Table of contents

- [What you get](#what-you-get)
- [Module layout](#module-layout)
- [Dependency management](#dependency-management)
- [Prerequisites for attribute filtering](#prerequisites-for-attribute-filtering)
- [Configuration prefixes](#configuration-prefixes)
- [Developer handbook](#developer-handbook)
  - [1) Example domain and sample data](#1-example-domain-and-sample-data)
  - [2) Controller usage (Spring Boot)](#2-controller-usage-spring-boot)
  - [3) Combining predicates with path variables](#3-combining-predicates-with-path-variables)
  - [4) Paging and sorting examples](#4-paging-and-sorting-examples)
  - [5) Full QueryDSL operator reference](#5-full-querydsl-operator-reference)
  - [6) Date and datetime field formats](#6-date-and-datetime-field-formats)
  - [7) Enum field resolution](#7-enum-field-resolution)
  - [8) Combined query examples](#8-combined-query-examples)
  - [9) Field selection utility (optional helper)](#9-field-selection-utility-optional-helper)
  - [10) Full combined scenario](#10-full-combined-scenario-filter--paging--sorting--field-selection--range-statuses)
  - [11) Correlated sort (MongoDB)](#11-correlated-sort-mongodb)
- [Reference](#reference)
  - [Backend-specific nested-path guidance (JPA vs Mongo)](#backend-specific-nested-path-guidance-jpa-vs-mongo)
  - [`filter=` JsonPath syntax (Jayway 3.x)](#filter-jsonpath-syntax-jayway-3x)
  - [Mongo `filter=` support scope](#mongo-filter-support-scope)
  - [JPA `filter=` support scope](#jpa-filter-support-scope)
  - [When `filter=` throws `400 Bad Request`](#when-filter-throws-400-bad-request)
- [POC databases used](#poc-databases-used)
- [Build](#build)
- [License](#license)

## What you get

- TMF630-style paging and sorting (`offset`, `limit`, `sort`)
- Attribute filtering mapped to QueryDSL `Predicate`
- JsonPath-based `filter=` support merged into the same QueryDSL predicate pipeline
- Strict same-element array correlation for document backends (Mongo `$elemMatch` translation)
- Range-aware response helpers (`Content-Range`, `X-Total-Count`, `X-Result-Count`)
- Field selection utility (`fields=` support) for response shaping — automatic when using `@Tmf630Response`
- `@Tmf630Response` annotation for fully transparent TMF630 response handling (status, headers, field selection)
- Works in both Spring Boot and plain Spring projects

## Module layout

| Module                                             | Description                                                                          |
|----------------------------------------------------|--------------------------------------------------------------------------------------|
| `tmf630-toolkit-paging-sorting-core`               | Paging, sorting, field selection (no Boot dependency)                                |
| `tmf630-toolkit-paging-sorting-autoconfigure`      | Spring Boot auto-configuration for paging/sorting                                    |
| `tmf630-toolkit-attribute-filtering-core`          | Attribute filtering to QueryDSL `Predicate` (no Boot dependency)                     |
| `tmf630-toolkit-attribute-filtering-autoconfigure` | Spring Boot auto-configuration for filtering                                         |
| `tmf630-toolkit-mongo-aggregation`                 | **Optional.** Correlated-sort `Aggregation` executor for MongoDB-backed services     |
| `tmf630-toolkit-all`                               | Convenience artifact depending on both filtering autoconfigure modules               |

The four "core" + "autoconfigure" modules are intentionally **DB-agnostic in
production scope** — they declare only `querydsl-core`, `spring-web`,
`spring-data-commons`, and `json-path`. JPA-only and other non-Mongo
consumers pay no Mongo dependency cost.

`tmf630-toolkit-mongo-aggregation` is an opt-in module that ships
`spring-data-mongodb` and `querydsl-mongodb` as production dependencies.
Add it only when your service uses MongoDB and wants the correlated-sort
features (JsonPath / simple-rich grammar with `$let` / `$filter` /
`$first` aggregation pipelines). See the [**Correlated sort**](#11-correlated-sort-mongodb)
section below.

## Dependency management

### First: import opentmf dependency versions
This will manage the dependencies of the opentmf libraries to use their latest compatible version.
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

If your service is MongoDB-backed and you want to accept correlated
sort terms (e.g. sort by the value of an array element identified by a
predicate), add the optional Mongo aggregation module **in addition to**
the autoconfigure modules above:

```xml
<!-- Optional: correlated-sort Aggregation executor for Mongo backends -->
<dependency>
  <groupId>org.opentmf.query</groupId>
  <artifactId>tmf630-toolkit-mongo-aggregation</artifactId>
</dependency>
```

Adding this dependency:
- Pulls `spring-data-mongodb` and `querydsl-mongodb` (with the legacy
  `mongo-java-driver` excluded — Spring Boot 4's `mongodb-driver-core`
  is used).
- Auto-registers a `Tmf630MongoCorrelatedSortExecutor` bean when a
  `MongoTemplate` is on the classpath.
- Adds the `TmfRichPageable` controller parameter binding (the
  recommended two-parameter shape — see [section 11](#11-correlated-sort-mongodb)).
  `TmfSort` is also bound for the alternative three-parameter
  `(Predicate, TmfSort, Pageable)` shape. Both are wired by the rich
  resolvers in `paging-sorting-autoconfigure`, registered alongside the
  existing `Sort` and plain `Pageable` resolvers — plain-only
  controllers stay unchanged.

JPA-only and other non-Mongo services should **not** add this
dependency. The base toolkit's plain `Sort` resolver continues to 400
on correlated terms, which is the correct behavior for a JPA backend
in v1.

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

Dependency versions are aligned via Spring Boot BOM `4.0.6`.

## Prerequisites for attribute filtering

The toolkit provides query-string-to-predicate translation but does **not** ship a specific persistence backend. Your service must add the QueryDSL binding for the backend it uses, plus a compile-time annotation processor to generate Q-classes from your entity models.

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

Mongo entities must be annotated with `@QueryEntity` (from `com.querydsl.core.annotations`) in addition to `@Document`:

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

These are typically already present in your service. The toolkit does not pull them transitively because it is backend-agnostic.

### What the toolkit provides transitively (no action needed)

- `querydsl-core` — the predicate API used internally
- `spring-data-commons` — shared Spring Data types (`Pageable`, `Page`, `Sort`, `QuerydslPredicateExecutor`)
- `json-path` — used for `filter=` JsonPath parsing

## Configuration prefixes

| Prefix                               | Purpose                                          |
|--------------------------------------|--------------------------------------------------|
| `opentmf.tmf630.paging`              | Paging/sorting behavior                          |
| `opentmf.tmf630.attribute-filtering` | Query filter parsing and rules                   |
| `opentmf.tmf630.field-selection`     | `@Tmf630Response` field selection behavior       |
| `opentmf.tmf630.mongo-aggregation`   | Correlated-sort behavior (mongo-aggregation module only) |

### Common paging properties

- `opentmf.tmf630.paging.enabled` (default: `true`)
- `opentmf.tmf630.paging.default-limit` (default: `50`)
- `opentmf.tmf630.paging.max-limit` (default: `500`)
- `opentmf.tmf630.paging.strict-mode` (default: `true`)
- `opentmf.tmf630.paging.allow-nested-sort-properties` (default: `false`)
- `opentmf.tmf630.paging.sort-allowlist` (default: empty, means unrestricted)

### Common filtering properties

- `opentmf.tmf630.attribute-filtering.enabled` (default: `true`)
- `opentmf.tmf630.attribute-filtering.implicit-eq-enabled` (default: `true`)
- `opentmf.tmf630.attribute-filtering.combine-repeated-values` (`OR` or `AND`)
- `opentmf.tmf630.attribute-filtering.allow-nested-paths-jpa` (default: `false`)
- `opentmf.tmf630.attribute-filtering.allow-nested-paths-docdb` (default: `true`)
- `opentmf.tmf630.attribute-filtering.regex.enabled` (default: `false`)
- `opentmf.tmf630.attribute-filtering.regex.max-length` (default: `256`)
- `opentmf.tmf630.attribute-filtering.limits.max-clauses` (default: `50`)
- `opentmf.tmf630.attribute-filtering.limits.max-values-per-key` (default: `20`)
- `opentmf.tmf630.attribute-filtering.allowlist.mode` (default: `ALLOW_ALL`)
- `opentmf.tmf630.attribute-filtering.allowlist.entities.<EntityName>=...`
- `opentmf.tmf630.attribute-filtering.on-unknown-field` (`REJECT` or
  `IGNORE`, default `REJECT`) — applies to attribute-style filters such as
  `?status.eq=Launched` where an unknown key is usually a client typo.
- `opentmf.tmf630.attribute-filtering.on-unknown-json-path-field`
  (`REJECT` or `IGNORE`, default `IGNORE`) — applies to JSON Path
  filters such as `?filter=$[?(@.optionalField == 'X')]`. Defaults to
  `IGNORE` because TMF630 Part 6 specifies that an unmatched JSON Path
  is an empty result rather than a validation error. Flip to `REJECT`
  if you want the strict behaviour for both forms.
- `opentmf.tmf630.attribute-filtering.on-unknown-operator` (`REJECT` or `IGNORE`)
- `opentmf.tmf630.attribute-filtering.json-path-filter.enabled` (default: `true`)
- `opentmf.tmf630.attribute-filtering.json-path-filter.max-length` (default: `2048`)

### Field selection properties

- `opentmf.tmf630.field-selection.enabled` (default: `true`) — enables the `@Tmf630Response` auto-advice
- `opentmf.tmf630.field-selection.default-depth` (default: `1`) — how deep nested objects are auto-expanded when selected by name (see [depth semantics in section 9](#9-field-selection-utility-optional-helper))

### Mongo aggregation properties (only when `tmf630-toolkit-mongo-aggregation` is on the classpath)

- `opentmf.tmf630.mongo-aggregation.simple-rich.default-key` (default:
  `id`) — the field name used for the bare-value bracket form
  `arr[X]` in simple-rich sort terms. `arr[X]` is shorthand for
  `arr[<defaultKey>=X]`. Projects whose convention uses `name`,
  `code`, etc. flip this once globally without code changes.

### Configuration scenario 1: default behavior (no custom config)

If you add the autoconfigure modules and do not provide any `opentmf.tmf630.*` properties, the toolkit starts with safe defaults:

- Paging/sorting is enabled with `default-limit=50`, `max-limit=500`, strict range checks, and unrestricted sort fields.
- Attribute filtering is enabled with implicit `eq` support (for example, `name=alice`).
- Repeated values are combined with `OR`.
- Nested paths are disabled by default for JPA entities and enabled by default for document-style entities.
- Allowlist mode defaults to `ALLOW_ALL` (fields are not blocked by default).
- Unknown fields/operators are rejected (`400 Bad Request`).
- JsonPath `filter=` support is enabled with a max length of `2048`.

In plain terms: a service gets TMF630 paging/sorting and filtering automatically with minimal setup, while still protecting itself from unknown or malformed query keys.

### Configuration scenario 2: minimal override (change only a few items)

```yaml
opentmf:
  tmf630:
    paging:
      max-limit: 200
    attribute-filtering:
      allow-nested-paths-docdb: true
      on-unknown-operator: IGNORE
```

How this behaves:

- Clients still get all default TMF630 capabilities.
- Page size is capped at `200` instead of `500`.
- Nested field paths are explicitly enabled for document-style entities.
- Unknown operators are ignored instead of rejected, which is more tolerant for mixed client traffic.

### Configuration scenario 3: full non-default example

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

- Query parsing is strict about explicit operators (`implicit-eq-enabled=false`), so clients should send `field.eq=value`.
- Repeated values are treated as `AND` constraints, making filtering narrower.
- Nested-path behavior is backend-aware (`false` for JPA, `true` for document-style entities).
- Regex operators are enabled with tighter safety limits.
- Filtering and sorting are constrained to explicit allowlists to protect exposed query surface.
- Unknown fields/operators are ignored to avoid hard failures when older/newer clients send extra parameters.
- JsonPath filtering remains available but with a tighter max expression length.

Note: merge mode between attribute filters and `filter=` is controlled per request via `filter.combineWithAttributes=AND|OR` (query parameter), not via YAML configuration.

For safer production posture, switch allowlist mode to `DENY_ALL` and explicitly configure permitted fields per entity:

```yaml
opentmf:
  tmf630:
    attribute-filtering:
      allowlist:
        mode: DENY_ALL
        entities:
          Person:
            - id
            - name
            - surname
            - birthdate
            - sex
```

## Developer handbook

### 1) Example domain and sample data

Assume endpoint `GET /api/persons` with this simple model:

- `id` (Long)
- `name` (String)
- `surname` (String)
- `birthdate` (LocalDate)
- `sex` (String, for example `female` or `male`)

Sample records:

| id | name   | surname | birthdate   | sex    |
|----|--------|---------|-------------|--------|
| 1  | Alice  | Doe     | 1992-05-10  | female |
| 2  | Bob    | Smith   | 1988-11-23  | male   |
| 3  | Carol  | Doe     | 2001-01-17  | female |
| 4  | David  | Brown   | 1995-09-02  | male   |
| 5  | Eva    | Stone   | 1998-04-05  | female |
| 6  | Fiona  | Blake   | 1994-12-21  | female |
| 7  | Grace  | Miller  | 2003-07-30  | female |
| 8  | Hannah | Wilson  | 1991-02-14  | female |
| 9  | Irene  | Moore   | 1985-10-01  | female |
| 10 | Julia  | Clark   | 1996-06-11  | female |

### 2) Controller usage (Spring Boot)

There are three ways to wire TMF630 responses, from most transparent to most manual.

**Level 1: Fully transparent — `@Tmf630Response` on a `Page<T>` return** (recommended)

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

The library does everything: resolves the HTTP status (`200`, `206`, or `416`), adds `Content-Range` / `X-Total-Count` / `X-Result-Count` headers, serializes page content as a JSON array, and transparently applies `fields=` selection when that query parameter is present.

`@Tmf630Response` can be placed at the class level to apply to every handler method in the controller.

**Controlling expansion depth per endpoint**

The optional `depth` attribute overrides the global `default-depth` for a specific endpoint:

```java
@GetMapping("/persons")
@Tmf630Response(depth = 2)    // expand complex fields 2 levels deep for this endpoint
Page<Person> list(Pageable pageable) { ... }

@GetMapping("/orders")
@Tmf630Response(depth = 1)    // only top-level scalar fields of any selected complex field
Page<Order> orders(Pageable pageable) { ... }

@GetMapping("/simple")
@Tmf630Response               // inherits opentmf.tmf630.field-selection.default-depth (default 1)
Page<Simple> simple(Pageable pageable) { ... }
```

The resolution order is: **method-level `depth`** → **class-level `depth`** → **`opentmf.tmf630.field-selection.default-depth`**.

`depth` controls how deep a named complex field (e.g. `fields=address`) is expanded by the library's field mapper:
- `depth=0` — the field is included as a raw value; Jackson serializes it natively. All of the object's getters are called by Jackson, including any lazy-loaded sub-associations.
- `depth=1` (**default**) — only the direct scalar fields of `address` are included; complex sub-fields (e.g. `country`, or a `@OneToMany states`) are excluded. Their getters are never called, preventing unintended JPA lazy-load cascades.
- `depth=2` — `address` and its first-level complex sub-fields (e.g. `country`) are both expanded; `country`'s own complex sub-fields are not.

The default of `1` is intentional: when a client sends `fields=address`, the library maps only the scalar properties of `address` into an explicit sub-map. Jackson never receives the raw `Address` POJO and therefore never calls `getStates()` or any other lazy-loaded association. To include a nested association, use an explicit dot-path (`fields=address.states`) or raise the depth.

Explicit dot-paths in `fields` (e.g. `fields=address.country.code`) always resolve regardless of `depth`.

---

#### Avoiding JPA lazy-load cascades — recommended patterns

When no `fields=` parameter is present, the library returns `page.getContent()` as-is. Jackson then serializes the raw JPA entity objects and calls **every getter**, including any `@OneToMany` or `@ManyToOne` lazy associations. Depending on whether the JPA session is still open, this either triggers N+1 queries or throws a `LazyInitializationException`.

The following patterns prevent this, ordered from best to most pragmatic.

---

**Pattern A — Return a DTO from the repository (strongly recommended)**

The cleanest solution: your Spring Data query returns a flat DTO that contains only the fields you need. Lazy associations do not exist on the DTO, so there is nothing to load.

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

Because `PersonDto` is a plain record with no associations, serialization is always safe. The `@Tmf630Response` advice still applies `fields=` selection on top if the client requests it.

This is the pattern we recommend for all list/search endpoints in production microservices.

---

**Pattern B — Use `fields=` with only scalar field names**

When the client sends `fields=id,name,birthdate` and none of those names resolve to a lazy association, `FieldSelectionUtil` at `depth=1` (the default) maps only scalar properties. The raw entity POJO is never passed to Jackson — instead Jackson receives a plain `Map` — so `getAddress()`, `getChildren()`, etc. are never called.

> **Rule of thumb:** only lazy-load risk arises when a field name in `fields=` refers to a complex JPA association. Scalar fields (`String`, `Long`, `LocalDate`, enums, …) are always safe.

---

**Pattern C — Fetch the association explicitly when you need it**

If you genuinely need a nested object in the response, use `@EntityGraph` or `JOIN FETCH` so the association is loaded in a single query:

```java
@EntityGraph(attributePaths = {"address"})
Page<Person> findAll(Predicate predicate, Pageable pageable);
```

Then the client sends `fields=id,name,address`. With `depth=1` (the default), the library maps only the scalar sub-fields of `address` (e.g. `city`, `zip`), never touching `address.states` or other deeper associations. To go one level deeper for a specific endpoint, annotate it with `@Tmf630Response(depth = 2)`.

---

**Patterns to avoid**

| Pattern | Problem |
|---|---|
| OSIV enabled (Spring Boot default `spring.jpa.open-in-view=true`) | Session stays open for the entire HTTP request; lazy loads silently succeed but produce hidden N+1 queries |
| `@Transactional` on a controller method | Same hidden N+1 risk, slightly narrower scope |
| `@Tmf630Response(depth = 0)` with no `fields=` | Raw entity handed to Jackson; every getter called; full object graph loaded |

> **Recommendation for new projects:** set `spring.jpa.open-in-view=false` in `application.yml` and use Pattern A (DTOs) for all list endpoints. This makes lazy-load issues surface at development time as `LazyInitializationException` rather than silently degrading performance in production.

---

**Level 2: Manual paging, transparent field selection**

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

`Tmf630Util.tmfPage(page)` handles status and headers. The `@Tmf630Response` annotation enables automatic `fields=` selection without any extra parameter or code.

**Level 3: Fully manual (no annotation needed)**

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

This gives the developer full control over every step. `Tmf630Util.tmfPage(page, fields)` returns `ResponseEntity<List<Map<String, Object>>>` with field selection pre-applied.

### 3) Combining predicates with path variables

The toolkit builds a `Predicate` exclusively from query parameters. Path variables (like `{id}`) are not included — combining them with the generated predicate is the developer's responsibility.

This is common for sub-resource endpoints such as `GET /master/{id}/children`, where the result must be scoped to a specific parent entity.

```java
@RestController
@RequestMapping("/api/masters/{masterId}/children")
class ChildController {

  private final ChildRepository repository;

  ChildController(ChildRepository repository) {
    this.repository = repository;
  }

  @GetMapping
  ResponseEntity<List<Child>> search(
      @PathVariable Long masterId,
      @QuerydslPredicate(root = Child.class) Predicate predicate,
      Pageable pageable) {
    Predicate combined = QChild.child.master.id.eq(masterId).and(predicate);
    Page<Child> page = repository.findAll(combined, pageable);
    return Tmf630Util.tmfPage(page);
  }
}
```

In this example:

- `predicate` contains everything parsed from query parameters (attribute filters, `filter=`, etc.).
- The developer wraps it with the parent-scoping condition using standard QueryDSL.
- The two are combined with `.and(...)`, so the final query is: `master.id = :masterId AND (query-param filters)`.

This separation is intentional — the library stays focused on query string parsing and does not make assumptions about URL structure or entity relationships.

### 4) Paging and sorting examples

- `GET /api/persons?offset=0&limit=2`
- `GET /api/persons?offset=2&limit=2`
- `GET /api/persons?offset=0&limit=10&sort=-surname,+name`

Sort tokens:

- `+field` or `field` = ascending
- `-field` = descending
- multiple values allowed via comma separation

For services that need to sort by a value taken from a specific element
of an embedded array (TMF "characteristics" pattern), the
`tmf630-toolkit-mongo-aggregation` module adds two additional sort
grammars on top of the plain form. See section
[**11) Correlated sort (MongoDB)**](#11-correlated-sort-mongodb) below.

Example paged response (partial):

```http
HTTP/1.1 206 Partial Content
Content-Range: items 1-2/4
X-Total-Count: 4
X-Result-Count: 2
Content-Type: application/json
```

```json
[
  { "id": 4, "name": "David", "surname": "Brown", "birthdate": "1995-09-02", "sex": "male" },
  { "id": 1, "name": "Alice", "surname": "Doe", "birthdate": "1992-05-10", "sex": "female" }
]
```

### 5) Full QueryDSL operator reference

Query parameter format: `field.operator=value`

If `implicit-eq-enabled=true`, `field=value` is also supported and treated as `field.eq=value`.

| Operator suffix | Meaning                        | Example                                                     |
|-----------------|--------------------------------|-------------------------------------------------------------|
| `eq`            | equals                         | `name.eq=Alice`                                             |
| `ne`            | not equals                     | `surname.ne=Smith`                                          |
| `eqi`           | equals ignore case             | `name.eqi=alice`                                            |
| `nei`           | not equals ignore case         | `surname.nei=smith`                                         |
| `gt`            | greater than                   | `birthdate.gt=1990-01-01`                                   |
| `gte`           | greater than or equal          | `birthdate.gte=1990-01-01`                                  |
| `lt`            | less than                      | `birthdate.lt=2000-01-01`                                   |
| `lte`           | less than or equal             | `birthdate.lte=2000-01-01`                                  |
| `between`       | value range (2 values)         | `birthdate.between=1990-01-01&birthdate.between=1999-12-31` |
| `in`            | in set (multi-value)           | `surname.in=Doe&surname.in=Brown` _or_ `surname.in=Doe,Brown` |
| `nin`           | not in set (multi-value)       | `surname.nin=Smith&surname.nin=Jones` _or_ `surname.nin=Smith,Jones` |
| `isnull`        | is null (no value)             | `name.isnull`                                               |
| `isnotnull`     | is not null (no value)         | `surname.isnotnull`                                         |
| `like`          | SQL like                       | `name.like=A%`                                              |
| `likei`         | SQL like ignore case           | `name.likei=a%`                                             |
| `contains`      | contains substring             | `surname.contains=ow`                                       |
| `containsi`     | contains substring ignore case | `surname.containsi=OW`                                      |
| `startswith`    | starts with                    | `name.startswith=Da`                                        |
| `startswithi`   | starts with ignore case        | `name.startswithi=da`                                       |
| `endswith`      | ends with                      | `surname.endswith=oe`                                       |
| `endswithi`     | ends with ignore case          | `surname.endswithi=OE`                                      |
| `regex`         | regular expression match       | `name.regex=^A.*`                                           |
| `regexi`        | regex match ignore case        | `name.regexi=^a.*`                                          |

Notes:

- `regex` / `regexi` require `opentmf.tmf630.attribute-filtering.regex.enabled=true`.
- `between`, `in`, `nin` are multi-value operators. Values can be provided
  either as repeated query parameters (`?key.in=A&key.in=B`) or as a single
  comma-separated list (`?key.in=A,B`). A literal comma inside a value can be
  escaped with `\,`. The two forms can be mixed in one request.
- unknown fields/operators are validated by `on-unknown-field` and `on-unknown-operator`.
- JSON Path filters (`?filter=$[?(...)]`) use a separate policy
  `on-unknown-json-path-field` (default `IGNORE`) so that unmatched paths
  yield an empty result per TMF630 Part 6.

#### Reserved parameter names

The following query parameter names are reserved for paging, sorting, field selection, and filter control. The attribute filtering engine skips them automatically — they are never treated as entity field filters:

`page`, `size`, `sort`, `offset`, `limit`, `fields`, `filter`, `filter.combineWithAttributes`

If your entity happens to have a field with one of these names (for example, a column called `offset`), you can still filter by it using the explicit operator suffix form:

| Query | Behavior |
|---|---|
| `offset=5` | Reserved — interpreted as TMF630 pagination offset, **not** as a filter |
| `offset.eq=5` | Attribute filter — matches records where the `offset` field equals `5` |
| `offset.gte=3` | Attribute filter — matches records where `offset >= 3` |

The bare form (`field=value`) is ambiguous for reserved names, and the library resolves it in favor of the framework parameter. The explicit operator form (`field.op=value`) always bypasses the reservation.

### 6) Date and datetime field formats

The library uses Spring's `DefaultFormattingConversionService` internally to parse query parameter values into their Java target types. All `java.time` types use strict ISO-8601 format by default:

| Java type | Accepted format | Example query |
|---|---|---|
| `LocalDate` | `yyyy-MM-dd` | `birthdate.gt=1990-06-15` |
| `LocalTime` | `HH:mm:ss[.SSS]` | `startTime.gte=14:30:00` |
| `LocalDateTime` | `yyyy-MM-dd'T'HH:mm:ss[.SSS]` | `createdAt.lt=2024-01-15T14:30:00` |
| `OffsetDateTime` | `yyyy-MM-dd'T'HH:mm:ssXXX` | `updatedAt.gte=2024-01-15T14:30:00+03:00` |
| `ZonedDateTime` | `yyyy-MM-dd'T'HH:mm:ssXXX'['VV']'` | `scheduledAt.lt=2024-01-15T14:30:00+03:00[Europe/Istanbul]` |
| `Instant` | `yyyy-MM-dd'T'HH:mm:ssX` (UTC) | `timestamp.gte=2024-01-15T11:30:00Z` |

> Fractional seconds (`[.SSS]`) are optional for `LocalTime`, `LocalDateTime`, and `LocalDateTime`.

#### Error messages

When a value cannot be parsed, the library returns **`400 Bad Request`** with a structured JSON body:

```json
{
  "code": "400",
  "status": "Bad Request",
  "reason": "Invalid filter parameter.",
  "message": "Field \"birthdate\" (LocalDate) could not be parsed from value \"15/06/1990\". Expected format: yyyy-MM-dd, example: 1990-06-15"
}
```

The library registers `Tmf630FilteringExceptionHandler` at `@Order(Ordered.HIGHEST_PRECEDENCE)`. This ensures the `400` response reaches the client even when the consuming application has a catch-all `@ExceptionHandler(Exception.class)` that would otherwise return `500`. The handler only intercepts `TmfFilteringException`; all other exception types are left to the consuming application's own handlers.

> **Consuming service note:** If you still see `500` after upgrading, verify that no framework-level component (e.g., an API gateway, Sentry integration, or a custom `HandlerExceptionResolver`) strips the response before it reaches the client.

#### `@DateTimeFormat` annotations are not respected

The library converts by **Java type**, not by inspecting field annotations. If your entity field is annotated:

```java
@DateTimeFormat(pattern = "dd/MM/yyyy")
private LocalDate birthdate;
```

the library still expects `yyyy-MM-dd` in the query string. The `@DateTimeFormat` pattern only affects Spring MVC's own binding (form fields, `@RequestParam`), not this library's predicate resolution.

#### Customising the accepted format

The `ValueConverter` bean is registered with `@ConditionalOnMissingBean`, so you can override it in a `@Configuration` class to accept a custom format or to share the application's own `ConversionService`:

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

### 7) Enum field resolution



When a query parameter targets an enum field, the library resolves the string value to an enum constant using this chain:

1. **Custom factory methods** — the library scans the enum class for `public static` methods that accept a single `String` parameter and return the enum type itself (excluding `valueOf`). If any such method returns a non-null result, that value is used. Exceptions thrown by factory methods are silently ignored.
2. **Standard `Enum.valueOf`** — if no factory method succeeds, the library falls back to `Enum.valueOf(EnumType.class, rawValue)`, which is exact and case-sensitive.
3. **Error** — if all attempts fail, a `400 Bad Request` is returned.

This means: if your enum has no custom factory methods, the current behavior (exact-match via `valueOf`) applies with no changes. If you add a factory method, the library picks it up automatically.

#### Example: plain enum (no factory method)

```java
public enum Status {
  NEW, DONE, FAILED
}
```

| Query | Result |
|---|---|
| `status=NEW` | matches `Status.NEW` |
| `status=new` | `400 Bad Request` (case-sensitive) |
| `status.in=NEW&status.in=DONE` | matches `Status.NEW` or `Status.DONE` |

#### Example: enum with a case-insensitive factory

```java
public enum Status {
  NEW, DONE, FAILED;

  public static Status initFrom(String value) {
    return valueOf(value.toUpperCase());
  }
}
```

| Query | Result |
|---|---|
| `status=new` | matches `Status.NEW` via `initFrom` |
| `status=Done` | matches `Status.DONE` via `initFrom` |
| `status=FAILED` | matches `Status.FAILED` via `initFrom` (or via `valueOf` fallback) |

#### Example: enum with multiple factory methods

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

| Query | Result |
|---|---|
| `status=nuevo` | matches `Status.NEW` via `fromAlias` |
| `status=done` | matches `Status.DONE` via `fromLower` |
| `status=FAILED` | matches `Status.FAILED` via `fromLower` or `valueOf` fallback |
| `status=bogus` | `400 Bad Request` (all methods and `valueOf` fail) |

Factory methods are discovered once per enum type and cached for the lifetime of the application.

### 8) Combined query examples

#### Example A: multiple operators in one request

```http
GET /api/persons?surname.eq=Doe&birthdate.gt=1990-01-01&sort=-birthdate&offset=0&limit=10
```

Example response:

```http
HTTP/1.1 206 Partial Content
Content-Range: items 1-2/2
X-Total-Count: 2
X-Result-Count: 2
```

```json
[
  { "id": 3, "name": "Carol", "surname": "Doe", "birthdate": "2001-01-17", "sex": "female" },
  { "id": 1, "name": "Alice", "surname": "Doe", "birthdate": "1992-05-10", "sex": "female" }
]
```

#### Example B: set and null checks

```http
GET /api/persons?surname.in=Doe&surname.in=Brown&name.isnotnull&offset=0&limit=5
```

#### Example C: combining attribute filtering with `filter=` JsonPath

```http
GET /api/persons?name.eq=gokhan&filter=$[?(@.surname == 'Demir' || @.birthdate >= '1990-01-01')]
```

Rules for `filter=`:

- must be a JsonPath **filter expression** (`$[?(...)]`)
- restricted subset is supported: `&&`, `||`, parentheses, and comparison operators (`==`, `!=`, `>`, `>=`, `<`, `<=`)
- array correlation syntax is supported: `@.arrayField[?(...)]`
- array correlation uses strict **same-element** semantics
- for Mongo/document backends, array correlation is translated to explicit Mongo `$elemMatch`
- for JPA backends, array correlation inside `filter=` is intentionally rejected with `400 Bad Request`
- invalid or unsupported expression returns `400 Bad Request`
- attribute params and `filter` are combined by `AND` by default
- merge override can be sent per request using `filter.combineWithAttributes=AND|OR`

Merge examples:

Default merge (`AND`):

```http
GET /api/persons?name.eq=gokhan&birthdate.gt=2025-01-01&filter=$[?(@.sex == 'female')]
```

Effective logic:

`(name == gokhan AND birthdate > 2025-01-01) AND (filter-expression)`

Request-level `OR` override:

```http
GET /api/persons?name.eq=gokhan&birthdate.gt=2025-01-01&filter=$[?(@.sex == 'female')]&filter.combineWithAttributes=OR
```

Effective logic:

`(name == gokhan AND birthdate > 2025-01-01) OR (filter-expression)`

#### Example D: strict same-element array correlation (Mongo/document backends)

Given document data similar to:

```json
{
  "externalReference": [
    { "name": "MARKET_ACCOUNT_ID", "id": "OPCO-ID-012" },
    { "name": "ORDER_REFERENCE", "id": "OPCO-ORDER-012" }
  ]
}
```

Positive (same array item matches both conditions):

```http
GET /api/orders?filter=$[?(@.externalReference[?(@.name == 'ORDER_REFERENCE' && @.id == 'OPCO-ORDER-012')])]
```

Negative (cross-element mismatch does **not** match):

```http
GET /api/orders?filter=$[?(@.externalReference[?(@.name == 'MARKET_ACCOUNT_ID' && @.id == 'OPCO-ORDER-012')])]
```

Behavior summary:

- Mongo/document: translated to `$elemMatch` and evaluated with same-element semantics
- JPA: this array-correlation pattern is rejected with `400` (by design)

#### Example E: TMF630 shorthand forms (`$.`-less filter)

Per TMF630, the leading `$` / `$.` may be omitted from `filter=` for simplicity. All three of the following match identically:

```http
GET /api/orders?filter=$[?(@.status == 'Pending')]
GET /api/orders?filter=[?(@.status == 'Pending')]
GET /api/orders?filter=$.[?(@.status == 'Pending')]
```

The bare-wrapper form drops the `$` entirely. The dotted-prefix form leaves it but writes the predicate without the bracket-around-`$` style — both are accepted.

Sub-array correlation also supports the shorthand:

```http
GET /api/orders?filter=statusChange[?(@.status == 'Pending')]
GET /api/orders?filter=$.statusChange[?(@.status == 'Pending')]
```

Both rewrite internally to the canonical correlated form `$[?(@.statusChange[?(@.status == 'Pending')])]` and produce the same Mongo `$elemMatch` query.

#### Example F: JsonPath wildcard `[*]` as transparent projection

Canonical JsonPath uses `[*]` to project across array elements. Mongo's BSON path-equality auto-projects implicitly, so the toolkit accepts both forms equivalently:

```http
GET /api/orders?filter=$[?(@.externalReference[*].name == 'ORDER_REFERENCE')]
GET /api/orders?filter=$[?(@.externalReference.name == 'ORDER_REFERENCE')]
```

Both match orders whose `externalReference` array has at least one element with `name == 'ORDER_REFERENCE'`. The `[*]` is stripped at parse time; quoted string literals containing `[*]` are preserved verbatim.

For details on what `filter=` accepts, supported syntax, and exact error semantics, see the **Reference** section below ([JsonPath syntax](#filter-jsonpath-syntax-jayway-3x), [Mongo support scope](#mongo-filter-support-scope), [JPA support scope](#jpa-filter-support-scope), [400 conditions](#when-filter-throws-400-bad-request)).

### 9) Field selection utility (optional helper)

`FieldSelectionUtil` can map objects into filtered `Map<String, Object>` views, useful when clients request specific fields.

Package:

- `org.opentmf.query.commons.fieldselection.FieldSelectionUtil`

For list endpoints backed by `Page<T>`, the preferred approach is `@Tmf630Response` (see [section 2](#2-controller-usage-spring-boot)) — field selection is handled automatically without any extra code. For single-object endpoints or cases where manual control is needed, `FieldSelectionUtil` can be called directly:

```java
Map<String, Object> result = FieldSelectionUtil.fieldsToMap(person, "id,name,surname");
```

Controller example (single-object endpoint):

```java
@RestController
@RequestMapping("/api/persons")
class PersonFieldSelectionController {

  private final PersonRepository repository;

  PersonFieldSelectionController(PersonRepository repository) {
    this.repository = repository;
  }

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
}
```

Request example:

```http
GET /api/persons/1?fields=id,name,surname
```

Response example:

```json
{
  "id": 1,
  "name": "Alice",
  "surname": "Doe"
}
```

### 10) Full combined scenario (filter + paging + sorting + field selection + range statuses)

This section combines all major capabilities in one flow.

**Recommended approach** — let `@Tmf630Response` handle everything:

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

Status (`200`/`206`/`416`), range headers, and `fields=` selection are all handled automatically. Use `@Tmf630Response(depth = N)` to control how deep complex fields are expanded.

**Manual equivalent** (Level 3, full control):

```java
@GetMapping
ResponseEntity<List<Map<String, Object>>> search(
    @QuerydslPredicate(root = Person.class) Predicate predicate,
    Pageable pageable,
    @RequestParam(required = false) String fields) {
  Page<Person> page = repository.findAll(predicate, pageable);

  List<Map<String, Object>> content =
      (fields == null || fields.isBlank())
          ? FieldSelectionUtil.fieldsToMapList(page.getContent())
          : FieldSelectionUtil.fieldsToMapList(page.getContent(), fields);

  Page<Map<String, Object>> mappedPage =
      new org.springframework.data.domain.PageImpl<>(content, pageable, page.getTotalElements());
  return Tmf630Util.tmfPage(mappedPage);
}
```

#### Example A: second page + sort by birthdate desc + selected fields + filters

Request:

```http
GET /api/persons?birthdate.gt=1990-01-01&sex.eq=female&sort=-birthdate&offset=2&limit=2&fields=id,name,birthdate
```

Explanation:

- filters to `birthdate > 1990-01-01` and `sex = female`
- sorts by `birthdate` descending
- returns the second page slice (`offset=2`, `limit=2`)
- returns only `id`, `name`, `birthdate`

Response:

```http
HTTP/1.1 206 Partial Content
Content-Range: items 3-4/7
X-Total-Count: 7
X-Result-Count: 2
Content-Type: application/json
```

```json
[
  { "id": 5, "name": "Eva", "birthdate": "1998-04-05" },
  { "id": 10, "name": "Julia", "birthdate": "1996-06-11" }
]
```

#### Example B: requested range is not satisfiable (416)

Request:

```http
GET /api/persons?birthdate.gt=1990-01-01&sex.eq=female&sort=-birthdate&offset=99&limit=2
```

Response:

```http
HTTP/1.1 416 Requested Range Not Satisfiable
Content-Range: items */7
X-Total-Count: 7
X-Result-Count: 0
Content-Type: application/json
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

Request:

```http
GET /api/persons?sort=-birthdate&offset=0&limit=3
```

Response:

```http
HTTP/1.1 206 Partial Content
Content-Range: items 1-3/10
X-Total-Count: 10
X-Result-Count: 3
Content-Type: application/json
```

```json
[
  { "id": 7, "name": "Grace", "surname": "Miller", "birthdate": "2003-07-30", "sex": "female" },
  { "id": 3, "name": "Carol", "surname": "Doe", "birthdate": "2001-01-17", "sex": "female" },
  { "id": 5, "name": "Eva", "surname": "Stone", "birthdate": "1998-04-05", "sex": "female" }
]
```

`206` here means the request is successful and returns only part of the full result set.

### 11) Correlated sort (MongoDB)

> **Module required**: `tmf630-toolkit-mongo-aggregation`. JPA-only
> services skip this section; the plain `Sort` resolver continues to
> 400 on correlated terms. See [`docs/correlated-sort.md`](./docs/correlated-sort.md)
> for the full design note (URL grammar, semantics, capability matrices
> for both grammars, and module layout).

TMF Open API resources frequently encode attributes as arrays of
`{name, value}` objects (Characteristics, ExternalReferences,
RelatedParty, etc.). Sorting by, say, the `value` of the
`characteristic` whose `name` equals `'price'` is **not expressible in
a plain `find()` query** — sorting on `characteristic.value` picks the
min/max value across the whole array, not the value of a specific
element. The `mongo-aggregation` module addresses this with an
`Aggregation`-based execution path triggered by two new sort
grammars.

#### Two grammars, one IR, one executor

Both grammars produce the same internal AST and run through the same
aggregation pipeline emitter. Pick whichever reads better at the call
site.

| Grammar | Example sort term | Notes |
| --- | --- | --- |
| **JsonPath** | `$.characteristic[?(@.name == 'price')].value` | Rich predicates (`&&`, `\|\|`, `==`, `!=`, `>`, `<`, `>=`, `<=`); the same syntax used by `filter=`. |
| **Simple-rich** | `characteristic[name=price].value` | Equality only, terse, default-key inference (`arr[X]` → `arr[id=X]`); supports `min` / `max` / `str` / `num` / `date` functions. |

Plain dotted sort terms (`-createdOn,+id`) keep the existing cheap
`find()` path — there is no regression for non-correlated requests.
The branch happens at the controller and is one `if`.

#### Consumer pattern

**Recommended shape** — controllers declare `TmfRichPageable` as the
single sort+paging parameter. `TmfRichPageable` extends Spring Data
`Pageable` and additionally exposes `tmfSort()` carrying both plain
and correlated terms. Add `@Tmf630Response` for full TMF630 response
handling (`Content-Range`, `X-Total-Count`, `X-Result-Count`,
`200` / `206` / `416` status, `fields=` selection — see
[section 2](#2-controller-usage-spring-boot)):

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

Note: `pageable.getSort()` returns the **plain subset** of the sort
(or `Sort.unsorted()` when every term is correlated). When the
correlated-sort branch fires, the executor reads sort via
`pageable.tmfSort()`. The find()-path call simply passes `pageable`
to Spring Data — its `getSort()` handles plain terms correctly and
returns unsorted when correlated terms are present (the
correlated-sort branch handles those itself, so the find()-path
case never sees correlated terms).

**Three-parameter shape** — also supported, identical end-state
semantics. Useful if you have an existing controller using
Spring Data `Pageable` and want to add correlated-sort awareness
incrementally without changing the parameter type:

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

`@Tmf630Response` works identically across both branches — the advice
operates on the returned `Page<T>` shape, not on how the page was
built. The correlated-sort executor returns a standard
`PageImpl<T>`, so `Content-Range` / `X-Total-Count` / `X-Result-Count`
headers, `200` / `206` / `416` status differentiation, and `fields=`
selection all apply to correlated-sort responses with no extra
wiring. `@Tmf630Response(depth = N)` also works as documented in
section 2 — `depth` controls how aggressively `fields=` selection
expands nested complex fields, and applies to whatever entity the
executor returns.

`Tmf630MongoCorrelatedSortExecutor` is auto-wired from the new module.
Plain-only controllers using `Sort` as the parameter type are
unchanged — they continue to 400 on correlated terms exactly as
before.

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

#### Example A: sort by the value of a specific characteristic — JsonPath

Sort ascending by the `value` of the `price` characteristic:

```http
GET /api/products?sort=$.characteristic[?(@.name == 'price')].value
```

For the dataset above the result order is:

```
2 (price 18), 1 (price 20.5), 3 (no price → null sorts last regardless of direction)
```

To exclude documents that don't have a `price` characteristic, pair
the sort with an explicit filter:

```http
GET /api/products
  ?filter=$[?(@.characteristic[?(@.name == 'price')])]
  &sort=$.characteristic[?(@.name == 'price')].value
```

Result: `2, 1`.

#### Example A.1: filter and sort that both target an auto-promoted `id` field

A common TMF pattern is to filter `productOffering` documents whose
`prodSpecCharValueUse` array contains an entry with a specific `id`,
and sort by a value taken from that same entry. Realistic shape:

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

Returns documents whose array contains a `RC_OFFER_TYPE` entry,
ordered by that entry's nested `value`. Both the filter and the sort
target the nested `id` field; Spring Data Mongo auto-promotes nested
`id` properties to BSON `_id` on write, and the toolkit applies the
same mapping on both code paths so the request shape works whether or
not a correlated sort is present.

#### Example B: same sort, simple-rich form

```http
GET /api/products?sort=characteristic[name=price].value
```

Identical end state to Example A. Both lower to the same
`Aggregation` pipeline.

#### Example C: default-key shorthand

When `name` is the bracket key (or whatever you've configured via
`opentmf.tmf630.mongo-aggregation.simple-rich.default-key`), you can
drop the `name=` prefix:

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

Returns documents ordered by `price` descending. Documents without a
`price` characteristic sort **last** in descending (mirrors
ascending-puts-null-first).

#### Example E: tie-breaking with a plain term

When multiple documents resolve to the same correlated key, MongoDB
does not guarantee a stable order between them. Append a stable plain
term to make it deterministic:

```http
GET /api/products?sort=-characteristic[name=price].value,+id
```

#### Example E.1: multi-term sort with multiple correlated keys

A single sort string may carry **N correlated terms** plus optional
plain terms. Each term is comma-separated, takes the usual `+` / `-`
direction prefix, and is honored in declaration order. The executor
emits one synthetic key per correlated term (`_sortKey0`, `_sortKey1`,
…) inside `$addFields` and the final `$sort` stage orders by all of
them in the order the user wrote.

Two JsonPath terms — primary descending by `price` value, secondary
ascending by `stock` value:

```http
GET /api/products?sort=-$.characteristic[?(@.name == 'price')].value,+$.characteristic[?(@.name == 'stock')].value
```

The same two-correlated-key pattern in simple-rich:

```http
GET /api/products?sort=-characteristic[name=price].value,+characteristic[name=stock].value
```

Grammars can mix in a single sort — JsonPath + simple-rich + plain
tiebreaker, in any order:

```http
GET /api/products?sort=-$.characteristic[?(@.name == 'price')].value,+characteristic[name=stock].value,+id
```

The `+id` tail keeps the result deterministic when the two correlated
keys also tie. Plain terms in the mix don't force a different
execution shape — the presence of any correlated term is what routes
the request through the aggregation pipeline; plain terms then ride
along inside the same `$sort` stage.

#### Example F: nested correlation (two levels deep)

For TMF `ServiceOrder`-shaped resources where the array hop chains
through another array, both grammars allow multi-level chaining.

JsonPath:

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

The translator emits one `$let` per array level, defensively wrapping
each `input` in `$ifNull: [..., []]` so a missing intermediate array
resolves cleanly to `null` at the leaf instead of erroring at runtime.

#### Example F.1: trailing dotted path crossing an object intermediate (simple-rich only)

When the trailing path (after the last `[...]`) crosses an object
intermediate before reaching a nested array, simple-rich treats the
whole trailing portion as a Mongo path expression rather than a chain
of naked hops. This works because Mongo auto-traverses objects and
auto-projects fields across arrays.

```http
GET /api/serviceOrder?sort=serviceOrderItem[A100].service.serviceCharacteristic.value
```

Lowering: one explicit hop on `serviceOrderItem` with predicate
`id == 'A100'` (default-key shorthand), and the trailing
`service.serviceCharacteristic.value` becomes the leaf path. The
translator emits:

```js
$let: {
  vars: {
    m0: { $first: { $filter: {
      input: { $ifNull: [ "$serviceOrderItem", [] ] },
      as: "c",
      cond: { $eq: [ "$$c.id", "A100" ] }
    }}}
  },
  in: "$$m0.service.serviceCharacteristic.value"
}
```

Mongo evaluates `$$m0.service.serviceCharacteristic.value` by
traversing through the `service` sub-document and projecting `.value`
across `serviceCharacteristic`'s elements. For single-element arrays
(the common TMF case where each `prodSpecCharValueUse` has one
`productSpecCharacteristicValue`), the result is effectively that
element's `value`. For multi-element arrays, Mongo's `$sort` uses the
**min** element ascending and the **max** element descending — write
`.min(value)` / `.max(value)` explicitly when you want unambiguous
multi-element semantics.

JsonPath does not accept this exact form: it requires a `[?(...)]`
predicate at every array hop and rejects naked array traversal in the
trailing path. Either drop to simple-rich for the trailing portion, or
add a second predicate (Example F).

#### Example G: aggregator functions (simple-rich only)

For arrays where multiple elements match the bracket predicate (or
where you don't want predicate-based selection at all), simple-rich
adds `min` and `max` aggregator functions:

```http
GET /api/products?sort=-characteristic[name=score].max(value)
```

For each document, take the **maximum** `value` across all
characteristics whose `name` is `score`, and order documents by that.

```http
GET /api/products?sort=+characteristic[name=score].min(value)
```

Same shape, ascending by minimum.

#### Example H: type coercion (simple-rich only)

When the leaf field is `Object`-typed across documents (a number in
some, a string in others), MongoDB's BSON sort order groups by **type
first** then value — strings cluster after numbers regardless of
intuitive ordering. Coerce to a uniform type:

```http
GET /api/products?sort=characteristic[name=value].str(value)
```

All values are coerced to string before sorting. Failures degrade to
`null` (sort order applies to the null per the asc-first /
desc-last rule). Available coercions: `str` (string), `num`
(double), `date` (BSON Date — accepts ISO-8601, numeric ms-since-epoch,
ObjectId).

Per TMF630 §4.7, the coercion wrapper may also enclose the entire
sort term. The two forms produce the same aggregation pipeline:

```http
# Inner-wrapper form
GET /api/products?sort=characteristic[name=value].num(value)

# Outer-wrapper form (equivalent)
GET /api/products?sort=num(characteristic[name=value].value)
```

Outer wrappers compose recursively, so `num(str(arr[X].leaf))` is
also accepted.

#### Example I: composing aggregator and coercion

Both orderings parse and run, but produce different results on
mixed-type input:

```http
# Recommended: coerce each element first, then aggregate
GET /api/products?sort=characteristic[name=value].max(str(value))

# Possible but rarely intended: BSON-aggregate first, then coerce result
GET /api/products?sort=characteristic[name=value].str(max(value))
```

For mixed-type fields, prefer the inner-coercion form — `max(str(...))`.
The outer form's BSON ordering ranks strings above numbers regardless
of intuitive ordering and is rarely what users mean.

#### Example J: TMF630 shorthand and `[*]` wildcard for sort

Per TMF630, `$.` may be omitted from a JsonPath sort term. Canonical
JsonPath users can also include `[*]` as the explicit projection sigil.
All four of the following parse to the same `SortPath` and produce the
same Mongo aggregation pipeline:

```http
GET /api/products?sort=$.characteristic[?(@.name == 'price')].value
GET /api/products?sort=characteristic[?(@.name == 'price')].value
GET /api/products?sort=$.characteristic[?(@.name == 'price')][*].value
GET /api/products?sort=characteristic[?(@.name == 'price')][*].value
```

The classifier identifies any sort term containing `[?(...)]` or `[*]`
as JsonPath even when `$.` is absent, and the parser strips `[*]`
outside quoted strings before lowering to the IR. This keeps URLs
interoperable with external JsonPath tooling (jsonpath.com, Jayway
evaluation) without the toolkit having to choose between
strict-prefix-required and tolerant.

#### Capability cheat-sheet — what each grammar accepts

| Construct | Plain | Simple-rich | JsonPath |
| --- | --- | --- | --- |
| Equality match | (n/a — single field) | yes | yes |
| Comparison operators (`>`, `<`, `>=`, `<=`, `!=`) | n/a | drop to JsonPath | yes |
| Logical `&&` / `\|\|` in predicate | n/a | drop to JsonPath | yes |
| Multi-level chained correlation | n/a | yes | yes |
| Predicate on a sub-array of the matched element | n/a | drop to JsonPath | yes |
| Aggregator functions (`min`, `max`) | n/a | yes | not supported |
| Coercion wrappers (`str`, `num`, `date`) | n/a | yes | not supported |
| Default-key bracket shorthand `arr[X]` | n/a | yes | n/a |
| Trailing dotted path crossing object/array intermediates (e.g. `arr[X].deep.path.value`) | n/a | yes — Mongo path auto-traversal (min/max element of multi-element arrays per `$sort` direction) | rejected (400 — JsonPath requires a predicate at every array hop) |
| JsonPath wildcard `[*]` (transparent projection) | n/a | n/a | accepted — stripped at parse time; equivalent to the same expression without `[*]` |
| Recursive descent (`..`), array slices (`[0:5]`), JsonPath functions (`length()`) | rejected | n/a | rejected (HTTP 400) |
| Trailing predicate without leaf field | n/a | n/a | rejected (HTTP 400) |
| Mixed with plain terms in one comma-separated sort | yes | yes | yes |

#### Runtime requirements

- **MongoDB 4.0 or newer.** The aggregation path uses
  `$convert ... onError`, introduced in 4.0. The cheap `find()` path
  (plain sorts only) has no version requirement beyond what the base
  toolkit already supports.
- The new module's auto-configuration registers
  `Tmf630MongoCorrelatedSortExecutor` only when a `MongoTemplate`
  bean is on the classpath; without one, the bean is not created and
  no behavior changes for non-Mongo services that happen to pull the
  dependency transitively.

#### Spring Data Mongo entity-mapping gotchas (worth knowing)

If you hand-write or generate entity classes for a Mongo collection
(typical for a read-only "proxy" service that filters/sorts data
written by a different application), two Spring Data idiosyncrasies
bite specifically when you start using nested-array predicates in
correlated sort or filter.

##### Gotcha 1: name-based id-property auto-promotion on nested classes

Spring Data Mongo's id-property detection runs on **every persistent
entity, root or nested**:

1. If a property is annotated `@Id` → it's the id, mapped to `_id`.
2. Otherwise, if a property is literally named `id` → **promote it to
   id**, also mapped to `_id`.

Rule 2 historically caused JsonPath / simple-rich predicates referencing
`@.id == 'X'` / `[id=X]` to silently fail to match nested objects, since
the BSON field had been renamed to `_id`. **As of 2.0.1, this is
handled transparently by the toolkit** — `MongoFieldResolver` consults
your `MongoMappingContext` at translation time and rewrites the user-
facing path to whatever BSON name your entity declares. You can write
predicates against `id` regardless of whether the nested field has
`@Field("id")` or relies on the default auto-promotion.

The mitigations below remain useful as **awareness items** — they
explain *why* the resolver does what it does — but for typical Spring
Data Mongo entities, no consumer-side configuration is required.

##### Diagnose first — what does your writer actually store?

Before picking a mitigation, **inspect a real document.** The fix
depends on which stack wrote the data:

```javascript
// In mongo shell, against the actual collection:
db.productOffering.findOne({}, { _id: 0 })
```

Look at one nested object. Is the inner key called `id` or `_id`?

| Writer stack | Nested field stored as | Reader's job |
| --- | --- | --- |
| Spring Data Mongo (Java/Kotlin) | `_id` | Either alias `_id` ↔ `id` on read, or write JsonPath/simple-rich predicates against `_id` directly |
| Anything else (Node, Python, Go, raw shell, vendor binary) | `id` | Tell Spring Data Mongo NOT to auto-remap, so `@.id` stays `id` end to end |

You will not pick the right mitigation by guessing. Run the diagnostic.

##### Mitigation A: surgical `@Field("id")` (writer doesn't remap)

When the BSON keeps nested `id` as `id`, annotate every nested class's
`id` field on the reader so Spring Data does not auto-promote it to
`_id`:

```java
public class ProductSpecificationRef {
  @Field("id") String id;     // reference, not an entity identity
  String version;
  String href;
  String name;
}
```

Pros: locality — the exception is visible at the field. No global
behavior change.
Cons: easy to forget when adding a new entity class. Audit on every
new model.

##### Mitigation B: global "only `@Id` counts" override (writer doesn't remap)

If you'd rather rule out name-based auto-promotion entirely, replace
the default `MongoMappingContext` with one that only honors `@Id`:

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
Cons: now you must be **explicit about the top-level `_id`** — every
root entity's identity property has to be `@Id`-annotated (or
`@Field("_id")`). For composite-key resources like TMF620
ProductOffering (where `id + version` is the composite identity),
this is actually a feature: a bare `id` field stays as `id` and the
composite is whatever you choose to make `@Id`-annotated.

##### Mitigation C: alias `_id` → `id` on read (writer does remap)

When the writer is also Spring Data Mongo and the BSON has `_id` on
nested objects, neither `@Field("id")` nor a mapping-context override
will help — the field truly isn't `id` in storage. The cleanest fix
is a custom read-side converter that aliases nested `_id` back to
`id` for non-root entities. This keeps every JsonPath / simple-rich
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

Wire it into `MongoCustomConversions` and the
`MappingMongoConverter`. The exact registration depends on your
read-path needs (you may want to scope this to nested-only, leaving
root `_id` intact). Treat the snippet as a starting point and verify
with an integration test against real data.

##### Mitigation D: bake `@Field("id")` into your code generator

If you generate models with `openapi-generator-maven-plugin`, the
post-generation step should add `@Field("id")` to every property
named `id` automatically. A small Mustache template override in
`src/main/resources/openapi/templates/pojo.mustache` is enough — flag
the generator with a `vendorExtensions.x-is-id-field` rule and emit
the annotation conditionally. One-time template work, every nested
id annotated forever.

##### Gotcha 2: the `_class` discriminator field

Spring Data writes the FQN of the Java class to `_class` by default.
For a read-only proxy that reads documents written by a different
service, you can decouple from the writer's package layout by
overriding the `MappingMongoConverter`'s type mapper:

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

This sidesteps polymorphic-type FQN matching entirely; Spring Data
falls back to the call-site class for every read. Safe for read-only
proxies that don't have polymorphic field types keyed on `_class`.

## Reference

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

How this works in practice: nested-path policy is backend-aware through separate properties (`allow-nested-paths-jpa` and `allow-nested-paths-docdb`). In mixed JPA+Mongo applications, use explicit allowlists for tighter control.

### `filter=` JsonPath syntax (Jayway 3.x)

[Jayway JsonPath 3.x](https://github.com/json-path/JsonPath) is used **solely** as a syntax validator: the library calls `JsonPath.compile()` to verify that the incoming `filter=` expression is syntactically valid JsonPath. Jayway is **never** used to evaluate the expression against a result array at runtime. Once the expression passes Jayway's syntax check, the library's own parser takes over, translates the expression into a QueryDSL `Predicate` (or a MongoDB `$elemMatch` for array-correlation patterns), and the database executes the query natively. Only the **restricted subset** listed below is accepted by the library's parser — anything not listed here will return `400 Bad Request`.

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
      {"name": "ORDER_REFERENCE", "id": "OPCO-ORDER-012"}
    ]
  }
]
```

#### Simple field equality

```
$[?(@.status == 'active')]
$[?(@.status != 'active')]
```

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

Matches rows where the named field is missing or `null`. Useful for
"absent attribute" filtering without needing to compare against
`null` explicitly. Translates to the toolkit's `IS_NULL` predicate.
Negation of array-match subforms — e.g. `!@.externalReference[?(...)]`
— is not supported.

#### Logical AND / OR

```
$[?(@.status == 'active' && @.name == 'Fiber 100Mbps')]
$[?(@.status == 'active' || @.status == 'suspended')]
$[?((@.status == 'active' || @.status == 'suspended') && @.category == 'broadband')]
```

Parentheses control grouping. The library supports arbitrary nesting depth.

#### Nested field access

```
$[?(@.externalReference.name == 'ORDER_REFERENCE')]
```

Subject to allowlist and nested-path configuration (`allowNestedPathsJpa`, `allowNestedPathsDocdb`).

#### Combining attribute filtering with `filter=`

Attribute query parameters and `filter=` are merged into a single predicate:

```http
GET /api/orders?category.eq=broadband&filter=$[?(@.status == 'active' && @.price > 10)]
```

Default merge is `AND`. Override per request:

```http
GET /api/orders?category.eq=broadband&filter=$[?(@.status == 'suspended')]&filter.combineWithAttributes=OR
```

#### Correlated multi-field matching in nested arrays (Mongo only)

When you need to ensure that **the same** nested array element satisfies multiple conditions (e.g. `name == 'ORDER_REFERENCE'` **and** `id == 'OPCO-ORDER-012'` on the same `externalReference` entry), use a nested filter:

```
$[?(@.externalReference[?(@.name == 'ORDER_REFERENCE' && @.id == 'OPCO-ORDER-012')])]
```

This library translates the nested `[?(...)]` pattern to a MongoDB `$elemMatch` query, ensuring strict **same-element** semantics. If `name` and `id` come from different array elements, the document does **not** match.

> **JPA note:** Array-correlation patterns are intentionally rejected with `400 Bad Request` on JPA backends, since relational databases do not have a direct `$elemMatch` equivalent.

> **Warning — uncorrelated matching:**
> Using separate attribute parameters like `externalReference.name.eq=ORDER_REFERENCE&externalReference.id.eq=OPCO-ORDER-012` does **not** guarantee that both values come from the same array element. An object with `name=ORDER_REFERENCE` on one reference and `id=OPCO-ORDER-012` on a **different** reference would be a false positive. Always use the nested `[?(...)]` form for correlated conditions on Mongo backends.

#### What this library does NOT support

The following Jayway JsonPath features are **not** part of this library's restricted `filter=` subset and will return `400 Bad Request`:

- Regex match (`=~`)
- `IN` / `NIN` (use attribute-level `.in` / `.nin` operators instead)
- `SIZE`, `EMPTY`, `CONTAINS` (Jayway-specific operators)
- Exists check (`$[?(@.field)]`)
- Recursive descent operator (`..`)
- Functions (`length()`, `count()`, etc.)
- Script expressions

For set operations, null checks, pattern matching, and other advanced filtering, use the attribute-level query parameters (`.eq`, `.ne`, `.in`, `.nin`, `.like`, `.likei`, `.isnull`, `.isnotnull`, `.regex`, etc.) which provide full coverage of these use cases.

### Mongo `filter=` support scope

Supported subset (Mongo/document backends):

- wrapper form: `$[?(...)]` (canonical), `[?(...)]` (bare wrapper, TMF630 shorthand), or `<arrayPath>[?(...)]` / `$.<arrayPath>[?(...)]` (sub-array correlation shorthand, rewritten to the canonical correlated form)
- logical operators: `&&`, `||`
- grouping with parentheses
- comparisons: `==`, `!=`, `>`, `>=`, `<`, `<=`
- literals: string, number, boolean, `null`
- field paths: `@.field`, `@.nested.field`
- array correlation: `@.arrayField[?(...)]` with strict same-element semantics via `$elemMatch`
- JsonPath wildcard `[*]` is accepted as a transparent projection sigil (stripped at parse time; equivalent to the same expression without `[*]`)
- merge with attribute filtering in the same request:
  - default: `AND`
  - override: `filter.combineWithAttributes=OR`

Not supported yet:

- full JsonPath language/functions (only restricted subset above)
- nested array filters inside array filters in a single expression
- broader wildcard/function/script-style JsonPath constructs

Maturity note:

- the supported subset is intended for production use
- behavior is intentionally constrained for predictable parsing and backend translation

### JPA `filter=` support scope

Key capability:

- JPA clients can express complex grouped where-conditions using `filter=` with parentheses, `&&`, and `||` for non-array-correlation scenarios.

Supported subset (JPA backends):

- wrapper form: `$[?(...)]` (canonical), `[?(...)]` (bare wrapper, TMF630 shorthand), or `<arrayPath>[?(...)]` / `$.<arrayPath>[?(...)]` (sub-array correlation shorthand, rewritten to the canonical correlated form)
- logical operators: `&&`, `||`
- grouping with parentheses
- comparisons: `==`, `!=`, `>`, `>=`, `<`, `<=`
- literals: string, number, boolean, `null`
- field paths: `@.field`, `@.nested.field` (subject to allowlist and nested-path configuration)
- merge with attribute filtering in the same request:
  - default: `AND`
  - override: `filter.combineWithAttributes=OR`

Not supported in JPA:

- array-correlation patterns such as `@.arrayField[?(...)]`
- these are intentionally rejected with `400 Bad Request`

### When `filter=` throws `400 Bad Request`

The library throws a filtering exception (mapped to HTTP `400`) for unsupported or invalid `filter=` usage, including:

- expression is not a filter wrapper (must be `$[?(...)]`)
- expression has invalid JsonPath syntax
- unsupported operators/tokens are used
- unsupported literal forms are used
- null is used with unsupported operators (for example `> null`)
- more than one `filter` parameter is sent
- `filter.combineWithAttributes` has invalid value (must be `AND` or `OR`) or appears multiple times
- unknown/disallowed field paths when unknown-field behavior is `REJECT`
- nested path usage when nested paths are disabled
- array-correlation usage on JPA backends
- `filter=` exceeds configured max length
- JsonPath filter feature is disabled by configuration

## POC databases used

The current proof-of-concept and integration coverage has been verified with these databases:

- PostgreSQL `18.1-alpine` (via Testcontainers `jdbc:tc:postgresql:18.1-alpine:///db`) for JPA/SQL predicate and SQL reflection tests
- H2 (in-memory) for lightweight JPA-based integration scenarios
- MongoDB `8.0.5` (via Testcontainers `mongo:8.0.5`) for document-oriented filtering, including JsonPath array-correlation behavior
- MariaDB `11.4.4` (via Testcontainers profile `it-mariadb`) for SQL/JPA compatibility validation
- Microsoft SQL Server `2022-CU14-ubuntu-22.04` (via Testcontainers profile `it-mssql`) for SQL/JPA compatibility validation
- Oracle XE `21-slim-faststart` (via Testcontainers profile `it-oracle`) for dedicated SQL/JPA validation
- IBM DB2 `11.5.0.0a` (via Testcontainers profile `it-db2`) for dedicated SQL/JPA validation

Notes:

- JsonPath array-correlation in `filter=` is supported for Mongo/document backends.
- The same array-correlation pattern is intentionally rejected for JPA backends (HTTP `400`).
- MariaDB coverage is used as a practical compatibility indicator for the MySQL family due to shared lineage and behavior.
- Microsoft SQL Server coverage is used as a practical compatibility indicator for Sybase-family behavior due to shared historical lineage.

## Build

```bash
mvn clean install
```

`install` (rather than `verify`) is recommended because the toolkit is a
multi-module project and the modules depend on each other transitively in
the local Maven repository. `install` writes the built `-SNAPSHOT`
artifacts to `~/.m2/repository`, making them resolvable from downstream
projects on the same machine. `verify` runs all tests and integration
tests but stops before installation, so a downstream project building
against `2.0.1-SNAPSHOT` would not find the artifacts.

## License

Apache License 2.0.
