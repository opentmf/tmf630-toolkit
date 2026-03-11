package org.opentmf.query.tmf630.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
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
    assertEquals("416", response.getBody().code());
    assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.getReasonPhrase(), response.getBody().status());
  }

  @Test
  void adviceFormatsRangeMessageWhenTotalIsZero() {
    Tmf630RangeExceptionHandler handler = new Tmf630RangeExceptionHandler();
    ResponseEntity<ErrorMessage> response =
        handler.handle(new RequestedRangeNotSatisfiableException(2, 0));
    assertNotNull(response.getBody());
    assertTrue(response.getBody().message().contains("between 0 and 0"));
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
    ErrorMessage error = new ErrorMessage("400", "Bad Request", "Validation", "Invalid");
    assertEquals("400", error.code());
    assertEquals("Bad Request", error.status());
    assertEquals("Validation", error.reason());
    assertEquals("Invalid", error.message());
  }

  @Test
  void tmfPageWithFieldsReturnsFilteredMaps() {
    Page<SamplePerson> page =
        new PageImpl<>(
            List.of(new SamplePerson("Alice", 30)),
            new OffsetLimitPageRequest(0, 10, Sort.unsorted()),
            1);

    ResponseEntity<List<Map<String, Object>>> response = Tmf630Util.tmfPage(page, "name");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(1, response.getBody().size());
    assertTrue(response.getBody().get(0).containsKey("name"));
    assertFalse(response.getBody().get(0).containsKey("age"));
  }

  @Test
  void tmfPageWithNullFieldsReturnsAllFields() {
    Page<SamplePerson> page =
        new PageImpl<>(
            List.of(new SamplePerson("Bob", 25)),
            new OffsetLimitPageRequest(0, 10, Sort.unsorted()),
            1);

    ResponseEntity<List<Map<String, Object>>> response = Tmf630Util.tmfPage(page, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertTrue(response.getBody().get(0).containsKey("name"));
    assertTrue(response.getBody().get(0).containsKey("age"));
  }

  @Test
  void errorMessageRecordSupportsEqualsAndToString() {
    ErrorMessage a = new ErrorMessage("400", "Bad Request", "reason", "msg");
    ErrorMessage b = new ErrorMessage("400", "Bad Request", "reason", "msg");
    assertEquals(a, b);
    assertTrue(a.toString().contains("400"));
  }

  @Test
  void pagingSettingsExposeAllValues() {
    Tmf630PagingSettings settings =
        new Tmf630PagingSettings(true, 20, 200, false, true, List.of("id"));

    assertTrue(settings.enabled());
    assertEquals(20, settings.defaultLimit());
    assertEquals(200, settings.maxLimit());
    assertFalse(settings.strictMode());
    assertTrue(settings.allowNestedSortProperties());
    assertEquals(List.of("id"), settings.sortAllowlist());
  }

  @Test
  void pagingSettingsDefensivelyCopiesSortAllowlist() {
    Tmf630PagingSettings a = new Tmf630PagingSettings(true, 50, 500, true, false, null);
    assertEquals(List.of(), a.sortAllowlist());
    Tmf630PagingSettings b =
        new Tmf630PagingSettings(true, 50, 500, true, false, List.of("x"));
    assertEquals(List.of("x"), b.sortAllowlist());
  }

  @Test
  void pagingSettingsRecordSupportEquals() {
    Tmf630PagingSettings a = new Tmf630PagingSettings(true, 50, 500, true, false, List.of());
    Tmf630PagingSettings b = new Tmf630PagingSettings(true, 50, 500, true, false, List.of());
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  public static class SamplePerson {
    private String name;
    private int age;

    public SamplePerson(String name, int age) {
      this.name = name;
      this.age = age;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public int getAge() {
      return age;
    }

    public void setAge(int age) {
      this.age = age;
    }
  }
}
