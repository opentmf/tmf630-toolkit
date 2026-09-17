package org.opentmf.query.tmf630.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The query-parameter guard: an oversize parameter value answers {@code 400}, an oversize
 * query string {@code 414}, both as TMF error bodies, before any handler runs — so nothing
 * downstream (the {@code Link} header included) can echo the value. Limits are set to
 * deliberately small, non-default values so a pass cannot come from a built-in constant.
 *
 * <p>Parameters are placed in the request URI on purpose: the guard measures {@code
 * getQueryString()}, which MockMvc only populates from the URI, never from {@code .param()}.
 */
class Tmf630QueryLimitsIT {

  @SpringBootTest(
      classes = TestApp.class,
      properties = {
        "opentmf.tmf630.query-limits.max-query-string-length=120",
        "opentmf.tmf630.query-limits.max-param-value-length=40"
      })
  @AutoConfigureMockMvc
  @Nested
  class Enabled {

    @Autowired private MockMvc mockMvc;
    @Autowired private Counter counter;

    @BeforeEach
    void reset() {
      counter.calls.set(0);
    }

    @Test
    @DisplayName("value above max-param-value-length → 400 TMF body naming the parameter")
    void oversizeValueIs400() throws Exception {
      mockMvc
          .perform(get("/guarded?fields=" + "a".repeat(41)))
          .andExpect(status().isBadRequest())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
          .andExpect(jsonPath("$.code").value("400"))
          .andExpect(jsonPath("$.status").value("Bad Request"))
          .andExpect(jsonPath("$.reason").value("Query parameter too long."))
          .andExpect(
              jsonPath("$.message")
                  .value("Query parameter 'fields' is 41 characters long; the limit is 40."));
      assertEquals(0, counter.calls.get(), "the handler must not run");
    }

    @Test
    @DisplayName("query string above max-query-string-length → 414 TMF body")
    void oversizeQueryStringIs414() throws Exception {
      // Five values of 30 chars each are individually fine; the query string is 5*33+4 = 169 > 120.
      mockMvc
          .perform(
              get(
                  "/guarded?p1={v}&p2={v}&p3={v}&p4={v}&p5={v}",
                  "a".repeat(30), "a".repeat(30), "a".repeat(30), "a".repeat(30),
                  "a".repeat(30)))
          .andExpect(status().isUriTooLong())
          .andExpect(jsonPath("$.code").value("414"))
          .andExpect(jsonPath("$.status").value("URI Too Long"))
          .andExpect(jsonPath("$.reason").value("Query string too long."))
          .andExpect(jsonPath("$.message").value("Query string is 169 characters long; the limit is 120."));
      assertEquals(0, counter.calls.get(), "the handler must not run");
    }

    @Test
    @DisplayName("values at the limits pass through to the handler")
    void valueAtTheLimitReachesTheHandler() throws Exception {
      mockMvc
          .perform(get("/guarded?fields=" + "a".repeat(40)))
          .andExpect(status().isOk())
          .andExpect(content().string("ok"));
      assertEquals(1, counter.calls.get());
    }

    @Test
    @DisplayName("no query string at all is never rejected")
    void noQueryStringPasses() throws Exception {
      mockMvc.perform(get("/guarded")).andExpect(status().isOk());
      assertEquals(1, counter.calls.get());
    }

    @Test
    @DisplayName("the guard reads the query string, not a form body")
    void formBodyIsNotMeasured() throws Exception {
      mockMvc
          .perform(
              post("/guarded")
                  .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                  .content("fields=" + "a".repeat(500)))
          .andExpect(status().isOk());
      assertEquals(1, counter.calls.get());
    }

    @Test
    @DisplayName("the guard's handler wins over the application's catch-all")
    void guardHandlerWinsOverCatchAll() throws Exception {
      // TestApp registers @ExceptionHandler(Exception.class) → 500; ours is HIGHEST_PRECEDENCE.
      mockMvc
          .perform(get("/guarded?x=" + "b".repeat(100)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("400"));
    }
  }

  @SpringBootTest(
      classes = TestApp.class,
      properties = {
        "opentmf.tmf630.query-limits.enabled=false",
        "opentmf.tmf630.query-limits.max-param-value-length=40"
      })
  @AutoConfigureMockMvc
  @Nested
  class Disabled {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("enabled=false switches the guard off")
    void oversizeValuePassesWhenDisabled() throws Exception {
      mockMvc
          .perform(get("/guarded?fields=" + "a".repeat(500)))
          .andExpect(status().isOk());
    }
  }

  @SpringBootApplication
  @Import({GuardedController.class, Counter.class, CatchAll.class})
  static class TestApp {}

  static class Counter {
    final AtomicInteger calls = new AtomicInteger();
  }

  @RestController
  static class GuardedController {

    private final Counter counter;

    GuardedController(Counter counter) {
      this.counter = counter;
    }

    @RequestMapping(path = "/guarded", method = {RequestMethod.GET, RequestMethod.POST})
    public String guarded() {
      counter.calls.incrementAndGet();
      return "ok";
    }
  }

  @RestControllerAdvice(assignableTypes = GuardedController.class)
  static class CatchAll {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handle(Exception ex) {
      return ResponseEntity.status(500).body("catch-all");
    }
  }
}
