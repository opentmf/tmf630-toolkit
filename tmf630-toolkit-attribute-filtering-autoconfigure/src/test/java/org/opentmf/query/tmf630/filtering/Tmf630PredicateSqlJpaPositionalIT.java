package org.opentmf.query.tmf630.filtering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.it.sql.SqlCaptureInspector;
import org.opentmf.query.tmf630.filtering.it.sqlnested.SqlOrderController;
import org.opentmf.query.tmf630.filtering.it.sqlnested.SqlOrderEntity;
import org.opentmf.query.tmf630.filtering.it.sqlnested.SqlOrderNote;
import org.opentmf.query.tmf630.filtering.it.sqlnested.SqlOrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Row 8 of the top capability matrix — positional index {@code [N]} in {@code filter=}
 * on a JPA entity. Verifies that when the target collection carries
 * {@code @OrderColumn}, the toolkit compiles {@code hop[N].leaf==literal} to a correlated
 * {@code EXISTS} subquery with a JPQL {@code INDEX(alias) = N} constraint, and that
 * unordered collections or nested-positional shapes are rejected with actionable messages.
 */
@SpringBootTest(
    classes = Tmf630PredicateSqlJpaPositionalIT.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.show-sql=false",
      "spring.datasource.url=${tmf630.sql.it.datasource.url:jdbc:tc:postgresql:18.1-alpine:///db}",
      "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
      "spring.jpa.properties.hibernate.session_factory.statement_inspector=org.opentmf.query.tmf630.filtering.it.sql.SqlCaptureInspector",
      "opentmf.tmf630.attribute-filtering.allow-nested-paths-jpa=true"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Tmf630PredicateSqlJpaPositionalIT {

  @Autowired private MockMvc mockMvc;
  @Autowired private SqlOrderRepository repository;

  @BeforeEach
  void setUp() {
    SqlCaptureInspector.clear();
    repository.deleteAll();

    SqlOrderEntity a = new SqlOrderEntity();
    a.setReference("ORD-A");
    a.setStatus("OPEN");
    a.addNote(newNote("Alice", "first note on A"));
    a.addNote(newNote("Bob", "second note on A"));

    SqlOrderEntity b = new SqlOrderEntity();
    b.setReference("ORD-B");
    b.setStatus("OPEN");
    b.addNote(newNote("Bob", "first note on B"));
    b.addNote(newNote("Alice", "second note on B"));

    SqlOrderEntity c = new SqlOrderEntity();
    c.setReference("ORD-C");
    c.setStatus("CLOSED");
    c.addNote(newNote("Carol", "only note on C"));

    repository.saveAll(List.of(a, b, c));
    SqlCaptureInspector.clear();
  }

  @Test
  @DisplayName(
      "positional [0] on @OrderColumn collection matches parents whose first note author"
          + " is Alice — ORD-A only (ORD-B's first is Bob, ORD-C's first is Carol)")
  void positionalZeroSelectsFirstNoteAuthor() throws Exception {
    mockMvc
        .perform(
            get("/sql-orders")
                .param("filter", "$[?(@.notes[0].author == 'Alice')]")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
        .andExpect(jsonPath("$[0].reference").value("ORD-A"));

    String sql = firstSelectSql();
    // Hibernate translates JPQL INDEX(n) = 0 to a WHERE clause on the @OrderColumn
    // (note_order in the schema); the literal 0 becomes a bind parameter (?).
    assertThat(sql).contains("exists").contains("note_order=?");
  }

  @Test
  @DisplayName(
      "positional [1] on @OrderColumn collection matches parents whose second note author is Alice"
          + " — ORD-B only (ORD-A's second is Bob, ORD-C has no second note)")
  void positionalOneSelectsSecondNoteAuthor() throws Exception {
    mockMvc
        .perform(
            get("/sql-orders")
                .param("filter", "$[?(@.notes[1].author == 'Alice')]")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
        .andExpect(jsonPath("$[0].reference").value("ORD-B"));
  }

  @Test
  @DisplayName(
      "positional filter on an unordered @OneToMany (no @OrderColumn) rejected with actionable"
          + " message naming the annotation and the keyed alternative")
  void rejectsPositionalOnUnorderedCollection() throws Exception {
    mockMvc
        .perform(
            get("/sql-orders")
                .param("filter", "$[?(@.items[0].state == 'Pending')]")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("nested positional (v1 out-of-scope) rejected with clear message")
  void rejectsNestedPositional() throws Exception {
    // Purely a parser-level rejection — the shape 'a[0].b[0].c' isn't the single-hop
    // supported by v1, so it never reaches the @OrderColumn check.
    mockMvc
        .perform(
            get("/sql-orders")
                .param("filter", "$[?(@.notes[0].nested[0].value == 'X')]")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("positional combines with parent-side attribute filters via AND")
  void positionalComposesWithParentFilter() throws Exception {
    mockMvc
        .perform(
            get("/sql-orders")
                .param("status", "OPEN")
                .param("filter", "$[?(@.notes[0].author == 'Alice')]")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
        .andExpect(jsonPath("$[0].reference").value("ORD-A"));
  }

  private static SqlOrderNote newNote(String author, String text) {
    SqlOrderNote note = new SqlOrderNote();
    note.setAuthor(author);
    note.setText(text);
    return note;
  }

  private static String firstSelectSql() {
    List<String> selects =
        SqlCaptureInspector.snapshot().stream()
            .filter(sql -> sql.trim().toUpperCase(Locale.ROOT).startsWith("SELECT"))
            .map(sql -> sql.toLowerCase(Locale.ROOT))
            .toList();
    assertThat(selects).isNotEmpty();
    return selects.get(0);
  }

  @SpringBootApplication(
      scanBasePackageClasses = SqlOrderController.class,
      exclude = {
        MongoAutoConfiguration.class,
        DataMongoAutoConfiguration.class,
        DataMongoRepositoriesAutoConfiguration.class
      })
  static class TestApp {}
}
