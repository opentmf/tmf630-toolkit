package org.opentmf.query.tmf630.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.exception.RequestedRangeNotSatisfiableException;
import org.opentmf.query.tmf630.exception.TmfPagingException;
import org.opentmf.query.tmf630.paging.TmfSortKeyValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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
        .andExpect(content().string("7:3:createdOn:DESC"));
  }

  // The 50 in the two sort-without-paging expectations below is the toolkit's
  // default-limit default — NOT Spring Data's hard-coded 20. Pinning 20 here would
  // reintroduce the pre-3.1.1 defect where the no-offset/limit fallback ignored
  // opentmf.tmf630.paging.default-limit entirely.
  @Test
  void resolvesSignedSortWithoutOffsetLimitForPageable() throws Exception {
    mockMvc
        .perform(get("/page").param("sort", "-createdOn"))
        .andExpect(status().isOk())
        .andExpect(content().string("0:50:createdOn:DESC"));
  }

  @Test
  void resolvesPlusSortWithoutOffsetLimitForPageable() throws Exception {
    mockMvc
        .perform(get("/page").param("sort", " transformationId"))
        .andExpect(status().isOk())
        .andExpect(content().string("0:50:transformationId:ASC"));
  }

  @Test
  void noPagingParamsFallsBackToConfiguredDefaultLimit() throws Exception {
    mockMvc
        .perform(get("/page"))
        .andExpect(status().isOk())
        .andExpect(content().string("0:50:none"));
  }

  @Test
  void mapsRangeExceptionTo416WithHeaders() throws Exception {
    mockMvc
        .perform(get("/range-error"))
        .andExpect(status().isRequestedRangeNotSatisfiable())
        .andExpect(header().string("Content-Range", "items */9"));
  }

  @Test
  void resolvesSortOnlyWithTmfSignedSyntax() throws Exception {
    mockMvc
        .perform(get("/sort-only").param("sort", " transformationId").param("sort", "-createdOn"))
        .andExpect(status().isOk())
        .andExpect(content().string("transformationId:ASC|createdOn:DESC"));
  }

  @Test
  void contributedSortKeyValidatorReachesThePageableAndSortResolvers() throws Exception {
    mockMvc
        .perform(get("/page").param("sort", "-nosuch"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.reason").value("Invalid sort or paging parameter."))
        .andExpect(jsonPath("$.message").value("Unknown sort property: nosuch"));
    mockMvc
        .perform(get("/sort-only").param("sort", "nosuch"))
        .andExpect(status().isBadRequest());
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
  static class TestApp {

    /** Stands in for the attribute-filtering module's validator: pins the SPI wiring only. */
    @Bean
    TmfSortKeyValidator rejectingSortKeyValidator() {
      return (parameter, keys) -> {
        if (keys.contains("nosuch")) {
          throw new TmfPagingException("Unknown sort property: nosuch");
        }
      };
    }
  }

  @RestController
  static class TestController {
    @GetMapping("/page")
    public String page(Pageable pageable) {
      String sortInfo =
          pageable.getSort().isSorted()
              ? pageable.getSort().toList().get(0).getProperty()
                  + ":"
                  + pageable.getSort().toList().get(0).getDirection().name()
              : "none";
      return pageable.getOffset() + ":" + pageable.getPageSize() + ":" + sortInfo;
    }

    @GetMapping("/sort-only")
    public String sortOnly(Sort sort) {
      if (sort.isUnsorted()) {
        return "none";
      }
      StringBuilder sb = new StringBuilder();
      for (Sort.Order order : sort) {
        if (!sb.isEmpty()) {
          sb.append("|");
        }
        sb.append(order.getProperty()).append(":").append(order.getDirection().name());
      }
      return sb.toString();
    }

    @GetMapping("/range-error")
    public String rangeError() {
      throw new RequestedRangeNotSatisfiableException(99, 9);
    }
  }
}
