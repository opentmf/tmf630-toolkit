package org.opentmf.query.tmf630.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.opentmf.query.commons.fieldselection.FieldSelectionUtil;
import org.opentmf.query.tmf630.exception.RequestedRangeNotSatisfiableException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

public final class Tmf630Util {

  private Tmf630Util() {}

  public static <T> ResponseEntity<List<T>> tmfPage(Page<T> page) {
    HttpStatus status = resolveStatusOrThrow(page);

    HttpHeaders headers = new HttpHeaders();
    applyRangeHeaders(
        headers,
        page.getTotalElements(),
        page.getPageable().getOffset(),
        page.getNumberOfElements(),
        true);
    applyLinkHeaderFromCurrentRequest(
        headers, page.getTotalElements(), page.getPageable().getOffset(), page.getSize());

    return ResponseEntity.status(status).headers(headers).body(page.getContent());
  }

  public static <T> ResponseEntity<List<Map<String, Object>>> tmfPage(
      Page<T> page, String fields) {
    HttpStatus status = resolveStatusOrThrow(page);

    HttpHeaders headers = new HttpHeaders();
    applyRangeHeaders(
        headers,
        page.getTotalElements(),
        page.getPageable().getOffset(),
        page.getNumberOfElements(),
        true);
    applyLinkHeaderFromCurrentRequest(
        headers, page.getTotalElements(), page.getPageable().getOffset(), page.getSize());

    List<Map<String, Object>> body;
    if (fields == null || fields.isEmpty()) {
      body = FieldSelectionUtil.fieldsToMapList(page.getContent());
    } else {
      body = FieldSelectionUtil.fieldsToMapList(page.getContent(), fields);
    }

    return ResponseEntity.status(status).headers(headers).body(body);
  }

  static HttpStatus resolveStatusOrThrow(Page<?> page) {
    long total = page.getTotalElements();
    Pageable pageable = page.getPageable();
    long offset = pageable.getOffset();
    long returned = page.getNumberOfElements();

    if (total == 0) {
      return HttpStatus.OK;
    }

    if (offset >= total) {
      throw new RequestedRangeNotSatisfiableException(offset, total);
    }

    if (offset == 0 && returned == total) {
      return HttpStatus.OK;
    }

    return HttpStatus.PARTIAL_CONTENT;
  }

  public static void applyRangeHeaders(
      HttpHeaders headers, long total, long offset, long returned, boolean satisfiable) {
    headers.add("X-Total-Count", String.valueOf(total));
    headers.add("X-Result-Count", String.valueOf(returned));

    String contentRange;
    if (!satisfiable || returned == 0) {
      contentRange = "items */" + total;
    } else {
      long start = offset + 1;
      long end = offset + returned;
      contentRange = "items " + start + "-" + end + "/" + total;
    }

    headers.add("Content-Range", contentRange);
  }

  /**
   * Emits the TMF-630 Part 1 §4.5 pagination navigation link header — comma-separated
   * {@code <uri>; rel="first"}, {@code prev}, {@code next}, {@code last} — computed from the
   * current request URI. Reads the URI via {@link ServletUriComponentsBuilder#fromCurrentRequest}
   * and returns silently if no request context is bound to the current thread (e.g. a unit test
   * calling {@link #tmfPage(Page)} directly), so the helper is always safe to call.
   */
  public static void applyLinkHeaderFromCurrentRequest(
      HttpHeaders headers, long total, long offset, long limit) {
    String baseUri;
    try {
      baseUri = ServletUriComponentsBuilder.fromCurrentRequest().build().toUriString();
    } catch (IllegalStateException noRequestContext) {
      return;
    }
    applyLinkHeader(headers, baseUri, total, offset, limit);
  }

  /**
   * Same as {@link #applyLinkHeaderFromCurrentRequest} but with an explicitly supplied
   * {@code baseUri}. Prefer this overload from paths where the request is already available
   * (e.g. inside a {@code ResponseBodyAdvice.beforeBodyWrite}) — it avoids the
   * {@code RequestContextHolder} lookup.
   */
  public static void applyLinkHeader(
      HttpHeaders headers, String baseUri, long total, long offset, long limit) {
    if (total <= 0 || limit <= 0 || baseUri == null) {
      return;
    }
    long lastOffset = ((total - 1) / limit) * limit;
    List<String> links = new ArrayList<>(4);
    links.add(linkWithOffset(baseUri, 0L) + "; rel=\"first\"");
    if (offset > 0) {
      links.add(linkWithOffset(baseUri, Math.max(0L, offset - limit)) + "; rel=\"prev\"");
    }
    if (offset + limit < total) {
      links.add(linkWithOffset(baseUri, offset + limit) + "; rel=\"next\"");
    }
    links.add(linkWithOffset(baseUri, lastOffset) + "; rel=\"last\"");
    headers.add(HttpHeaders.LINK, String.join(", ", links));
  }

  private static String linkWithOffset(String baseUri, long offset) {
    String rebuilt =
        UriComponentsBuilder.fromUriString(baseUri)
            .replaceQueryParam("offset", offset)
            .build()
            .toUriString();
    return "<" + rebuilt + ">";
  }
}
