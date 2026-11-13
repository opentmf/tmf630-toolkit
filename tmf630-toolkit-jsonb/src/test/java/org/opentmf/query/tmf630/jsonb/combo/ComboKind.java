package org.opentmf.query.tmf630.jsonb.combo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The declared-type axis of the combination suite: one constant per {@link ComboLeaf} field, with
 * the JSON values the rows store for it and the values the queries use.
 *
 * <p>Stored values are labelled {@code d<n>} (the declared JSON type), {@code o<n>} (another JSON
 * type), {@code x<n>} (the declared JSON type, but content no cast reads) and {@code nul} (JSON
 * null). The key absent is every row of another field.
 */
enum ComboKind {
  // "a_ph%_o" holds the text operators' values literally, and each also finds a decoy when its
  // % or _ is read as a wildcard: startswith a_ (alpha), contains %_o and endswith _o (bravo).
  STRING("s", "String", Shape.TEXT,
      List.of("\"alpha\"", "\"bravo\"", "\"Charlie\"", "\"a_ph%_o\""), List.of("42", "true"), List.of(),
      new Probe("alpha", "bravo", "alpha", "bravo", "alpha", "Charlie", "a_", "%_o", "_o")),
  INTEGER("i", "Integer", Shape.NUMBER,
      List.of("1", "5", "9", "5.0"), List.of("\"5\""), List.of(),
      new Probe("5", "4", "2", "8", "1", "9", "5", "5", "5")),
  LONG("l", "Long", Shape.NUMBER,
      List.of("1", "5", "9007199254740993"), List.of("\"5\""), List.of(),
      new Probe("9007199254740993", "5", "2", "9007199254740993", "1", "5", "9", "0", "3")),
  DECIMAL("d", "BigDecimal", Shape.NUMBER,
      List.of("1.5", "2.50", "9.25"), List.of("\"2.5\""), List.of(),
      new Probe("2.5", "2", "1", "3", "1.5", "9.25", "2", "5", "5")),
  DOUBLE("f", "Double", Shape.NUMBER,
      List.of("0.1", "2.5", "9.75"), List.of("\"2.5\""), List.of(),
      new Probe("0.1", "1", "0", "3", "0.1", "9.75", "0", "1", "1")),
  BOOLEAN("b", "Boolean", Shape.BOOLEAN,
      List.of("true", "false"), List.of("\"true\"", "1"), List.of(),
      new Probe("true", "false", "false", "true", "true", "true", "t", "ru", "ue")),
  DATE("ld", "LocalDate", Shape.DATE,
      List.of("\"2025-01-01\"", "\"2025-06-30\"", "\"2025-12-31\""), List.of("20250101"),
      List.of("\"someday\""),
      new Probe("2025-01-01", "2025-03-01", "2025-01-01", "2025-06-30", "2025-01-01",
          "2025-12-31", "2025", "-06-", "31")),
  DATE_TIME("t", "OffsetDateTime", Shape.INSTANT,
      List.of("\"2025-01-01T08:00:00Z\"", "\"2025-01-01T10:00:00+02:00\"", "\"2025-06-30T00:00:00Z\""),
      List.of("1735718400"), List.of("\"soon\""),
      new Probe("2025-01-01T08:00:00Z", "2025-03-01T00:00:00Z", "2025-01-01T00:00:00Z",
          "2025-02-01T00:00:00Z", "2025-01-01T08:00:00Z", "2025-06-30T00:00:00Z", "2025",
          "T08", "Z")),
  PLAIN_ENUM("e", "enum (plain)", Shape.TEXT,
      List.of("\"RED\"", "\"GREEN\""), List.of("1"), List.of(),
      new Probe("RED", "GREEN", "GREEN", "RED", "RED", "GREEN", "R", "RE", "ED")),
  WIRE_ENUM("w", "enum (@JsonValue)", Shape.TEXT,
      List.of("\"inProgress\"", "\"done\""), List.of("\"IN_PROGRESS\"", "7"), List.of(),
      new Probe("inProgress", "done", "done", "inProgress", "inProgress", "done", "in", "Prog",
          "ss")),
  OBJECT("o", "Object", Shape.TEXT,
      List.of("\"alpha\"", "5", "true", "{\"k\":\"v\"}"), List.of(), List.of(),
      new Probe("alpha", "bravo", "alpha", "bravo", "alpha", "5", "al", "lph", "ha")),
  MAP("m", "Map<String,Object>", Shape.TEXT,
      List.of("{\"k\":\"v\"}"), List.of("\"alpha\""), List.of(),
      new Probe("alpha", "bravo", "alpha", "bravo", "alpha", "bravo", "al", "lph", "ha")),
  STRING_LIST("ls", "List<String>", Shape.TEXT,
      List.of("[\"alpha\",\"bravo\"]", "[]"), List.of("\"alpha\""), List.of(),
      new Probe("alpha", "bravo", "alpha", "bravo", "alpha", "zulu", "al", "lph", "vo")),
  OBJECT_LIST("lo", "List<ComboTag>", Shape.TEXT,
      List.of("[{\"code\":\"alpha\"},{\"code\":\"bravo\"}]", "[]"), List.of("{\"code\":\"alpha\"}"),
      List.of(),
      new Probe("alpha", "bravo", "alpha", "bravo", "alpha", "zulu", "al", "lph", "vo"));

  /** How a stored value of the declared JSON type is compared. */
  enum Shape {
    TEXT,
    NUMBER,
    BOOLEAN,
    DATE,
    INSTANT
  }

  /**
   * Query values: {@code eq} (EQ/NE and the case-insensitive pair), {@code above} (GT, GTE, LT,
   * LTE), {@code low}/{@code high} (BETWEEN), {@code inA}/{@code inB} (IN, NIN), and the text
   * operators' {@code prefix}, {@code infix}, {@code suffix}.
   */
  record Probe(
      String eq,
      String above,
      String low,
      String high,
      String inA,
      String inB,
      String prefix,
      String infix,
      String suffix) {}

  private final String key;
  private final String declared;
  private final Shape shape;
  private final List<String> declaredValues;
  private final List<String> otherValues;
  private final List<String> unreadableValues;
  private final Probe probe;

  ComboKind(
      String key,
      String declared,
      Shape shape,
      List<String> declaredValues,
      List<String> otherValues,
      List<String> unreadableValues,
      Probe probe) {
    this.key = key;
    this.declared = declared;
    this.shape = shape;
    this.declaredValues = declaredValues;
    this.otherValues = otherValues;
    this.unreadableValues = unreadableValues;
    this.probe = probe;
  }

  String key() {
    return key;
  }

  String declared() {
    return declared;
  }

  Shape shape() {
    return shape;
  }

  Probe probe() {
    return probe;
  }

  /** The leaf of the URL path below the position: {@code lo.code} for the object list. */
  String leafPath() {
    return this == OBJECT_LIST ? "lo.code" : key;
  }

  /** Every stored value with its label, JSON null last. */
  List<String[]> storedValues() {
    List<String[]> all = new ArrayList<>();
    for (int n = 0; n < declaredValues.size(); n++) {
      all.add(new String[] {"d" + n, declaredValues.get(n)});
    }
    for (int n = 0; n < otherValues.size(); n++) {
      all.add(new String[] {"o" + n, otherValues.get(n)});
    }
    for (int n = 0; n < unreadableValues.size(); n++) {
      all.add(new String[] {"x" + n, unreadableValues.get(n)});
    }
    all.add(new String[] {"nul", "null"});
    return all;
  }

  /** Whether the kind's values are text the text operators apply to. */
  boolean isText() {
    return shape == Shape.TEXT;
  }

  /** A value to compare: a {@link String}, {@link BigDecimal}, {@link Boolean}, date or instant. */
  Optional<Comparable<?>> parseQuery(String text) {
    try {
      return Optional.of(
          switch (shape) {
            case TEXT -> this == WIRE_ENUM ? wireOf(text) : text;
            case NUMBER -> new BigDecimal(text);
            case BOOLEAN -> Boolean.valueOf(text);
            case DATE -> LocalDate.parse(text);
            case INSTANT -> OffsetDateTime.parse(text).toInstant();
          });
    } catch (NumberFormatException | DateTimeParseException e) {
      return Optional.empty();
    }
  }

  /** The literal of a {@code filter=} expression for a query value: quoted unless a number or boolean. */
  String filterLiteral(String text) {
    String value = this == WIRE_ENUM ? wireOf(text) : text;
    return shape == Shape.NUMBER || shape == Shape.BOOLEAN ? value : "'" + value + "'";
  }

  private static String wireOf(String text) {
    for (ComboStage stage : ComboStage.values()) {
      if (stage.name().equals(text)) {
        return stage.wire();
      }
    }
    return text;
  }

  /** Upper-cases a text query value for the case-insensitive operators. */
  static String upper(String text) {
    return text.toUpperCase(Locale.ROOT);
  }

  /** The instant of a stored date-time text, or empty when no parser reads it. */
  static Optional<Instant> instant(String text) {
    try {
      return Optional.of(OffsetDateTime.parse(text).toInstant());
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }

  /** The date of a stored date text, or empty when no parser reads it. */
  static Optional<LocalDate> date(String text) {
    try {
      return Optional.of(LocalDate.parse(text));
    } catch (DateTimeParseException e) {
      return Optional.empty();
    }
  }
}
