# Per-endpoint query-parameter pass-through (`@Tmf630PassThrough`)

**Status:** SHIPPED in **3.2.0** (2026-09-12), together with the companion sort-key
validation in §6. **Raised:** 2026-08-27, during `opentmf-outbox-service` 1.2.0, whose
consumer adopted a path variable (`GET /ops/outbox/state/{state}`) in the meantime.
**Scheduled:** 2026-09-12, when the estate ruled that an unknown filter field answers `400`
on every TMF-630 list endpoint. That ruling left `dnms-catalog` — whose branch-scoped lists
take a mandatory `version` selector that is not an entity property — needing a second
non-entity selector beside a `@QuerydslPredicate`, which was this document's trigger.

Sections 1–3 are the original analysis, kept as the record of why the feature has the shape
it has.

## 1. The behaviour that surprised us

`Tmf630PredicateArgumentResolver` resolves a handler's `Predicate` argument from **the entire
query-parameter map**, and `Tmf630FilterParser` treats every parameter as a TMF-630 attribute
filter except a **hard-coded** reserved set:

```java
// Tmf630FilterParser
private static final Set<String> RESERVED_PARAMS =
    Set.of("page", "size", "sort", "offset", "limit", "fields", "depth", "expand");
private static final String FILTER_PARAM = "filter";
private static final String FILTER_COMBINE_PARAM = "filter.combineWithAttributes";
```

An attribute name that does not resolve on the `@QuerydslPredicate(root=…)` entity goes
through `handleUnknownField`, and under the default
`opentmf.tmf630.attribute-filtering.on-unknown-field: REJECT` throws `TmfFilteringException` →
HTTP 400 — **in the argument resolver, before the controller method runs**.

Consequence: a handler such as

```java
@GetMapping("/ops/outbox")
@Tmf630Response
Page<OutboxRowView> list(
    @QuerydslPredicate(root = OutboxEvent.class) Predicate predicate,
    @RequestParam(required = false) String state,   // NOT an entity field
    Pageable pageable)
```

could never receive `?state=parked`: `state` was parsed as a filter on `OutboxEvent`, did not
resolve, and the request was rejected. Declaring the `@RequestParam` did not help — the
predicate resolver does not consult the handler's other parameters, only the reserved-name
set.

## 2. Why we wanted it

`opentmf-outbox-service` exposes an operations list whose most useful dimension — the row's
*state* (`pending | parked | relayed | cancelled`) — is **derived** from three nullable
timestamps (`relayed_on`, `cancelled_on`, `parked_on`), not a column. A derived state is
exactly the kind of non-entity selector an ops endpoint wants beside the entity filters:

```
GET /ops/outbox?state=parked&destination=comm.requests.v1&size=20
```

## 3. Alternatives evaluated and rejected

| Option | Why rejected |
|---|---|
| `opentmf.tmf630.attribute-filtering.on-unknown-field: IGNORE` in the consumer | Service-**global** (`Tmf630AttributeFilteringProperties`, one `@ConfigurationProperties` bean, no per-endpoint scope). It makes every business list endpoint of that service return an **unfiltered 200** for a mistyped filter name instead of 400 — the client/server validation-parity contract the estate pins in its ITs and QA suites. A library cannot require a consumer to flip it. |
| Add a `state` column to the entity | Duplicates derived state; the outbox keeps state derived on purpose (no status column to drift). |
| A `@Transient`/`@Formula` field named `state` | Not resolvable by the QueryDSL path resolver; still rejected. |
| Widening `RESERVED_PARAMS` with `state` (or `version`) | Global and name-specific; the next endpoint wants `mode`, `view`, `format`… Reserved names must stay the TMF-630 vocabulary. `version` in particular is a real attribute of many TMF resources (e.g. `ProductOffering.version`): reserving it would silently stop `?version=` filtering for every consumer. |
| **Path variable** `GET /ops/outbox/state/{state}` | Adopted as the interim. The resolver never sees path variables; entity filters and paging still apply on top. Costs one extra route per endpoint that needs a non-entity selector. |

## 4. Design as shipped (3.2.0)

A per-handler, per-parameter **pass-through allowance** the handler declares and both filter
resolvers honour for that request only:

```java
@GetMapping("/ops/outbox")
@Tmf630Response
@Tmf630PassThrough({"state"})               // names the resolver must NOT parse as filters
Page<OutboxRowView> list(
    @QuerydslPredicate(root = OutboxEvent.class) Predicate predicate,
    @RequestParam(required = false) String state,
    Pageable pageable)
```

- `@Tmf630PassThrough` (`tmf630-toolkit-attribute-filtering-core`, `ElementType.METHOD`).
  Read through `MethodParameter#getMethodAnnotation` (`Tmf630PassThroughNames.of`), which on a
  Spring MVC handler parameter is the handler method's merged lookup — an annotation on the
  API interface method is honoured.
- `Tmf630FilterParser#parse(root, params, passThrough)`; the 2-argument form delegates with an
  empty set. `isReservedKey` also honours the set. A handler that names `filter` /
  `filter.combineWithAttributes` also takes those away from the JsonPath handling — a
  passed-through name is simply not part of the filter surface.
- Both terminals: `Tmf630PredicateArgumentResolver`, and `Tmf630JsonbClauseArgumentResolver`
  through a `Tmf630JsonbClauseBuilder#build(domainType, params, passThrough)` overload.
- Exact names only (`state.eq` is still a filter key). It does not loosen `on-unknown-field`
  for any other name. Nothing global, no property, `RESERVED_PARAMS` unchanged.
- Auto-deriving pass-through names from the handler's `@RequestParam`s was rejected: a
  declared-but-forgotten `@RequestParam` would silently punch a hole in strict filtering.

## 5. Test plan (as implemented)

| Case | Where |
|---|---|
| Pass-through name is left to the handler, never parsed as a filter | `Tmf630PredicateArgumentResolverTest#passThroughNameIsLeftToTheHandlerAndNeverParsedAsFilter` |
| An unknown name beside a pass-through name is still rejected under `REJECT` | `Tmf630PredicateArgumentResolverTest#unknownNameBesideAPassThroughNameIsStillRejected` |
| Exact name only — `version.eq` is still a filter key | `Tmf630PredicateArgumentResolverTest#passThroughMatchesTheExactParameterNameOnly` |
| Annotation absent → behaviour identical to before | `Tmf630PredicateArgumentResolverTest#withoutTheAnnotationThePassThroughNameIsStillParsedAsFilter` |
| A pass-through name shadows an entity field on that handler | `Tmf630PredicateArgumentResolverTest#passThroughNameShadowsAnEntityFieldOnThatHandler` |
| `filter` / `filter.combineWithAttributes` can be passed through | `Tmf630PredicateArgumentResolverTest#passedThroughFilterNamesAreNotReadAsJsonPathFilter` |
| Annotation on the **API interface** method, QueryDSL terminal, `DENY_ALL` allowlist | `Tmf630PredicateArgumentResolverIT#passThroughDeclaredOnApiInterfaceReachesHandlerNotFilterGrammar`, `#passThroughDoesNotLoosenTheAllowlistForOtherNames`, `#passThroughNameIsStillAFilterOnHandlersThatDoNotDeclareIt` |
| Annotation on the **API interface** method, **both terminals** (JPA/QueryDSL and JSONB), `ALLOW_ALL` + `REJECT` | `Tmf630JsonbUrlBindingParityIT#passThroughNameReachesHandlerNotFilterGrammar`, `#unknownNameBesidePassThroughStillRejected`, `#passThroughIsExactNameOnly` (each parameterized over `/parity/scoped/jpa` and `/parity/scoped/jsonb`) |
| Absent on both terminals → the same name is an unknown field (400) | `Tmf630JsonbUrlBindingParityIT#sameUrlSameRejection` ("pass-through name on a handler WITHOUT @Tmf630PassThrough") |

## 6. Companion in 3.2.0: unknown plain sort keys against the filter root

The same estate ruling reads naturally as "unknown anything → 400", and `sort=` did not follow
it: the toolkit's sort parser never checked a plain key against the entity (only the nesting
switch and the global `sort-allowlist`), so an unknown key failed inside Spring Data on JPA
(`PropertyReferenceException` → `500` through a service's catch-all) and was silently ignored
by Mongo and the JSONB executor. 3.2.0 validates plain sort keys against the handler's
**filter root**:

- `TmfSortKeyValidator` (SPI, `tmf630-toolkit-paging-sorting-core`) is called by every
  resolver that parses `sort=` — `Pageable`, `TmfRichPageable`, `Sort`, `TmfSort` — after the
  nesting and allowlist gates, with the PLAIN keys only. It is an SPI because
  paging-sorting-core cannot depend on attribute-filtering-core (the reverse dependency
  already exists).
- `FilterRootSortKeyValidator` (`tmf630-toolkit-attribute-filtering-core`) finds the root on a
  sibling parameter through `Tmf630FilterRootLocator`s — `@QuerydslPredicate(root)` built in,
  `@Tmf630JsonbFilter(root)` contributed by the jsonb module — read through Spring's
  `AnnotatedMethod`, so an API-interface declaration counts. It resolves each key with the
  filter grammar's `FieldPathResolver` and answers `400` (*"Unknown sort property: `<key>`"*)
  on a miss.
- **A handler without a filter root is NOT validated and behaves exactly as before 3.2.0**:
  JPA → `PropertyReferenceException` (500 through a catch-all); Mongo and JSONB → the unknown
  key is ignored. 3.2.0 is not "unknown sort key → 400 everywhere". The toolkit's own
  `Tmf630JsonbSubResourceController.listChildren` (a `Pageable`-only handler) is such a case.
- Correlated terms and the correlated executors are untouched. Opt-out: a consumer
  `TmfSortKeyValidator` bean (e.g. `TmfSortKeyValidator.NONE`).

| Case | Where |
|---|---|
| Unknown key → 400 on JPA (Spring Data Querydsl path) and JSONB, plain and API-interface handlers | `Tmf630JsonbUrlBindingParityIT#unknownSortKeyRejectedAgainstFilterRoot` (4 endpoints) |
| Unknown key → 400 on Mongo (`TmfRichPageable`) | `Tmf630PredicateMongoIT#rejectsUnknownPlainSortKeyAgainstTheFilterRootOnMongo` |
| A valid key orders exactly as before | `Tmf630JsonbUrlBindingParityIT#knownSortKeyOrdersAsBefore` |
| Nesting switch and allowlist unchanged, and checked first | `Tmf630JsonbUrlBindingParityIT#nestedSortKeyStillRejectedByTheNestingSwitch`, `TmfSortKeyValidatorResolverTest#nestingAndAllowlistStillRejectBeforeTheValidatorIsAsked` |
| No filter root → today's behaviour (JPA 500, JSONB ignored, valid keys still sort) | `Tmf630JsonbUrlBindingParityIT#handlerWithoutFilterRootKeepsJpaBehaviour`, `#handlerWithoutFilterRootKeepsJsonbBehaviour`, `#handlerWithoutFilterRootStillSortsKnownKeys` |
| Only PLAIN keys reach the validator; every resolver applies it; legacy constructors accept every key | `TmfSortKeyValidatorResolverTest` |
| Root on an API-interface parameter; nested and inherited fields; custom locators | `FilterRootSortKeyValidatorTest` |
| SPI wiring through the paging autoconfiguration; opt-out bean | `Tmf630PagingAutoConfigurationIT#contributedSortKeyValidatorReachesThePageableAndSortResolvers`, `Tmf630AttributeFilteringAutoConfigurationTest` |
