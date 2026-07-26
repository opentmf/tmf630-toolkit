package org.opentmf.query.tmf630.versioning;

/**
 * How a {@link Tmf630Versioned}-marked entity's {@code version} field values are
 * ordered when the resolver picks the "latest" version.
 *
 * <p>Choose per the value shape your writer stores:
 *
 * <ul>
 *   <li>{@link #LEX} (default) — lexicographic (SQL/BSON native string ordering).
 *       {@code "1", "10", "2"} sort as {@code "1", "10", "2"}. Same trap for
 *       semver-like values ({@code "1.10" < "1.9"} lexically). Fast: one-row DB fetch.
 *   <li>{@link #NUMERIC_STRING} — parse each value as an integer, then compare.
 *       DNext-convention numeric-string versions ({@code "0", "1", "2", ..., "13",
 *       "28"}). Requires all rows for a given logical id to hold parse-safe numeric
 *       strings; a non-numeric value poisons the whole comparison.
 *   <li>{@link #SEMVER} — parse each value as a dot-separated list of integers, then
 *       compare component-wise. {@code "1.9" &lt; "1.10" &lt; "2.0"}. Handles arbitrary
 *       depth; a non-semver-parseable value falls back to lex order for that entry.
 * </ul>
 *
 * <p><strong>Performance note.</strong> {@code LEX} uses a single-row DB fetch on all
 * backends (native ORDER BY). {@code NUMERIC_STRING} and {@code SEMVER} fall back to
 * an in-JVM sort — the resolver fetches every version for the logical id and picks
 * the max locally. Cost is {@code O(N)} rows per lookup where N is the version count
 * per logical id; typical PLM domains have N in the single digits, so this is
 * acceptable in practice. Consumers with time-series-shaped versioning (thousands per
 * logical id) should model differently.
 */
public enum VersionOrder {
  LEX,
  NUMERIC_STRING,
  SEMVER
}
