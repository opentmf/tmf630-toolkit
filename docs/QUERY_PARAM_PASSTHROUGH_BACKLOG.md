# Backlog: per-endpoint query-parameter pass-through for `@QuerydslPredicate` resolution

**Status:** backlog (not scheduled). **Raised:** 2026-08-27, during
`opentmf-outbox-service` 1.2.0. **Decision of record:** the consumer that
needed it used a PATH variable instead (`GET /ops/outbox/state/{state}`), so
nothing ships on this item today. This document exists so the next person who
hits the same wall finds the analysis, the rejected alternatives and the
intended design instead of re-deriving them.

## 1. The behaviour that surprised us

`Tmf630PredicateArgumentResolver` resolves a handler's `Predicate` argument
from **the entire query-parameter map**:

```java
// tmf630-toolkit-attribute-filtering-core/.../Tmf630PredicateArgumentResolver.java:74
return buildPredicate(rootEntity, webRequest.getParameterMap());
```

`Tmf630FilterParser` treats every parameter as a TMF-630 attribute filter
except a **hard-coded** reserved set:

```java
// Tmf630FilterParser.java:35-38
private static final Set<String> RESERVED_PARAMS =
    Set.of("page", "size", "sort", "offset", "limit", "fields", "depth", "expand");
private static final String FILTER_PARAM = "filter";
private static final String FILTER_COMBINE_PARAM = "filter.combineWithAttributes";
```

An attribute name that does not resolve on the `@QuerydslPredicate(root=…)`
entity goes through `handleUnknownField`, and under the default
`opentmf.tmf630.attribute-filtering.on-unknown-field: REJECT` throws
`TmfFilteringException` → HTTP 400 — **in the argument resolver, before the
controller method runs**.

Consequence: a handler such as

```java
@GetMapping("/ops/outbox")
@Tmf630Response
Page<OutboxRowView> list(
    @QuerydslPredicate(root = OutboxEvent.class) Predicate predicate,
    @RequestParam(required = false) String state,   // NOT an entity field
    Pageable pageable)
```

can never receive `?state=parked`: `state` is parsed as a filter on
`OutboxEvent`, does not resolve, and the request is rejected. Declaring the
`@RequestParam` does not help — the predicate resolver does not consult the
handler's other parameters, only the reserved-name set.

## 2. Why we wanted it

`opentmf-outbox-service` exposes an operations list whose most useful
dimension — the row's *state* (`pending | parked | relayed | cancelled`) —
is **derived** from three nullable timestamps (`relayed_on`, `cancelled_on`,
`parked_on`), not a column. TMF-630 attribute filtering cannot express
"column is null" portably, and a derived state is exactly the kind of
non-entity selector an ops endpoint wants beside the entity filters:

```
GET /ops/outbox?state=parked&destination=comm.requests.v1&size=20
```

The plans and runbooks of four services were written assuming this shape.
It is the natural form; every REST developer will write it first.

## 3. Alternatives evaluated and rejected

| Option | Why rejected |
|---|---|
| `opentmf.tmf630.attribute-filtering.on-unknown-field: IGNORE` in the consumer | Service-**global** (`Tmf630AttributeFilteringProperties`, one `@ConfigurationProperties` bean, no per-endpoint scope). It makes every business list endpoint of that service return an **unfiltered 200** for a mistyped filter name instead of 400 — the client/server validation-parity contract the estate pins in its ITs and its QA suites assert. Every DNMS service explicitly sets `REJECT` for that reason; a library cannot require a consumer to flip it. |
| Add a `state` column to the entity | Duplicates derived state; the outbox's design keeps state derived on purpose (no status column to drift). |
| A `@Transient`/`@Formula` field named `state` | Not resolvable by the QueryDSL path resolver; still rejected. |
| Widening `RESERVED_PARAMS` with `state` | Global and name-specific; the next endpoint wants `mode`, `view`, `format`… Reserved names must stay the TMF-630 vocabulary. |
| **Path variable** `GET /ops/outbox/state/{state}` | **Adopted.** The resolver never sees path variables; entity filters and paging still apply on top; unknown state → 400 from the handler. Costs one extra route per endpoint that needs a non-entity selector. |

## 4. Intended design (when scheduled)

A per-endpoint, per-parameter **pass-through allowance** the handler declares
and the resolver honours for that request only:

```java
@GetMapping("/ops/outbox")
@Tmf630Response
@Tmf630PassThrough({"state"})               // names the resolver must NOT parse as filters
Page<OutboxRowView> list(
    @QuerydslPredicate(root = OutboxEvent.class) Predicate predicate,
    @RequestParam(required = false) String state,
    Pageable pageable)
```

- `Tmf630PredicateArgumentResolver.resolveArgument` reads the annotation from
  `parameter.getMethod()` (or `parameter.getMethodAnnotation(...)`) and passes
  the names to `Tmf630FilterParser` as an additional reserved set for that
  call — the existing `RESERVED_PARAMS` check at `Tmf630FilterParser.java:83`
  becomes `RESERVED_PARAMS.contains(rawKey) || passThrough.contains(rawKey)`.
- Alternative placement: an attribute on the toolkit's own annotation, e.g.
  `@Tmf630Response(passThrough = {"state"})`, if a second annotation is
  considered noise. Either way the allowance is **local to the handler** —
  never a property, never global.
- Auto-derive option (nice-to-have): treat every `@RequestParam` name declared
  on the same handler as pass-through automatically. Explicit is preferred:
  a declared-but-forgotten `@RequestParam` would otherwise silently punch a
  hole in strict filtering.
- Semantics: a passed-through name is simply not a filter. It does not
  loosen `on-unknown-field` for any other name. Size: ~20 lines + tests.
- Tests: pass-through name accepted while an unrelated unknown name still
  400s on the same request; annotation absent ⇒ behaviour identical to today
  (the 1045-test suite stays green); ArchUnit/`ApiContractTests` in the
  service template unaffected.
- Version: additive → a `3.x` minor of tmf630-toolkit; the first consumer
  would be `opentmf-outbox-service` (replace the path form or offer both).

## 5. Trigger to schedule it

Schedule when a second endpoint in the estate needs a non-entity query
selector beside a `@QuerydslPredicate` — i.e. when the path form starts
multiplying routes. Until then the path form is the ruled contract.
