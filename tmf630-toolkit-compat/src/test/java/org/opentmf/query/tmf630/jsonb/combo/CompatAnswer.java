package org.opentmf.query.tmf630.jsonb.combo;

import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * One request's answer, as the compatibility report compares it: the status (200, 400 or 500) and
 * what came back (the ids, sorted for a filter and in order for a sort; the 400's message; the
 * 500's exception, for the reader only).
 */
record CompatAnswer(int status, String answer) {

  /** The ids a filter found, in id order. */
  static CompatAnswer rows(Collection<String> ids) {
    return new CompatAnswer(200, String.join(",", new TreeSet<>(ids)));
  }

  /** The ids a sort returned, in its order. */
  static CompatAnswer ordered(List<String> ids) {
    return new CompatAnswer(200, String.join(",", ids));
  }

  /** The toolkit's own refusal. */
  static CompatAnswer refused(String message) {
    return new CompatAnswer(400, oneLine(message));
  }

  /** Anything else: what the service would answer with a 500. */
  static CompatAnswer failed(Throwable error) {
    Throwable root = error;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    return new CompatAnswer(500, root.getClass().getSimpleName() + ": " + oneLine(root.getMessage()));
  }

  private static String oneLine(String text) {
    String value = String.valueOf(text);
    int newline = value.indexOf('\n');
    return (newline < 0 ? value : value.substring(0, newline)).replace('\t', ' ');
  }
}
