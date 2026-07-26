package org.opentmf.query.tmf630.jsonb;

import java.util.regex.Pattern;

/**
 * Produces Postgres JSONB path extraction fragments for a dotted field path. Uses
 * {@code payload->>'field'} for a single-level scalar field and
 * {@code payload#>>'{a,b,c}'} for nested paths — both return {@code text}, matching
 * what {@link JsonbCast} expects on the other side of the comparison.
 *
 * <p>Sanitizes each path segment against a strict allowlist (JavaBean-style identifier
 * plus digits — the latter to support TMF Part 6 positional {@code [N]} segments after
 * they're normalized to dotted numerics elsewhere in the pipeline). Rejects anything
 * else — the toolkit's URL parser already enforces this on the resolver side, but the
 * belt-and-suspenders check here guarantees no SQL injection through the JSONB path
 * regardless of what the caller passes.
 */
public final class JsonbPathExtractor {

  private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|[0-9]+");

  private final String payloadColumn;

  public JsonbPathExtractor(String payloadColumn) {
    if (payloadColumn == null || payloadColumn.isBlank()) {
      throw new IllegalArgumentException("payloadColumn must not be blank");
    }
    if (!SAFE_SEGMENT.matcher(payloadColumn).matches()) {
      throw new IllegalArgumentException("Illegal payload column name: " + payloadColumn);
    }
    this.payloadColumn = payloadColumn;
  }

  /**
   * Extracts a scalar text value at the dotted path. Single-level: {@code payload->>'x'}.
   * Multi-level: {@code payload#>>'{a,b,c}'}.
   */
  public String extractAsText(String dottedPath) {
    String[] segments = validateAndSplit(dottedPath);
    if (segments.length == 1) {
      return payloadColumn + "->>'" + segments[0] + "'";
    }
    return payloadColumn + "#>>'{" + String.join(",", segments) + "}'";
  }

  /**
   * Extracts the raw jsonb value at the dotted path (useful for {@code IS NULL},
   * {@code jsonb_array_length}, containment tests, etc.). Single-level:
   * {@code payload->'x'}. Multi-level: {@code payload#>'{a,b,c}'}.
   */
  public String extractAsJsonb(String dottedPath) {
    String[] segments = validateAndSplit(dottedPath);
    if (segments.length == 1) {
      return payloadColumn + "->'" + segments[0] + "'";
    }
    return payloadColumn + "#>'{" + String.join(",", segments) + "}'";
  }

  /**
   * Returns a fragment testing whether the payload has the given key at the top level
   * ({@code payload ? 'x'}). Only meaningful for single-segment paths; multi-hop
   * existence checks should go through {@link #extractAsJsonb(String)} +
   * {@code IS NOT NULL}.
   */
  public String hasTopLevelKey(String key) {
    String[] segments = validateAndSplit(key);
    if (segments.length != 1) {
      throw new IllegalArgumentException(
          "hasTopLevelKey requires a single-segment path; got: " + key);
    }
    return payloadColumn + " ? '" + segments[0] + "'";
  }

  private static String[] validateAndSplit(String dottedPath) {
    if (dottedPath == null || dottedPath.isBlank()) {
      throw new IllegalArgumentException("dottedPath must not be blank");
    }
    String[] segments = dottedPath.split("\\.");
    for (String segment : segments) {
      if (!SAFE_SEGMENT.matcher(segment).matches()) {
        throw new IllegalArgumentException(
            "Illegal JSONB path segment: '" + segment + "' in " + dottedPath);
      }
    }
    return segments;
  }
}
