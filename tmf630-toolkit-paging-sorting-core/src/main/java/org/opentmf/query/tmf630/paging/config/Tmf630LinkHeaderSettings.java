package org.opentmf.query.tmf630.paging.config;

/**
 * Size budget for the TMF-630 Part 1 §4.5 pagination {@code Link} header.
 *
 * <p>The navigation links echo the request's query string (with {@code offset} rewritten) up to
 * four times — {@code first}, {@code prev}, {@code next}, {@code last} — so one long query value
 * multiplies into a header that can exceed the servlet container's response-header buffer
 * (8 KB on Tomcat by default). When that happens the container fails the whole response.
 *
 * <p>The budget bounds that: when any single query-parameter value is longer than {@link
 * #maxParamValueLength()}, or the assembled header value would be longer than {@link
 * #maxLength()}, the {@code Link} header is omitted altogether. Dropping or truncating a value
 * would produce a link that selects a different result set or a value the target rejects, so
 * omission is the only bounded behaviour that is never wrong; {@code X-Total-Count} and {@code
 * Content-Range} — the paging contract the spec makes mandatory — are unaffected, and {@code
 * Link} itself is a SHOULD.
 *
 * <p>Lengths are measured on the values as they appear in the request URI, i.e. in their
 * encoded form: that is what ends up in the header bytes the container counts.
 *
 * @param maxParamValueLength longest single query-parameter value the header will echo
 * @param maxLength longest header value emitted; a longer one is omitted
 */
public record Tmf630LinkHeaderSettings(int maxParamValueLength, int maxLength) {

  /** {@code 256} chars per value, {@code 2048} chars per header. */
  public static final Tmf630LinkHeaderSettings DEFAULT = new Tmf630LinkHeaderSettings(256, 2048);

  public Tmf630LinkHeaderSettings {
    if (maxParamValueLength <= 0) {
      throw new IllegalArgumentException("maxParamValueLength must be > 0");
    }
    if (maxLength <= 0) {
      throw new IllegalArgumentException("maxLength must be > 0");
    }
  }
}
