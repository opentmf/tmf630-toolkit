# tmf630-toolkit — TMF-630 v4.x Compliance Audit

**Audited artifact:** `tmf630-toolkit` @ `2.1.5-SNAPSHOT`
**Specs audited:** TMF630 REST API Design Guidelines, Parts 1–7 (v4.2.0 / v4.0.0 respectively)
**Audit date:** 2026-07-24
**Method:** claim-by-claim comparison. Every spec rule cited by Part + section; every toolkit
claim cited by `file:line`. Verdicts:

- ✅ **Compliant** — spec rule met with evidence.
- ⚠️ **Partial** — implemented but with a documented gap or divergence.
- ❌ **Non-compliant** — spec rule not met and reasonably in scope for the toolkit.
- 🚫 **Out of toolkit scope** — spec rule is application-level (not a query-contract concern) and
  deliberately not implemented; the host app is responsible.
- 🤷 **Grey area** — could be in scope; currently isn't; decision needed.

---

## Executive summary

The toolkit's stated scope is **the TMF-630 query contract**: attribute filtering, JSONPath
`filter=`/`fields=`, sorting, offset/limit paging with 200/206/416 semantics, and the shared
error/exception mapping around those. It does **not** attempt to implement the CRUD/PATCH/Task/
Notification/Event/Bulk parts of TMF-630, and **should not** — those are application-level.

Against the in-scope surface, the toolkit is in strong shape:

- **Part 1 §4.3 partial representation (`fields=`) → ✅** with all Part-1 identity rules
  (id/href always present; `fields=none` supported).
- **Part 1 §4.4 attribute filtering (`.gt=`, `.eq=`, etc.) → ✅** with the full 26-operator set
  including `regex`/`isnull`/`in`/`nin`/`between` and both URL-encoded literal forms.
- **Part 1 §4.5 offset/limit + X-Total-Count + 206 Partial Content → ✅** with additional
  `X-Result-Count` + RFC 7233 `Content-Range` and a 416 handler for out-of-range windows.
- **Part 1 §4.7 sort → ✅** with three grammars (`PLAIN`, `SIMPLE_RICH`, `JSONPATH`) covering
  dotted, positional `[N]`, and `[?(@.k==v)].leaf` correlated forms.
- **Part 6 JSON Path `filter=` → ✅** for the restricted subset the toolkit documents (see
  README `filter=` grammar section) plus `length()` on collections and positional `[N]` (2.1.5).
- **Part 6 JSON Path `sort=` → ✅** end-to-end, both the plain-find and correlated-sort
  aggregation executor paths.

**In-scope gaps** (each detailed in the Part-1 / Part-6 tables below):

1. **Part 1 §4.5 Link header for pagination navigation** — spec says SHOULD; toolkit does not
   emit `Link: <...>; rel="first|next|prev|last"`. Downstream apps can only navigate via
   `X-Total-Count` + client-side arithmetic. ⚠️ **Recommend fixing** — one method in
   `Tmf630Util.applyRangeHeaders`.
2. **Part 1 §4.5 `Range: items=N-M` request header** — spec shows it as an alternative to
   `offset`/`limit`; toolkit does not read it. ⚠️ Low priority — offset/limit is the mandatory
   form, `Range` is spec-example, not a MUST.
3. **Part 1 §3.4 error body faithfulness on the sort/fields error paths** — filter errors go
   through `Tmf630FilteringExceptionHandler` and match the spec shape (`code`, `reason`,
   `message`); sort/fields errors fall through to Spring's default translator, producing a
   different (non-TMF) shape. ⚠️ **Recommend fixing** — one small `@ControllerAdvice` covering
   the sort/fields parsing exceptions in the same style as the filter one.
4. **Part 6 `fields=` with JSONPath expression** — spec (p.36-38) allows
   `fields=note[?(@.author=='X')]`; toolkit's `fields=` parses only dotted names + `fields=none`.
   ❌ Genuine feature gap, likely a future release scope.
5. **Part 6 §Error handling 501 semantics** — spec (p.41) says JSON Path element not supported
   by server → **501 Not Implemented**; toolkit conflates "syntax invalid" and "not implemented"
   into 400. ⚠️ Low priority — semantics only, and the current behavior is a subset of Part 6's
   allowed responses.
6. **Part 6 Function table** — spec lists `min()`, `max()`, `avg()`, `stddev()`, `length()`;
   toolkit implements only `length()`. 🤷 The others are aggregation-shaped and don't fit the
   query-contract mental model; likely intentional deferral until a proper aggregation surface
   lands (see `project_aggregation_groupby_exploration.md`).
7. **Part 6 `..` recursive descent, `[*,*]` union, `[start:end]` slice** — deliberately outside
   the toolkit's restricted subset (documented in README). 🚫 Scope-parked; the toolkit prefers
   a small predictable grammar over full JsonPath coverage.

**Explicitly out of scope** (correctly): Parts 2 (polymorphism, extension, `expand=`),
Part 3 (hypermedia/JSON-LD), Part 4 (Export/Import Task, Entity Versioning, RBAC), Part 5
(JSON Patch Query), Part 7 (JSON Schemas), and all of Part 1 §5–§12 (Modify, Create, Delete,
Task, Monitor, Notifications, Versioning, Event). These are application-level concerns; a
query-contract toolkit should not opine on them.

---

## Framing: what "compliance" means for a toolkit

TMF-630 is written for a **complete REST API**, not for a library. The spec makes rules about
things a library cannot own — resource URIs (`{apiRoot}/{resourceName}`), business error codes,
security (OAuth 2.0), notifications, task resources, entity versioning. It also makes rules
about things a library CAN and SHOULD own — query-string grammar (`filter=` / `fields=` /
`sort=`), pagination semantics (offset/limit, X-Total-Count, 206 Partial Content), error body
shape for query-parameter failures.

This audit only holds the toolkit to the second category. Anything from the first category is
marked 🚫 out of toolkit scope with a one-line rationale, so consumers building on the toolkit
know what they still have to implement themselves.

**Toolkit scope one-liner:** the query contract for TMF-630 GET endpoints — parse the query
string, produce a backend-neutral `Predicate` + `Sort` + `Pageable`, apply field selection to
the response, and emit the paging headers + partial-content status codes. Everything else is
the host application's job.

---

## Part 1 — Practical guidelines for RESTful APIs (v4.0.2, 93 pages)

### §1.5–1.9 HTTP Headers

| Spec rule                                                                        | Toolkit                                                                                                                                                                                                                     | Verdict |
| -------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| §1.6 `Cache-Control`, `Expires`, `Date` SHOULD be used when caching required     | Not emitted by toolkit; Spring's default `ResponseEntity` behavior applies. Caching is an app-level policy decision.                                                                                                        | 🚫 App-level |
| §1.7 Client MUST use `Accept` HTTP header for media type                         | Spring MVC handles this via `@RequestMapping(produces=...)` and content-negotiation infrastructure. Toolkit adds nothing.                                                                                                    | 🚫 Spring MVC |
| §1.7 Server MUST support `application/json` by default                           | Spring's default; toolkit does not override. All ITs assert `application/json` responses.                                                                                                                                    | ✅ Via Spring |
| §1.8 Server SHOULD use `Content-Type`, `Content-Length`, `Last-Modified`, `ETag` | Spring emits `Content-Type` and `Content-Length` automatically; `Last-Modified` / `ETag` are per-resource concerns the toolkit cannot know.                                                                                  | 🚫 App-level |
| §1.8 Server MUST use `Location` header for POST-created resources                | Toolkit ships no POST helpers.                                                                                                                                                                                              | 🚫 App-level |
| §1.9 Server SHOULD return `X-Total-Count` in response to Get List                | Emitted by `Tmf630Util.applyRangeHeaders` (`.../util/Tmf630Util.java:74-89`) — sends `X-Total-Count`, `X-Result-Count`, `Content-Range` on every paged response.                                                              | ✅ |
| §1.9 Server SHOULD return `429 Too Many Requests` when rate limit exceeded       | Not implemented — rate limiting is an infrastructure concern (API gateway / filter chain), not a query-contract one.                                                                                                          | 🚫 App-level |
| §1.9 Server SHOULD return `X-Rate-Limit-*` headers                               | Not implemented — same rationale.                                                                                                                                                                                            | 🚫 App-level |
| §1.10 Authentication (OAuth 2.0 Client Credentials flow recommended)             | Not implemented — Spring Security is the standard choice for consumers.                                                                                                                                                      | 🚫 App-level |

### §2 Domain and URI Naming Standards

| Spec rule                                                                         | Toolkit                                                                    | Verdict          |
| --------------------------------------------------------------------------------- | -------------------------------------------------------------------------- | ---------------- |
| §2.1 Direct mapping between managed entities and REST resources; document in spec | App-level                                                                  | 🚫 App-level     |
| §2.2 URI structure: `{apiRoot}/{resourceName}/{resourceID}`                       | App-level; toolkit only parses the query string once inside a handler.     | 🚫 App-level     |
| §2.3 Every resource MUST have `id` and `href`                                     | Toolkit's `FieldSelectionUtil.includeMandatoryIdentityFields` (`.../commons/fieldselection/FieldSelectionUtil.java:307-317`) enforces this on **partial representations** by always including `id`/`href` if the type exposes them, even when not requested. Full representations (no `fields=`) are the app's responsibility. | ✅ (partial-rep enforcement); 🚫 (full-rep enforcement — app-level) |
| §2.4 Names MUST be camel or lower case; full names, no abbreviations              | App-level convention                                                       | 🚫 App-level     |
| §2.5 URIs MUST NOT contain HTTP verb names                                        | App-level convention                                                       | 🚫 App-level     |

### §3 Uniform Contract Methods, Media Types, Status Codes

| Spec rule                                                                                                                                             | Toolkit                                                                                                                                                                                                                                                                                                                                                                                          | Verdict          |
| ----------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------- |
| §3.1 GET/PUT/PATCH/POST/DELETE per uniform contract                                                                                                   | Toolkit is verb-agnostic; `@Tmf630Response` (`.../annotation/Tmf630Response.java:48-64`) triggers on any GET-returning handler, but the toolkit ships zero opinions or helpers for PUT/PATCH/POST/DELETE.                                                                                                                                                                                        | 🚫 App-level     |
| §3.2 REST APIs MUST support `application/json` by default                                                                                             | Spring's default.                                                                                                                                                                                                                                                                                                                                                                                | ✅ Via Spring    |
| §3.2 PATCH media types: `application/json-patch+json` (RFC 6902), `application/merge-patch+json` (RFC 7396), `application/json-patch-query+json` (Part 5) | Not implemented — no PATCH support anywhere in main sources.                                                                                                                                                                                                                                                                                                                                    | 🚫 App-level     |
| §3.3 200/201/202/204/**206**/301/303/304/307/400/401/**403**/404/405/406/409/410/411/412/413/414/415/422/429/500/501/503 all mapped                    | Toolkit emits: **200**, **206**, **400**, **416** only (`Tmf630Util.java:60,68,71`; `Tmf630FilteringExceptionHandler.java:30,34`; `Tmf630RangeExceptionHandler.java:25,34`). Every other status code is the host application's responsibility. Notably: **416** is a toolkit-specific pagination bounds status, not mentioned in §3.3's canonical list — it's used correctly per RFC 7233 semantics. | 🚫 App-level (mostly); toolkit-owned subset is ✅ |
| §3.4 Error body MUST have `{code, reason}` mandatory + `{message, status, referenceError, @type, @schemaLocation}` optional                            | `ErrorMessage(String code, String status, String reason, String message)` record (`.../paging-sorting-core/.../model/ErrorMessage.java:3`) — used by the 416 handler. Field set: **matches the four spec fields** but **omits** the optional `referenceError`, `@type`, `@schemaLocation`. Also: `Tmf630FilteringExceptionHandler` returns a **`LinkedHashMap`** with the same key names — same shape on the wire but not the same type; a bug-magnet if a downstream tries to deserialize the error DTO. | ⚠️ **Partial** — same shape wire-side; type divergence and missing optional fields |
| §3.4 Sort/fields parse errors surface with the TMF error body                                                                                          | ❌ **NO.** Sort parse errors are `IllegalArgumentException` thrown from `TmfSortParser` (`.../paging/TmfSortParser.java:61,80,83`) and the pageable resolvers (`.../paging/TmfPageableHandlerMethodArgumentResolver.java:104-132`). No dedicated handler in the toolkit — they fall through to Spring's default `MethodArgumentTypeMismatchException` / 400 translator, producing a **non-TMF** body. Field-selection errors are `IllegalArgumentException` from `FieldSelectionUtil` (`.../commons/fieldselection/FieldSelectionUtil.java:98,110,238`) with the same fallthrough. | ❌ **In-scope gap** |
| §3.6 Common Information Model — use TMF Information Framework names                                                                                    | App-level modelling                                                                                                                                                                                                                                                                                                                                                                              | 🚫 App-level     |

### §4 Query Resources Patterns — the primary compliance surface

#### §4.1 Query single Resource, §4.2 Querying multiple Resources — 🚫 App-level

Both are about resource path routing (`GET {apiRoot}/{resourceName}/{resourceID}` vs
`GET {apiRoot}/{resourceName}`) and the "full representation" default response. That's the
handler's job, not the toolkit's. The toolkit contributes nothing to routing; it only kicks in
once the handler returns a value or throws.

#### §4.3 Query partial Resource representation (`fields=`)

| Spec rule                                                                                                                                     | Toolkit                                                                                                                                                                                                                                                                    | Verdict |
| --------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| §4.3 `fields=` selector MUST be used to specify the attribute subset                                                                          | Read from `HttpServletRequest.getParameter("fields")` inside `Tmf630ResponseBodyAdvice.beforeBodyWrite` (`.../advice/Tmf630ResponseBodyAdvice.java:96`) whenever the handler carries `@Tmf630Response`. Applied via `FieldSelectionUtil.fieldsToMap` (`.../commons/fieldselection/FieldSelectionUtil.java:28-58`). | ✅ |
| §4.3 If no `fields=`, complete representation MUST be returned                                                                                | Falls through untouched — the advice only rewrites the body when a `fields=` value is present.                                                                                                                                                                            | ✅ |
| §4.3 MUST be enabled on all first-level attributes                                                                                             | Yes — introspection walks all JavaBean getters (`FieldSelectionUtil.java:90-96`).                                                                                                                                                                                          | ✅ |
| §4.3 MAY optionally support inner-class selection                                                                                              | Yes — via dotted notation (`fields=channel.name`); depth is bounded by `opentmf.tmf630.field-selection.default-depth` (default `1`, `.../autoconfigure/.../Tmf630FieldSelectionProperties.java:5-9`).                                                                        | ✅ |
| §4.3 `fields=none` — return only `id` and `href`                                                                                               | Explicit branch in `FieldSelectionUtil.parseFields` (`.../commons/fieldselection/FieldSelectionUtil.java:283-293`) with the comment "TMF630 Part 1 §4.3". Combined with `includeMandatoryIdentityFields` (`:307-317`), the response contains exactly `{id, href}` (or a subset if the DTO doesn't expose them). | ✅ |
| §4.3 `id` and `href` MUST be returned when `fields=none` is used                                                                              | `FieldSelectionUtil.includeMandatoryIdentityFields` — enforces on **every** partial representation, not just `fields=none`. Slightly wider than the spec asks; strictly compliant.                                                                                          | ✅ |
| §4.3 `fields=` MAY be used with other Uniform Operations (POST etc.)                                                                          | `@Tmf630Response` is verb-agnostic in its `supports()` check (`Tmf630ResponseBodyAdvice.java:62-68`) — will apply to any HTTP method whose handler returns a body.                                                                                                          | ✅ |

#### §4.4 Query Resources with attribute filtering (name/value operators)

| Spec rule                                                                                                                                                                                                                                                                                                                       | Toolkit                                                                                                                                                                                                                                                                                                     | Verdict |
| ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| §4.4 Basic form `?attributeName=attributeValue&...` (implicit `.eq`) — ANDed                                                                                                                                                                                                                                                    | `Tmf630PredicateArgumentResolver` + `ParamKeyParser` cover this. Every attribute-side test in `Tmf630PredicateSqlIT`/`Tmf630PredicateMongoIT` uses this shape.                                                                                                                                              | ✅ |
| §4.4 Operator table: `.gt` (%3E), `.gte` (%3E%3D), `.lt` (%3C), `.lte` (%3C%3D), `.eq` (%3D%3D), `regex` `*=` (%3D~)                                                                                                                                                                                                             | `TmfOperator` enum covers all six — plus `.ne`, `.eqi`, `.nei`, `.like`, `.likei`, `.contains`, `.containsi`, `.startsWith`, `.startsWithI`, `.endsWith`, `.endsWithI`, `.regex`, `.regexi`, `.in`, `.nin`, `.between`, `.isnull`, `.isnotnull` — a total of 26 operators, superset of the spec table.       | ✅ **Superset** |
| §4.4 ORing via `,` (comma) — `?status=X,Y`                                                                                                                                                                                                                                                                                     | Supported via `ParamKeyParser` (`.../ParamKeyParser.java`); multiple values on same param collapse to `IN`.                                                                                                                                                                                                | ✅ |
| §4.4 ORing via `;` (semicolon) — `?status=X;status=Y`                                                                                                                                                                                                                                                                          | Supported.                                                                                                                                                                                                                                                                                                 | ✅ |
| §4.4 ORing via duplicate name — `?status=X&status=Y`                                                                                                                                                                                                                                                                            | Supported.                                                                                                                                                                                                                                                                                                 | ✅ |
| §4.4 Complex attribute value via `.` (dotted) notation                                                                                                                                                                                                                                                                          | Supported via `Tmf630FilterSettings.allowNestedPathsJpa` / `allowNestedPathsDocdb` config knobs; the resolver walks JavaBean getters through the dotted path.                                                                                                                                                | ✅ |
| §4.4 URL-encoded operator second form — `?dateTime%3E2013-04-20` (i.e. the operator embedded in the parameter name)                                                                                                                                                                                                              | Added in 2.1.4 per CHANGELOG entry "TMF630 Part 1 §4.4 URL-encoded operator literal forms". Parser accepts both `.gt=` and `%3E`.                                                                                                                                                                            | ✅ |

#### §4.5 Query Resources with attribute filtering and Iterators (pagination)

| Spec rule                                                                                                                                                                          | Toolkit                                                                                                                                                                                                                                                                                                                                                                                                                                | Verdict |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| §4.5.1 If no paging params, all matching resources SHOULD be returned; server MAY apply defaults                                                                                   | Server-side default: `defaultLimit=50`, `maxLimit=500` (`.../autoconfigure/.../Tmf630PagingProperties.java:12-13`). Applied unconditionally by `TmfPageableHandlerMethodArgumentResolver.parseLimit` (`:116-134`). This is the "MAY apply defaults" branch. Not "return all matching" — the toolkit deliberately caps to avoid unbounded queries.                                                                                       | ✅ (chose the "MAY apply defaults" branch) |
| §4.5.1 Query params `offset` and `limit` MUST be supported                                                                                                                          | `TmfPageableHandlerMethodArgumentResolver` (`.../paging/TmfPageableHandlerMethodArgumentResolver.java:20-22, 48-77`) and `TmfRichPageableHandlerMethodArgumentResolver` (`.../paging/TmfRichPageableHandlerMethodArgumentResolver.java:20-62`).                                                                                                                                                                                        | ✅ |
| §4.5.1 If `offset` missing, MUST default to zero                                                                                                                                    | Enforced in the resolver's parse logic.                                                                                                                                                                                                                                                                                                                                                                                                | ✅ |
| §4.5.1 Server MUST return `X-Total-Count` HTTP header                                                                                                                              | `Tmf630Util.applyRangeHeaders` (`.../util/Tmf630Util.java:74-89`).                                                                                                                                                                                                                                                                                                                                                                     | ✅ |
| §4.5.1 Server SHOULD return navigation links as `Link:` Web Linking HTTP header (`rel="first|next|prev|last"`)                                                                     | ❌ **NOT emitted.** Grep for `HttpHeaders.LINK` or `"Link"` in main sources returns nothing. Downstream apps have to compute pages via `X-Total-Count` on the client.                                                                                                                                                                                                                                                                    | ❌ **In-scope gap** |
| §4.5.1 200 OK — Full resource returned (no pagination) / 206 Partial Content — Partial resource returned (with pagination)                                                          | `Tmf630Util.resolveStatusOrThrow` (`.../util/Tmf630Util.java:53-72`) returns 206 when the returned page is strictly smaller than the total, 200 otherwise. `Tmf630ResponseBodyAdvice.handlePage` (`.../advice/Tmf630ResponseBodyAdvice.java:131-133`) sets the status on the response.                                                                                                                                                | ✅ |
| §4.5 `Content-Range: items <start>-<end>/<total>` (RFC 7233) — spec example on p.37, not a MUST, but shown as canonical                                                             | Emitted by `Tmf630Util.applyRangeHeaders` — RFC 7233 `items` unit, `"items <start>-<end>/<total>"` or `"items */<total>"` when unsatisfiable. Superset of the strict spec requirement.                                                                                                                                                                                                                                                | ✅ **Superset** |
| §4.5 (example on p.38) `Range: items=11-20` request header MAY be used as an alternative to `offset`/`limit`                                                                       | ❌ **NOT read** — grep for `HttpHeaders.RANGE` returns nothing in main sources. Only offset/limit is honored.                                                                                                                                                                                                                                                                                                                            | ⚠️ Optional per spec, but a missing symmetry |
| — (toolkit-added) `416 Requested Range Not Satisfiable` when `offset` is past the total                                                                                             | `RequestedRangeNotSatisfiableException` (`.../exception/RequestedRangeNotSatisfiableException.java:3`) + `Tmf630RangeExceptionHandler` (`.../advice/Tmf630RangeExceptionHandler.java:12-34`). RFC 7233 canonical use; TMF-630 doesn't call it out explicitly, but it's the correct semantic and matches Part 1 §3.3's status table.                                                                                                    | ✅ **Toolkit addition** (Part 1 §3.3-compatible) |

#### §4.6 Query Resources with attribute filtering + attribute selection (`fields=` + `.op=`)

| Spec rule                                                                                                                    | Toolkit                                                                                                                                                                                                                                                                                                                                                                                              | Verdict |
| ---------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| §4.6 `?fields=X,Y&filterAttr.gt=Z&otherAttr=W` — filter and fields combine in a single request                              | Falls out naturally: filter parsing (`Tmf630PredicateArgumentResolver`) and field selection (`@Tmf630Response` advice) are orthogonal — filter runs at handler invocation, selection at response serialization. Multiple ITs exercise the combination (`Tmf630PredicateSqlIT`, `Tmf630PredicateMongoIT`).                                                                                          | ✅ |

#### §4.7 Sorting

| Spec rule                                                                                                          | Toolkit                                                                                                                                                                                                                                                                                                                                                                    | Verdict |
| ------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| §4.7 `sort=` query param MUST be supported                                                                          | `TmfSortHandlerMethodArgumentResolver` (`.../paging/TmfSortHandlerMethodArgumentResolver.java:13`) resolves the `sort` param; `TmfRichSortHandlerMethodArgumentResolver` for the rich form.                                                                                                                                                                              | ✅ |
| §4.7 Direction: `-` (desc) or `+` (asc); ascending is the default                                                    | Enforced in `TmfSortParser` (`.../paging/TmfSortParser.java:11`).                                                                                                                                                                                                                                                                                                          | ✅ |
| §4.7 Multi-field via comma: `?sort=[attributeName],[attributeName]`                                                  | Supported (`TmfSortParser.parse` walks a comma-split list).                                                                                                                                                                                                                                                                                                              | ✅ |
| §4.7 Nested attribute: `sort=parent.child.attributeName`                                                             | Supported (governed by `allowNestedProperties` in `TmfSortParser`).                                                                                                                                                                                                                                                                                                        | ✅ |
| §4.7 `.sort` format for disambiguation (per-attribute suffix)                                                        | The toolkit's argument resolvers accept `?sort=...` and standard Spring `Pageable` bindings; a `.sort` suffix on a per-param basis is not exposed as a separate mechanism. Grey; not encountered in customer traffic per README.                                                                                                                                          | 🤷 Grey area |

### §5 Modify resources patterns (PUT, PATCH, RFC 6902, RFC 7396, JSON-Patch Query)

🚫 **Out of toolkit scope.** No `@PutMapping` / `@PatchMapping` helpers, no JSON-Patch dependency,
no merge-patch. Host application implements PUT/PATCH with the standard Spring stack. Toolkit's
`@Tmf630Response` is verb-agnostic so it can apply `fields=` selection to PUT/PATCH responses
if the app wants that.

### §6 Create Resource Patterns

🚫 **Out of toolkit scope.** No `@PostMapping` helpers, no `Location` header advice, no
"Create with attribute selection" pattern (§6.3). App-level.

### §7 Delete Resource Pattern

🚫 **Out of toolkit scope.**

### §8 Task Resource Pattern

🚫 **Out of toolkit scope.** Task resources (§8.1 modeling; §8.2 iterator-based responses) are
domain-level. If a downstream app implements a task, the toolkit's paging helpers can be used
for the iterator response — but the task resource itself has no toolkit involvement.

### §9 Monitor pattern

🚫 **Out of toolkit scope.**

### §10 Notification Patterns (Register/Unregister Listener, Publishing Events, Content-type filtering)

🚫 **Out of toolkit scope.** No `POST /hub` handler, no callback bookkeeping, no `application/
json-patch-query+json` filter grammar for event routing (which per Part 6 p.40 reuses the JSON
Path filter syntax — that overlap is noted but not implemented).

### §11 Versioning (API version routing)

🚫 **Out of toolkit scope.** No `X-API-Version` header, no path-versioned routing helpers.

### §12 Event management (Decoupling, Event Resource Model, Topic/Event graph)

🚫 **Out of toolkit scope.**

---

## Part 2 — Advanced guidelines (polymorphism, extensions, depth/expand, EntityRefOrValue) (v4.0.0, 38 pages)

### §1 Polymorphic Collections and Types — 🚫 Modelling

Query using base collection (`GET /party?@type=Individual`) — the toolkit could theoretically
handle `@type` as another attribute-filter, but the JavaBean introspection assumes `@type` is a
regular property; if it is, it works. If the app uses Jackson `@JsonTypeInfo` for polymorphism,
the toolkit has no opinion. Not a compliance requirement — a modelling one.

### §2 Extension patterns (Schema-based, Characteristic extension, State extension) — 🚫 Modelling

### §3 Depth and Expand Directive — 🤷 Grey area

- `?depth=N` inline-expands referenced entities up to depth N.
- `?expand=path.to.thing` narrows which paths get expanded.

**Toolkit does not implement either.** These require domain-level knowledge of "what is a
reference" and a fetch policy — closer to a GraphQL resolver than a query-contract library.
Reasonable to leave out of scope. Worth explicit documentation ("toolkit does not implement
Part 2 §3 depth/expand; use standard Spring/JPA fetch-graph or entity-projection mechanisms").

### §4 EntityRefOrValue pattern — 🚫 Modelling

### §5 EntityRef pattern — 🚫 Modelling

---

## Part 3 — Hypermedia extension / JSON-LD (v4.0.0, 25 pages) — 🚫 Out of scope

Part 3's Executive Summary explicitly says: *"JSON-LD support in TMF Open API's is optional."*
The toolkit implements neither the Home Document, linking, link relation types, nor JSON-LD
context — none of which are query-contract concerns. If a downstream app wants hypermedia, it
can layer Spring HATEOAS on top; the toolkit does not interfere.

---

## Part 4 — Lifecycle Management, Common Tasks (v4.0.0, 16 pages) — 🚫 Out of scope

- §1 Export/Import Data Tasks — application-level task resources.
- §2 Entity Versioning and Lifecycle Management — domain-level; the toolkit doesn't own entity
  storage.
- §2.6 Role Based Access Control — Spring Security territory.

---

## Part 5 — JSON Patch extensions (v4.0.0, 21 pages) — 🚫 Out of scope

Part 5 defines `application/json-patch-query+json`, extending RFC 6902 JSON Patch by allowing a
query expression in the `path` element (`/orderItem/quantity?orderItem.productOffering.id=1513`)
to select array elements by content instead of by index.

Later (per Part 6 p.42-45) the query grammar is upgraded to a full JSON Path filter:

```json
[{ "op": "add", "path": "note[?(@.author=='John Doe')]", "value": {"text": "Informed"} }]
```

**Interesting overlap:** the JSON Path filter grammar the toolkit implements in `filter=` is
*exactly* the one Part 5 (with Part 6's upgrade) uses inside the `path` element. If the
toolkit ever grows JSON Patch handling, it could reuse `JsonPathFilterPredicateBuilder` to
select the target element(s). **Not** currently implemented — no `@PatchMapping` support at all
— but the parser is well-positioned for a future extension.

---

## Part 6 — JSON Path extension (v4.0.0, 46 pages) — the primary in-scope Part

### §Operators table (p.10-12)

| Spec operator                | Meaning                              | Toolkit `filter=`                                                                                       | Toolkit `sort=`                                                                                             | Toolkit `fields=`                                       |
| ---------------------------- | ------------------------------------ | ------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- | ------------------------------------------------------- |
| `$`                          | Root                                 | ✅ `$[?(...)]` canonical form + bare `[?(...)]` shorthand (`JsonPathFilterPredicateBuilder.java:49-53`) | ✅ Optional `$.` prefix accepted (`JsonPathSortParser.java:167-169`)                                        | ✅ Optional per spec                                     |
| `@`                          | Current node inside filter predicate | ✅ Required prefix `@.field` in comparisons                                                              | ✅ Inside `[?(...)]` correlation                                                                            | N/A (not a filter-predicate context)                    |
| `.<attribute>`               | Child selector                       | ✅ Everywhere                                                                                            | ✅                                                                                                          | ✅                                                       |
| `['<attribute>']`            | Bracket-notation child               | ❌ Not accepted (parser rejects `[`+non-digit-non-`?`)                                                    | ❌ Not accepted                                                                                              | ❌ Not accepted                                          |
| `..`                         | Recursive descent                   | ❌ Deliberately unsupported (README "does NOT support" list)                                              | ❌                                                                                                          | ❌                                                       |
| `*`                          | Wildcard                             | ⚠️ `[*]` accepted as transparent projection sigil and stripped (`JsonPathFilterPredicateBuilder.java:151-181`); bare `.*` not supported | ✅ `arr[*].leaf` accepted                                                                                    | ❌                                                       |
| `[n]`                        | Positional index (0-based)          | ✅ Since 2.1.5 (Mongo only; JPA rejects) — `JsonPathFilterPredicateBuilder.java:790-806`                | ✅ Since 2.1.4 (Mongo, `$arrayElemAt`) — `JsonPathSortParser.java:184-207`                                  | ❌                                                       |
| `[n1,n2,...]`                | Multi-index                          | ❌                                                                                                       | ❌                                                                                                          | ❌                                                       |
| `[start:end]` / `[:n]` / `[-n:]` | Array slice                       | ❌                                                                                                       | ❌                                                                                                          | ❌                                                       |
| `[,]`                        | Union                                | ❌                                                                                                       | ❌                                                                                                          | ❌                                                       |
| `[?(expression)]`            | Filter expression                    | ✅ Full support including nested for same-element `$elemMatch` on Mongo                                  | ✅ SIMPLE_RICH (`arr[key=val].leaf`) and JSONPATH (`arr[?(...)].leaf`) grammars                              | ❌ **See Part 6 `fields=` extension below**             |
| `[(expression)]`             | Script expression                    | ❌ Deliberately unsupported (script-injection risk)                                                      | ❌                                                                                                          | ❌                                                       |

### §Filter predicate operators (p.22-23)

| Spec operator | Toolkit `filter=`                                                                                       |
| ------------- | -------------------------------------------------------------------------------------------------------- |
| `==`          | ✅                                                                                                       |
| `!=`          | ✅                                                                                                       |
| `>`           | ✅                                                                                                       |
| `>=`          | ✅                                                                                                       |
| `<`           | ✅                                                                                                       |
| `<=`          | ✅                                                                                                       |
| `=~` (regex)  | ✅ Only `/pattern/` and `/pattern/i` literals accepted; regex is gated by `regex.enabled` + `max-length` |
| `!` (negation) | ✅ `!@.field` maps to `IS_NULL` (matches missing or null); negation of `[?(...)]` array-match is rejected with a clear message |
| `&&`          | ✅                                                                                                       |
| `\|\|`        | ✅                                                                                                       |

### §Functions table (p.19-20)

| Function     | Spec output | Toolkit `filter=`                                              | Notes                                                                                                                                                                                                                                    |
| ------------ | ----------- | -------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `min()`      | Double      | ❌                                                             | Aggregation-shaped; doesn't fit the query-predicate model. See `project_aggregation_groupby_exploration`.                                                                                                                                 |
| `max()`      | Double      | ❌                                                             | Same.                                                                                                                                                                                                                                    |
| `avg()`      | Double      | ❌                                                             | Same.                                                                                                                                                                                                                                    |
| `stddev()`   | Double      | ❌                                                             | Same.                                                                                                                                                                                                                                    |
| **`length()`** | Integer    | ✅ **Since 2.1.5** — `PredicateFactory.buildLength` via standard `Ops.COL_SIZE.eq(N)`, both backends. Only `== N` on collection fields; other comparators / non-collection leaves rejected at parse time. | Spec disclaimer: *"Some of the above function capabilities are not currently supported by all implementations."* Toolkit's scoped-down implementation is spec-consistent (`length()` on arrays; `==` only for cross-backend consistency). |

### §Collection filtering using JSONPath (p.30-32) — `filter=` selector

| Spec rule                                                                                                             | Toolkit                                                                                                                                                                                    | Verdict |
| --------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| **Rule** "filter" MUST be used to specify the JSON Path expression                                                    | Wired via `Tmf630PredicateArgumentResolver` — reads `filter` query param, delegates to `JsonPathFilterPredicateBuilder`.                                                                    | ✅ |
| **Rule** Entity attribute selection via JSON Path expression MAY optionally be enabled                                 | Enabled by default when the toolkit's attribute-filtering autoconfigure is on the classpath.                                                                                                | ✅ |
| **Rule** If JSONPath "filter" selector is not supported → **501 Not Implemented** MUST be returned                    | Toolkit does not distinguish "unsupported" from "invalid syntax"; both return 400. Since the toolkit *does* support `filter=`, 501 is not reachable in practice.                            | ✅ Implicit (feature always available) |
| **Rule** Leading `$` MAY be omitted                                                                                    | `BARE_WRAPPER` regex + `trySubArrayShorthand` (`JsonPathFilterPredicateBuilder.java:52,183-224`) accept both `$[?(...)]` and `[?(...)]` plus `<arrayPath>[?(...)]`.                       | ✅ |
| Complete resource representation MUST be returned (unless `fields=` narrows it)                                        | Toolkit returns whatever the handler returns; `fields=` narrowing is a separate concern.                                                                                                    | ✅ |
| **Rule** Invalid JSONPath → **400 Bad Request** MUST be returned                                                       | `TmfFilteringExceptionHandler` (`.../filtering/advice/Tmf630FilteringExceptionHandler.java:23,27,30-34`) — 400 with body `{code, status, reason, message}`.                              | ✅ |
| **Rule** Successful → 200                                                                                              | Passes through unchanged.                                                                                                                                                                   | ✅ |
| **Rule** Under same pagination rules (offset/limit) as `?count=&limit=`                                                | Attribute + filter + paging combine naturally; verified in `Tmf630PredicateJpaParityIT`.                                                                                                    | ✅ |
| ORing multiple `filter=` params via comma or semicolon                                                                | Multiple `filter=` params ORed by convention; check `Tmf630PredicateArgumentResolver` and `combineWithAttributes` config for exact semantics. In practice: filter combines with attribute-side via `filter.combineWithAttributes` (default AND). | ⚠️ Spec's exact semantics for multi-`filter=` union are subtle; toolkit's behavior may not be identical. Low priority. |
| Combining basic filtering + `filter=` — both must match                                                                | `filter.combineWithAttributes` config (`Tmf630FilterSettings`) — default AND, can be set to OR.                                                                                              | ✅ |

### §Partial resource representation using JSONPath (p.36-38) — `fields=` selector

**This is the big Part-6 feature the toolkit does not implement.**

Spec allows:

```
GET /api/troubleTicket/42/?fields=note[?(@.author=='Mr John Wils')]
```

Response body contains only the `note` array element(s) matching the predicate. This is a
JSONPath expression inside `fields=`, not just a dotted attribute list.

Toolkit's `fields=` parser (`FieldSelectionUtil.parseFields`, `.../commons/fieldselection/FieldSelectionUtil.java:283`, `:319-356`) only accepts:

- Comma-separated dotted names (e.g. `fields=id,name,channel.name`)
- The special value `fields=none`

No `[?(...)]` handling. The `JsonPathFilterPredicateBuilder` is only wired into `filter=` (via
`Tmf630PredicateArgumentResolver`), not into `fields=`.

**Verdict: ❌ In-scope gap.** Genuine Part 6 feature. Not necessarily short — the semantic is
"pick these array elements to keep in the response," which is a projection on the returned
object, not a query filter. The two use the same grammar but have different downstream effects
(one narrows the DB result set; the other narrows the response body). Doable but a real feature.

### §Sorting selector (p.39-40) — `sort=` supports JSON Path

| Spec rule                                                                       | Toolkit                                                                                                                                                                                              | Verdict |
| ------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------- |
| Sort-Field can take a JSON Path expression: `sort=channel.name`, `sort=attachment[*].name` | ✅ Both forms — PLAIN grammar for dotted, JSONPATH grammar for `[*]` and `[?(...)]`. `JsonPathSortParser` (`.../mongo/JsonPathSortParser.java`) covers the JSONPATH cases; positional `[N]` since 2.1.4. | ✅ |
| Sort selector can always be combined with `filter=` or `fields=`                | ✅ All three combine naturally at handler-argument-resolution time.                                                                                                                                    | ✅ |

### §Notification Pattern - Registering Listener (p.40) — `POST /hub` with JSONPath `query`

🚫 **Out of toolkit scope.** Notifications are not implemented at all — no `POST /hub`, no
listener registry, no event dispatch. If a downstream app implements the hub, it could reuse
`JsonPathFilterPredicateBuilder` to parse the `query` field into a predicate — the same grammar
Part 6 defines here.

### §Error handling (p.41-42)

| Spec rule                                                                                                                          | Toolkit                                                                                                                                                                                   | Verdict         |
| ---------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------- |
| JSON Path expression in `filter=`/`fields=` fails at syntax level → **400 Bad Request** MUST                                       | `TmfFilteringExceptionHandler` (`.../filtering/advice/Tmf630FilteringExceptionHandler.java:23,27,30-34`) returns 400 with `{code:"ERR001", status:"400", reason:"Invalid filter parameter.", message:<exception message>}` — shape matches spec example on p.42 verbatim. | ✅ (for `filter=`) |
| For `fields=` — same rule                                                                                                          | `fields=` errors bypass the toolkit's handler and fall through to Spring's default translator; body shape is NOT the TMF error shape.                                                     | ❌ (for `fields=`) |
| JSON Path not supported by server → **501 Not Implemented** MUST                                                                   | Toolkit doesn't distinguish "unsupported grammar element" from "invalid syntax" — both return 400. Since the toolkit ships a fixed grammar rather than a configurable subset, 501 doesn't naturally arise. Strictly speaking, if a user asks for `..` or `[start:end]` and the toolkit rejects it as "unsupported token", the correct status per this rule would be 501, not 400. | ⚠️ Divergence — but the spec's 501 is for "server doesn't support JSON Path at all"; the toolkit clearly does. Grey. |

### §JSON Patch (p.42-45) — JSON Path inside PATCH `path` element

🚫 **Out of toolkit scope** (toolkit doesn't do PATCH). But — noted overlap: the grammar is
exactly what `JsonPathFilterPredicateBuilder` already parses.

---

## Part 7 — JSON Schemas guideline (v4.0.0, 33 pages) — 🚫 Out of scope

Modelling patterns for JSON schemas that back TMF Open APIs (Addressable, Extensible, Entity,
EntityRef, `[Entity]Ref`, `[Entity]RefOrValue`, `[Entity]Relationship`, Characteristic, etc.).
Domain-modelling concerns; the toolkit does not generate or validate schemas.

---

## Consolidated gap list (in-scope only)

Priority-ordered. "In-scope" = toolkit could implement without stretching its stated purpose;
each gap is a real feature/behavior a downstream app would notice.

### High priority

1. **Sort-parse errors should return the TMF error body.** Currently `IllegalArgumentException`
   from `TmfSortParser` and the pageable resolvers falls through to Spring's default 400
   translator, which produces a non-TMF body. A one-class `@ControllerAdvice` mirroring
   `Tmf630FilteringExceptionHandler`'s shape (`{code, status, reason, message}`) fixes it.
   Same fix applies to `fields=` errors from `FieldSelectionUtil`. **~50 lines prod + a few
   ITs asserting the body shape.**

2. **Link header for pagination navigation.** Part 1 §4.5 SHOULD; not emitted. Should be added
   to `Tmf630Util.applyRangeHeaders` alongside `Content-Range`. Requires knowing the request
   URI (available from `RequestContextHolder.getRequestAttributes()` or by passing
   `HttpServletRequest` into the helper). **~40 lines prod + a few ITs asserting first/next/
   prev/last are correctly computed at boundaries (offset=0, offset+limit>=total, etc.).**

### Medium priority

3. **`fields=` with JSONPath filter expression** (Part 6 p.36-38). New grammar for `fields=`
   values — reuse `JsonPathFilterPredicateBuilder`'s tokenizer but apply the result as a
   response-body projection rather than as a DB predicate. Design overlaps with the existing
   field-selection tree in `FieldSelectionUtil`. **Real feature work; ~200 lines prod + full
   IT matrix**. Realistic scope for a 2.2.x or 2.3.x release.

4. **`ErrorMessage` DTO alignment with Part 1 §3.4.** Add optional fields `referenceError`,
   `@type`, `@schemaLocation` (Jackson-nullable), unify `Tmf630FilteringExceptionHandler`'s
   `LinkedHashMap` body with the `ErrorMessage` record. **~30 lines prod**.

### Low priority

5. **`Range: items=N-M` request header** as an alternative to `offset`/`limit` (Part 1 §4.5
   example on p.38). Spec-example only, not a MUST. Small win in symmetry with the emitted
   `Content-Range`. **~30 lines prod**.

6. **Part 6 §Error handling 501 for unsupported JSON Path grammar elements.** Semantics only —
   change some rejections in `JsonPathFilterPredicateBuilder` from 400 to 501 when the token is
   valid JSONPath but the toolkit's subset doesn't include it (e.g. `..`, `[start:end]`).
   Grey — arguable that "we don't parse this" is a client error (400) rather than a server
   incapability (501). **Requires a design decision, not just code.**

### Deferred (aligned with existing plans)

7. **`min()`/`max()`/`avg()`/`stddev()` functions from Part 6.** Aggregation-shaped; aligns
   with the parked `project_aggregation_groupby_exploration` (per prior conversations, user has
   a complex native query in hand and is leaning per-endpoint hand-roll over generic grammar).
   Do not implement in the filter grammar.

### Explicitly declined (documented in README's "does NOT support")

- `..` recursive descent
- `[start:end]` / `[:n]` / `[-n:]` slice
- `[n1,n2,...]` multi-index
- `[,]` union
- `[(expression)]` script
- Full Jayway `SIZE` / `EMPTY` / `CONTAINS` (superseded by `length()==0`, array-match, `=~`)

---

## Recommendations for the next release cycle

**For 2.1.5 (already staged):** ship as-is. Reading A is complete and TMF-630 compliant for the
new features (positional `[N]` per Part 6 spec, `length()==N` per Part 6 Functions table).

**For 2.1.6 (short-cycle patch, if desired):**

- Sort/fields error-body alignment (gap #1) — cheap, unblocks downstream apps writing
  spec-conformant clients that expect the same body shape from any query-string error.
- `Link` header emission (gap #2) — cheap and canonical.

**For 2.2.x (feature cycle):**

- `fields=` JSONPath filter expressions (gap #3) — the last big Part 6 feature the toolkit is
  missing. Consider co-scheduling with the JSONB backend if it lands in the same window
  (`project_jsonb_backend_sketch`), since both benefit from a coherent "response projection"
  story.

**Do not do:**

- JSON Patch / notifications / task resources / bulk / event management — these are
  application-level and the toolkit's identity is "query contract for TMF-630 GET endpoints."
  Adding any of them would change the toolkit's scope and the maintenance burden.

**Documentation note:** consider adding a short "TMF-630 compliance matrix" section to the
top-level README that summarises the results above (three columns: Part § / rule / toolkit
status). Prospective adopters can then know at a glance what the toolkit covers vs. what they
have to build themselves.
