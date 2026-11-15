package org.opentmf.query.tmf630.jsonb;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a field of a {@link Tmf630JsonbBacked} row entity is a real column holding a COPY
 * of one value of the payload: the domain field at {@link #path()} (since 3.6.0, ledger #4250 and
 * #4256). The payload stays the complete document and the response is still read from it.
 *
 * <p>The URL value operators and the sort on that path compare the column with a typed bind
 * ({@code sent_at >= ?}, {@code ORDER BY sent_at}), so an ordinary index on it serves them, and a
 * table partitioned on it is pruned by them. What still reads the payload: a {@code filter=}
 * JSONPath, the null tests under the default {@code isnull-semantics: MISSING_ONLY} (a NULL column
 * cannot tell an absent key from a JSON null), and, on a column that is not text, the string
 * operators. A request that names no declared path sends the same SQL as before.
 *
 * <p>The column and the payload must hold the same value: that is the service's duty on every
 * write. {@link Tmf630JsonbColumns#copy} fills every declared field of a row from its domain object
 * before a JPA save; a hand-written {@code jsonb_set} update sets the column in the same statement;
 * {@link Tmf630JsonbColumnCheck#mismatches} lists the rows where the two differ.
 *
 * <p>The path is a scalar at a fixed position of the domain type ({@code sendTime},
 * {@code receiver.postalAddress.city}): not through a list, not a list, map or JSON value. The
 * field's Java type is the domain field's (an enum may also be copied into a {@code String}). The
 * column's name is the field's {@code @Column(name)}, else its name in snake case.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Tmf630JsonbColumn {

  /** The dotted path of the domain field this column copies. */
  String path();
}
