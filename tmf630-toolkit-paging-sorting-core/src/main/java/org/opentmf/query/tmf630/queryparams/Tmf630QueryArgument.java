package org.opentmf.query.tmf630.queryparams;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

/**
 * What one handler argument reads from the query string — the answer a {@link
 * Tmf630QueryArgumentClassifier} gives for it.
 *
 * @param kind how the argument relates to the query string
 * @param names the query-parameter names it reads; non-empty only for {@link Kind#NAMED}
 */
public record Tmf630QueryArgument(Kind kind, Set<String> names) {

  private static final Tmf630QueryArgument NONE = new Tmf630QueryArgument(Kind.NONE, Set.of());
  private static final Tmf630QueryArgument UNKNOWN =
      new Tmf630QueryArgument(Kind.UNKNOWN, Set.of());
  private static final Tmf630QueryArgument FILTER = new Tmf630QueryArgument(Kind.FILTER, Set.of());

  /** How an argument relates to the query string. */
  public enum Kind {

    /** Reads nothing from the query string: a path variable, a header, the body, ... */
    NONE,

    /** Reads exactly {@link #names()}. */
    NAMED,

    /**
     * May read any name, and the guard cannot see which — a catch-all map, the raw request, a
     * model attribute. The handler is not judged unless it states its vocabulary with {@link
     * Tmf630AcceptsQueryParameters}.
     */
    UNKNOWN,

    /**
     * A TMF-630 filter terminal: the whole query string is the filter grammar's, which refuses
     * unknown names itself. The handler is never judged by this guard.
     */
    FILTER
  }

  public Tmf630QueryArgument {
    Objects.requireNonNull(kind, "kind");
    names = Set.copyOf(names);
    if (kind == Kind.NAMED && names.isEmpty()) {
      throw new IllegalArgumentException("NAMED needs at least one name");
    }
    if (kind != Kind.NAMED && !names.isEmpty()) {
      throw new IllegalArgumentException(kind + " takes no names");
    }
  }

  /** An argument that reads nothing from the query string. */
  public static Tmf630QueryArgument none() {
    return NONE;
  }

  /** An argument that reads exactly these query-parameter names. */
  public static Tmf630QueryArgument named(String... names) {
    return new Tmf630QueryArgument(Kind.NAMED, Set.copyOf(Arrays.asList(names)));
  }

  /** An argument that may read any name the guard cannot see. */
  public static Tmf630QueryArgument unknown() {
    return UNKNOWN;
  }

  /** A TMF-630 filter terminal, which owns the whole query string. */
  public static Tmf630QueryArgument filter() {
    return FILTER;
  }
}
