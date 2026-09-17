# Plan — bounded `Link` header + query-parameter guard → 3.3.0

> Why now: the DAST-2 active scan of 2026-09-17 (dnms-681 and dnms-journal;
> evidence in the security-assessment repo under
> `wave1-v2/evidence/dast2-2026-09-17/`) turned any TMF-630 list endpoint into
> a `500` with an empty body and a closed connection by sending one long query
> parameter. The toolkit builds the response for every adopter, so the fix is a
> toolkit fix. Target release: **3.3.0** (MINOR — see §5 for why not a PATCH).

## 1. The finding, precisely

```
GET /communicationMessage?fields=<≥2100 chars>&offset=10&limit=10
→ HTTP 500, Connection: close, body "0"   (dnms-681, dnms-journal)
```

Any adopter with a paged list endpoint is exposed. Confirmed on 681 and
journal; 667-ia and store were not exposed only because the scanned endpoints
there were not paged.

## 2. Measured facts (reproduced in this repo against embedded Tomcat, 2026-09-17)

1. **The `Link` header echoes the full request query.** `Tmf630Util.linkWithOffset`
   rebuilds every link with `UriComponentsBuilder.fromUriString(baseUri)
   .replaceQueryParam("offset", …)`, so a 2 100-char `fields=` value appears
   four times (`first`, `prev`, `next`, `last`) in one header value.
2. **Tomcat's response-header buffer is 8 KB** (`server.max-http-response-header-size`,
   Spring Boot default `8KB`). Four links × (base URI + 2 100 chars) ≈ 8.6 KB, so
   `Http11OutputBuffer.checkLengthBeforeWrite` throws
   `org.apache.coyote.http11.HeadersTooLargeException` when the headers are
   written. Thresholds match the field report: with `offset=10&limit=10` (four
   links) the scan measured the overflow at 2 100 chars per value and this
   repo's reproduction fails at 2 000 (shorter base URI); with `limit=1` the
   scan measured 4 000.
3. **Where the exception surfaces depends on the body size.**
   - Body under the 8 KB response buffer: nothing is flushed until the servlet
     returns; Tomcat commits the headers itself in `finishResponse`, catches the
     exception, logs `Http11Processor : Error processing request`, and answers
     `500` with `Connection: close` and **no body**. Spring never sees it; no
     `@ExceptionHandler` runs.
   - Body over the buffer (a real page of resources): the converter's first
     flush commits the headers, the exception is thrown **inside the message
     converter**, and Spring's exception chain runs. The response is *not*
     committed at that moment (Tomcat only reset its byte buffer; the
     `MimeHeaders` still carry the oversized `Link`), so the application's
     catch-all `@ExceptionHandler(Exception.class)` runs, returns its error
     entity, and the second header write fails identically
     (`ExceptionHandlerExceptionResolver : Failure in @ExceptionHandler …`).
     Boot's `/error` dispatch then fails a third time ("response committed
     already"), and the client sees `500`, `Connection: close`, body `0` — the
     terminating chunk of the chunked encoding that was already negotiated.
     This is the exact shape the scan recorded.
4. **No existing limit bounds the offending value.** The filtering module caps
   `filter=` (`json-path-filter.max-length`, 2 048) and regexes (256), but
   `fields=`, `sort=` and any pass-through or unknown parameter are unbounded, and
   `@Tmf630Response` endpoints without a filter binding run none of those checks
   anyway. Tomcat's *request* header limit (8 KB) is the only ceiling, and it is
   above the response-side overflow point.
5. **TMF-630 Part 1 §4.5.1** (v4.2.0, p. 35): `X-Total-Count` **MUST** be
   returned "so that the client can calculate the next page"; the navigation
   `Link` header **SHOULD** be returned "to ease the navigation". The link header
   is a convenience on top of a contract the client can always fall back on.

## 3. Design

Three independent layers; each is sufficient against the scan on its own, and
together they cover adopter-added headers and raised limits.

### (a) Bounded `Link` header — `tmf630-toolkit-paging-sorting-core`

New value type `Tmf630LinkHeaderSettings(int maxParamValueLength, int maxLength)`
with `DEFAULT = (256, 2048)`, and a new overload
`Tmf630Util.applyLinkHeader(headers, baseUri, total, offset, limit, settings)`.
The existing five-argument overload and `tmfPage(...)` delegate with `DEFAULT`.

Rule: the links are built exactly as today (every query parameter preserved,
`offset` rewritten), then

- if any single query-parameter value in the request is longer than
  `maxParamValueLength`, **or**
- if the assembled header value (all links, joined) is longer than `maxLength`,

the `Link` header is **omitted entirely** and the decision is logged once at
`DEBUG` with the request path and the offending length. `X-Total-Count`,
`X-Result-Count`, `Content-Range` and the status are unaffected.

**Refinement of the ruled shape ("drop or truncate any single query value above
a cap").** Dropping or truncating a *filter* value from a navigation link
produces a link that selects a different result set (`next` walks an unfiltered
collection while `X-Total-Count` still describes the filtered one), and
truncating `fields=` or `filter=` produces a value the target would reject with
`400`. Both are silent lies in a header whose only purpose is to be followed
verbatim. Omitting the whole header is the one bounded behaviour that is never
wrong: the spec makes `Link` a SHOULD precisely because `X-Total-Count` +
`offset`/`limit` are the contract, and a client that depends on `Link` fails
visibly instead of paginating the wrong data. The caps are configurable, so an
adopter with legitimately long `fields=` lists raises them rather than losing
the header. Clients must treat `Link` as optional — it is a SHOULD — and page by
`offset`/`limit` against `X-Total-Count` when it is absent; that guidance goes
into the README next to the header table.

Lengths are measured on the values as they appear in the request URI (encoded
form) — that is what ends up in the header bytes Tomcat counts.

Budget arithmetic with the defaults: four links × (≈ 100-char base URI + a few
short paging/sort parameters + up to 256 chars of echoed values) stays under
2 048, and 2 048 leaves the other ≈ 6 KB of the 8 KB buffer to the adopter's
own headers (security headers, CORS, correlation ids). A one-parameter,
256-char request always gets its links; a request whose echo cannot fit is the
attack shape, not a use case.

Configuration (`Tmf630PagingProperties`, nested `link`):

| Property | Default | Meaning |
| --- | --- | --- |
| `opentmf.tmf630.paging.link.max-param-value-length` | `256` | Longest single query-parameter value the `Link` header will echo |
| `opentmf.tmf630.paging.link.max-length` | `2048` | Longest `Link` header value emitted; anything longer is omitted |

`Tmf630ResponseBodyAdvice` gains a constructor taking the settings; the
existing constructors keep `DEFAULT`. `Tmf630FieldSelectionAutoConfiguration`
passes the paging properties' settings when the paging auto-configuration is
present.

### (b) Query-parameter guard — before any handler runs

New `Tmf630QueryLimitsInterceptor` (`HandlerInterceptor`, core) registered by a
new `Tmf630QueryLimitsAutoConfiguration` (autoconfigure) via
`WebMvcConfigurer.addInterceptors`, applied to every mapping. In `preHandle` it
reads `request.getQueryString()` (raw; never `getParameterMap()`, which would
parse a form body) and rejects:

| Condition | Status | Body |
| --- | --- | --- |
| whole query string longer than `max-query-string-length` | `414 URI Too Long` | TMF `ErrorMessage` (`code` `414`) |
| one `name=value` pair whose value is longer than `max-param-value-length` | `400 Bad Request` | TMF `ErrorMessage` naming the parameter and the limit |

Both are thrown as `TmfQueryLimitException` (carries the status) and answered
by `Tmf630QueryLimitExceptionHandler` (`@RestControllerAdvice`,
`HIGHEST_PRECEDENCE`, same reason as the paging/filtering handlers: it must win
over an adopter's catch-all). No handler, argument resolver or advice runs for a
rejected request, so nothing downstream can echo the value.

Configuration (`Tmf630QueryLimitsProperties`, own prefix — the guard is a
request-level concern, not paging's, and must not switch off with
`paging.enabled=false`):

| Property | Default | Meaning |
| --- | --- | --- |
| `opentmf.tmf630.query-limits.enabled` | `true` | Master switch for the guard |
| `opentmf.tmf630.query-limits.max-query-string-length` | `4096` | Longest raw query string accepted (`414` above) |
| `opentmf.tmf630.query-limits.max-param-value-length` | `2048` | Longest single raw parameter value accepted (`400` above) |

Defaults are deliberately generous: `2048` equals the existing
`json-path-filter.max-length`, so no `filter=` that the filtering module
accepts today is newly rejected; `4096` is half of Tomcat's request-line
ceiling, so the typed TMF error is reached before Tomcat's untyped one.

### (c) Never re-enter header writing after `HeadersTooLargeException`

New `Tmf630HeadersTooLargeRecoveryResolver` (`HandlerExceptionResolver`,
`HIGHEST_PRECEDENCE`, registered as a bean by
`Tmf630ExceptionHandlingAutoConfiguration`). When the exception — or any cause
in its chain — is `org.apache.coyote.http11.HeadersTooLargeException` (matched
by class name; the toolkit does not depend on Tomcat) and the response is not
committed, it:

1. logs at `WARN` the request path and the name and size of the largest response
   headers currently set, so the operator learns *which* header overflowed;
2. calls `response.reset()`, which clears the status, the body buffer and — the
   part that matters — Tomcat's `MimeHeaders`, so the next write starts from an
   empty header set;
3. returns `null` so the remaining resolvers (the adopter's catch-all, or Boot's
   `/error`) produce the `500` body exactly as they would for any other failure.

If the response is already committed it logs at `WARN` and returns `null`
without touching the response — nothing can be written any more, and the
container closes the connection. Either way the toolkit never writes headers
into a response that has just refused them.

## 4. Tests — red first

Each test is written against the current code, seen to fail, then made to pass.

1. `Tmf630UtilAndAdviceTest` (unit, core):
   - a 3 000-char `fields=` in `baseUri` → no `Link` header; the other headers
     unchanged;
   - a 257-char value with `maxParamValueLength = 256` → no `Link`; the same
     value with `= 300` → four links present;
   - `maxLength` reached by many short parameters → no `Link`;
   - the five-argument overload keeps today's output for today's inputs (the
     existing assertions are the proof).
2. `Tmf630LinkHeaderBoundsIT` (autoconfigure, **real embedded Tomcat**,
   `RANDOM_PORT`, plain `java.net.http.HttpClient`) — the regression test for the
   scan itself, with the guard disabled so layer (a) is exercised alone:
   - `GET /items?status=<3000 chars>&offset=10&limit=10` → `206`, `X-Total-Count`
     present, no `Link`, body intact, connection kept alive;
   - the same request with a 200-char value → `206` and four links;
   - properties `opentmf.tmf630.paging.link.*` honoured.
3. `Tmf630QueryLimitsIT` (autoconfigure, MockMvc): oversize parameter → `400`
   TMF body naming the parameter; oversize query string → `414` TMF body; the
   handler is not invoked (a counter in the test controller stays at 0); a
   request under both limits reaches the handler; `enabled=false` disables the
   guard; the handler wins over a catch-all `@ExceptionHandler(Exception.class)`.
4. `Tmf630HeadersTooLargeRecoveryIT` (autoconfigure, real Tomcat): a test
   controller sets an 9 KB custom response header itself (bypassing layers (a)
   and (b)); the adopter's catch-all is present. Expected: one
   `HeadersTooLargeException`, then `500` **with the catch-all's JSON body** and
   no second failure — versus today's empty/`0` body. Also a unit test for the
   resolver: unrelated exceptions return `null` untouched; a committed response
   is not reset.
5. Existing suites (`Tmf630ResponseBodyAdviceIT`, `PagingSortingCoreIT`, the
   jsonb parity and Mongo HTTP ITs) stay green — the no-behaviour-change proof
   for requests within the budget.

## 5. Release

- **Version 3.3.0**, not 3.2.3: (b) and (a) add public configuration
  properties and public types (`Tmf630LinkHeaderSettings`, the
  `applyLinkHeader` overload, `TmfQueryLimitException`), and the guard changes
  the observable contract for oversize requests (`400`/`414` where the container
  previously answered `500` or Tomcat's own `400`). Per the repo's versioning,
  new surface is MINOR. The reactor moves to `3.3.0-SNAPSHOT` on the feature
  branch (as 3.2.0 did).
- CHANGELOG under `## [3.3.0]`: *Fixed* (the `500` on long parameters, with the
  scan reference), *Added* (the guard and the `Link` budget, both property
  tables), *Changed* (`Link` omitted when it cannot be emitted within budget).
- README: §4 response headers (`Link` header details + the new omission rule),
  configuration prefixes (new `opentmf.tmf630.query-limits` prefix, new
  `paging.link.*` rows), §"When sort / paging / fields parameters return 400"
  (the `400`/`414` from the guard), §9 error shapes.
- Readiness before "cut ready": push build green on the merged head (this repo
  has no CI workflow — "green" means the local `mvn clean verify` on the exact
  sha, stated as such), Sonar zero without exclusions, both `versions` goals
  with every profile and the estate ignore string, tree identical to
  `origin/develop`.
- `opentmf-versions` is **not** bumped here; the BOM cut is decided separately.
- Downstream note: adopters whose legitimate `fields=` lists exceed 256 chars
  lose the `Link` header until they raise `paging.link.max-param-value-length`;
  the DNMS services do not (their longest documented `fields=` example is under
  100 chars).
