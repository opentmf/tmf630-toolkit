package org.opentmf.query.tmf630.versioning;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.opentmf.query.tmf630.exception.TmfPagingException;

/**
 * TMF-630 Part 4 §2.5 "versioned resource" identifier as parsed from a URL path.
 * Two shapes:
 *
 * <ul>
 *   <li>Bare logical id: {@code /VirtualStorage} → {@code TmfVersionedId("VirtualStorage",
 *       empty)}.
 *   <li>Version-directed: {@code /VirtualStorage:(version=1.0)} →
 *       {@code TmfVersionedId("VirtualStorage", Optional.of("1.0"))}.
 * </ul>
 *
 * <p>The colon-prefixed {@code :(version=X)} form is the canonical spec spelling. The
 * sloppier {@code /X(Version=1.0)} spelling that appears in the Part 4 §2.5 example is a
 * typo in the spec text, not an authorized alternative; this parser rejects it with a
 * {@link TmfPagingException} so consumers can't rely on lenient parsing.
 *
 * <p>Bound to controller handler parameters by
 * {@code TmfVersionedIdArgumentResolver} — the developer declares
 * {@code @PathVariable("ref") TmfVersionedId ref} on their handler.
 */
public record TmfVersionedId(String id, Optional<String> version) {

  private static final Pattern PATTERN =
      Pattern.compile("^([^:()\\s]+)(?::\\(version=([^)\\s]+)\\))?$");

  @SuppressWarnings("java:S2789") // record accepts null for the Optional and normalises it — public API safety net
  public TmfVersionedId {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id must not be blank");
    }
    version = version == null ? Optional.empty() : version;
  }

  /**
   * Parses a URL path variable into a versioned id. Throws {@link TmfPagingException}
   * (maps to 400) if the input doesn't match either supported shape.
   */
  public static TmfVersionedId parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new TmfPagingException("Versioned id must not be blank.");
    }
    Matcher matcher = PATTERN.matcher(raw);
    if (!matcher.matches()) {
      throw new TmfPagingException(
          "Malformed versioned id '"
              + raw
              + "'. Expected '<id>' or '<id>:(version=<value>)' per TMF-630 Part 4 §2.5.");
    }
    return new TmfVersionedId(matcher.group(1), Optional.ofNullable(matcher.group(2)));
  }
}
