package org.opentmf.query.tmf630.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.opentmf.query.commons.fieldselection.FieldSelectionUtil;
import org.opentmf.query.tmf630.exception.RequestedRangeNotSatisfiableException;
import org.opentmf.query.tmf630.paging.config.Tmf630LinkHeaderSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

public final class Tmf630Util {

  private static final Logger log = LoggerFactory.getLogger(Tmf630Util.class);

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
   * {@code RequestContextHolder} lookup. Applies the {@link Tmf630LinkHeaderSettings#DEFAULT
   * default} size budget.
   */
  public static void applyLinkHeader(
      HttpHeaders headers, String baseUri, long total, long offset, long limit) {
    applyLinkHeader(headers, baseUri, total, offset, limit, Tmf630LinkHeaderSettings.DEFAULT);
  }

  /**
   * Emits the pagination {@code Link} header for {@code baseUri} within the given size budget.
   * Every query parameter of {@code baseUri} is preserved and only {@code offset} is rewritten
   * per link; when a single query value is longer than {@link
   * Tmf630LinkHeaderSettings#maxParamValueLength()} or the assembled header would be longer
   * than {@link Tmf630LinkHeaderSettings#maxLength()}, the header is omitted and the decision
   * is logged at {@code DEBUG} — see the settings record for why omission, not truncation.
   */
  public static void applyLinkHeader(
      HttpHeaders headers,
      String baseUri,
      long total,
      long offset,
      long limit,
      Tmf630LinkHeaderSettings settings) {
    if (total <= 0 || limit <= 0 || baseUri == null) {
      return;
    }
    UriComponents request = UriComponentsBuilder.fromUriString(baseUri).build();
    if (exceedsValueCap(request, settings)) {
      return;
    }
    String value = buildLinks(request, total, offset, limit);
    if (value.length() > settings.maxLength()) {
      log.debug(
          "Omitting the Link header for {}: {} chars exceed the {}-char budget",
          request.getPath(),
          value.length(),
          settings.maxLength());
      return;
    }
    headers.add(HttpHeaders.LINK, value);
  }

  private static boolean exceedsValueCap(UriComponents request, Tmf630LinkHeaderSettings settings) {
    MultiValueMap<String, String> params = request.getQueryParams();
    for (Map.Entry<String, List<String>> param : params.entrySet()) {
      for (String value : param.getValue()) {
        if (value != null && value.length() > settings.maxParamValueLength()) {
          log.debug(
              "Omitting the Link header for {}: query parameter '{}' is {} chars, cap is {}",
              request.getPath(),
              param.getKey(),
              value.length(),
              settings.maxParamValueLength());
          return true;
        }
      }
    }
    return false;
  }

  private static String buildLinks(UriComponents request, long total, long offset, long limit) {
    long lastOffset = ((total - 1) / limit) * limit;
    List<String> links = new ArrayList<>(4);
    links.add(linkWithOffset(request, 0L) + "; rel=\"first\"");
    if (offset > 0) {
      links.add(linkWithOffset(request, Math.max(0L, offset - limit)) + "; rel=\"prev\"");
    }
    if (offset + limit < total) {
      links.add(linkWithOffset(request, offset + limit) + "; rel=\"next\"");
    }
    links.add(linkWithOffset(request, lastOffset) + "; rel=\"last\"");
    return String.join(", ", links);
  }

  private static String linkWithOffset(UriComponents request, long offset) {
    String rebuilt =
        UriComponentsBuilder.newInstance()
            .uriComponents(request)
            .replaceQueryParam("offset", offset)
            .build()
            .toUriString();
    return "<" + rebuilt + ">";
  }
}
