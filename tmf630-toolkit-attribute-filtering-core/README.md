# tmf630-toolkit-attribute-filtering-core

TMF630 attribute filtering to QueryDSL `Predicate` via query parameters. No Spring Boot dependency.

## Usage

```java
@GetMapping("/transformations")
public Page<Transformation> search(
    @QuerydslPredicate(root = Transformation.class) Predicate predicate,
    Pageable pageable) {
  return repository.findAll(predicate, pageable);
}
```

Examples: `?createdOn.gte=2026-01-01T00:00:00Z`, `?transformationId.eq=abc`, `?transformationId=abc` (implicit eq)

Non-Boot users: wire `Tmf630PredicateArgumentResolver` and related beans manually.

## Optional `filter=` support (restricted JsonPath filter)

The resolver can also parse a `filter` query parameter and merge it with attribute filters into one predicate.

Supported JsonPath filter subset:

- expression wrapper: `$[?(...)]`
- logical operators: `&&`, `||`
- grouping with parentheses
- comparisons: `==`, `!=`, `>`, `>=`, `<`, `<=`
- left side path format: `@.field` or `@.nested.field`
- literal values: quoted strings, numbers, booleans, `null`
- array correlation syntax: `@.arrayField[?(...)]`

Array correlation behavior:

- Mongo/document backends: translated to explicit Mongo `$elemMatch` with strict same-element semantics
- JPA backends: array-correlation patterns in `filter=` are rejected with `400`

Example:

`?transformationId.eq=abc&filter=$[?(@.status == 'NEW' && @.priority >= 1)]`

Array-correlation examples:

- positive (same element): `?filter=$[?(@.externalReference[?(@.name == 'ORDER_REFERENCE' && @.id == 'OPCO-ORDER-012')])]`
- negative (cross-element mismatch): `?filter=$[?(@.externalReference[?(@.name == 'MARKET_ACCOUNT_ID' && @.id == 'OPCO-ORDER-012')])]`

Merge behavior:

- default merge between attribute filters and `filter` is `AND`
- request-level override: `filter.combineWithAttributes=AND|OR`

If `filter` is invalid or outside the supported subset, the resolver throws `TmfFilteringException` (mapped to HTTP `400` in Boot integration).
