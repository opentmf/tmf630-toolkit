package org.opentmf.query.tmf630.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.advice.Tmf630RangeExceptionHandler;
import org.opentmf.query.tmf630.exception.RequestedRangeNotSatisfiableException;
import org.opentmf.query.tmf630.model.ErrorMessage;
import org.opentmf.query.tmf630.paging.OffsetLimitPageRequest;
import org.opentmf.query.tmf630.paging.config.Tmf630PagingSettings;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class Tmf630UtilAndAdviceTest {

  @Test
  void tmfPageReturnsOkForEmptyCollection() {
    Page<String> page =
        new PageImpl<>(List.of(), new OffsetLimitPageRequest(0, 10, Sort.unsorted()), 0);

    ResponseEntity<List<String>> response = Tmf630Util.tmfPage(page);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("items */0", response.getHeaders().getFirst("Content-Range"));
  }

  @Test
  void tmfPageReturnsOkWhenFullCollectionReturned() {
    Page<String> page =
        new PageImpl<>(List.of("a", "b"), new OffsetLimitPageRequest(0, 10, Sort.unsorted()), 2);

    ResponseEntity<List<String>> response = Tmf630Util.tmfPage(page);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals("items 1-2/2", response.getHeaders().getFirst("Content-Range"));
  }

  @Test
  void tmfPageReturnsPartialContentWhenWindowIsPartial() {
    Page<String> page =
        new PageImpl<>(List.of("c"), new OffsetLimitPageRequest(1, 1, Sort.unsorted()), 2);

    ResponseEntity<List<String>> response = Tmf630Util.tmfPage(page);

    assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
    assertEquals("items 2-2/2", response.getHeaders().getFirst("Content-Range"));
  }

  @Test
  void tmfPageThrowsForOutOfRangeOffset() {
    Page<String> page =
        new PageImpl<>(List.of(), new OffsetLimitPageRequest(10, 5, Sort.unsorted()), 3);

    assertThrows(RequestedRangeNotSatisfiableException.class, () -> Tmf630Util.tmfPage(page));
  }

  @Test
  void applyRangeHeadersSupportsUnsatisfiedRange() {
    HttpHeaders headers = new HttpHeaders();

    Tmf630Util.applyRangeHeaders(headers, 8, 20, 0, false);

    assertEquals("8", headers.getFirst("X-Total-Count"));
    assertEquals("0", headers.getFirst("X-Result-Count"));
    assertEquals("items */8", headers.getFirst("Content-Range"));
  }

  @Test
  void applyRangeHeadersTreatsZeroReturnedAsUnsatisfiedEvenWhenSatisfiable() {
    HttpHeaders headers = new HttpHeaders();
    Tmf630Util.applyRangeHeaders(headers, 12, 5, 0, true);
    assertEquals("items */12", headers.getFirst("Content-Range"));
  }

  @Test
  void adviceBuildsExpectedErrorResponse() {
    Tmf630RangeExceptionHandler handler = new Tmf630RangeExceptionHandler();
    RequestedRangeNotSatisfiableException ex = new RequestedRangeNotSatisfiableException(12, 5);

    ResponseEntity<ErrorMessage> response = handler.handle(ex);

    assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, response.getStatusCode());
    assertEquals("items */5", response.getHeaders().getFirst("Content-Range"));
    assertNotNull(response.getBody());
    assertEquals("416", response.getBody().getCode());
    assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.getReasonPhrase(), response.getBody().getStatus());
  }

  @Test
  void adviceFormatsRangeMessageWhenTotalIsZero() {
    Tmf630RangeExceptionHandler handler = new Tmf630RangeExceptionHandler();
    ResponseEntity<ErrorMessage> response =
        handler.handle(new RequestedRangeNotSatisfiableException(2, 0));
    assertNotNull(response.getBody());
    assertTrue(response.getBody().getMessage().contains("between 0 and 0"));
  }

  @Test
  void requestedRangeExceptionHandlesZeroTotalInMessage() {
    RequestedRangeNotSatisfiableException ex = new RequestedRangeNotSatisfiableException(1, 0);
    assertEquals(1, ex.getRequestedOffset());
    assertEquals(0, ex.getTotalElements());
    assertTrue(ex.getMessage().contains("0..0"));
  }

  @Test
  void errorMessageSupportsAllAccessors() {
    ErrorMessage error = new ErrorMessage();
    error.setCode("400");
    error.setStatus("Bad Request");
    error.setReason("Validation");
    error.setMessage("Invalid");
    assertEquals("400", error.getCode());
    assertEquals("Bad Request", error.getStatus());
    assertEquals("Validation", error.getReason());
    assertEquals("Invalid", error.getMessage());
  }

  @Test
  void pagingSettingsExposeAllValues() {
    Tmf630PagingSettings settings =
        new Tmf630PagingSettings(true, 20, 200, false, true, List.of("id"));

    assertEquals(true, settings.isEnabled());
    assertEquals(20, settings.getDefaultLimit());
    assertEquals(200, settings.getMaxLimit());
    assertEquals(false, settings.isStrictMode());
    assertEquals(true, settings.isAllowNestedSortProperties());
    assertEquals(List.of("id"), settings.getSortAllowlist());
  }
}
