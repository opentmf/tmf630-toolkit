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
