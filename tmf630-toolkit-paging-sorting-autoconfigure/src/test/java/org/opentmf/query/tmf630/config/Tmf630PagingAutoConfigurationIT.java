package org.opentmf.query.tmf630.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.exception.RequestedRangeNotSatisfiableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    classes = Tmf630PagingAutoConfigurationIT.TestApp.class,
    properties = {"opentmf.tmf630.paging.enabled=true"})
@AutoConfigureMockMvc
class Tmf630PagingAutoConfigurationIT {

  @Autowired private MockMvc mockMvc;

  @Test
  void resolvesOffsetLimitThroughAutoConfiguration() throws Exception {
    mockMvc
        .perform(get("/page").param("offset", "7").param("limit", "3").param("sort", "-createdOn"))
        .andExpect(status().isOk())
        .andExpect(content().string("7:3:createdOn"));
  }

  @Test
  void mapsRangeExceptionTo416WithHeaders() throws Exception {
    mockMvc
        .perform(get("/range-error"))
        .andExpect(status().isRequestedRangeNotSatisfiable())
        .andExpect(header().string("Content-Range", "items */9"));
  }

  @Test
  void respectsEnabledPropertyCondition() {
    new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                WebMvcAutoConfiguration.class,
                Tmf630WebMvcConfigurer.class,
                Tmf630ExceptionHandlingAutoConfiguration.class))
        .withPropertyValues("opentmf.tmf630.paging.enabled=false")
        .run(
            context -> {
              org.assertj.core.api.Assertions.assertThat(context)
                  .doesNotHaveBean(Tmf630WebMvcConfigurer.class);
              org.assertj.core.api.Assertions.assertThat(context)
                  .hasSingleBean(Tmf630ExceptionHandlingAutoConfiguration.class);
            });
  }

  @SpringBootApplication
  @Import(TestController.class)
  static class TestApp {}

  @RestController
  static class TestController {
    @GetMapping("/page")
    public String page(Pageable pageable) {
      String property =
          pageable.getSort().isSorted() ? pageable.getSort().toList().get(0).getProperty() : "none";
      return pageable.getOffset() + ":" + pageable.getPageSize() + ":" + property;
    }

    @GetMapping("/range-error")
    public String rangeError() {
      throw new RequestedRangeNotSatisfiableException(99, 9);
    }
  }
}
