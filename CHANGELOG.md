# Changelog

All notable changes to `tmf630-toolkit` are documented in this file.

## [3.0.0] - 2026-07-26

### Added (JPA gap closure — v3.0.0 Phase (a))

- **New module `tmf630-toolkit-jpa-correlated-sort`** (Phase a.4 of V3 roadmap,
  first cut). Adds `Tmf630JpaCorrelatedSortExecutor` — an entry-point bean that
  compiles `TmfSort` terms into a JPQL query executed via `EntityManager`, honoring
  correlated sub-queries for the simple-rich grammar. Auto-wired when the
  toolkit sees an `EntityManager` bean; developer usage mirrors the
  Mongo-aggregation module (`executor.findAll(rootType, predicate, tmfSort, pageable)`).

  Supported sort grammar in this first cut:
  - Plain dotted terms (`sort=name,-createdAt`) — rendered as standard
    `OrderSpecifier`s over the parent path.
  - Simple-rich single-hop `hopField[matchKey=matchValue].leafField`
    (e.g. `sort=characteristics[name=price].value`) — rendered as a
    correlated scalar sub-query via `JPAExpressions`:
    ```
    ORDER BY (SELECT alias.value FROM parent.characteristics alias
              WHERE alias.name = 'price') ASC|DESC
    ```
    Same-element semantics guaranteed by construction (single sub-query, single
    aliased subroot). Works only on `@OneToMany` / `@ManyToMany` /
    `@ElementCollection` associations.

  Explicit **rejection** with actionable messages for grammar that is out of
  scope for the JPA path (see V3_ROADMAP.md §2 for the scope decision):
  - JsonPath sort grammar (`sort=$.arr[?(...)].leaf`) — deferred; on JSONB
    services (Phase b) it maps natively via `jsonb_path_query_first`.
  - Positional `[N]` — not portable across JPA dialects without
    `@OrderColumn` assumptions the toolkit cannot make.
  - Wildcard `[*]` — same reason.
  - Aggregators `min()` / `max()` — deferred.
  - Coercions `num()` / `str()` / `date()` — dialect-specific SQL that
    doesn't compile portably.

  The `opentmf.tmf630.paging.nulls-last` property from Phase (a.1) is honored
  by the correlated-sort executor: when true, every emitted `OrderSpecifier` is
  decorated with `.nullsLast()`.

- **Array correlation in `filter=` JSONPath now works on JPA for JOIN-mapped
  collections** (Phase a.3 of V3 roadmap). Pre-3.0.0, a URL like
  `?filter=$[?(@.externalReference[?(@.name == 'X' && @.id == 'Y')])]` returned
  `400 Bad Request` on any JPA-rooted query (documented as
  *"Array correlation in jsonPath filter is supported only for document
  databases"*). Since 3.0.0, when the target collection field on the JPA entity
  is annotated with `@OneToMany`, `@ManyToMany`, or `@ElementCollection`, the
  toolkit emits a correlated `EXISTS` subquery via `com.querydsl.jpa.JPAExpressions`
  (reflectively loaded to keep `attribute-filtering-core` free of a compile-time
  querydsl-jpa dependency). Multi-condition inner filters get *same-element*
  semantics — `items[?(@.state == 'X' && @.sku == 'Y')]` compiles to a single
  `EXISTS (SELECT 1 FROM ... alias WHERE alias.state = ? AND alias.sku = ?)`
  rather than the cross-element `EXISTS(...state=?) AND EXISTS(...sku=?)` that
  QueryDSL's naive `.any()` produces. Collections NOT annotated with one of
  those three JPA relationship annotations (for example, a
  `@JdbcTypeCode(SqlTypes.JSON)`-mapped list stored as a JSON column) continue
  to return `400 Bad Request` with an actionable message naming the escape
  hatch — see [`docs/JPA_BACKEND_GAP_ANALYSIS.md`](./docs/JPA_BACKEND_GAP_ANALYSIS.md)
  §3.3. Nested `[?(...)]` inside `[?(...)]` composes recursively; each level
  gets its own uniquely-aliased subquery.

### Changed

- **`.regex` / `.regexi` on JPA entities rejected by default** (Phase a.2 of V3
  roadmap). Attribute-side `?field.regex=` / `?field.regexi=` and JSONPath
  `filter=$[?(@.field =~ /pattern/)]` targeting an entity annotated
  `@jakarta.persistence.Entity` (or the legacy `javax.persistence.Entity`) now
  return `400 Bad Request` at parse time rather than silently rendering as SQL
  `LIKE`. Rationale: querydsl-jpa's default `HQLTemplates` render `Ops.MATCHES` /
  `Ops.MATCHES_IC` as `LIKE`/`LOWER(x) LIKE LOWER(?)`, and Hibernate does not
  translate regex metacharacters (`^`, `$`, `.`, `*`, `?`, character classes)
  into their `LIKE` equivalents — so the same URL that produces a real regex on
  Mongo/JSONB backends silently matches by `LIKE` on JPA, with wrong-for-a-regex
  results (e.g. `^A` matches names literally starting with the caret character,
  not names starting with `A`). See
  [`docs/JPA_BACKEND_GAP_ANALYSIS.md`](./docs/JPA_BACKEND_GAP_ANALYSIS.md) §3.6
  for the full analysis. **Escape hatch (deprecated):** set
  `opentmf.tmf630.attribute-filtering.regex.allow-jpa-like-semantics=true` to
  preserve the pre-3.0.0 `LIKE`-based behavior. A one-time `WARN` log is
  emitted on first regex request when the compat flag is enabled. This flag is
  slated for removal in a future release; for real-regex semantics on
  Postgres, use the JSONB backend (native `~` / `~*` operators) shipping in
  Phase (b) of the same v3.0.0 release.

### Added

- **Nulls-last-regardless-of-direction property.** New opt-in property
  `opentmf.tmf630.paging.nulls-last` (default `false`, backward-compatible).
  When set to `true`, every plain sort order emitted by the toolkit is
  decorated with `Sort.Order.nullsLast()` (Spring Data's
  `NullHandling.NULLS_LAST`). The decoration flows through
  `TmfSortParser` → `TmfSort.toPlainSort(true)` and both pageable
  resolvers (plain `Pageable` and `TmfRichPageable`). Closes the
  cross-backend consistency gap where Mongo has offered
  nulls-last-regardless-of-direction since 2.1.1 (via the `_hasKey`
  companion in `Tmf630MongoCorrelatedSortExecutor`) but JPA inherited
  whatever the underlying database defaults to (Postgres/Oracle
  nulls-last in ASC, MySQL/H2 nulls-first in ASC). Backend rendering
  notes: on Postgres, Hibernate emits the SQL `NULLS LAST` modifier
  only when it differs from the dialect's inherent default (elided on
  ASC where nulls-last is already the default, emitted on DESC); the
  semantic outcome — null-valued rows appearing last in the result
  regardless of direction — holds either way. Non-native dialects
  (MySQL/H2/older SQL Server) fall back to Hibernate's `CASE WHEN
  x IS NULL` synthetic sort key; this has index-scan cost implications
  on large tables, which is why the property is opt-in and per-service.

## [2.1.5] - 2026-07-24

### Added

- **Defensive hardening — JSONPath filter parser recursion cap.** The
  `JsonPathFilterPredicateBuilder`'s recursive-descent parser is now bounded at 32
  levels of structural nesting, threaded across both within-Parser recursion (via
  parenthesised sub-expressions) and across-Parser recursion (nested array-match
  `[?(...)]` subfilters). Expressions that would previously have blown the JVM stack
  with a `StackOverflowError` (surfacing as a 500) now cleanly return `400 Bad Request`
  with the TMF `ErrorMessage` body. The 2048-character `json-path-filter.max-length`
  cap already bounded input size but not structural depth — a 2048-char string can
  pack hundreds of nested `((((...))))` parens.
- **Defensive hardening — FieldSelectionUtil cycle detection.** `resolveProperties`
  now threads a `Set<Class<?>>` of types currently on the recursion stack; when it
  would re-enter a type already being resolved (e.g. `Person.friend: Person`), it
  emits a scalar `FieldNode` placeholder instead of recursing again. Bounds walks on
  cyclic type graphs at any `depth` setting without dropping the referenced field
  from the response.
- **`FieldSelectionUtil` reflection-error split → 500.** The three
  `IllegalArgumentException` throws in `FieldSelectionUtil` (all reflection
  catastrophes — `Introspector.getBeanInfo` refuses a class, `PropertyDescriptor`
  can't be built for a record component, a getter throws in `invoke`) are now
  `TmfFieldSelectionInternalException` (a new `RuntimeException` subtype in
  `org.opentmf.query.commons.fieldselection`) and mapped to `500 Internal Server
  Error` by the new `Tmf630FieldSelectionExceptionHandler` (auto-wired via
  `Tmf630ExceptionHandlingAutoConfiguration`). Unknown `fields=` values in client
  requests continue to be silently skipped per TMF-630 Part 1 §4.3 — this handler is
  reserved for genuine server-side type-integrity failures, never bad user input.
- **TMF630 Part 1 §4.5 `Link` header for pagination navigation.** Paged
  responses (via `@Tmf630Response` on a `Page<>` return or via
  `Tmf630Util.tmfPage(Page)`) now emit
  `Link: <...offset=0...>; rel="first", <...>; rel="prev", <...>; rel="next",
  <...>; rel="last"` alongside the existing `X-Total-Count`, `X-Result-Count`,
  and `Content-Range` headers. `rel="prev"` is omitted on the first page and
  `rel="next"` is omitted on the last page; `first` and `last` are always
  present when the result set is non-empty. Unrelated query parameters
  (`status=`, `filter=`, `sort=`, etc.) are preserved in the emitted URIs so
  clients can navigate without reconstructing them. The helper is safe to
  call outside a request context (silent no-op).
- **TMF630 Part 1 §3.4 error body coverage for sort/paging parameter
  errors.** New `TmfPagingException` (extends `IllegalArgumentException` for
  backward compatibility) is thrown by `TmfSortParser`,
  `TmfPageableHandlerMethodArgumentResolver`, and
  `TmfRichPageableHandlerMethodArgumentResolver` for invalid `sort=`,
  `offset=`, or `limit=` values. A new `Tmf630PagingExceptionHandler`
  advice (auto-wired via `Tmf630ExceptionHandlingAutoConfiguration`) returns
  `400 Bad Request` with the same TMF `ErrorMessage` body shape as
  `Tmf630FilteringExceptionHandler` and `Tmf630RangeExceptionHandler`.
  Previously these errors fell through to Spring's default 400 translator
  and produced a non-TMF body, so a consumer trying to deserialize errors
  uniformly across `filter=`/`sort=`/`offset=`/`limit=` would fail on the
  sort/paging side.
- **TMF630 Part 1 §3.4 optional error fields.** `ErrorMessage` record
  gains `referenceError`, `@type`, and `@schemaLocation` — all optional,
  omitted from the JSON body when unset via `@JsonInclude(NON_NULL)`. The
  existing four-argument constructor is preserved. `Tmf630FilteringExceptionHandler`
  migrated from a `LinkedHashMap` body to the `ErrorMessage` record, so all
  four handlers (filtering, sort/paging, range, and any consumer using
  `ErrorMessage` directly) now produce byte-identical body shapes.
- **TMF630 Part 6 positional index `[N]` in `filter=` field paths (Mongo).**
  `filter=$[?(@.productOrderItem[2].state == 'completed')]` and multi-hop
  `@.a[0].b[1].c` now parse and execute on document backends, resolved as a
  dotted numeric path (`productOrderItem.2.state`) that MongoDB navigates
  natively. Out-of-range indices match nothing (Mongo-native), not an error.
  The allowlist is authored by JavaBean field name; `[N]` narrows the
  element, not the field, so an allowlist entry `externalReference.id`
  covers `externalReference[N].id` too. Positional index in `filter=` on
  JPA backends is rejected with a clear `400` — element-N indexing is not
  portable JPQL — mirroring the existing array-correlation guard.
  Symmetric with the `[N]` positional segment shipped in the sort grammar
  in 2.1.4; closes the filter-side gap.
- **TMF630 Part 6 `length()` function in `filter=` on collection fields
  (both backends).** `filter=$[?(@.tags.length() == 0)]` closes the
  "companion size-based predicate" gap the 2.1.4 CHANGELOG named for
  `isnull-semantics: NULLISH`, which deliberately excludes empty arrays on
  the plain find path. Both backends emit through the standard QueryDSL
  `Ops.COL_SIZE` fast path — `SIZE(coll) = N` on JPA, `{field: {$size: N}}`
  on Mongo — so no raw `$expr` emission or Spring Data serializer override
  is introduced. Scope is deliberately narrow: `== N` only on collection
  fields. Non-`==` comparators (`!=`, `>`, `>=`, `<`, `<=`) and non-collection
  leaves (strings, objects, scalars) are rejected at parse time with
  specific `400` messages, each naming an escape hatch — this keeps the URL
  grammar cross-backend-consistent (a URL that works on JPA and 400s on
  Mongo would be a bug factory) and defers the raw-emission architectural
  conversation until the JSONB backend forces it for all three backends
  together.

## [2.1.4] - 2026-07-06

### Added
- **TMF630 Part 1 §4.3 partial-representation identity rule: `id` and `href`
  are now always present, and `fields=none` is supported.** When a `fields=`
  selection is applied (via `@Tmf630Response`, `Tmf630Util.tmfPage`, or
  `FieldSelectionUtil` directly), the resource's `id` and `href` properties
  are included whether requested or not, per the spec's "id and href
  attributes are always present". `fields=none` selects no resource
  properties, so the response carries exactly the identity fields. Both apply
  only when the type exposes `id`/`href` as scalar properties — DTOs without
  them are unaffected, as are full representations (no `fields=` parameter).
  Note this is a deliberate behavior change for consumers that relied on
  partial representations omitting an unrequested `id`.
- **TMF630 Part 1 §4.4 URL-encoded operator literal forms.** The operator
  table's second spelling — the operator embedded in the parameter name,
  e.g. `?dateTime%3E2013-04-20` (decoded name `dateTime>2013-04-20`) — now
  maps onto the equivalent suffix operator: `>` → `.gt`, `>=` → `.gte`,
  `<` → `.lt`, `<=` → `.lte`, `==` → `.eq` (explicit: never value-list
  split), `=~` → `.regex` (gated by `regex.enabled`). Values may sit in the
  name remainder or in the value slot (`?field%3E=v`). The spec's ORING
  example (`?dateTime%3C2013-04-20;dateTime%3C2017-04-20`, one decoded name
  carrying two expressions) splits on `;` with the duplicate `<field><op>`
  prefix stripped and folds like repeated parameters. Keys are rewritten
  before parsing, so allowlists, limits, and conversion apply unchanged.
- **TMF630 Part 6 `=~` regex predicate in the JSONPath `filter=` grammar.**
  `?filter=$[?(@.status =~ /Resol.*/i)]` now parses: `/pattern/` maps to the
  existing REGEX predicate and `/pattern/i` to REGEXI, exactly the spec's
  literal form. Composes inside array-match correlation.
  Gated by the same `regex.enabled` (default `false`) and `regex.max-length`
  settings as the attribute-side operators; flags other than `i` are
  rejected (the spec's own table notes library variance — silently ignoring
  flags would change match semantics).
- **`depth` and `expand` are now reserved parameter names.** They are the
  TMF630 Part 2 Ch.3 dereferencing directives; the toolkit does not
  implement reference expansion (application-level data access), but no
  longer misreads a spec-compliant `?depth=2` as an attribute filter. The
  explicit-operator escape (`depth.eq=2`) keeps same-named entity fields
  filterable.
- **TMF630 Part 1 §4.4 explicit `;` ORing for implicit-eq attribute
  filters.** Both spec shapes are now supported: the value list
  `?attr=v1;v2` and the repeated-pair form `?attr=v1;attr=v2` (servlet
  containers deliver the whole `;`-tail inside the first value; the
  redundant `<sameKey>=` prefix is stripped per segment). Semicolons
  compose with the comma value list — `?attr=a,b;c` ORs all three — and
  splitting happens in a single pass so an escape intended for one
  separator is never consumed by the other. Same rules as the comma form:
  implicit-eq only (explicit operators keep literals), `\;` embeds a
  literal semicolon, per-element type conversion, elements count toward
  `max-values-per-key`/`max-clauses`. A segment prefixed with a
  *different* key (`?a=x;b=y`) stays a literal element — cross-attribute
  `;` pairs are out of scope. New setting
  `opentmf.tmf630.attribute-filtering.implicit-eq-semicolon-or` (default
  `true`, per spec) restores the previous literal behaviour when `false`.
  Note: containers URL-decode `%3B` before the resolver runs, so an
  encoded semicolon cannot be distinguished from a raw one — the escape
  hatches are `\;` and explicit operators.
- **Positional-index sort `[N]` in the JsonPath sort grammar.** TMF630
  Part 6 allows any JSON Path expression as a Sort-Field and defines
  0-based index access, so `?sort=$.arr[0].value` and the predicate+index
  shape `?sort=$.outer[?(@.id=='X')].sub[1].value` now parse and execute on
  `Tmf630MongoCorrelatedSortExecutor`. Semantics are LITERAL-POSITIONAL
  (`$arrayElemAt`), deliberately bypassing the min/max direction fold
  used for `[*]`/plain array paths; an out-of-bounds index yields a null
  sort key → nulls-last per the existing contract. Coercion and
  aggregator wrappers compose (`$.arr[0].num(value)` converts the picked
  element only; aggregator leaves map over just the picked element via
  `$slice`). The AST models this as an optional `index` on `ArrayHop`
  (source-compatible constructor retained), and the translator's only
  change is picking `$arrayElemAt` instead of `$first` where an index is
  present — predicate hops are byte-identical. The bare simple-rich
  spelling `arr[0].value` keeps its default-key meaning (`arr[id=0]`) for
  backward compatibility; positional requires the JsonPath form. A new
  `JSONPATH_INDEX` column in the cross-executor parity matrix pins
  filter+`[N]` row/total equivalence with the plain find path (the
  `$match` continues through the 2.1.4 `QueryMapper` pass).

- **TMF630 value-list (comma-OR) semantics for implicit-eq attribute
  filters.** `?attr=a,b` now matches `a` OR `b`, equivalent to the
  repeated-parameter form `?attr=a&attr=b` and to `?attr.in=a,b`. This
  completes the value-list support added for `.in`/`.nin`/`.between` in
  2.1.0 — the single-value branch previously converted the raw value as one
  literal, so `?attr=a,b` became `EQ("a,b")` and silently matched nothing.
  Splitting reuses the existing multi-value CSV parser (`\,` escapes a
  literal comma, blank segments are dropped), each element is
  type-converted individually (so `?num=1,2` works on numeric fields), and
  every element counts toward `max-values-per-key` and `max-clauses`
  exactly as in the multi-value branch. Mixed forms compose per
  `combine-repeated-values`: `?attr=a,b&attr=c` folds to `(a OR b) OR c`
  under `OR` and `(a OR b) AND c` under `AND`. Only the implicit spelling
  splits — every explicit single-value operator (`.eq`, `.ne`, `.like`,
  ...) keeps its raw value as one literal, which is the documented escape
  hatch for values that legitimately contain a comma. Reserved parameters
  (`fields`, `sort`, ...) are untouched. New setting
  `opentmf.tmf630.attribute-filtering.implicit-eq-csv-or` (default `true`,
  per spec) restores the previous literal behaviour when set to `false`.

- **New setting `opentmf.tmf630.attribute-filtering.isnull-semantics`
  (`MISSING_ONLY` \| `NULLISH`, default `MISSING_ONLY`).** TMF630 Part 1
  defines no null-test operator and Part 6's `[?(!@.field)]` glosses as
  *"items that do not have the property"* — exactly the `$exists:false`
  the toolkit emits by default, and the semantics MISSING_ONLY preserves
  unchanged. Downstream consumers whose data model treats a missing
  field, an explicit `null`, and an empty array as equivalent "no value"
  states can opt into `NULLISH`: `?attr.isnull=true` (and the `filter=`
  forms `!@.field` / `== null`) then also match documents where the
  field is explicitly `null`. The widening applies on Mongo
  `@Document` roots — Spring Data's `QueryMapper` post-processing pass
  strips size / typed-empty-list clauses from the OR, so empty-array
  matching on the plain find path requires a companion size-based
  predicate; on JPA `@Entity` roots the widening is skipped because
  SQL's `IS NULL` already captures the only "no value" state for
  scalars, and applying the `NOT IN (NULL)` complement would poison
  IS_NOT_NULL to zero rows via SQL trilean UNKNOWN. IS_NOT_NULL under
  NULLISH is the exact boolean complement of the widened IS_NULL
  (built as an AND of per-branch complements, never `NOT (...)`).

### Fixed
- **Mongo aggregation executor no longer returns an empty page when a
  filter targets a `@Field`-renamed path.** When a QueryDSL attribute
  filter referenced a field whose BSON name differs from its Java name —
  any `@Field("...")` rename, including sub-fields of an `@Id`
  composite-key type and `id` fields of embedded list elements stored as
  `_id` — AND the sort routed through `Tmf630MongoCorrelatedSortExecutor`,
  the request returned zero rows and `total=0` while the same filter on the
  plain `repository.findAll` path matched correctly. Root cause: dotted
  Java paths arrive in the predicate as a single path element, so
  `NoRefDocumentSerializer` could not resolve them per-segment and emitted
  the Java name verbatim into a raw `$match` stage that Spring Data's
  `QueryMapper` never remaps (the stage is added as a raw aggregation
  lambda). The executor now runs the serialized predicate through the same
  `QueryMapper` (built from `mongoTemplate.getConverter()`) that the plain
  find path uses, against the target entity's own mapping metadata — fully
  generic, no name special-casing. The `$match + $count` total pipeline
  shares the same stage list, so data page and total are fixed together. A
  cross-executor parity IT (5 filters × 4 sorts against an `@Id`
  composite-key fixture) pins executor results to `repository.findAll`
  equivalence.

## [2.1.3] - 2026-06-09

### Fixed
- **JSONPath `?sort=` now accepts double-quoted predicate literals, matching
  the filter parser.** The sort grammar's three quote-aware scanners
  (`JsonPathSortParser.readLiteral`, `stripWildcards`, and
  `outerCallSpansEntireExpression`) hard-coded `'` as the only string-literal
  delimiter and treated `"` as an unexpected character — so the identical
  predicate `@.id == "X"` was valid in `?filter=` (after the 2.1.3 filter
  fixes) but rejected with HTTP 400 in `?sort=`. The scanners now treat
  `"` as an equivalent delimiter and use the opening quote char as the close
  sentinel, so `'X"` does not terminate at the `"` (and vice versa). This is
  purely additive — single-quoted inputs are unaffected — and aligns the
  toolkit with canonical JSONPath (Jayway). The same change carries through
  to the wildcard-stripping pass (`[*]` inside `"..."` is preserved as a
  literal, just as it already was inside `'...'`) and the outer-wrap paren
  counter (parens inside `"..."` are excluded from the depth count).
- **`?name.regexi=` no longer returns HTTP 500 on MongoDB backends.**
  `PredicateFactory.regexIgnoreCase` built the predicate as
  `root.getString(field).lower().matches(pattern.toLowerCase())`, which emits
  a standalone `Ops.LOWER` call. The QueryDSL Mongo serializer
  (`MongodbDocumentSerializer`) does not implement `LOWER` and threw
  `UnsupportedOperationException: Illegal operation lower(...)` at query
  execution. The fix routes through `Ops.MATCHES_IC` directly via
  `Expressions.predicate(Ops.MATCHES_IC, field, constant)` — the Mongo
  serializer translates that op to a `$regex` query with `$options:"i"`, and
  JPA serializers translate it idiomatically too. The other case-insensitive
  operators (`eqi` / `nei` / `likei` / `containsi` / `startswithi` /
  `endswithi`) were never affected — they already used QueryDSL's
  `equalsIgnoreCase` / `likeIgnoreCase` / etc. builders, which emit
  `*_IC` ops the Mongo serializer recognises. A roundtrip test now pins all
  seven case-insensitive operators as Mongo-serializable.
- **`num()` / `str()` / `date()` over a multi-value array intermediate now
  converts each element BEFORE reducing, matching documented semantics.**
  When a coercion wraps a leaf path that crosses a collection-typed
  intermediate (e.g. `$.arr[?].sub.num(value)` where `sub` is a list of
  string-stored numerics like `["105.34", "12.2", "4.31"]`), the 2.1.2/2.1.3
  shape was `$convert($min(array))` — it took the lex-extreme element first
  and then converted, giving 105.34 instead of the documented numeric min
  4.31. The new shape is `$min/$max of $map(input, "$$e", $convert($$e))` —
  every element is coerced first, then the converted values are reduced
  direction-aware. The naked-hop AST shape that earlier turned
  `arr[id=X].sub.num(value)` into hops=[(arr, id==X), (sub, AlwaysTrue)] +
  Coercion(NUM, FieldRef("value")) is suppressed when the leaf chain is
  pure Coercion — pre-function dotted segments are now kept as part of the
  FieldRef path so the new translator branch applies uniformly to both
  grammar forms. Aggregator-containing leaves (`min(value)`, `max(value)`,
  `num(min(value))`) still promote pre-function segments to naked hops as
  before, because the aggregator's `$map` needs to iterate the right array.
  Single-element inner arrays are unaffected.
- **JSONPath `?filter=` now tolerates a trailing projection suffix on the
  sub-array shorthand form.** DPC-style consumers construct filter URLs by
  reusing their sort-URL templates, leaving a trailing projection suffix
  like `.productSpecCharacteristicValue[*].value` after the filter's
  `[?(...)]`. The projection has no semantic effect on the matched row set
  — that's fully determined by the predicate — so 2.1.3 strips the suffix
  in `JsonPathFilterPredicateBuilder.trySubArrayShorthand` and proceeds
  with the standard rewrite to `@.<arrayPath>[?(...)]`. Stripping happens
  only when the suffix is a pure dotted path (after `stripWildcards`
  removes `[*]`); a suffix containing nested `[?(...)]` predicates,
  bracket index access (`[0]`, `[0:5]`), or non-identifier characters is
  still rejected with the standard "must be a filter expression" error
  so the caller learns to rewrite rather than silently losing a nested
  filter. The double-quoted predicate literal claim in the same report
  (`@.id == "X"` vs `@.id == 'X'`) was already supported in 2.1.2 — the
  tokenizer accepts both — and a regression test pinning the behaviour
  inside the sub-array shorthand form is added.
- **Restored MongoDB's native array-key sort semantics broken by 2.1.2.** The
  2.1.2 parallel-arrays fix wrapped every leaf whose path crosses a
  collection-typed intermediate in `$arrayElemAt: [path, 0]` to keep
  `_sortKeyN` scalar. That kept the multi-key `$sort` working, but it
  silently switched ordering from MongoDB's pre-2.1.2 behaviour (the
  array-valued sort key was reduced to its minimum element for ASC and its
  maximum element for DESC implicitly by `$sort`) to "first matching element
  regardless of direction." Downstream consumers who relied on the
  min/max-by-direction semantics had to work around the change. The 2.1.3
  fix replaces `$arrayElemAt: [path, 0]` with `{$min: path}` for ASC terms
  and `{$max: path}` for DESC terms — both fold the auto-projected array to
  a scalar (so the parallel-arrays fix is fully preserved) AND restore the
  pre-2.1.2 ordering. Direction is plumbed from
  `Tmf630MongoCorrelatedSortExecutor` (which already inspects
  `term.direction()`) down through `AggregationKeyTranslator.translate` via
  a new `leafArrayReducerOp` parameter (constants `MIN_REDUCER` / `MAX_REDUCER`
  on the translator). The same reducer applies to `PLAIN` sort terms via
  `plainSortKeyExpression` and to coercion-wrapped leaves
  (`num(arr[X].sub.value)` etc.) for consistency. Intermediate hop selection
  — the `$first` of the predicate-filtered array — is unchanged: only the
  final leaf reduction differs.

### Added
- **JSONPath sort terms now accept coercion (`num()` / `str()` / `date()`) and
  aggregator (`min()` / `max()`) wrappers**, matching the SimpleRich grammar
  byte-for-byte. Two forms are accepted and produce equivalent translations:
  a leaf-level call (e.g.
  `?sort=$.characteristic[?(@.name=='price')].num(value)`) and an outer wrap
  enclosing the entire expression (e.g.
  `?sort=num($.characteristic[?(@.name=='price')].value)`). Pre-function
  dotted segments are promoted to naked array hops with `AlwaysTruePredicate`,
  exactly as SimpleRich does, so the existing translator handles both grammars
  uniformly. Behaviour for plain dotted leaves (no function call) is unchanged
  — the entire trailing path stays a single `FieldRef` and Mongo's
  expression-context auto-traversal continues to handle object intermediates
  naturally. The 2.1.2 parallel-arrays fix continues to apply: coerced leaves
  whose paths cross collection intermediates are still wrapped in
  `$arrayElemAt: [..., 0]` so multi-key `$sort` stays scalar.

### Why
- Downstream consumers (notably DNext, an internal TMF implementation suite)
  store `Characteristic.value` as `String` even when its `valueType` is
  `"number"`, so a bare sort against price-like characteristics ordered the
  values alphabetically: `"105.34" < "12.2" < "4.31"` instead of the expected
  numeric order. SimpleRich already exposes `num(value)`; the JSONPath
  grammar did not, forcing callers to switch dialects mid-application. The
  two grammars now compose identically and the choice is purely stylistic.

### Not changed
- **JSONPath filter coercion is deliberately not added.** The filter pipeline
  compiles a QueryDSL `Predicate` that is shared between Hibernate (JPA) and
  querydsl-mongodb backends. Hibernate can express `cast(value as decimal)`
  via `Expressions.numberTemplate`, but the Mongo find-language has no
  equivalent — supporting filter coercion on Mongo would require either
  bypassing querydsl-mongodb for some terms (carrying parallel predicate
  worlds), encoding `$expr`/`$toDouble` via a custom serializer, or accepting
  backend-asymmetric behaviour. Each option leaks backend awareness into the
  filter contract, defeats indexes, and locks the URL grammar into a
  workaround for an upstream schema choice. The sort path's `num()` wrapper
  remains the supported affordance for numeric ordering of string-typed
  characteristic values.

## [2.1.2] - 2026-06-03

### Fixed
- **Multi-term Mongo correlated sort no longer fails with "parallel arrays".**
  When `?sort=` carried two or more correlated terms whose leaf paths each
  descended into a collection-typed intermediate (e.g.
  `?sort=arr[id=A].sub.value,arr[id=B].sub.value` where `sub` is a list),
  the executor emitted two synthetic `_sortKeyN` fields whose values
  auto-projected to arrays under Mongo's expression-context path traversal.
  Mongo's `$sort` rejected the multi-key sort document with
  `cannot sort with keys that are parallel arrays` (BadValue, code 2),
  surfacing as `500 Internal Server Error`. A single term worked because
  Mongo internally reduces one array sort key, but the second array key
  triggered the error. `AggregationKeyTranslator` now wraps the per-element
  leaf in `$arrayElemAt: [..., 0]` whenever
  `MongoFieldResolver.hasArrayIntermediate` flags a collection-typed segment,
  so each synthetic sort key stays scalar. The same reduction is applied to
  `PLAIN` terms in `Tmf630MongoCorrelatedSortExecutor` for symmetry.
  Behaviour for single-term sort on a non-array-intermediate leaf is
  unchanged; tests covering plain dotted leaves, aggregator (`min()` /
  `max()`) reducers, and coercion (`num()` / `str()` / `date()`) continue
  to pass without modification.

## [2.1.1] - 2026-05-16

### Fixed
- **Mongo correlated-sort executor now places rows with null / missing sort
  keys last regardless of direction.** Previously, the executor delegated
  null-position semantics to Mongo's default `$sort`, which uses BSON natural
  order — rows with a null sort key landed *first* in ASC and *last* in DESC.
  Paginated consumers issuing `?sort=field&limit=N` would see a first page
  populated with rows missing the sort field, which rarely matches REST API
  conventions. `Tmf630MongoCorrelatedSortExecutor` now derives a paired
  `_hasKey` boolean for every sort term (PLAIN, JSONPATH, and SIMPLE_RICH) in
  a follow-up `$addFields` stage and prepends it ascending in the `$sort`
  document, so present-keyed rows always come before missing-keyed ones. The
  guard expression uses `$ifNull` so that Mongo's MISSING (absent field) and
  explicit `null` collapse to the same bucket. Affects the correlated grammar
  (e.g. `?sort=arr[id=X].leaf` against rows where the predicate matches no
  element) and plain top-level sort (e.g. `?sort=description` against rows
  that omit the field). The two `$addFields` and trailing `$project` are
  transparent to callers — synthetic keys never appear in the response.

### Notes
- The JPA backend continues to inherit the underlying database's
  null-position default (PostgreSQL / Oracle: nulls last in ASC; MySQL /
  H2 / SQL Server: nulls first in ASC). Bringing JPA into parity with
  Mongo is deferred to a later release alongside JPA correlated-sort
  support.

## [2.1.0] - 2026-05-11

### Added
- **Comma-separated value lists are now accepted for the multi-value
  attribute-filter operators `.in`, `.nin`, and `.between`.** TMF630 §4.4
  allows clients to OR-fold values either via repeated query parameters
  (`?status.in=A&status.in=B`) or via a comma-separated list
  (`?status.in=A,B`). The toolkit previously treated the comma form as a
  single literal `"A,B"` and matched nothing. The resolver now expands each
  raw value on unescaped commas, dropping empty components and honouring
  `\,` as a literal-comma escape. The expanded total is re-checked against
  `predicate.limits.max-values-per-key`. Mixing the two forms in a single
  request (e.g. `?status.in=A,B&status.in=C`) is supported and yields
  `["A", "B", "C"]`.
- **JSON Path filter grammar now supports the unary negation form
  `[?(!@.field)]`.** TMF630 Part 6 (Jayway parity) treats `!@.field` as a
  filter that selects rows where the named field is missing or null. The
  toolkit's parser previously rejected the `!` token outright with
  `400 Bad Request "Unsupported token"`. Negation is translated to the
  toolkit's existing `IS_NULL` predicate (i.e. matches `null` and missing
  fields). Negation of the array-match subform — e.g.
  `[?(!@.externalReference[?(@.id == 'X')])]` — remains unsupported and is
  rejected with a clear error message.
- **Simple-rich correlated sort now accepts the outer coercion wrapper form
  `num(arr[id=X].leaf)` / `str(...)` / `date(...)`.** TMF630 §4.7 permits the
  coercion function to sit either at the leaf segment (the previously
  supported `arr[id=X].num(leaf)` form) or wrap the entire sort term. The
  `SimpleRichSortParser` previously rejected the outer form with
  `must contain at least one [...] hop`. Both forms now parse and produce
  equivalent sort orderings on `Tmf630MongoCorrelatedSortExecutor`.
  Outer wrappers compose recursively, so e.g. `num(str(arr[X].leaf))` is
  accepted. The aggregation translator detects when a coerced leaf path
  crosses a collection-typed intermediate and wraps the path in
  `$arrayElemAt: [..., 0]` before `$convert` — without that projection,
  Mongo's expression-context path traversal returns an array of values
  that silently collapses to `null` inside `$convert` and breaks the sort.
- **New setting `opentmf.tmf630.attribute-filtering.on-unknown-json-path-field`
  (default `IGNORE`).** TMF630 Part 6 specifies that an unmatched JSON Path
  inside `?filter=` is an empty result, not a validation error. The previous
  unified `on-unknown-field` setting (default `REJECT`) — which is the right
  default for attribute filtering, where unknown keys are usually client typos
  — also gated the JSON Path filter, causing valid `?filter=$[?(@.optionalField
  == 'X')]` requests to return `400 Bad Request "Unknown field path"` whenever
  the field wasn't in the entity's allowlist or class. The two policies are
  now independent: `on-unknown-field` continues to default to `REJECT` for
  attribute filtering, while `on-unknown-json-path-field` defaults to `IGNORE`
  for JSON Path filters (returning `200 OK` with an empty page, per spec).
  The setting also covers the array-correlation subform — e.g.
  `[?(@.unknownArray[?(@.id == 'X')])]` no longer 400s under the default
  policy. Existing consumers that depend on the strict behaviour can opt back
  in by setting the property explicitly to `REJECT`.

## [2.0.2] - 2026-05-06

### Fixed
- **`Tmf630MongoAggregationAutoConfiguration` no longer breaks consumers that
  pull the toolkit onto the classpath without a configured `MongoTemplate`
  bean.** The autoconfiguration previously activated on
  `@ConditionalOnClass(MongoTemplate.class)` alone, which would attempt to
  wire `Tmf630MongoCorrelatedSortExecutor` even in JPA-only or test contexts
  where no Mongo bean exists, failing application startup. The autoconfig
  now also requires `@ConditionalOnBean(MongoTemplate.class)` and is ordered
  after Spring Boot's `DataMongoAutoConfiguration` so the bean condition is
  evaluated against a fully-populated context.
- **Combining `?filter=<jsonPath>` with a correlated `?sort=` no longer
  silently returns an empty page.** When a request routed through
  `Tmf630MongoCorrelatedSortExecutor` (correlated sort term) and the filter
  predicate referenced a property that Spring Data Mongo auto-promotes —
  most notably any nested `id` field, which is stored as `_id` — the
  executor's predicate serializer emitted the raw Java field name in the
  generated `$match` stage. The plain `find()` path applied Spring Data's
  field-name mapping and matched real documents; the aggregation path did
  not, and matched zero documents. The executor's `NoRefDocumentSerializer`
  now consults the active `MongoMappingContext` and translates each path
  segment through `MongoPersistentProperty#getFieldName()`, applying the
  same translation Spring Data applies on the `find()` path (`id` →
  `_id`, plus any `@Field("…")` overrides). Filter and correlated sort now
  see the same `$match` document regardless of which code path serializes
  it.

## [2.0.1] - 2026-05-05

### Changed
- **Spring Boot baseline upgraded from 4.0.4 to 4.0.6.** Picked up via the
  Spring Boot BOM. No source-level changes required in the toolkit; the
  upgrade flows through to consuming projects' transitive dependency
  versions.

### Added
- **Correlated sort for MongoDB-backed services.** A new opt-in module
  `tmf630-toolkit-mongo-aggregation` adds support for sorting by values
  taken from a *specific element* of an embedded array — the canonical
  TMF "characteristics" pattern. Two equivalent sort grammars are
  accepted on the wire:
  - **JsonPath**, e.g. `?sort=$.characteristic[?(@.name == 'price')].value`.
    Rich predicates with `&&`, `||`, comparison operators, multi-level
    nesting. Compatible with canonical JsonPath syntax — `[*]` is
    accepted as a transparent projection sigil so URLs remain
    interoperable with external JsonPath tooling.
  - **Simple-rich**, e.g. `?sort=characteristic[name=price].value`.
    Equality only, bare-value default-key shorthand `arr[X]`,
    aggregator functions `.min(field)` / `.max(field)`, and
    type-coercion wrappers `.str(...)` / `.num(...)` / `.date(...)` for
    sorting across mixed-type fields.
  Plain comma-separated sort terms (`?sort=-createdOn,+id`) keep their
  existing `find()` path with no behavior change. Correlated terms route
  to a Mongo `Aggregation` pipeline.
- **`TmfRichPageable` and `TmfSort` controller parameter types** in
  `tmf630-toolkit-paging-sorting-core` for opt-in controllers that want
  to accept correlated sort terms. `TmfRichPageable` extends Spring Data
  `Pageable` and additionally exposes `tmfSort()` carrying both plain
  and correlated terms — recommended for two-parameter
  `(Predicate, TmfRichPageable)` controllers that match the standard
  Spring Data shape. `TmfSort` alone is also exposed for
  three-parameter `(Predicate, TmfSort, Pageable)` controllers. The
  existing Spring Data `Sort` and plain `Pageable` parameter types are
  unchanged — plain-only controllers keep working as-is and continue
  to 400 on correlated terms. A one-line
  `if (pageable.tmfSort().requiresAggregation())` (or
  `sort.requiresAggregation()` in the three-param form) branch in the
  controller selects between the existing `find()` path and the new
  aggregation executor. Works seamlessly with `@Tmf630Response` for
  full TMF630 response handling (`Content-Range`, `X-Total-Count`,
  `X-Result-Count`, `200`/`206`/`416` status, `fields=` selection).
- **Auto-configuration** for `Tmf630MongoCorrelatedSortExecutor` —
  registered as a Spring `@Bean` automatically when `MongoTemplate` is
  on the classpath. New configuration property
  `opentmf.tmf630.mongo-aggregation.simple-rich.default-key` (default
  `id`) controls the key inferred by simple-rich's bare-value bracket
  shorthand for projects whose convention uses `name`, `code`, etc.
- **Field-name resolution that respects each consumer's entity model.**
  The toolkit consults Spring Data Mongo's `MappingMongoConverter` at
  query-translation time and rewrites every Java field path to the
  matching BSON name — including `@Field` overrides, custom
  `FieldNamingStrategy`, and Spring Data's default `id → _id`
  auto-promotion on nested classes. Predicates referencing `@.id == 'X'`
  / `[id=X]` work whether the underlying BSON stores the field as `id`
  or `_id`, with no `@Field("id")` annotation required anywhere in the
  consumer's model.
- **TMF630 `$.`-less JsonPath shorthand accepted in both `filter=` and
  `?sort=`.** Per the TMF630 recommendation, the leading `$.` may be
  omitted for simplicity. The toolkit now accepts all three of these
  forms equivalently:
  - **Filter**: `?filter=$[?(@.status == 'Pending')]` (canonical
    wrapper), `?filter=[?(@.status == 'Pending')]` (bare wrapper),
    and `?filter=statusChange[?(@.status == 'Pending')]` (sub-array
    shorthand — rewritten internally to the canonical correlated
    form `$[?(@.statusChange[?(@.status == 'Pending')])]`).
  - **Sort**: `?sort=$.arr[?(@.id == 'X')].value` and
    `?sort=arr[?(@.id == 'X')].value` parse to the same SortPath. The
    classifier recognises any term containing `[?(...)]` or `[*]` as
    JsonPath, even without the prefix.
- Comprehensive design note at
  [`docs/correlated-sort.md`](./docs/correlated-sort.md) — URL grammar
  for both forms, semantics (null-sort behavior, tie-breaking, runtime
  requirements), capability matrices, the consumer controller pattern,
  and the entity-mapping gotchas that bite hand-written or generated
  TMF models.

### Notes
- **MongoDB 4.0+** is required at runtime for the new aggregation path
  (uses `$convert ... onError` for safe type coercion). The existing
  `find()` path used by plain sorts is unaffected and works on whatever
  MongoDB versions the toolkit already supported.
- `tmf630-toolkit-mongo-aggregation` is **not** bundled into
  `tmf630-toolkit-all`. Consumers explicitly add the dependency when
  they want correlated sort. JPA-only and other non-Mongo services pay
  no Mongo dependency cost.

## [2.0.0] - 2026-03-26

### Changed
- **Spring Boot 4.0.4** baseline (was 3.5.x). This is a major upgrade that requires consuming projects to use Spring Boot 4.x.
- **Jackson 3** (`tools.jackson`) is now the default JSON stack. Spring Boot 4 auto-configures `JsonMapper` instead of the Jackson 2 `ObjectMapper`.
- **Jayway JsonPath 3.0.0** (`com.jayway.jsonpath:json-path:3.0.0`), aligned with Jackson 3. Correlated multi-field matching in nested arrays uses the nested `[?(...)]` filter form (translated to `$elemMatch` on Mongo).
- **Testcontainers 2.0.4** (managed by Boot 4 BOM). Artifact IDs now use the `testcontainers-` prefix (e.g. `testcontainers-postgresql`, `testcontainers-mongodb`).
- **QueryDSL 5.1.0** (`com.querydsl`) with `jakarta` classifier — unchanged group/artifact, version now referenced via `${querydsl.version}` property.
- Deprecated `spring-boot-starter-web` replaced with `spring-boot-starter-webmvc`.
- `attribute-filtering-core` now depends on `spring-web` instead of `spring-webmvc`; redundant `spring-core` and `spring-context` compile-scope dependencies removed (both are transitive). `spring-context` is retained as test-scoped. `spring-webmvc` is now declared explicitly in `attribute-filtering-autoconfigure` where it is directly used.
- MongoDB connection properties moved from `spring.data.mongodb.*` to `spring.mongodb.*` (Spring Boot 4 change).
- Spring Boot 4 package reorganization: `AutoConfigureMockMvc` moved to `org.springframework.boot.webmvc.test.autoconfigure`, `WebMvcAutoConfiguration` moved to `org.springframework.boot.webmvc.autoconfigure`.

### Added
- Maven Enforcer plugin now requires **Java 17** (`<requireJavaVersion>[17,)</requireJavaVersion>`).
- Comprehensive JsonPath filter guide in README with Jayway 3.x syntax examples, including correlated multi-field matching in nested arrays using the nested `[?(...)]` form.

### Tests
- Integration test fixtures moved to isolated subpackages (`it.mongo`, `it.jpa`, `it.sql`) to prevent cross-context bean conflicts in Spring Boot 4.
- Each IT's `@SpringBootApplication` now uses `scanBasePackageClasses` and `exclude` to precisely control auto-configuration loading.
- QueryDSL APT compiler plugin configuration consolidated to a single top-level `<configuration>` block (removes duplicate `testCompile` execution).
- External test dataset copied to `src/test/resources/fixtures/` for CI portability.

## [1.0.7] - 2026-03-20

### Changed
- Parent POM: `spring-boot.version` (imported BOM) bumped to `3.5.12`.
- `tmf630ResolverOrderingPostProcessor` no longer eagerly injects `Tmf630PredicateArgumentResolver`.
  - The `BeanPostProcessor` now accepts `ObjectProvider<Tmf630PredicateArgumentResolver>` and lazily resolves the bean inside `postProcessAfterInitialization`. This eliminates the "not eligible for getting processed by all BeanPostProcessors" warnings for all library beans (`OperatorRegistry`, `ParamKeyParser`, `ValueConverter`, `FieldPathResolver`, `PredicateFactory`, `JsonPathFilterPredicateBuilder`, `FieldAllowlistProvider`, `Tmf630FilteringExceptionHandler`).

### Fixed
- `TmfFilteringException` now propagates directly from `Tmf630PredicateArgumentResolver` instead of being wrapped in `ResponseStatusException`.
  - **Root cause of 500 in consuming services**: Spring's `ResponseStatusException` wrapping was being intercepted by consuming applications that had a catch-all `@ExceptionHandler(Exception.class)`, which returned a generic `500 Internal Server Error` to the client.
  - **Solution**: A new `Tmf630FilteringExceptionHandler` (`@RestControllerAdvice` at `@Order(Ordered.HIGHEST_PRECEDENCE)`) is auto-configured as part of the attribute-filtering feature. It catches `TmfFilteringException` before any catch-all handler in the consuming application and returns a structured `400 Bad Request` response:
    ```json
    {
      "code": "400",
      "status": "Bad Request",
      "reason": "Invalid filter parameter.",
      "message": "Field \"createdOn\" (Instant) could not be parsed from value \"2025-01-01\". Expected format: yyyy-MM-dd'T'HH:mm:ssX (ISO-8601 UTC), example: 1990-06-15T11:30:00Z"
    }
    ```
  - The `@Order(HIGHEST_PRECEDENCE)` annotation ensures the library handler wins over any consuming service's `@ExceptionHandler(Exception.class)`. The handler only intercepts `TmfFilteringException` and leaves all other exception types to the consuming service's own handlers.
- `ValueConverter` now returns `400 Bad Request` with a descriptive error message when a query parameter value cannot be parsed for `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`, `ZonedDateTime`, and `Instant` fields.
  - Previously, conversion failures produced either a generic `500 Internal Server Error` or an unhelpful `400` message such as `"Failed to convert value 'blabla' to LocalDate"`.
  - The new message includes the field name, the Java type, the expected ISO-8601 format, and a concrete example:
    ```
    Field "birthdate" (LocalDate) could not be parsed from value "15/06/1990".
    Expected format: yyyy-MM-dd, example: 1990-06-15
    ```
  - Enum conversion errors now follow the same pattern, naming the field and the enum type.
  - The field name is now propagated from all three call sites (`Tmf630PredicateArgumentResolver` single-value, multi-value, and `JsonPathFilterPredicateBuilder`).

### Added
- `Tmf630FilteringExceptionHandler` — a new `@RestControllerAdvice` at `@Order(Ordered.HIGHEST_PRECEDENCE)` that handles `TmfFilteringException` and returns a structured `400` response. Auto-configured via `Tmf630AttributeFilteringAutoConfiguration` with `@ConditionalOnMissingBean`, so it can be replaced by a custom bean if needed.
- `ValueConverter.TYPE_FORMAT_HINTS` — a static `Map<Class<?>, FormatHint>` that maps known `java.time` types to their expected format string and a concrete example value. Public to allow introspection and extension.
- `ValueConverter.convert(String rawValue, Class<?> targetType, String fieldName)` overload — produces richer error messages when the field name is known at the call site. The original two-argument overload delegates to this with `fieldName = null`.

### Docs
- New README section **6) Date and datetime field formats** documenting:
  - Accepted ISO-8601 formats for all six supported `java.time` types with example query strings.
  - Clarification that `@DateTimeFormat` annotations on entity fields are **not** respected (conversion is type-based).
  - How to override the `ValueConverter` bean to accept a custom format or to share the application's own `ConversionService`.
- Renumbered developer handbook sections 7–9 to 8–10 to accommodate the new section 6.

### Tests
- 2 new IT tests in `Tmf630PredicateArgumentResolverIT`:
  - `filteringExceptionHandlerReturns400WithStructuredBodyEvenWhenAppHasCatchAllHandler` — verifies that our handler at `HIGHEST_PRECEDENCE` wins over a `@ExceptionHandler(Exception.class)` defined in the same application context.
  - `filteringExceptionBodyContainsFieldNameAndFormatHintForTemporalType` — verifies the field name and ISO format hint appear in the response body for a temporal-type parse failure.
- 2 new unit tests in `Tmf630FilteringExceptionHandlerTest` covering the response status, body structure, and message content.
- Updated 6 unit tests in `Tmf630PredicateArgumentResolverTest` to assert `TmfFilteringException` instead of `ResponseStatusException`.
- `Tmf630ResolverOrderingPostProcessorTest` updated to supply `ObjectProvider<Tmf630PredicateArgumentResolver>` (anonymous provider) to match the auto-configuration signature.
- 5 new unit tests in `ValueConverterTest`:
  - `includesFieldNameAndFormatHintInErrorMessageForTemporalTypes` — verifies rich message for `LocalDate`, `LocalDateTime`, `OffsetDateTime`.
  - `includesFieldNameInEnumErrorMessage` — verifies field name and enum type appear in enum parse failure.
  - `fallsBackToGenericMessageWhenFieldNameIsNull` — verifies format hint still included even without a field name.
  - `allKnownTemporalTypesHaveFormatHints` — asserts all six `java.time` types are registered in `TYPE_FORMAT_HINTS`.

## [1.0.6] - 2026-03-11

### Added
- `@Tmf630Response` annotation for fully transparent TMF630 response handling.
  - When placed on a controller method (or class) returning `Page<T>`, the library automatically resolves the HTTP status (`200`, `206`, or `416`), adds `Content-Range` / `X-Total-Count` / `X-Result-Count` headers, serializes the page content as a JSON array, and applies `fields=` query parameter selection — all without any boilerplate code.
  - Works at method level or class level (applies to every handler method in the controller).
  - Three usage layers: (1) fully transparent `@Tmf630Response` + `Page<T>` return, (2) manual `tmfPage()` + annotation for auto field selection, (3) fully manual with no annotation.
  - Optional `depth` attribute (`@Tmf630Response(depth = N)`) overrides the global `default-depth` for a specific endpoint. `depth=1` (the default) maps only the scalar sub-fields of any explicitly-named complex field, preventing lazy-load cascades into deeper associations; `depth=0` passes the raw object to Jackson (all getters called); `depth=2` expands one additional level. Explicit dot-paths always resolve regardless of depth. Resolution order: method-level `depth` → class-level `depth` → `opentmf.tmf630.field-selection.default-depth`.
- `Tmf630ResponseBodyAdvice` — a `ResponseBodyAdvice` that powers the `@Tmf630Response` annotation. Auto-configured via Spring Boot; non-Boot users can register it as a bean.
- `Tmf630Util.tmfPage(Page<T>, String fields)` — convenience overload that combines TMF630 page response construction with field selection in a single call.
- New configuration namespace `opentmf.tmf630.field-selection`:
  - `enabled` (default: `true`) — enables/disables the `@Tmf630Response` advice.
  - `default-depth` (default: `1`) — controls how deep nested objects are auto-expanded during field selection. Defaults to `1` so that when a client sends `fields=address`, the library maps only the scalar properties of the named field into an explicit sub-map; Jackson never receives the raw POJO and therefore never triggers lazy-load cascades into deeper associations (e.g. a `@OneToMany states` on `Address`).
- Java record support in `FieldSelectionUtil`.
  - Records are detected via `clazz.isRecord()` and their components discovered through `RecordComponent` API.
  - Both property discovery and value reading bypass `Introspector` for records, since Java 17's `Introspector` does not reliably recognize record accessor methods (`name()` vs `getName()`).
  - Flat records, nested records, record-inside-bean, and bean-inside-record combinations are all supported.
- New public overloads for depth-aware field selection with explicit field lists:
  - `fieldsToMap(Object obj, String fields, int depth)`
  - `fieldsToMapList(List<?> objects, String fields, int depth)`
  - The `depth` parameter controls how deep complex fields are auto-expanded when selected by name (e.g., `fields=child` with `depth=2` expands one nested level into child's complex sub-properties). Explicit dot-paths (e.g., `child.address.city`) always resolve regardless of depth.

### Changed
- **Breaking**: `FieldSelectionUtil` default depth changed from `1` to `0`. Overloads without an explicit `depth` parameter (`fieldsToMap(obj)`, `fieldsToMapList(list)`, `fieldsToMap(obj, fields)`, `fieldsToMapList(list, fields)`) now emit scalar properties only; complex sub-objects are not auto-expanded. Use the `depth` overloads (e.g., `fieldsToMap(obj, 1)`) to restore previous behavior.
- `Tmf630ResponseBodyAdvice` / `@Tmf630Response` default depth changed from `0` to `1`. Scalar sub-fields of any explicitly-named complex field are now included automatically; deeper associations (e.g. `@OneToMany`) remain excluded, preventing JPA lazy-load cascades. Use `@Tmf630Response(depth = 0)` or set `opentmf.tmf630.field-selection.default-depth=0` to opt out.
- `ErrorMessage` converted from a mutable POJO (with setters) to a Java record. JSON serialization is identical; constructor replaces setters.
- `Tmf630PagingSettings` converted from a manual immutable class to a Java record for consistency with `Tmf630FilterSettings`.
- `Tmf630Util.applyRangeHeaders` parameter `returned` widened from `int` to `long` for consistency with `total` and `offset`.
- `OffsetLimitPageRequest` now implements `equals`, `hashCode`, and `toString`.

### Fixed
- `FieldSelectionUtil`: broad field selector overwritten by narrow dot-path.
  - When both `child` and `child.name` appeared in `fields`, the dot-path recursion overwrote the broader auto-expansion. Changed `if` to `else if` in `parseFieldsRecursive` so the two branches are mutually exclusive.
- `FieldSelectionUtil`: `ClassCastException` with wildcard or bounded generic types.
  - `getType` now safely unwraps `WildcardType` (returns upper bound), `TypeVariable` (returns bound), and nested `ParameterizedType` (returns raw type) instead of performing an unchecked cast.
- `FieldSelectionUtil`: `java.sql.Timestamp`, `java.sql.Date`, and `java.sql.Time` treated as scalar values.
  - Added `java.sql` to the excluded-packages set so these types are no longer expanded into their bean properties (e.g., `nanos`).
- `FieldSelectionUtil`: `parseFields` is now depth-aware, delegating to `resolveProperties` for auto-expansion of matched complex fields instead of a hardcoded scalar-only loop.

### Docs
- New README section: **"Avoiding JPA lazy-load cascades — recommended patterns"**, placed directly after the `depth` semantics explanation.
  - **Pattern A (strongly recommended):** use a DTO/record as the repository return type. Because the DTO carries no JPA associations, serialization is always safe with or without `fields=`. Includes a complete code example (record DTO + `@Query` repository + `@Tmf630Response` controller).
  - **Pattern B:** restrict `fields=` to scalar field names; the library's `depth=1` default ensures the raw entity POJO is never handed to Jackson, so lazy association getters are never called.
  - **Pattern C:** when a nested association is genuinely needed, use `@EntityGraph` or `JOIN FETCH` to load it eagerly in a single query, then let `depth=1` prevent cascading into deeper levels.
  - Patterns-to-avoid table: OSIV, `@Transactional` on controller, and `depth=0` without `fields=`.
  - Recommendation callout: set `spring.jpa.open-in-view=false` for new projects so lazy-load problems surface as hard errors at development time rather than silent N+1 queries in production.
- Clarified `depth` semantics: `depth=0` passes the raw POJO to Jackson (all getters called, including lazy associations); `depth=1` maps only scalar sub-fields into an explicit `Map`, preventing any getter call on the named complex object.
- Clarified that `depth` is only consulted when a `fields=` query parameter is present; without `fields=`, raw entity objects are returned to Jackson unconditionally regardless of the `depth` setting.

### Tests
- Add 21 unit tests for `Tmf630ResponseBodyAdvice`: Page→200/206/416 handling, field selection on Page/List/single-object, null body, empty page/list, ResponseEntity-wrapped Page (skips status override), class-level annotation detection, default constructor, depth resolution (method-level, class-level, method-overrides-class, zero depth, global fallback), and depth-aware field selection.
- Add 14 integration tests for `@Tmf630Response`: fully transparent Page, partial content, 416, field selection, class-level annotation, empty page, manual `tmfPage()` + annotation, non-annotated endpoint isolation, method-level depth (depth=1 excludes second-level complex fields, depth=2 includes them), class-level depth inheritance, and method depth overriding class depth.
- Add 2 tests for `OffsetLimitPageRequest` equals/hashCode/toString.
- Add 3 tests for `Tmf630PagingSettings` record: accessors, defensive copy, equals/hashCode.
- Add 2 tests for `Tmf630Util.tmfPage(Page, String fields)` overload.
- Add tests for `Tmf630FieldSelectionProperties` defaults and setters.
- Add 6 tests for record field selection: flat record, explicit field selection, nested records with depth, record-inside-bean, bean-inside-record, and list of records.
- Add 6 tests for depth-aware field selection with explicit fields: depth 1/2/3, list mapping, explicit dot-path unaffected by depth, and backward compatibility with existing overload.
- Add regression tests for the three `FieldSelectionUtil` bug fixes: broad-vs-narrow selector, wildcard/TypeVariable/nested-ParameterizedType generics, and `java.sql.Timestamp` as scalar.

## [1.0.5] - 2026-03-03

### Fixed
- Add `offset`, `limit`, and `fields` to the reserved parameter set in `Tmf630PredicateArgumentResolver`.
  - Previously only `page`, `size`, and `sort` were reserved, causing TMF630 paging and field-selection parameters to be misinterpreted as attribute filter fields.
  - With `onUnknownField=REJECT` (default), requests combining attribute filtering with `offset`/`limit` would fail with `400 Bad Request`.

### Documentation
- Add "Reserved parameter names" section to the README explaining which query parameter names are skipped by attribute filtering, and how to filter by entity fields that share a reserved name using explicit operator suffixes (e.g., `offset.eq=5`).

### Tests
- Add unit test verifying `offset`, `limit`, `fields`, and `sort` are ignored by the predicate resolver.
- Add controller-level integration test combining attribute filtering with `offset`, `limit`, `fields`, and `sort` to prevent regression.

## [1.0.4] - 2026-03-03

### Added
- Enum-aware value conversion with automatic factory method discovery.
  - Before falling back to `Enum.valueOf`, the converter scans for `public static` methods that accept a single `String` and return the enum type.
  - Multiple factory methods are tried in order; exceptions are silently ignored.
  - Factory methods are discovered once per enum type and cached.

### Changed
- Bump Spring Boot BOM from `3.5.10` to `3.5.11`.
- Bump Maven Surefire and Failsafe plugins from `3.5.4` to `3.5.5`.

### Documentation
- Add "Prerequisites for attribute filtering" section covering required peer dependencies (QueryDSL JPA/MongoDB bindings, `querydsl-apt`, Spring Data starters) and what the toolkit provides transitively.
- Add "Combining predicates with path variables" section with a controller example for sub-resource endpoints (`GET /master/{id}/children`).
- Add "Enum field resolution" section explaining the factory method discovery chain with plain, case-insensitive, and multi-factory examples.

### Tests
- Add unit tests for enum conversion: plain enum, single factory, null-returning factory, multiple factories, and full-chain failure.

## [1.0.3] - 2026-02-17

### Fixed
- Parse signed TMF sort values consistently for both `Pageable` and `Sort` arguments.
- Handle plus-prefixed ascending sort when `+` is URL-decoded as whitespace (for example `sort=+transformationId` -> `sort= transformationId`).
- Prevent Spring Data property lookup errors for signed/whitespace sort tokens (for example `-createdOn` and ` transformationId` being treated as raw property names).
- Restore QueryDSL-generated Mongo test Q-types (for example `QMongoSearchEntity`) to prevent integration-test context startup failures.

### Tests
- Add unit test coverage for `TmfSortHandlerMethodArgumentResolver`.
- Add resolver test coverage for sort-only pageable requests (without `offset`/`limit`).
- Add parser test coverage for whitespace/plus sort normalization.
- Add controller-level auto-configuration integration tests for signed sort requests without TMF paging parameters.
- Add test-compile QueryDSL annotation processing path to keep generated test Q-classes stable.


## [1.0.2] - 2026-02-13
- Improve TMF630 filtering defaults, backend-aware nested-path handling, and docs.
  - add implicit-eq fallback for dotted attribute keys with unknown suffixes when implicit eq is enabled
  - fix deep nested keys (for example `a.b.c=...`) to parse as field-path `eq` instead of unknown-operator errors
  - split nested-path configuration into backend-specific settings:
    - `opentmf.tmf630.attribute-filtering.allow-nested-paths-jpa`
    - `opentmf.tmf630.attribute-filtering.allow-nested-paths-docdb`
  - resolve nested-path behavior per root type in runtime settings (`@Entity` -> JPA setting, `@Document` -> docdb setting)
  - propagate backend-aware nested-path resolution through attribute filtering and JsonPath filter translation
  - update JsonPath predicate builder internals to use resolved nested-path policy consistently, including array-correlation flow
  - change default allowlist mode from `DENY_ALL` to `ALLOW_ALL` for out-of-the-box filtering usability
  - remove deprecated legacy nested-path property bridge (`allowNestedPaths`) from autoconfigure properties
  - update and extend tests across core and autoconfigure modules for:
    - new parser fallback behavior
    - backend-specific nested-path settings
    - removed deprecated property bridge
    - Mongo integration test context stability
  - refactor Mongo IT to use an inline test controller in the test app for reliable context loading under verify/failsafe
  - refresh README configuration documentation with:
    - default behavior scenario
    - minimal override YAML scenario
    - full non-default YAML scenario
    - backend-specific nested-path guidance (JPA vs docdb)
    - explicit safer production recommendation (`DENY_ALL` + allowlisted fields per entity)

## [1.0.1] - 2026-02-11

### Added
- `filter=` JsonPath support (restricted subset) merged into the same predicate flow.
- Request-level merge override between attribute filters and `filter=` via `filter.combineWithAttributes=AND|OR`.
- Mongo-oriented array-correlation support in `filter=` with strict same-element behavior (`$elemMatch` translation).
- Dedicated integration profiles for SQL database validation using Testcontainers (PostgreSQL, MariaDB, Microsoft SQL Server, Oracle XE, IBM DB2).

### Documentation
- Expanded root README with end-to-end usage, operator reference, backend support scope, and tested database matrix.
- Added explicit Mongo and JPA `filter=` support boundaries and `400` error conditions.

## [1.0.0] - 2026-02-10
- Initial release.

