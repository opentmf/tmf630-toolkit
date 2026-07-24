# length() and positional `[N]` — proposed plan for 2.1.5

This document is a response to `docs/TOOLKIT_REQUIREMENTS_LENGTH_FILTER.md`
(colleague-authored, 2026-07-09). It explains **what we propose to ship**, **why
some parts of the original request are excluded**, and **how it would be
implemented**, so a go / no-go decision can be made without re-deriving the
reasoning.

Target version: **2.1.5** (`pom.xml` is `2.1.5-SNAPSHOT`; latest CHANGELOG heading
is `## [2.1.4]`, so a new `## [2.1.5]` section will be created per the SNAPSHOT
rule).

---

## Part 1 — Why only the `==` operator?

For each comparator we need three things to hold:

- **JPA emission** — standard JPQL that Hibernate accepts.
- **Mongo emission** — something `querydsl-mongodb` serialises **and** Spring
  Data's `QueryMapper` doesn't strip.
- **Semantics parity** — the same row set on both backends per the A–F table in
  the requirements doc.

Here's what each comparator would take:

| Op            | JPA                                   | Mongo                                                                                                                                                                                                                                                                                                                                                    | Verdict                                       |
| ------------- | ------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------- |
| `==`          | `SIZE(coll) = N` — standard JPQL      | `{field: {$size: N}}` via `querydsl-mongodb`'s special-case rewrite of `EQ(COL_SIZE(coll), N)`. Spike proved it survives in top-level, `$and`, and `$or`.                                                                                                                                                                                                | ✅ ships                                      |
| `!=`          | `SIZE(coll) != N` — standard JPQL     | No direct `$size` complement. `{field: {$not: {$size: N}}}` wrongly matches missing / null (Mongo `$not` semantics: *"selects documents that do not match the operator expression, including documents that do not contain the field"*). Correct shape is `{field: {$exists: true, $not: {$size: N}}}` — composite construction outside standard QueryDSL. | ❌ blocked on Mongo without extra machinery   |
| `>`, `>=`, `<`, `<=` | `SIZE(coll) > N` etc. — standard JPQL | Spike proved `querydsl-mongodb` throws `UnsupportedOperationException: Illegal operation size(...)` at `MongodbDocumentSerializer.visit:273`. It only rewrites the `EQ + literal` fast path; every other comparator hits the throw. Correct shape would be `{$expr: {$gt: [{$size: "$field"}, N]}}` — needs a raw-Document mechanism (M1/M2 from the earlier design memo). | ❌ blocked on Mongo without extra machinery |

### Cross-backend consistency argument

We could technically ship all six on JPA (they're standard JPQL) and only `==`
on Mongo. But then the same URL — `?filter=$[?(@.arr.length()>0)]` — works on a
JPA-backed service and 400s on a Mongo-backed one. Same toolkit, same grammar,
different behaviour per backend. That's a bug factory: customers write filter
templates once, share them across services, and half of them break silently.
Uniform "`==` only, everywhere" is honest.

### Why we don't reach for the raw-Document mechanism just to unlock `!=` / `>` / `<`

M1 / M2 from the earlier design memo are structural (annotation change or
serializer override) and lock us into per-backend raw-emission handling.
Adding that for one feature — before the JSONB backend even lands — is exactly
where the concern about complexity applies. When JSONB arrives, raw emission
will need to be solved for **all three** backends together; that's the natural
time to consider a broader mechanism, not now.

---

## Part 2 — Implementation plan

Two independent features. Ship them as two separate commits, both in the 2.1.5
cycle.

### Phase A — REQ-2: positional `[N]` in filter paths

Smallest and truly orthogonal (no `length()` dependency).

**Step A1** — extend the tokenizer's `@`-scan to accept `[<digit>+]` alongside
the existing `[?(`:

File: `tmf630-toolkit-attribute-filtering-core/src/main/java/org/opentmf/query/tmf630/filtering/JsonPathFilterPredicateBuilder.java`,
inside `Parser.tokenize()`.

Add an `else if (c == '[')` branch after the existing `[?(` handling: scan
digits, require closing `]`, include the bracket span in the FIELD token text
(`@.a[2].b`).

**Step A2** — extend `FieldPathResolver.resolve()` to accept numeric segments
and treat them as one hop into the collection element type.

- **Mongo**: the numeric segment stays in the dotted path
  (`productOrderItem.2.state`). Mongo resolves this natively — no operator
  needed, no `$expr`.
- **JPA**: any numeric segment → throw
  `new TmfFilteringException("Positional index [N] is supported only for document databases.")`
  (mirrors the existing array-correlation guard).

**Step A3** — tests:

- `JsonPathFilterPredicateBuilderTest`: parse+emit `@.arr[2].leaf` → dotted BSON
  path `arr.2.leaf`; multi-hop `@.a[0].b[1].c`; out-of-range index (`[99]`) → no
  error at parse time.
- `Tmf630PredicateMongoIT`: seed a 3-element array; assert
  `@.arr[2].state=='completed'` matches only the intended row; assert
  `@.arr[99].state=='X'` returns 0 rows (Mongo-native no-match).
- `Tmf630PredicateSqlIT`: assert `?filter=$[?(@.arr[0].leaf=='X')]` → 400 with
  the "Mongo-only" message.
- Existing tests must keep passing.

**Step A4** — CHANGELOG entry under a new `## [2.1.5]` heading (SNAPSHOT rule).

---

### Phase B — REQ-1: `length() == N` on collection fields

**Step B1 — grammar.** Extend tokenizer to consume optional `.length()` suffix
after the `@`-path scan (~5 lines). Extend `Parser.parsePrimary()` to detect the
suffix, require an operator + non-negative integer, and produce a new
`LengthComparisonNode`. Reject at parse time (distinct messages):

- `.length()` not followed by a comparator → `"length() must be compared with a non-negative integer literal"`
- `.length()` with `=~` → `"length() cannot be used with =~"`
- `.length()` with negative / non-integer / null RHS → `"length() requires a non-negative integer literal"`
- `.length()` with a comparator other than `==` → `"length() supports only == comparison; use [?(...)] for non-empty checks"`

**Step B2 — `toPredicate` dispatch.** New branch for `LengthComparisonNode` that
resolves the path, verifies the leaf is a `Collection` (else
`"length() is supported only on collection fields"` — uniform across backends),
and calls `predicateFactory.buildLength(rootPath, resolvedField, size)`.

**Step B3 — `PredicateFactory.buildLength`.** Single-shape emission via standard
QueryDSL:

```java
public Predicate buildLength(PathBuilder<?> root, ResolvedField field, int size) {
  CollectionPathBase<?, ?, ?> coll =
      root.getCollection(field.fieldPath(), (Class) field.elementType());
  return Expressions.numberOperation(Integer.class, Ops.COL_SIZE, coll).eq(size);
}
```

No `isMongoRoot` branch needed — the same expression serialises correctly on
both backends (`SIZE(...)=N` on JPA, `{field: {$size: N}}` on Mongo).

**Step B4 — tests:**

- `JsonPathFilterPredicateBuilderTest`: unit tests for each rejection message
  (bare `length()`, `length()>0`, `length()==-1`, `length()==null`,
  `length()=='x'`, `length()` on a scalar leaf); happy-path `@.arr.length()==0`
  and `@.arr.length()==3`.
- `Tmf630PredicateMongoIT`: A / B / C / E rows from the requirements doc (skip
  D — objects — since we reject them; skip F — strings — same); pin
  `length()==0` matches only row C, `length()==2` matches only row E;
  composition with `&&` and `||` and array-match sub-filter.
- **Aggregation parity** (acceptance criterion #2 from the requirements doc): a
  variant IT that forces the correlated-sort executor and asserts the same row
  set + `$count` — reuse the `Tmf630MongoFilterSortParityIT` pattern.
- `Tmf630PredicateSqlIT`: mirror the Mongo happy-path tests to prove JPA parity
  (row-set + captured SQL contains `size(`); assert scalar / string leaf → 400;
  assert non-`==` comparator → 400.
- `@Field` remap test: an embedded `id`-mapped-to-`_id` field, `length()==N`
  matches correctly (the standard `Ops.COL_SIZE` path honours Spring Data's
  field remapping via `QueryMapper` — no `$expr` complication).

**Step B5 — README additions:**

- New "length() predicate" subsection under **Attribute filtering**. Documents:
  - **What it does**: `?filter=$[?(@.arr.length()==N)]` on collection fields.
  - **What it doesn't**: strings, objects, non-`==` comparators. Each with the
    specific rejection message.
  - **Escape hatches**: array-match `[?(...)]` for non-empty; `=~` regex for
    string-length approximations; repository-level QueryDSL for anything else.
- One-line note that this closes the "companion size-based predicate" gap the
  2.1.4 CHANGELOG named.

**Step B6 — CHANGELOG.** Same 2.1.5 section as Phase A.

---

### Phase C — cleanup / verify

- Full reactor `mvn clean verify` green.
- Grep for the two features' behaviours in existing IT logs to confirm no
  unrelated regressions.
- Coverage: no new files should drop the module below its existing JaCoCo
  threshold.

---

## What each phase delivers

- **After Phase A alone:** `[N]` in filter grammar works on Mongo; JPA cleanly
  rejects. Closes REQ-2 completely.
  Value: `?filter=$[?(@.productOrderItem[2].state=='completed')]`.
- **After Phase B:** `length()==N` on collections works on both backends.
  Closes REQ-1's most valuable case (the "exists but is empty" state named in
  the 2.1.4 CHANGELOG).
  Value: `?filter=$[?(@.externalReference.length()==0)]`.
- **After Phase C:** shipped in 2.1.5.

## What we're deliberately NOT doing

- `length()` on **strings** — customers use `=~` regex or repository-level
  `LENGTH()`.
- `length()` on **objects / Maps** — no strong use case has surfaced.
- `length() != N`, `> N`, `>= N`, `< N`, `<= N` — customers use array-match
  `[?(...)]` for non-empty, or a repository-level predicate for the rest.
- `$expr`-based emission and any sidecar / annotation mechanism — deferred
  until the JSONB backend forces a broader raw-emission conversation.
- Config gate (`length-enabled`) — not needed; feature is strictly additive
  over the current 400s.

## Estimated size

- **Phase A:** ~50 lines prod + ~100 lines tests
- **Phase B:** ~150 lines prod + ~250 lines tests
- **Docs:** ~40 lines README + 2 CHANGELOG entries
- One working session end-to-end.
