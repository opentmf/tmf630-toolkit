package org.opentmf.query.tmf630.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.annotation.Tmf630Response;
import org.opentmf.query.tmf630.paging.OffsetLimitPageRequest;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Real-Tomcat tests for the two layers the {@code Link} budget does not cover: the recovery
 * from a {@code HeadersTooLargeException} that some other header caused, and the typed
 * rejection of the scan's request shape at the default limits.
 */
@SpringBootTest(
    classes = Tmf630HeadersTooLargeRecoveryIT.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Tmf630HeadersTooLargeRecoveryIT {

  @LocalServerPort private int port;

  private final HttpClient client = HttpClient.newHttpClient();

  @BeforeEach
  void reset() {
    CatchAll.invocations.set(0);
  }

  @Test
  void adopterHeaderOverflowIsAnsweredWithATmf500BodyInsteadOfAnEmptyOne() throws Exception {
    // The controller itself sets a 9 KB header; before 3.3.0 the catch-all's own write failed
    // on the same header, Boot's /error failed a third time, and the client saw "0".
    HttpResponse<String> response = get("/recovery/oversized-header?offset=0&limit=10");

    assertEquals(500, response.statusCode());
    assertEquals(
        "{\"code\":\"500\",\"status\":\"Internal Server Error\","
            + "\"reason\":\"Response headers too large.\","
            + "\"message\":\"The response headers exceeded the server's header buffer; "
            + "the server log names the header that overflowed.\"}",
        response.body());
    assertEquals(Optional.of("application/json"), response.headers().firstValue("Content-Type"));
    assertEquals(Optional.of("chunked"), response.headers().firstValue("Transfer-Encoding"));
    assertEquals(Optional.empty(), response.headers().firstValue("X-Huge"));
    assertEquals(0, CatchAll.invocations.get(), "the toolkit answers before the catch-all");
  }

  @Test
  void scanRequestShapeIsRejectedTypedAtDefaultLimits() throws Exception {
    HttpResponse<String> response = get("/recovery/items?fields=" + "a".repeat(2100) + "&offset=10&limit=10");

    assertEquals(400, response.statusCode());
    assertTrue(response.body().contains("\"code\":\"400\""), response.body());
    assertTrue(
        response.body().contains("Query parameter 'fields' is 2100 characters long; the limit is 2048."),
        response.body());
    assertEquals(0, CatchAll.invocations.get(), "the toolkit's handler answers, not the catch-all");
  }

  @Test
  void requestUnderTheLimitsIsServedNormally() throws Exception {
    HttpResponse<String> response = get("/recovery/items?fields=" + "a".repeat(200) + "&offset=10&limit=10");

    assertEquals(206, response.statusCode());
    assertTrue(response.headers().firstValue("Link").isPresent());
  }

  private HttpResponse<String> get(String pathAndQuery) throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @SpringBootApplication
  @Import({ItemsController.class, CatchAll.class})
  static class TestApp {}

  @RestController
  static class ItemsController {

    @GetMapping("/recovery/items")
    @Tmf630Response
    public Page<Item> items() {
      return page();
    }

    @GetMapping("/recovery/oversized-header")
    public ResponseEntity<List<Item>> oversizedHeader() {
      HttpHeaders headers = new HttpHeaders();
      headers.add("X-Huge", "h".repeat(9000));
      return ResponseEntity.ok().headers(headers).body(page().getContent());
    }

    private static Page<Item> page() {
      // Large enough to flush mid-write, so the header commit fails inside the converter and
      // Spring's exception chain runs — the path the scan recorded.
      List<Item> content =
          IntStream.range(0, 10).mapToObj(i -> new Item("v".repeat(1500))).toList();
      return new PageImpl<>(content, new OffsetLimitPageRequest(10, 10, Sort.unsorted()), 50);
    }
  }

  @RestControllerAdvice(assignableTypes = ItemsController.class)
  static class CatchAll {
    static final AtomicInteger invocations = new AtomicInteger();

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handle(Exception ex) {
      invocations.incrementAndGet();
      return ResponseEntity.status(500)
          .header(HttpHeaders.CONTENT_TYPE, "application/json")
          .body("{\"code\":\"500\",\"reason\":\"catch-all\"}");
    }
  }

  public static class Item {
    private final String name;

    Item(String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }
  }
}
