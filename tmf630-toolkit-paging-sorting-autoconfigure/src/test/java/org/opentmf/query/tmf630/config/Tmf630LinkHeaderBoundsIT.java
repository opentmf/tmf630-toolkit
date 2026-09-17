package org.opentmf.query.tmf630.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Regression test for the DAST-2 finding (2026-09-17): one long query parameter, echoed in the
 * four pagination links, overflowed Tomcat's 8 KB response-header buffer and turned every paged
 * endpoint into a {@code 500} with an empty body. Runs against a real embedded Tomcat — MockMvc
 * has no header buffer and cannot fail this way — with the query-parameter guard switched off so
 * the {@code Link} budget is exercised on its own.
 */
@SpringBootTest(
    classes = Tmf630LinkHeaderBoundsIT.TestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "opentmf.tmf630.query-limits.enabled=false",
      "opentmf.tmf630.paging.link.max-param-value-length=300"
    })
class Tmf630LinkHeaderBoundsIT {

  private static final int PAGE_SIZE_BEYOND_RESPONSE_BUFFER = 10;

  @LocalServerPort private int port;

  private final HttpClient client = HttpClient.newHttpClient();

  @Test
  void oversizedQueryValueYieldsPageWithoutLinkHeaderInsteadOf500() throws Exception {
    HttpResponse<String> response = get("status=" + "a".repeat(3000) + "&offset=10&limit=10");

    assertEquals(206, response.statusCode());
    assertEquals(Optional.of("50"), response.headers().firstValue("X-Total-Count"));
    assertEquals(Optional.of("items 11-20/50"), response.headers().firstValue("Content-Range"));
    assertEquals(Optional.empty(), response.headers().firstValue("Link"));
    assertEquals(Optional.empty(), response.headers().firstValue("Connection"));
    assertTrue(response.body().startsWith("[{\"name\":\"vvv"), response.body());
  }

  @Test
  void queryValueWithinConfiguredCapKeepsAllFourLinks() throws Exception {
    String value = "a".repeat(280);
    HttpResponse<String> response = get("status=" + value + "&offset=10&limit=10");

    assertEquals(206, response.statusCode());
    String link = response.headers().firstValue("Link").orElseThrow();
    assertEquals(4, link.split("status=" + value).length - 1, link);
    assertTrue(link.contains("rel=\"prev\"") && link.contains("rel=\"next\""), link);
  }

  @Test
  void queryValueAboveConfiguredCapOmitsTheLinkHeader() throws Exception {
    HttpResponse<String> response = get("status=" + "a".repeat(301) + "&offset=10&limit=10");

    assertEquals(206, response.statusCode());
    assertFalse(response.headers().firstValue("Link").isPresent());
  }

  private HttpResponse<String> get(String query) throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/link-bounds/items?" + query))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  @SpringBootApplication
  @Import(ItemsController.class)
  static class TestApp {}

  @RestController
  static class ItemsController {

    /** Returns a page large enough to flush mid-write, so the header commit happens inside
     * the message converter — the path on which the application's exception handlers used to
     * re-enter header writing. */
    @GetMapping("/link-bounds/items")
    @Tmf630Response
    public Page<Item> items(
        @RequestParam(name = "offset", defaultValue = "0") int offset,
        @RequestParam(name = "limit", defaultValue = "10") int limit) {
      List<Item> content =
          IntStream.range(0, PAGE_SIZE_BEYOND_RESPONSE_BUFFER)
              .mapToObj(i -> new Item("v".repeat(1500)))
              .toList();
      return new PageImpl<>(content, new OffsetLimitPageRequest(offset, limit, Sort.unsorted()), 50);
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
