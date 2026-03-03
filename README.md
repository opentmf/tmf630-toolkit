# tmf630-toolkit

`tmf630-toolkit` is the adaptation of TMF-630 REST API Design Guidelines for Spring Web MVC.

When a Spring Boot based microservice references this library, it automatically gains TMF630-style paging/sorting and advanced filtering capabilities through configuration, without writing custom query parsing logic. Using the library from other Spring-based projects is also possible but requires explicit bean wiring.

Clients can request "only records 51-100", "sort by surname descending", or "find people born after 1990 whose surname starts with D" using standard query parameters.
In addition to the query parameters, clients can also use a JsonPath in the optional `filter=` parameter to express richer grouped conditions (parentheses, AND/OR, comparison operators) in a single query expression. When the backend is a document database (like MongoDB), this filter further allows array correlation.

Release notes and version history are available in [`CHANGELOG.md`](./CHANGELOG.md).

## What you get

- TMF630-style paging and sorting (`offset`, `limit`, `sort`)
- Attribute filtering mapped to QueryDSL `Predicate`
- JsonPath-based `filter=` support merged into the same QueryDSL predicate pipeline
- Strict same-element array correlation for document backends (Mongo `$elemMatch` translation)
- Range-aware response helpers (`Content-Range`, `X-Total-Count`, `X-Result-Count`)
- Field selection utility (`fields=` support) for response shaping
- Works in both Spring Boot and plain Spring projects

## Module layout

| Module                                             | Description                                                      |
|----------------------------------------------------|------------------------------------------------------------------|
| `tmf630-toolkit-paging-sorting-core`               | Paging, sorting, field selection (no Boot dependency)            |
| `tmf630-toolkit-paging-sorting-autoconfigure`      | Spring Boot auto-configuration for paging/sorting                |
| `tmf630-toolkit-attribute-filtering-core`          | Attribute filtering to QueryDSL `Predicate` (no Boot dependency) |
| `tmf630-toolkit-attribute-filtering-autoconfigure` | Spring Boot auto-configuration for filtering                     |
| `tmf630-toolkit-all`                               | Convenience artifact depending on both autoconfigure modules     |

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

## Then choose your dependencies

### Spring Boot (recommended one-liner)

```xml
<dependency>
  <groupId>org.opentmf.query</groupId>
  <artifactId>tmf630-toolkit-all</artifactId>
</dependency>
```

### Spring Boot (pick only what you need)

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

Dependency versions are aligned via Spring Boot BOM `3.5.10`.

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

## Configuration prefixes

| Prefix                               | Purpose                        |
|--------------------------------------|--------------------------------|
| `opentmf.tmf630.paging`              | Paging/sorting behavior        |
| `opentmf.tmf630.attribute-filtering` | Query filter parsing and rules |

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
- `opentmf.tmf630.attribute-filtering.on-unknown-field` (`REJECT` or `IGNORE`)
- `opentmf.tmf630.attribute-filtering.on-unknown-operator` (`REJECT` or `IGNORE`)
- `opentmf.tmf630.attribute-filtering.json-path-filter.enabled` (default: `true`)
- `opentmf.tmf630.attribute-filtering.json-path-filter.max-length` (default: `2048`)

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

```java
@RestController
@RequestMapping("/api/persons")
class PersonController {

  private final PersonRepository repository;

  PersonController(PersonRepository repository) {
    this.repository = repository;
  }

  @GetMapping
  ResponseEntity<List<Person>> search(
      @QuerydslPredicate(root = Person.class) Predicate predicate,
      Pageable pageable) {
    Page<Person> page = repository.findAll(predicate, pageable);
    return Tmf630Util.tmfPage(page);
  }
}
```

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
| `in`            | in set (multi-value)           | `surname.in=Doe&surname.in=Brown`                           |
| `nin`           | not in set (multi-value)       | `surname.nin=Smith&surname.nin=Jones`                       |
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
- `between`, `in`, `nin` are multi-value operators and should be sent as repeated query params.
- unknown fields/operators are validated by `on-unknown-field` and `on-unknown-operator`.

### 6) Enum field resolution

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

### 7) Combined query examples

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

#### Mongo `filter=` support scope (current state)

Supported subset (Mongo/document backends):

- wrapper form: `$[?(...)]`
- logical operators: `&&`, `||`
- grouping with parentheses
- comparisons: `==`, `!=`, `>`, `>=`, `<`, `<=`
- literals: string, number, boolean, `null`
- field paths: `@.field`, `@.nested.field`
- array correlation: `@.arrayField[?(...)]` with strict same-element semantics via `$elemMatch`
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

#### JPA `filter=` support scope (current state)

Key capability:

- JPA clients can express complex grouped where-conditions using `filter=` with parentheses, `&&`, and `||` for non-array-correlation scenarios.

Supported subset (JPA backends):

- wrapper form: `$[?(...)]`
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

#### When `filter=` throws `400 Bad Request`

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

### 8) Field selection utility (optional helper)

`FieldSelectionUtil` can map objects into filtered `Map<String, Object>` views, useful when clients request specific fields.

Package:

- `org.opentmf.query.commons.fieldselection.FieldSelectionUtil`

Example:

```java
Map<String, Object> result = FieldSelectionUtil.fieldsToMap(person, "id,name,surname");
```

Controller example:

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

### 9) Full combined scenario (filter + paging + sorting + field selection + range statuses)

This section combines all major capabilities in one flow.

Controller example for combined usage:

```java
@RestController
@RequestMapping("/api/persons")
class PersonCombinedController {

  private final PersonRepository repository;

  PersonCombinedController(PersonRepository repository) {
    this.repository = repository;
  }

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

## Build

```bash
mvn clean verify
```

## License

Apache License 2.0.
