# tmf630-toolkit — enhancement requirements: JSONPath `filter=` `length()` and positional `[N]`

> **From:** OneX Portugal product-catalog-proxy team
> **To:** tmf630-toolkit maintainers
> **Date:** 2026-07-09

| Requirement | Summary |
|---|---|
| **REQ-1** | `length()` function in the JSONPath `filter=` grammar (empty-object / empty-array / size predicates) |
| **REQ-2** | Positional `[N]` segments in filter field paths |

---

## REQ-1 — `length()` function in the JSONPath `filter=` grammar

### Current behavior (2.1.4 release / 2.1.5-SNAPSHOT, verified in source)

The `filter=` parser (`JsonPathFilterPredicateBuilder`, hand-written tokenizer + recursive
descent, module `attribute-filtering-core`) has **no function-call production**. Recognized
literals are strings, numbers, booleans, `null`, and `/regex/` — no functions of any kind.

```http
GET /productOffering?filter=$[?(@.channel.length()==0)]
```

tokenizes `@.channel.length` as a field path; the following `(` makes the parser throw
`TmfFilteringException("Expected comparison operator in jsonPath filter.")` → **HTTP 400**.

There is **no workaround via existing affordances**:

- `.isnull` / `!@.field` / `@.field == null` → `$exists:false` (or the NULLISH widening) —
  none of them match a field that exists with value `[]` or `{}`.
- `isnull-semantics: NULLISH` deliberately excludes empty arrays on the plain find path —
  Spring Data's `QueryMapper` strips `$size` / typed-empty-list clauses in its
  post-serialization pass (documented in the `PredicateFactory` Javadoc, CHANGELOG, README).
- No operator emits `$size`, `$expr`, or an `$eq: {}` / `$eq: []` comparison.

### Why this is needed

1. **The third "no value" state is not queryable.** Data models distinguish three shapes —
   field missing, field `null`, field present-but-empty (`[]` / `{}`). The first two became
   queryable in 2.1.4 (`isnull-semantics`); *"exists but is empty"* cannot be expressed at
   all on the Mongo path.
2. **Part 6 alignment.** TMF630 Part 6's JSONPath dialect is Goessner-family; `length()` is
   the de-facto function every mainstream evaluator ships (Jayway implements it for arrays
   and strings). The same `filter=` expression today gets 200 on an in-memory Jayway view
   and 400 on the toolkit's translation.
3. **Completes the NULLISH story.** The 2.1.4 CHANGELOG itself says empty-array matching
   needs *"a companion size-based predicate"* — this is that predicate. With it, the full
   "no value" matrix becomes composable client-side:
   `filter=$[?(!@.channel || @.channel.length()==0)]` = missing OR null (NULLISH) OR empty.

### Requested behavior

#### Grammar

Accept a function-call suffix on a field path, as the left-hand side of an ordinary
comparison:

```
term        := fieldPath [ ".length()" ] comparator literal
comparator  := == | != | > | >= | < | <=
literal     := non-negative integer                 ← for length() terms
```

- `@.path.length()` is only valid immediately followed by a comparator and a
  **non-negative integer literal**. Anything else (`length()=='x'`, bare `length()` as a
  boolean term, `length()==null`) → `TmfFilteringException` → 400, same style as the
  existing `"Null literal only supports == and != operators."` guard.
- Composes everywhere a comparison composes today: `&&`, `||`, parentheses, and inside
  array-match sub-filters (`@.arr[?(@.sub.length()>0)]`).
- **One spelling only:** `length()`. No `size()` / `empty()` aliases — no dialect drift.
- Suggested seams: tokenizer consumes the `.length()` suffix during the `@`-scan (new
  `TokenType.FUNCTION`); a `FunctionNode` beside `ComparisonNode`/`ArrayMatchNode`; a
  dedicated `PredicateFactory.buildLength(path, cmp, n)` (or a `TmfOperator.SIZE` family).

#### Semantics (normative — please implement exactly this table)

`length()` is **container-agnostic**: defined for arrays (element count), objects (key
count), and strings (character count). It is **undefined** — the predicate evaluates to
*no match*, never an error — for missing fields, `null`, numbers, and booleans.

Given six persisted shapes of `channel`:

| # | Document shape | `length()==0` | `length()>0` | `length()==2` |
|---|---|---|---|---|
| A | field **missing** | no match | no match | no match |
| B | `"channel": null` | no match | no match | no match |
| C | `"channel": []` | **match** | no match | no match |
| D | `"channel": {}` | **match** | no match | no match |
| E | `"channel": [ {..}, {..} ]` | no match | **match** | **match** |
| F | `"channel": "ab"` (string leaf) | no match | **match** | **match** |

Rows A/B are the critical distinction: `length()==0` means *"exists but is empty"* — it
must **not** silently widen into an absent check (that is `isnull`'s job). Row D is a
deliberate superset over Jayway (arrays/strings only); document the divergence — it is the
point of the feature.

#### Mongo emission

Suggested encodings (maintainers free to choose equivalents; the acceptance matrix is
binding **on both the plain find path and the aggregation path**):

- General form — works for all three container types and is immune to the `QueryMapper`
  stripping problem because `$expr` operands are not type-mapped:

  ```json
  { "$expr": { "$eq": [
      { "$switch": { "branches": [
          { "case": { "$isArray": "$field" },                       "then": { "$size": "$field" } },
          { "case": { "$eq": [ { "$type": "$field" }, "object" ] }, "then": { "$size": { "$objectToArray": "$field" } } },
          { "case": { "$eq": [ { "$type": "$field" }, "string" ] }, "then": { "$strLenCP": "$field" } }
        ], "default": -1 } },
      0 ] } }
  ```

  `default: -1` yields the "undefined → no match" semantics for missing/null/scalar. For
  `!=` build the complement as *length-is-defined AND ≠ N* (per-branch complements, never
  `NOT (...)` — the same discipline the NULLISH `IS_NOT_NULL` uses), so rows A/B stay
  excluded.

- Fast path (optional): `== N` on a known-array field may emit `{field: {$size: N}}`, but
  **only where it demonstrably survives** `QueryMapper` — 2.1.4's own CHANGELOG documents
  that it does not on the plain find path, which is exactly why `$expr` is the suggested
  default.
- The aggregation executors (`Tmf630MongoCorrelatedSortExecutor` etc.) must receive the
  same criteria through their `QueryMapper`-mapped `$match`; `@Field` renames
  (`id` → `_id`) must keep working inside `$expr` field references.

#### JPA emission

- Collections: QueryDSL `SIZE(collection) <cmp> N`. Strings: `LENGTH(column) <cmp> N`.
- Embedded/JSON objects: not portably expressible — **reject** with a clear
  `TmfFilteringException` on JPA roots (mirror the existing "array correlation is
  Mongo-only" guard). A NULLISH-style silent no-op is *not* acceptable here because the
  predicate would change the row set.

#### Configuration

No new flag requested — the grammar addition is strictly additive (every expression it
accepts is a 400 today, so nothing can regress). If a gate is preferred for symmetry with
`regex.enabled`, a single default-**on**
`opentmf.tmf630.attribute-filtering.json-path-filter.length-enabled` is acceptable.

---

## REQ-2 — Positional `[N]` segments in filter field paths

### Current behavior

The `@`-path tokenizer only special-cases `[?(` (array-match) and strips `[*]`. A bare
positional index breaks the scan:

```http
GET /productOrder?filter=$[?(@.productOrderItem[2].length()==0)]
```

→ `TmfFilteringException("Unsupported token in jsonPath filter")` → **HTTP 400** — before
`length()` is even reached. The same applies without any function:
`filter=$[?(@.productOrderItem[2].state=='completed')]` is a 400 today.

### Why this is needed

- Element-level predicates (*"the N-th element exists but is empty"*, *"the N-th element
  has state X"*) are inexpressible.
- Consistency: 2.1.4 already accepted positional `[N]` in the **sort** grammar
  (`sort=arr[0].leaf`); Part 6's JSONPath table includes the `[0]` index form. `filter=`
  is the missing counterpart.

### Requested behavior

- Tokenizer: allow `[<non-negative integer>]` inside the `@.`-path scan, any number of
  hops (`@.a[0].b[1].c`).
- Works with or without a trailing `.length()` — same grammar production as REQ-1.
- Mongo: dotted numeric path (`productOrderItem.2`), which Mongo resolves natively; the
  `$expr` form uses `{ "$arrayElemAt": [ "$productOrderItem", 2 ] }`.
- Out-of-range index → **no match**, not an error (Mongo-native behavior).
- JPA: **reject** (list-index navigation is not portable JPQL).

---

## Worked samples

```http
# 1 — channel exists but is an empty object {}                          (REQ-1)
GET /productOffering?filter=$[?(@.channel.length()==0)]

# 2 — productOrderItem exists but is an empty array []                  (REQ-1)
GET /productOrder?filter=$[?(@.productOrderItem.length()==0)]

# 3 — third order item exists but is an empty object {}                 (REQ-1 + REQ-2)
GET /productOrder?filter=$[?(@.productOrderItem[2].length()==0)]

# 4 — full "no value" matrix: missing OR null OR empty
#     (!@.channel under isnull-semantics: NULLISH covers missing+null)  (REQ-1)
GET /productOffering?filter=$[?(!@.channel || @.channel.length()==0)]

# 5 — non-empty check composed with a value predicate                   (REQ-1)
GET /productOffering?filter=$[?(@.channel.length()>0 && @.lifecycleStatus=='Launched')]

# 6 — inside an array-match sub-filter                                  (REQ-1)
GET /productOffering?filter=$[?(@.channel[?(@.characteristic.length()==0)])]

# 7 — exact size                                                        (REQ-1)
GET /productOrder?filter=$[?(@.productOrderItem.length()==2)]

# 8 — positional hop without length()                                   (REQ-2)
GET /productOrder?filter=$[?(@.productOrderItem[2].state=='completed')]

# 9 — must be 400: non-integer RHS
GET /productOffering?filter=$[?(@.channel.length()=='two')]

# 10 — must be 400: length() is not a boolean term
GET /productOffering?filter=$[?(@.channel.length())]
```

## Acceptance criteria

Seed matrix per target field: the six shapes A–F above, a nested variant for sample 6, and
a 3+-element array for REQ-2.

1. **Semantics table binding:** every cell of the A–F table, for `==0`, `>0`, `==N`,
   `!=N`, on the **plain find path**.
2. **Aggregation parity:** the same queries with a correlated/JSONPath `?sort=` forcing
   the aggregation executor return identical row sets **and totals** (`$match + $count`
   share the stage list) — in the style of `Tmf630MongoFilterSortParityIT`.
3. **`@Field` renames:** a `length()` predicate on a renamed path (embedded `id` stored
   as `_id`) matches correctly on both executors.
4. **Composition:** samples 4–8 return the expected rows; `&&`/`||`/parentheses and
   array-match nesting all compose.
5. **Rejections:** samples 9–10 → 400 with distinct messages; `length()` on JPA object
   paths and `[N]` on JPA roots → 400.
6. **Regression:** the existing `JsonPathFilterPredicateBuilderTest` suite passes
   unchanged; expressions without `length()`/`[N]` produce byte-identical criteria.
7. **REQ-2 specifics:** index + `length()`, index + scalar comparison, out-of-range index
   (→ no match, not error), multiple hops — on both executors.
