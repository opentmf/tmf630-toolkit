# A payload field copied into a real column, declared to the toolkit: design note

**For Gökhan:** this page proposes the shape you described for TOOLKIT-JSONB-DATE-FIELD-INDEXABLE-1
(ledger #4250). It holds two small choices for you: the annotation's name (§1), and whether the
toolkit offers a write helper (§4). Everything else follows from your description and is measured
below. Nothing is built.

Measured 2026-11-15 on PostgreSQL 18.1 with `DeclaredColumnProbeIT` (`-Dcolumn.probe=true`): runs at
200,000 rows (19:25:59Z) and at 1,000,000 rows (19:26:55Z). Plain SQL in the forms the toolkit would
send, each `EXPLAIN (ANALYZE, BUFFERS)`ed after six executions of a prepared statement (PostgreSQL's
default plan cache), and also under forced custom and generic plans; the conclusions hold under all
three.

## 1. The declaration

A new annotation on a field of the row entity (the `@Tmf630JsonbBacked` class). The field is a real
column kept **in addition to** the payload:
- the payload stays the complete document;
- the response still comes from the payload;
- the column is a copy of one value of it.

```java
@Entity
@Tmf630JsonbBacked(domainType = CommunicationMessage.class)
public class CommunicationMessageRow {
  @Id private String id;
  @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private String payload;

  @Tmf630JsonbColumn(path = "sendTime")              // the domain path the column copies
  @Column(name = "sent_at") private OffsetDateTime sentAt;

  @Tmf630JsonbColumn(path = "state") private String state;
}
```

- **Name (your choice):** `@Tmf630JsonbColumn` is proposed. It sits beside `@Tmf630JsonbBacked` and
  `@Tmf630JsonbSplitCollection`, and says "this payload field also lives in a column".
  `@Tmf630JsonbIndexedField` would name the purpose rather than the shape.
- **`path`** (required): the domain field's dotted path, resolved against the domain type at startup
  as the URL grammar resolves it. The row field's name need not be the same.
- **The column's name** comes from the field's `@Column(name)`, else from the naming strategy, as the
  toolkit already derives the table name. **The column's type and the bind** come from the field's
  Java type: `OffsetDateTime`, `Instant`, `LocalDate` and `LocalDateTime`; `String`; the integer
  types; `BigDecimal`; `Boolean`; an enum (bound as the text the payload holds).
- **Which paths may be declared:** a scalar at a fixed path (`sendTime`,
  `receiver.postalAddress.city`). A path through a list is refused at startup, with a message: a
  column holds one value. The same goes for a split collection's field, and for two declarations
  of one path.

## 2. What uses the column, and what does not

| Uses the column | Still reads the payload |
| --- | --- |
| every URL value operator on the path: `C = ?`, `C > ?`, `C BETWEEN ? AND ?`, `C IN (…)`, `C <> ?`, `C LIKE ?`, … with a typed bind | a `filter=` JSONPath on the same field (it is a jsonpath over the payload; documented) |
| the sort on the path: `ORDER BY C` | the null tests under the default `isnull-semantics: MISSING_ONLY` (§3) |
| the count, with the same WHERE | the response body |

The SQL of a request that names no declared path does not change.

## 3. The meanings stay the released ones

- **A field absent in the JSON is a NULL column.** `ne`, `nin`, `in`, the orderings and the string
  operators then answer on the column as they answer on the extracted JSON today:
  - SQL's `NULL <> ?` matches no row, exactly as `payload->>'f' <> ?` does for an absent key;
  - the sort puts NULLs where the extracted expression puts them.
- **Rule: under the default `isnull-semantics: MISSING_ONLY`, the null tests keep reading the
  payload.**
  - A NULL column cannot tell an absent key from a JSON null.
  - Today's `isnull` means "the key is absent" (`NOT (payload ? 'f')`), so a JSON null is "present".
  - Under `NULLISH` (absent or null) the column is exact and is used.
  - Consequence: under the default, an `isnull` / `isnotnull` on the partition field does not prune
    partitions.
- **Equality ignores `equality=containment` for a declared path.** The column comparison is
  index-served by its own btree, and there is no JSON type left to be exact about.
- **One difference, by construction: a value no cast reads.** Today, under the default `strict`,
  such a value fails the field's typed filters with a 500. In a declared column it cannot be stored
  at all, so the writer stores NULL or refuses the write. A 500 can thus become an answer, never the
  reverse.
- **The combination suite proves the rest.** A declared twin of each scalar kind must answer every
  cell as the undeclared field does, except where the plan is index-served. The cells of a value no
  cast reads are pinned with the reason above.

## 4. Keeping the column and the payload equal: the writer's duty

Measured:

| Way to fill the column | Plain table | Partitioned on the column |
| --- | --- | --- |
| the service sets it in the same statement as the payload (`UPDATE … SET payload = jsonb_set(…), sent_at = ? WHERE id = ?`) | works | works, and moves the row to its new partition (measured: after the `UPDATE` the row is in `cm_part_11`) |
| a `BEFORE INSERT OR UPDATE` trigger computing it from the payload | works, including after an in-place `jsonb_set` update | **fails**: a NULL key gives `no partition of relation "cm_part" found for row`; a corrected key gives `moving row to another partition during a BEFORE FOR EACH ROW trigger is not supported` |
| a generated column `GENERATED ALWAYS AS (payload->>'state') STORED` | works for text | refused for a date or time (`generation expression is not immutable`); refused as a partition key (`cannot use generated column in partition key`) |

- **A project with a no-triggers rule (DNMS):** the service fills the column in its own write, in the
  same statement as the payload. That includes its in-place `jsonb_set` updates: one `UPDATE` sets
  the payload and the column together. It is the only way that also serves a partition key.
- **The check, for every project:** a query the service runs in its tests (and may run in
  production) lists the rows where the two differ, using `IS DISTINCT FROM` so that NULL and absent
  compare correctly:

  ```sql
  SELECT id FROM communication_message
   WHERE sent_at IS DISTINCT FROM (payload->>'sendTime')::timestamptz
      OR state   IS DISTINCT FROM  payload->>'state';
  ```

  The toolkit can generate it from the declarations: `Tmf630JsonbColumnCheck.mismatches(Row.class)`
  returns the ids. It reads the whole table (measured: 375 ms at 1,000,000 rows), so it belongs in
  tests or a maintenance job, not in a request.
- **A write helper (your choice):** `Tmf630JsonbColumns.copy(domainObject, row)` sets every declared
  field of the row from the domain object, by the declared paths, before a JPA save. A service then
  cannot forget a column on the JPA path. It cannot cover a hand-written `jsonb_set` update; those
  must set the column themselves, and the check above catches a miss. Recommended: offer the helper
  and the check, and no listener or trigger. Both stay visible in the service's own code.

## 5. Partitioning (the toolkit knows nothing about it)

The toolkit only sends the column comparison. The measurement shows that the SQL it would send lets
PostgreSQL prune. The table is partitioned by month on a declared `timestamptz` column (12
partitions), with a btree on the column:

| Request | 200,000 rows | 1,000,000 rows |
| --- | --- | --- |
| one month's page, column range (`sent_at >= ? AND sent_at < ?`, newest first, 20 rows) | **1 of 12 partitions**, 22 buffers, 0.26 ms (a generic plan prunes 11 at run time) | 1 of 12, 22 buffers, 0.21 ms |
| one month's count, column range | 1 of 12, index-only, 47 buffers | 1 of 12, 227 buffers |
| the same page through today's JSON expression | **all 12 partitions**, sequential scans, 5,633 buffers, 134 ms | all 12, 27,849 buffers, 676 ms |
| the newest 20 rows, sorted by the column, no filter | every partition's index in partition order, 22 buffers, 0.44 ms | 22 buffers, 0.45 ms |
| a request WITHOUT the field (a JSON filter on `owner`, count) | all 12 partitions, 5,557 buffers, 28 ms; unpartitioned 5,549 buffers, 24 ms | 27,773 buffers, 132 ms; unpartitioned 27,771, 95 ms |
| `owner` declared too, plus one month | 1 partition, 19 buffers | 1 partition, 310 buffers, 4 ms |

A request without the partition field reads every partition: about the same buffers as one table,
with a few milliseconds of overhead. Declaring the fields a list is narrowed by (§8) is what keeps
such requests cheap.

## 6. Without partitions: the btree against today's scan

| Request (one table) | 200,000 rows: column / today | 1,000,000 rows: column / today |
| --- | --- | --- |
| a page of a date range | 24 buffers, 0.27 ms / 5,625 buffers, 124 ms | 24, 0.21 ms / 27,847, 759 ms |
| a count of a date range (one month) | 49 buffers, 59 ms / 5,549, 112 ms | 229, 282 ms / 27,771, 555 ms |
| the newest 20, sorted by the date | 23 buffers, 0.28 ms / 5,625, 593 ms | 23, 0.28 ms / 27,847, **2,862 ms** |
| equality on `owner` (1 in 1,000) | 4 buffers, 0.84 ms / 5,549, 37 ms | 5, 4.4 ms / 27,771, 111 ms |

- Today's JSON form can have no index for a date: PostgreSQL refuses an index on the text cast.
- A text field can have an expression index today. The column's advantage there is partitioning
  and a typed bind.

Sizes at 1,000,000 rows: the table is 217 MB; the btree on the `timestamptz` column is 21 MB.

## 7. Compatibility

- **An entity that declares nothing sends byte-identical SQL.** The compatibility report against
  the release is the gate. Every request of an undeclared entity must answer SAME, and no rule may
  name a difference for it.
- **The declared paths are an opt-in per field.** The suite's declared twin (§3) shows their
  answers.

## 8. Not only dates

The declaration is the same for any scalar a list is narrowed or sorted by: a state, an owner, a
use-case id.
- Measured on `owner`: equality with 1 value in 1,000 reads 4 to 5 buffers instead of the whole
  table.
- With the partition range: one partition, 19 buffers.
- dnms-681's lists, which are always ANDed with `owner = ?` and often sorted by `sendTime`, are the
  case this serves.
