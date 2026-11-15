# URL operators on a field of an open JSON type: design note

**For Gökhan: this page decides TOOLKIT-JSON-TYPED-FIELDS-RULING-1 and whether
TOOLKIT-URL-FILTER-ON-FREE-JSON-VALUE-1 is built, and how. It does not decide
TOOLKIT-JSONB-TYPE-EXACT-EVERYWHERE-RULING-1 or TOOLKIT-NEGATION-MEANING-RULING-1: `Object` keeps
its released answers, and `ne` on the new cells follows whatever that ruling says.**

Recommendation: **(b)**.
- It is the only option that answers the same rows on JSONB and Mongo.
- Every request it changes is a 400 today, and no released 200 changes.
- Its equality is served by the GIN index.
- JPA keeps the 400 under every option.

Measured 2026-11-15 on PostgreSQL 18.1 and Mongo 8.0.5, with the pinned snapshot
3.6.0-20261113.210402-2:
- the probe `CompatFreeJsonProbeIT` in `tmf630-toolkit-compat` (16:23:37–16:23:54Z);
- the compatibility run of the same day, in which 3.5.2 answers the `Object` and `Map` cells
  exactly as the snapshot does.

An answer marked *pred.* is predicted, not measured. Under (a) a `Map` or `JsonNode` field would take
the code path an `Object` field takes today, so its predicted answer is the measured `Object`
answer for the same stored values.

## The question

In a TMF model a characteristic is a name with a value of any JSON type. A service declares that
value as `Object`, `Map<String, Object>` or a Jackson `JsonNode`; dnms-681 declares
`characteristic.value` a `JsonNode`. What does a URL attribute filter on such a field mean:
`characteristic.value=42`, `characteristic.value.gt=10`, `characteristic.value=true`?

## Today (read in the code, answers measured)

- **`Object`** is "polymorphic" in the core. The URL value is bound as the **string** it is, and
  each backend compares it its own way (table 1).
- **`Map` and `JsonNode`** cannot be converted from a String. Every value operator is the core's 400
  `Field "j" has type JsonNode which cannot be converted from a String.`, on JSONB, Mongo and JPA,
  at the top level and inside an array.
  - `isnull` and `isnotnull` take no value and **answer 200 today** on all three. They stay as they
    are under every option.
- **A path below such a field** (`m.key`, `characteristic.value.amount`) is an unknown field to the
  core.
  - Under `on-unknown-field: REJECT`, the default, it is a 400.
  - Under `IGNORE` the clause is dropped, and the request answers **every row with a 200**.
- **`filter=`** reads into the JSON value on JSONB and Mongo. It is not touched by any option here.

## The options

- **(a) JSON-typed fields are polymorphic in the core, like `Object`.** `Map` and `JsonNode`
  answer as `Object` does on each backend: JSONB compares the text, Mongo is string-exact, and Mongo
  refuses ranges. On JPA it needs a new refusal, because the value bound against a JSON column is a
  **500** (measured below).
- **(b) A JSON reading of the URL value, on `Map` and `JsonNode` only.**
  - `42` matches a stored number equal to 42, or the string `"42"`.
  - `true` matches the boolean or the string `"true"`.
  - Any other value matches the string.
  - A range compares numbers only, and a non-numeric range value is the core's 400.
  - The string operators (`contains`, `startswith`, `regex`, …) match stored strings only.
  - JPA refuses with the core's 400, as today.
  - `Object` keeps its released behaviour.
- **(c) Keep the 400.** The README shows the `filter=` form for a characteristic value, which
  already answers on JSONB and Mongo.

**What can only ever be an opt-in** (rules #4072, #4075), under any option:
- ⚠1: resolving paths **below** a JSON field. It changes a released 200 on a service that runs
  `on-unknown-field: IGNORE`.
- ⚠2: any change to `Object`, whose answers in table 1 are released.

None of the cells in tables 2 and 3 carries a mark: each changes a 400 into an answer, or keeps it.

## Table 1: `Object`, released, unchanged under every option (measured)

The stored values: `"42"`, `42`, `42.0`, `true`, `"true"`, `"alpha"`, `7`, `{"amount":5}`, JSON
null, and the key absent.

| request | JSONB | Mongo |
| --- | --- | --- |
| `o.eq=42` | `42`, `"42"` (text; not `42.0`) | `"42"` |
| `o.eq=true` | `true`, `"true"` | `"true"` |
| `o.eq=alpha` | `"alpha"` | `"alpha"` |
| `o.in=42,alpha` | `42`, `"42"`, `"alpha"` | `"42"`, `"alpha"` |
| `o.ne=42` | every stored value except `42`, `"42"`, null and absent (`42.0` included) | every document without the string `"42"`, other fields' documents included |
| `o.gt=10` | **every non-null value** here, compared as text (`"7" > "10"`, `"alpha" > "10"`) | 400 `Operator requires comparable type for field: o` |
| `o.lt=10` | none (text order) | 400, same |
| `o.startswith=4`, `o.regex=^4` | `42`, `42.0`, `"42"` (text) | `"42"` |
| `o.contains=lph` | `"alpha"` | `"alpha"` |

## Table 2: `JsonNode` / `Map` at the top level

Today every cell is a 400 on JSONB, Mongo and JPA. The (b) cells for JSONB and Mongo were asked
directly, with the forms (b) would send.

| request | (a) JSONB | (a) Mongo | (b) JSONB = Mongo | JPA, any option |
| --- | --- | --- | --- | --- |
| `j.eq=42` | pred. `42`, `"42"` | pred. `"42"` | `42`, `42.0`, `"42"` | 400 |
| `j.eq=true` | pred. `true`, `"true"` | pred. `"true"` | `true`, `"true"` | 400 |
| `j.eq=alpha` | pred. `"alpha"` | pred. `"alpha"` | `"alpha"` | 400 |
| `j.gt=10` | pred. every non-null value (text order) | pred. 400 | `42`, `42.0` | 400 |
| `j.lt=10` | pred. none (text order) | pred. 400 | `7` | 400 |
| `j.startswith=4` | pred. `42`, `42.0`, `"42"` | pred. `"42"` | pred. `"42"` (strings only) | 400 |

- JPA under (a), emulated: the URL string bound against the JSON column is a **500**
  (`QueryArgumentException: … argument [42] is not assignable to java.util.Map`). So (a) needs a
  JPA refusal, and (b) keeps the 400.
- `ne` and `nin` on the new cells follow the backend's released meaning, which differs:
  TOOLKIT-NEGATION-MEANING-RULING-1.

## Table 3: a characteristic's value inside an array (`characteristic.value`, a `JsonNode`)

Each document holds `{"name":"x","value":<stored>}` and `{"name":"y","value":"other"}`. Today
every value operator is a 400 on JSONB and Mongo; `isnull` and `isnotnull` answer 200.

| request | (a) JSONB / Mongo | (b) JSONB = Mongo |
| --- | --- | --- |
| `characteristic.value.eq=42` | pred. as table 2 per backend, any element | `42`, `42.0`, `"42"` |
| `characteristic.value.gt=10` | pred. as table 2 | `42`, `42.0` |
| the correlated case: the element named `x` whose value is 42 | not a URL form under any option | `filter=` only, today: `$[?(@.characteristic[?(@.name == 'x' && @.value == 42)])]`; (b)'s reading measured with `exists`/`$elemMatch`: `42`, `42.0`, `"42"` |

The URL grammar has no correlated form on any backend. `characteristic.name=x&characteristic.value=42`
may match two different elements; the README warns about it. TMF-630 v4 shows no URL form for a
characteristic's value. A correlated URL grammar would be a row of its own.

## Table 4: which index serves (b) on PostgreSQL (measured)

The table holds 20,000 rows, with `GIN (payload jsonb_path_ops)` and an expression index on
`(payload->>'j')`; the plans are custom (`EXPLAIN` with the literal).

| form | plan |
| --- | --- |
| today's text equality `payload->>'j' = '42'` (as `Object`) | Bitmap Index Scan on the expression index |
| (b) `payload->'j' IN ('42'::jsonb, '"42"'::jsonb)` | **Seq Scan**, so (b) must not send this form |
| (b) `payload @> '{"j":42}' OR payload @> '{"j":"42"}'` | BitmapOr of two Bitmap Index Scans on the GIN index |
| (b) through the array, jsonpath `payload @? '$.characteristic[*] ? (@.value == 42 \|\| @.value == "42")'` | Bitmap Index Scan on the GIN index |
| (b) through the array, containment of both readings | BitmapOr on the GIN index |
| (b) correlated `filter=`, name and value | Bitmap Index Scan on the GIN index |
| (b) a range through the array, `@.value > 990` | Seq Scan (no key, as every keyless `filter=`) |

So (b) sends its equality as a containment of each reading, which the GIN index serves at the top
level and through an array. Its ranges scan, as every typed range on JSONB does without an
expression index.

## The six questions, answered

1. **What a URL value means against a stored string, number and boolean.**
   - Under (b): `42` finds the numbers equal to 42 (`42`, `42.0`) and the string `"42"`.
   - `true` finds the boolean and the string.
   - A range compares numbers only.
   - Why: a JSON-typed field declares no type to convert to. A characteristic's caller cannot
     know whether the producer stored `42` or `"42"`, and the URL cannot say which.
   - Under (a) the answer is `Object`'s, which depends on the backend (table 1).
2. **Per backend.**
   - (b) is identical on JSONB and Mongo in every measured cell.
   - (a) is not: text order on JSONB, type-exact with ranges refused on Mongo.
   - JPA cannot answer either option correctly: the value bound against a JSON column is a 500.
     JPA keeps its 400 under every option.
3. **The correlated case.** No URL form exists or is added. `filter=` remains the form, and it is
   GIN-served on JSONB (table 4).
4. **The index.** Table 4: (b)'s equality is a containment, GIN-served; its ranges scan.
5. **The type check and `filter=`.**
   - Under (a) and (b) a JSON-typed field is polymorphic, so a type check (not shipped) would admit
     on it what it admits on `Object`.
   - `filter=` is unchanged.
   - Option (b) of TOOLKIT-JSON-TYPED-FIELDS-RULING-1 (JSONB refuses as the core does) would turn
     dnms-681's released characteristic `filter=` into a 400. It is excluded.
6. **The exact list of requests whose answer changes.**
   - Every URL value operator on a field declared `Map` or `JsonNode`, at any position, on JSONB
     and Mongo: 21 operators, all but `isnull` and `isnotnull`. Each is a 400 today, and answered
     after.
   - None on JPA, and none on `Object`.
   - Paths below a JSON field stay unknown fields (⚠1 would need an opt-in).
   - The compatibility report against 3.5.2 proves that no other request changes.

## If (b) is chosen: the build, in outline (nothing built)

- The core:
  - a JSON-typed field (`Map`, `JsonNode`, Jackson 2 and 3) gets a JSON reading of the URL value,
    a list of candidates (the string, plus the number or the boolean it parses as);
  - JPA roots refuse it with a 400 naming `filter=`;
  - `Object` is untouched.
- JSONB: equality and `in` as a containment per candidate, `OR`'d; ranges through a numeric
  jsonpath; string operators on strings only.
- Mongo: `$in` of the candidates; ranges with a number only; string operators as today, matching
  strings.
- Tests:
  - the probe's cells become an IT on both backends;
  - the combination suite's `MAP` cells, plus a `JSON_NODE` kind, are re-pinned;
  - the compatibility report must show only the 400 → answered cells, each named.
