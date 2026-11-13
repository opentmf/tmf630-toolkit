package org.opentmf.query.tmf630.jsonb.combo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The name twin of a request on a wire-value enum ({@link ComboStage}): the same request with
 * every wire value ({@code inProgress}, {@code done}) written as its constant name
 * ({@code IN_PROGRESS}, {@code DONE}). 3.5.2 resolved only the name, so its answer to the twin is
 * the evidence for what the candidate answers to the wire value.
 */
final class CompatNames {

  private CompatNames() {}

  /** {@code text} with each whole-word wire value replaced by its constant's name. */
  static String byName(String text) {
    String named = text;
    for (ComboStage stage : ComboStage.values()) {
      Pattern word =
          Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(stage.wire()) + "(?![A-Za-z0-9_])");
      named = word.matcher(named).replaceAll(Matcher.quoteReplacement(stage.name()));
    }
    return named;
  }

  /** The parameters with every value written {@link #byName}. */
  static Map<String, String[]> byName(Map<String, String[]> params) {
    Map<String, String[]> named = new LinkedHashMap<>();
    params.forEach(
        (name, values) -> {
          String[] copy = new String[values.length];
          for (int i = 0; i < values.length; i++) {
            copy[i] = byName(values[i]);
          }
          named.put(name, copy);
        });
    return named;
  }

  /** Whether the request names a wire value at all. */
  static boolean namesAWireValue(String text) {
    return !byName(text).equals(text);
  }
}
