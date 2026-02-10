package org.opentmf.query.tmf630.util;

import java.util.List;
import org.opentmf.query.tmf630.exception.RequestedRangeNotSatisfiableException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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

    return ResponseEntity.status(status).headers(headers).body(page.getContent());
  }

  private static HttpStatus resolveStatusOrThrow(Page<?> page) {
    long total = page.getTotalElements();
    Pageable pageable = page.getPageable();
    long offset = pageable.getOffset();
    int returned = page.getNumberOfElements();

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
      HttpHeaders headers, long total, long offset, int returned, boolean satisfiable) {
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
}
