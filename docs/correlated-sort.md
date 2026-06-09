# Correlated Sort — Design Note

## Goal

Support sorting MongoDB collections by a value taken from a *specific element*
of an embedded array, where the element is identified by a correlated
predicate. Example: sort products by the `value` of the `characteristic` whose
`name` is `price`.

This pattern is common in TMF Open API resources (Characteristics,
ExternalReferences, RelatedParty, etc.) but is not expressible in a plain
MongoDB `find()` — sorting on `characteristic.value` picks the min/max value
across the whole array, not the value of a specific element.

## Requirements

- **Existing `find()` path**: no version change; works on whatever
  MongoDB versions the toolkit already supports.
- **Aggregation path** (engaged when any sort term is JSONPATH or
  SIMPLE_RICH): requires **MongoDB 4.0 or newer**, due to the
  `$convert ... onError` form used by the coercion wrappers. Earlier
  servers reject the pipeline at execution time with a driver error.

## Module layout

The toolkit's existing modules are intentionally **DB-agnostic in
production scope** — `attribute-filtering-core`, `attribute-filtering-
autoconfigure`, `paging-sorting-core`, and `paging-sorting-autoconfigure`
declare only `querydsl-core`, `spring-web`, `spring-data-commons`, and
`json-path` as production dependencies; `spring-data-mongodb` appears
only in test scope. We preserve that contract.

Correlated sort introduces a **new opt-in module** for the Mongo emit
path:

```
tmf630-toolkit/
├── tmf630-toolkit-attribute-filtering-core           (unchanged)
├── tmf630-toolkit-attribute-filtering-autoconfigure  (unchanged)
├── tmf630-toolkit-paging-sorting-core                (TmfSort + parseRich)
├── tmf630-toolkit-paging-sorting-autoconfigure       (rich sort resolver)
├── tmf630-toolkit-mongo-aggregation                  ★ NEW
│   └── depends on spring-data-mongodb (production scope)
└── tmf630-toolkit-all                                (umbrella)
```

Consumers that don't need correlated sort (e.g. JPA-only projects) do
not pull `tmf630-toolkit-mongo-aggregation` and pay no Mongo dependency
cost. Consumers that do need it add the new module and wire its bean
into their controllers.

The new module ships:

- **`Tmf630MongoCorrelatedSortExecutor`** — Spring bean that wraps a
  `MongoTemplate` and translates `(Predicate filter, TmfSort sort,
  Pageable pageable)` into a `MongoTemplate.aggregate(...)` call,
  returning `Page<T>`.
- **Auto-configuration** that registers the executor when
  `MongoTemplate` and Spring Data MongoDB are on the classpath.

The `paging-sorting-core` module ships:

- **`TmfSort`** — public type carrying both the plain-sort and
  correlated-sort representations, with `requiresAggregation()` /
  `toPlainSort()` accessors.
- **`TmfSortParser.parseRich(...)`** — new method returning `TmfSort`
  for callers that want to accept correlated terms. The existing
  `parse(...)` method (returning Spring Data `Sort`) is unchanged and
  still 400s on correlated terms, so plain-only controllers stay
  exactly as they are.

The `paging-sorting-autoconfigure` module ships:

- **`TmfRichPageableHandlerMethodArgumentResolver`** — binds
  `TmfRichPageable` as a controller parameter type. This is the
  recommended two-parameter shape `(Predicate, TmfRichPageable)`.
- **`TmfRichSortHandlerMethodArgumentResolver`** — binds `TmfSort` as
  a controller parameter type, supporting the three-parameter shape
  `(Predicate, TmfSort, Pageable)`.

Both rich resolvers are registered alongside the existing resolvers
for Spring Data `Sort` and plain `Pageable`, which are untouched. The
existing `TmfPageableHandlerMethodArgumentResolver` defers
`TmfRichPageable` parameters to the rich resolver via a
`supportsParameter` exclusion so the two never compete.

## Consumer pattern

Correlated-sort-aware controllers use a single `if`. The recommended
shape declares `TmfRichPageable` (extends Spring Data `Pageable`, also
carries the rich sort) as a single parameter:

```java
@RestController
class ProductController {

  private final ProductRepository repository;
  private final Tmf630MongoCorrelatedSortExecutor correlatedExecutor;

  @GetMapping("/products")
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

The three-parameter shape `(Predicate, TmfSort, Pageable)` is also
supported and produces identical results — useful for incrementally
adding correlated-sort awareness to existing `Pageable`-based
controllers.

That's the documented shape. No façade ships from the toolkit — the
two-line branch is short, self-documenting, and matches the existing
toolkit's "building blocks, not orchestrators" style. Plain-only
controllers using `Sort` as the parameter type are unchanged.

## URL Grammar

A sort term is one of:

- **Plain field path** (existing, unchanged): `name`, `+name`, `-createdOn`,
  `nested.field`.
- **JsonPath expression** (new): a string starting with `$.` (after the
  optional `+` / `-` direction prefix), e.g.
  `$.characteristic[?(@.name == 'price')].value`.

Multiple sort terms remain comma-separated.

### Examples

Plain (unchanged):

```
?sort=-createdOn,+id
```

Correlated, ascending:

```
?sort=$.characteristic[?(@.name == 'price')].value
```

Correlated descending, chained with a plain field:

```
?sort=-$.characteristic[?(@.name == 'price')].value,+id
```

Recommended composition with an explicit existence filter:

```
?filter=$[?(@.characteristic[?(@.name == 'price')])]
&sort=$.characteristic[?(@.name == 'price')].value
```

### Function wrappers — coercion and aggregator (added in 2.1.3)

The JSONPath sort grammar accepts the same five function wrappers as
simple-rich — three coercions (`num` / `str` / `date`) and two aggregators
(`min` / `max`). Two surface forms are accepted; both lower to the same AST
and are interchangeable for single-cardinality data:

| Form | Example |
| --- | --- |
| Leaf-level call | `$.characteristic[?(@.name == 'price')].num(value)` |
| Outer wrap around the entire expression | `num($.characteristic[?(@.name == 'price')].value)` |

The leaf-level form is the natural pick when the path traverses additional
dotted segments after the final `[?(...)]` — pre-function segments are
promoted to naked array hops with an `AlwaysTrue` predicate, so the
aggregator iterates the right collection. The outer-wrap form is preferable
when the trailing path crosses an **object** intermediate before reaching
the inner array; promoting an object segment to a naked hop would attempt
`$filter` on a non-array and fail at query time.

`num()` is the affordance for the most common downstream issue: TMF
characteristic values are often stored as `String` even when their
`valueType` is `number` (the DNext storage convention). A bare-leaf sort
would collate them alphabetically — `"105.34"` < `"12.2"` < `"4.31"`. The
`num()` wrapper coerces each value via
`$convert(input, "double", onError: null)` and gives the expected numeric
order. See **Simple-rich grammar → Aggregator and coercion functions** for
the full semantics, including how mixed-type leaves compose and what
`.date()` accepts as input — those rules apply identically here.

**Filter coercion is not supported.** The JSONPath filter pipeline emits
backend-agnostic QueryDSL predicates and there is no symmetric way to express
`cast(value as decimal)` in MongoDB's find language. Use `num()` in the sort
to force numeric ordering of string-typed characteristic values; for
filtering by numeric range against such fields, persist the value with its
declared type.

## Semantics

- A sort term is **correlated** when its key is a JsonPath expression
  containing a predicate (`[?(...)]`).
- The correlated key resolves to the **first** matching element's targeted
  scalar (mirrors `$first` in the aggregation). If no element matches, the
  resolved value is `null`.
- **Null / missing sort keys land last regardless of direction.** The
  executor pairs every emitted sort key with a `_hasKeyN` companion
  (`0` when the key resolves to a value, `1` otherwise — both MISSING
  and explicit `null` collapse to `1` via `$ifNull`) and prepends it
  ascending in the `$sort` document. This matches the
  PostgreSQL / Oracle / Elasticsearch convention and keeps paginated
  `?sort=field&limit=N` requests from returning a first page of
  missing-field rows. (Before 2.1.1 the executor inherited Mongo's
  BSON natural ordering — ASC put nulls first, DESC put them last —
  which diverged from in-memory comparators that follow the
  nulls-last convention.)
- **No implicit filtering.** Callers who want only matching documents must
  pair the sort with an explicit `filter=`. This is by design: it keeps
  semantics predictable and avoids hiding a `$match` behind a sort.
- A sort term whose path ends with a predicate and **no trailing `.field`**
  (e.g. `$.characteristic[?(@.name == 'price')]`) is rejected with
  **HTTP 400** at parse time — there is no scalar to compare against.
- Sort keys that resolve to non-scalar values at runtime (e.g. the leaf
  field happens to be an array or object) remain **undefined behavior**;
  we do not validate or guarantee an ordering for that case.
- **Tie-breaking is unspecified.** When multiple documents resolve to
  the same sort key, MongoDB does not guarantee a stable order between
  them — the order can vary across replica reads, shard merges, and
  server upgrades. Callers who need deterministic output must include
  a stable plain term (e.g. `+id`) at the tail of the sort.

## Nested correlation

A correlated path may chain predicates at multiple levels — the translator
is recursive, so this falls out for free without special-casing.

Example: sort by the `price` of the first `featured` subcategory of the
`electronics` category.

```
?sort=$.categories[?(@.name == 'electronics')]
       .subcategories[?(@.featured == true)]
       .price
```

(Whitespace shown for readability — in a real query string the path is one
unbroken token.)

This translates to nested `$let` / `$filter` / `$first` layers:

```
{ $let: {
    vars: { _c: { $first: { $filter: {
      input: { $ifNull: ["$categories", []] },
      as:    "c",
      cond:  { $eq: ["$$c.name", "electronics"] }
    }}}},
    in: { $let: {
      vars: { _s: { $first: { $filter: {
        input: { $ifNull: ["$$_c.subcategories", []] },
        as:    "s",
        cond:  { $eq: ["$$s.featured", true] }
      }}}},
      in: "$$_s.price"
    }}
}}
```

Two notes:

- Each layer wraps `input` in `$ifNull: [..., []]` so a missing array at any
  intermediate level resolves cleanly to `null` at the leaf instead of
  raising `$filter requires an array as input`.
- Predicates may themselves contain nested `[?(...)]` (e.g. matching an
  element by a condition on its own sub-array). That case is already
  handled by the correlated-filter translator and is reused as-is.

## Capability matrix

What the JsonPath sort grammar buys us, and what we deliberately reject.
The biggest non-obvious win is **grammar reuse**: every predicate construct
already supported by `filter=` works in `sort=` with no extra documentation.

### Supported

| Construct | Example |
| --- | --- |
| Plain field path (existing) | `+name`, `-createdOn`, `nested.field` |
| Single-condition predicate | `$.characteristic[?(@.name == 'price')].value` |
| Multi-condition predicate (`&&`) | `$.characteristic[?(@.name == 'price' && @.currency == 'USD')].value` |
| Logical OR in predicate (`\|\|`) | `$.characteristic[?(@.name == 'price' \|\| @.name == 'cost')].value` |
| Comparison operators (`>`, `<`, `>=`, `<=`, `!=`) | `$.measurements[?(@.timestamp > '2026-01-01')].value` |
| Predicate on a sub-array of the matched element | `$.orders[?(@.tags[?(@.name == 'priority')])].total` |
| Multi-level nested correlation | `$.categories[?(@.name == 'x')].subcategories[?(@.featured == true)].price` |
| Multiple correlated terms in one sort | `sort=-$.c[?(@.name == 'price')].value,+$.c[?(@.name == 'stock')].value` |
| Mixed correlated + plain terms | `sort=-$.c[?(@.name == 'price')].value,+id` |
| Wildcard `[*]` as transparent projection sigil | `$.characteristic[?(@.name == 'price')][*].value` (equivalent to the same expression without `[*]`) |

`[*]` is the canonical JsonPath syntax for projecting fields across all
elements of an array. Mongo's path expression auto-projects implicitly,
so the toolkit strips `[*]` at parse time and treats the expression as
if it were the equivalent Mongo-style trailing path. This aligns
toolkit syntax with jsonpath.com / Jayway: a user who writes a sort
URL through a JsonPath lens (which the `$.` prefix invites) can
include `[*]` and the same expression evaluates correctly in both the
toolkit and external JsonPath evaluators.

### Rejected with HTTP 400

These are syntactically valid JsonPath but cannot resolve to a single scalar
sort key, so we reject them at parse time rather than emit a pipeline that
would fail or behave unpredictably at runtime.

| Construct | Example | Reason |
| --- | --- | --- |
| Trailing predicate, no leaf field | `$.characteristic[?(@.name == 'price')]` | No scalar to compare |
| Recursive descent | `$..value` | Multi-value |
| Array slice | `$.characteristic[0:5].value` | Multi-value |
| JsonPath functions | `$.characteristic.length()` | Not in the Jayway subset the toolkit uses for filters |

## Simple-rich grammar (alternative parser)

The toolkit accepts a second sort grammar alongside the JsonPath form — call
it **simple-rich**. It is a deliberate convenience subset (equality-only,
single-condition, with implicit defaults) for the common TMF case:
`array[id=X].nested.field`. Users who need more expressiveness drop to
JsonPath; the two grammars coexist with no try/fallback logic and lower to
the same internal IR.

### Disambiguation

After stripping the optional `+` / `-` direction prefix, each sort term is
classified by its leading shape:

| Term shape | Parser | Backend |
| --- | --- | --- |
| Plain dotted path, no `[` | existing plain | `find()` |
| Contains `[`, does **not** start with `$.` | **simple-rich** (new) | aggregation |
| Starts with `$.` | JsonPath | aggregation |

Classification is O(1) per term. The three forms mix freely in a single
sort string:

```
?sort=-prodSpecCharValueUse[DATA_ALLOWANCE].productSpecCharacteristicValue.value,+name
```

### Lowering rules

Simple-rich is a pure front-end. The parser emits the same IR
(`ArrayMatchNode` / `ComparisonNode` tree) as JsonPath, after which every
downstream stage — allowlist checks, predicate translation, aggregation
emission — is shared.

| Simple-rich fragment | Lowering / Mongo behavior |
| --- | --- |
| `arr[key=val]` | `$first $filter` over `arr` with predicate `@.key == 'val'` (one explicit hop) |
| `arr[val]` | same as above with the configured default key (e.g. `id`) |
| Trailing dotted path after the last `]` (e.g. `.deep.path.value`) | Mongo path expression `$$mN.deep.path.value` — Mongo auto-traverses through both objects (sub-document field access) and arrays (auto-projection of field across elements). No additional hops are introduced. |

Chains compose: each `[...]` adds an explicit array hop with a
predicate; everything else is path traversal. The colleagues' example:

```
prodSpecCharValueUse[DATA_ALLOWANCE].productSpecCharacteristicValue.value
```

resolves to: pick the `prodSpecCharValueUse` element whose `id`
equals `DATA_ALLOWANCE` (one explicit hop, predicated), then evaluate
`productSpecCharacteristicValue.value` as a Mongo path expression
relative to that matched element. Because
`productSpecCharacteristicValue` is an array, Mongo auto-projects
`.value` across its elements, yielding an array of values. Mongo's
`$sort` on an array key uses the **min** element ascending and the
**max** element descending. For TMF resources where the array is
typically single-element, that effectively gives "the value of the
first element" without the parser having to know the field is an
array.

### Aggregator and coercion functions

V1 ships two small function families on top of the basic path grammar.

**Aggregator functions** operate on a leaf array, replacing the implicit
"first element" rule with an explicit reduction:

| Form | Meaning | Aggregation primitive |
| --- | --- | --- |
| `arr.max(field)` | maximum value of `field` across `arr` | `$max: "$arr.field"` |
| `arr.min(field)` | minimum value of `field` across `arr` | `$min: "$arr.field"` |

`min` / `max` over an empty or missing array yield `null`, which then
sorts per the null-sort rules in **Semantics** above (last regardless
of direction).

**Coercion wrappers** compose with aggregators or wrap raw fields, and
guarantee a stable target type for the sort key:

| Form | Meaning | Aggregation primitive |
| --- | --- | --- |
| `.str(expr)` | coerce `expr` to string | `$convert: { input: expr, to: "string", onError: null }` |
| `.num(expr)` | coerce `expr` to double | `$convert: { input: expr, to: "double", onError: null }` |
| `.date(expr)` | coerce `expr` to date | `$convert: { input: expr, to: "date", onError: null }` |

**`.date(...)` accepted inputs.** The `.date(...)` wrapper delegates to
MongoDB's native `$convert` and ships no format parameter and no
multi-format guessing. The accepted inputs are exactly:

| Input form | Behavior |
| --- | --- |
| ISO-8601 string (e.g. `"2026-04-29T12:07:07Z"`) | parsed to BSON Date |
| Number (Long / Double) | interpreted as **milliseconds since Unix epoch** |
| ObjectId | extracts the embedded timestamp |
| Already a BSON Date | passthrough |
| Anything else (non-ISO strings, `"15-Jan-2024"`, locale formats, …) | `null` per `onError` |

This is deliberate: format strings and multi-format auto-detection are
classic sources of subtle locale and ambiguity bugs (`"01/02/2024"` is
January 2 in the US, February 1 in Europe). Projects that store dates
in a non-ISO string format must either migrate the data, sort the
already-string field lexicographically when the format is itself
sortable, or normalize at the application layer before the sort hits
the toolkit. A future v2 may add a small named-format allowlist
(`'iso'`, `'rfc1123'`, `'epoch_seconds'`) — never raw format strings,
never multi-format guessing.

**Composition order matters** when the leaf field can hold values of
different types across documents. Both orderings parse and execute, but
they produce different results:

| Form | Computes | Notes |
| --- | --- | --- |
| `arr.max(str(value))` | coerce each element first, then max | **Recommended** for mixed-type fields — predictable lexicographic ordering |
| `arr.str(max(value))` | BSON-max first, then coerce | BSON ranks strings above numbers; rarely the intended semantics |

`onError: null` is always emitted, so a malformed value on one document
yields a `null` sort key for that document rather than aborting the whole
aggregation. The existing null-sort rules then apply (last regardless
of direction).

### Decisions baked in

- **Default key for the bare form is `id`**, exposed as the property
  `opentmf.tmf630.attribute-filtering.simple-rich.default-key`. Projects
  whose convention uses `name`, `code`, etc. flip it globally without
  code changes.

- **Trailing dotted paths are Mongo path expressions, not naked array
  hops.** Anything after the last `[...]` is concatenated as
  `$$mN.<trailing-path>` and Mongo handles object traversal and array
  auto-projection itself. This avoids the "what if the intermediate is
  an object, not an array" footgun that schema-unaware naked-hop
  semantics ran into. For arrays the auto-projection plus `$sort`'s
  array semantics (min for asc, max for desc) effectively pick the
  smallest/largest element; for single-element arrays — the common
  TMF case — that is the value of the only element. Users who want
  explicit `min` / `max` write the function form
  (`.min(field)` / `.max(field)`).

- **Value grammar inside `[...]` is restricted to** `[A-Za-z0-9_.\-]+` on
  each side of the optional `=`. Spaces, quotes, comparison operators, or
  any other character → HTTP 400 with an error message pointing the user
  at JsonPath.

- **Equality only.** No `>`, `<`, `>=`, `<=`, `!=`, `&&`, `||`. The escape
  hatch is JsonPath; the simple grammar stays genuinely simple.

- **Sort direction never selects the aggregator.** The `+` / `-` prefix
  controls ordering only; it does not change which element or which
  aggregator is used to compute the sort key. Tying `max` to `-` and
  `min` to `+` would make the same path resolve to different keys based
  on direction — silent, hard to explain, and ambiguous when multiple
  terms with different directions touch the same path. Users who want
  max/min write it explicitly. If a project finds itself repeatedly
  typing the same aggregator for a given field, that's an argument for
  a per-resource default registered in code, not a directional
  convention; we don't ship that until asked twice.

- **Coercion failures yield `null`, never abort.** All coercion wrappers
  emit `$convert ... onError: null`. A malformed value on one document
  produces a `null` sort key for that document; existing null-sort
  rules apply.

### Deferred

V1 deliberately ships only `min` / `max` aggregators and `str` / `num` /
`date` coercions. The following are natural future extensions, held
until real usage demands them:

- `count()` — sort by array length ("orders with most items first").
- `sum(field)`, `avg(field)` — numeric aggregations.
- `first(field)`, `last(field)` — explicit alternatives to the implicit
  naked-hop rule.
- `bool` coercion.
- Aggregator support in the JsonPath grammar — currently simple-rich
  has aggregators that JsonPath does not, an intentional asymmetry
  scoped to v1.

Custom expressions, `$reduce`-style folds, and arbitrary aggregation
operators are out of scope permanently — that's not the simple-rich
grammar's job; users with that level of need stay on JsonPath or write
domain-specific Mongo aggregations themselves.

### Capability summary

| Construct | Simple-rich | JsonPath |
| --- | --- | --- |
| Equality match | yes | yes |
| Comparison operators (`>`, `<`, ...) | drop to JsonPath | yes |
| Multi-condition (`&&`, `\|\|`) | drop to JsonPath | yes |
| Predicate on a sub-array of the matched element | drop to JsonPath | yes |
| Multi-level chained correlation | yes | yes |
| Trailing dotted path crossing object/array intermediates | yes — Mongo path auto-traversal (min/max element on multi-element arrays per `$sort` direction) | rejected (400) |
| Aggregator functions (`min`, `max`) | yes (v1) | not supported |
| Coercion wrappers (`str`, `num`, `date`) | yes (v1) | not supported |
| Trailing-predicate-only (no leaf field) | n/a (no leaf-less form) | rejected (400) |
| Mixed with plain terms in one sort | yes | yes |

### Filter parity (deferred)

Whether the simple-rich grammar should also be accepted on the filter side
— extending the existing query-param shortcut with `[id=val]` selectors
for correlated filters without requiring JsonPath — is a related but
separate question. It carries URL-encoding wrinkles around `[`, `]`, and
`=` inside query-param keys that don't apply to sort. Defer until the sort
form has shipped and real usage tells us whether filter parity is wanted.

### Worked example

Against the ProductOffering payload, sorting on the data-allowance value:

```
GET /api/productCatalogManagement/v4/productOffering
  ?sort=prodSpecCharValueUse[DATA_ALLOWANCE].productSpecCharacteristicValue.value
```

For the document shown the sort key resolves to `"200GB"` — the
`prodSpecCharValueUse` element with `id == 'DATA_ALLOWANCE'` is selected
by the predicate, the naked `productSpecCharacteristicValue` hop reduces
to the first element of that array, and `.value` reads its `value` field.
There is no exact JsonPath-textual equivalent for the naked hop; users
who need this in JsonPath must rephrase the query with explicit element
selection.

## Parser changes

Scope is limited to the sort parser:

- Replace the current comma split with a **depth-aware splitter** that
  tracks `[` / `]`, `(` / `)`, and quote state, splitting only on
  top-level commas.
- After stripping the optional `+` / `-` direction prefix, classify each
  term:
  - Starts with `$.` → **JSONPATH** kind, hand off to the existing
    JsonPath parser (reused as-is from the filter pipeline).
  - Contains `[` and does not start with `$.` → **SIMPLE_RICH** kind,
    hand off to the new simple-rich parser.
  - Otherwise → **PLAIN** kind, existing plain behavior.
- The simple-rich parser is a narrow front-end (equality only, single
  condition, default-key inference, trailing dotted path = Mongo path
  auto-traversal) whose
  output is the **same IR** as the JsonPath parser
  (`ArrayMatchNode` / `ComparisonNode` tree). All downstream stages —
  allowlist checks, predicate translation, aggregation emission — are
  shared between the two.
- Each parsed sort term carries: direction, kind (`PLAIN`,
  `SIMPLE_RICH`, `JSONPATH`), and the original expression text (so the
  backend can re-emit it without re-parsing).

## Backend split (Mongo)

The branch lives in the consumer's controller, expressed against
`TmfSort.requiresAggregation()` (see **Consumer pattern** above). The
toolkit does not own the controller call site; it provides the parser,
the rich sort type, and the aggregation executor as building blocks.

**When all sort terms are PLAIN** (`sort.requiresAggregation() == false`):
- The controller calls `repository.findAll(filter, pageable.withSort(sort.toPlainSort()))`.
- Spring Data + QueryDSL `MongodbDocumentSerializer` translate to a
  `find()` with the QueryDSL-derived predicate and a `$orderby`.
- **No regression for plain requests** — exactly the existing path.

**When any sort term is JSONPATH or SIMPLE_RICH**
(`sort.requiresAggregation() == true`):
- The controller calls `correlatedExecutor.findAll(EntityClass.class, filter, sort, pageable)`.
- `Tmf630MongoCorrelatedSortExecutor` (in `tmf630-toolkit-mongo-aggregation`)
  builds an `Aggregation` pipeline and runs it via `MongoTemplate.aggregate(...)`:
  1. `$match` — from the QueryDSL `Predicate`, serialized via the same
     `MongodbDocumentSerializer` the find path uses.
  2. `$addFields` — one synthetic key per correlated term (`_sortKey0`,
     `_sortKey1`, …), each computed via `$let` + `$first` + `$filter`
     over the named array.
  3. `$sort` — over synthetic + plain keys, preserving the user's term
     order and directions.
  4. `$project` — strip the synthetic keys before returning documents.
  5. Paging — `$facet` returning `data` (`$skip` / `$limit`) and
     `total` (`$count`) in a single round-trip, assembled into a
     `Page<T>`.

JsonPath → aggregation mapping for a term shaped
`$.<arrayPath>[?(<pred>)].<field>`:

```
{
  $let: {
    vars: {
      _m: {
        $first: {
          $filter: {
            input: { $ifNull: ["$<arrayPath>", []] },
            as:    "c",
            cond:  <pred translated to $expr>
          }
        }
      }
    },
    in: "$$_m.<field>"
  }
}
```

The predicate translator from the correlated filter case
(`@.name == 'price'` → `$eq`) is reused as-is — the new module depends
on `attribute-filtering-core` for the predicate AST and translation.

## JPA backend

Correlated sort is **not supported on JPA** in this iteration. JPA-backed
controllers should keep using Spring Data `Sort` as the controller
parameter type — the existing parser (`TmfSortParser.parse(...)`) rejects
JSONPATH and SIMPLE_RICH terms with HTTP 400 already, so JPA paths get
clean rejection automatically without depending on any of the new pieces.

The new `tmf630-toolkit-mongo-aggregation` module is Mongo-only by name
and by dependency (`spring-data-mongodb` is a production-scope
dependency only there). JPA-only projects do not pull it in and pay no
cost. We will revisit JPA correlated sort if demand warrants a
correlated-subquery emitter.

## Out of scope

- Sorting on multiple matches per document (always take the first match).
- Implicit filtering driven by sort presence.
- Sort keys whose leaf field resolves to a non-scalar value at runtime.
- PostgreSQL / JSONB backend (separate parked sketch).
- Performance work — correlated sort necessarily moves the request to the
  aggregation framework; index strategy for the synthetic key is left to
  consuming projects.
- Configuration flag to disable correlated sort. JsonPath detection is O(1)
  per term and rejecting at config-time would still require parsing the
  term, so a flag offers no parsing-duration benefit. Can be revisited if a
  policy-level (not performance-level) need appears.
