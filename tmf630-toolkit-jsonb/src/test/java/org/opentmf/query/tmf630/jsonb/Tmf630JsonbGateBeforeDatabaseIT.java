package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Under the opt-in {@code opentmf.tmf630.jsonb.querydsl-acceptance=true}, the type gate refuses
 * before any SQL: a request the QueryDSL terminal refuses is a 400 on the
 * JSONB endpoint with the core's message, and not one statement reaches the database for it. The
 * control, an accepted request on the same endpoint, does issue a statement, so the counter counts.
 * The same URL on the QueryDSL endpoint gets the same 400.
 */
@SpringBootTest(
    classes = Tmf630JsonbUrlBindingParityIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "opentmf.tmf630.attribute-filtering.regex.enabled=true",
      "opentmf.tmf630.jsonb.querydsl-acceptance=true"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(Tmf630JsonbGateBeforeDatabaseIT.CountingDataSourceConfig.class)
class Tmf630JsonbGateBeforeDatabaseIT {

  private static final AtomicInteger STATEMENTS = new AtomicInteger();
  private static final String STRING_OPERATOR =
      "String operator requires String or polymorphic (Object/Serializable) field: priority";

  @Autowired private MockMvc mockMvc;

  @BeforeEach
  void resetCounter() {
    STATEMENTS.set(0);
  }

  @Test
  void aRefusedUrlOperatorIssuesNoStatement() throws Exception {
    mockMvc
        .perform(get("/parity/jsonb").param("priority.contains", "1"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString(STRING_OPERATOR)));

    assertThat(STATEMENTS.get()).isZero();
  }

  @Test
  void aRefusedFilterIssuesNoStatement() throws Exception {
    mockMvc
        .perform(get("/parity/jsonb").param("filter", "$[?(@.priority =~ /1/)]"))
        .andExpect(status().isBadRequest());

    assertThat(STATEMENTS.get()).isZero();
  }

  @Test
  void theQuerydslEndpointRefusesTheSameUrlWithTheSameMessage() throws Exception {
    mockMvc
        .perform(get("/parity/jpa").param("priority.contains", "1"))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(containsString(STRING_OPERATOR)));
  }

  @Test
  void anAcceptedRequestDoesIssueAStatement() throws Exception {
    mockMvc.perform(get("/parity/jsonb").param("priority.gt", "1")).andExpect(status().isOk());

    assertThat(STATEMENTS.get()).isPositive();
  }

  /** Wraps the context's DataSource so every statement a connection prepares is counted. */
  @TestConfiguration(proxyBeanMethods = false)
  static class CountingDataSourceConfig {

    @Bean
    static BeanPostProcessor countingDataSource() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          if (bean instanceof DataSource dataSource && !(bean instanceof CountingDataSource)) {
            return new CountingDataSource(dataSource);
          }
          return bean;
        }
      };
    }
  }

  /** A DataSource whose connections count {@code prepare*} and {@code createStatement} calls. */
  static final class CountingDataSource extends DelegatingDataSource {

    CountingDataSource(DataSource target) {
      super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
      return counting(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return counting(super.getConnection(username, password));
    }

    private static Connection counting(Connection connection) {
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                String name = method.getName();
                if (name.startsWith("prepare") || "createStatement".equals(name)) {
                  STATEMENTS.incrementAndGet();
                }
                try {
                  return method.invoke(connection, args);
                } catch (InvocationTargetException e) {
                  throw e.getCause();
                }
              });
    }
  }
}
