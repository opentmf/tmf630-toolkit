package org.opentmf.query.tmf630.jsonb.combo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Typed fields INSIDE an array (and an array of arrays), every typed cast with a value no cast
 * reads: the 3.6.0 default skips such an element (3.5.2 read nothing through an array, so every
 * such request answered, with no row). Row {@code R} holds a readable and an unreadable element,
 * {@code X} only an unreadable one, {@code Y} forms the cast reads but the default skips inside an
 * array ({@code "+7"}, {@code "yes"}, {@code 20250101}), {@code O} the two elements in an array of
 * arrays. Each request states the rows the default answers: {@code R} through {@code items},
 * {@code O} through {@code outer.inner}.
 */
final class CompatElementSamples {

  static final String READABLE =
      "{\"n\":5,\"l\":9007199254740993,\"d\":2.50,\"f\":0.1,\"b\":true,\"ld\":\"2025-01-01\","
          + "\"t\":\"2025-01-01T08:00:00Z\",\"lt\":\"08:00:00\",\"ldt\":\"2025-01-01T08:00:00\"}";

  static final String UNREADABLE =
      "{\"n\":\"abc\",\"l\":9223372036854775808,\"d\":\"abc\",\"f\":\"abc\",\"b\":\"maybe\","
          + "\"ld\":\"someday\",\"t\":1735718400,\"lt\":\"noonish\","
          + "\"ldt\":\"2025-02-30T08:00:00\"}";

  static final String OTHER_FORMS = "{\"n\":\"+7\",\"b\":\"yes\",\"ld\":20250101}";

  /**
   * Per field: the kind, its key, and the values of eq, ne, gt, gte, lt, lte, in, nin, between
   * (null where the kind has no ordering) that match the readable element.
   */
  private static final List<String[]> FIELDS =
      List.of(
          new String[] {"INTEGER", "n", "5", "6", "4", "5", "6", "5", "5,7", "6", "4,6"},
          new String[] {"LONG", "l", "9007199254740993", "1", "1", "9007199254740993",
              "9007199254740994", "9007199254740993", "9007199254740993,1", "1",
              "1,9007199254740994"},
          new String[] {"DECIMAL", "d", "2.5", "3", "2", "2.5", "3", "2.5", "2.5,3", "3", "2,3"},
          new String[] {"DOUBLE", "f", "0.1", "0.2", "0", "0.1", "1", "0.1", "0.1,0.2", "0.2", "0,1"},
          new String[] {"BOOLEAN", "b", "true", "false", null, null, null, null, "true", "false",
              null},
          new String[] {"DATE", "ld", "2025-01-01", "2025-06-30", "2024-12-31", "2025-01-01",
              "2025-01-02", "2025-01-01", "2025-01-01,2025-06-30", "2025-06-30",
              "2024-12-01,2025-02-01"},
          new String[] {"DATE_TIME", "t", "2025-01-01T08:00:00Z", "2025-06-30T00:00:00Z",
              "2025-01-01T07:00:00Z", "2025-01-01T08:00:00Z", "2025-01-01T09:00:00Z",
              "2025-01-01T08:00:00Z", "2025-01-01T08:00:00Z,2025-06-30T00:00:00Z",
              "2025-06-30T00:00:00Z", "2025-01-01T00:00:00Z,2025-01-02T00:00:00Z"},
          new String[] {"TIME", "lt", "08:00:00", "09:00:00", "07:00:00", "08:00:00", "09:00:00",
              "08:00:00", "08:00:00,09:00:00", "09:00:00", "07:00:00,09:00:00"},
          new String[] {"LOCAL_DATE_TIME", "ldt", "2025-01-01T08:00:00", "2025-06-30T00:00:00",
              "2025-01-01T07:00:00", "2025-01-01T08:00:00", "2025-01-01T09:00:00",
              "2025-01-01T08:00:00", "2025-01-01T08:00:00,2025-06-30T00:00:00",
              "2025-06-30T00:00:00", "2025-01-01T00:00:00,2025-01-02T00:00:00"});

  private static final String[] OPERATORS = {
    "EQ", "NE", "GT", "GTE", "LT", "LTE", "IN", "NIN", "BETWEEN"
  };

  /** One request: kind, position, operator, the parameter, its value, the rows it answers. */
  record Request(
      String kind, String position, String operator, String param, String value, String rows) {}

  static final List<Request> REQUESTS = requests();

  private CompatElementSamples() {}

  private static List<Request> requests() {
    List<Request> all = new ArrayList<>();
    for (String[] field : FIELDS) {
      for (int i = 0; i < OPERATORS.length; i++) {
        String value = field[i + 2];
        if (value == null) {
          continue;
        }
        String suffix = i == 0 ? "" : "." + OPERATORS[i].toLowerCase();
        all.add(
            new Request(field[0], "ARRAY", OPERATORS[i], "items." + field[1] + suffix, value, "R"));
        all.add(
            new Request(
                field[0],
                "ARRAY_IN_ARRAY",
                OPERATORS[i],
                "outer.inner." + field[1] + suffix,
                value,
                "O"));
      }
    }
    return List.copyOf(all);
  }

  static void seed(JdbcClient jdbc) {
    jdbc.sql("TRUNCATE " + CompatElementRow.TABLE).update();
    Map.of(
            "R", "{\"items\":[" + READABLE + "," + UNREADABLE + "]}",
            "X", "{\"items\":[" + UNREADABLE + "]}",
            "Y", "{\"items\":[" + OTHER_FORMS + "]}",
            "O",
                "{\"outer\":[{\"inner\":[" + UNREADABLE + "]},{\"inner\":[" + READABLE
                    + "]}]}")
        .forEach(
            (id, payload) ->
                jdbc.sql(
                        "INSERT INTO " + CompatElementRow.TABLE
                            + " (id, payload) VALUES (?, ?::jsonb)")
                    .params(id, payload)
                    .update());
  }
}
