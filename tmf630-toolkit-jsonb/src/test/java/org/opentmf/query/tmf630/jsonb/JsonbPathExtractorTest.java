package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsonbPathExtractorTest {

  private final JsonbPathExtractor extractor = new JsonbPathExtractor("payload");

  @Test
  @DisplayName("single-segment scalar extraction uses ->>' operator")
  void singleSegmentText() {
    assertThat(extractor.extractAsText("status")).isEqualTo("payload->>'status'");
  }

  @Test
  @DisplayName("multi-segment scalar extraction uses #>>'{a,b,c}' operator")
  void multiSegmentText() {
    assertThat(extractor.extractAsText("customer.address.city"))
        .isEqualTo("payload#>>'{customer,address,city}'");
  }

  @Test
  @DisplayName("single-segment jsonb extraction uses ->'x' operator")
  void singleSegmentJsonb() {
    assertThat(extractor.extractAsJsonb("items")).isEqualTo("payload->'items'");
  }

  @Test
  @DisplayName("multi-segment jsonb extraction uses #>'{a,b}' operator")
  void multiSegmentJsonb() {
    assertThat(extractor.extractAsJsonb("customer.address"))
        .isEqualTo("payload#>'{customer,address}'");
  }

  @Test
  @DisplayName("hasTopLevelKey uses ? key-existence operator")
  void hasTopLevelKey() {
    assertThat(extractor.hasTopLevelKey("email")).isEqualTo("payload ? 'email'");
  }

  @Test
  @DisplayName("hasTopLevelKey rejects dotted path")
  void hasTopLevelKeyRejectsDotted() {
    assertThatThrownBy(() -> extractor.hasTopLevelKey("customer.email"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("accepts numeric segments (positional [N] normalisation)")
  void acceptsNumericSegments() {
    assertThat(extractor.extractAsText("productOrderItem.2.state"))
        .isEqualTo("payload#>>'{productOrderItem,2,state}'");
  }

  @Test
  @DisplayName("rejects segment with SQL metacharacters")
  void rejectsSqlInjectionAttempt() {
    assertThatThrownBy(() -> extractor.extractAsText("status'; DROP TABLE users; --"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> extractor.extractAsText("a.b}'::text; --"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("rejects blank path")
  void rejectsBlank() {
    assertThatThrownBy(() -> extractor.extractAsText(""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> extractor.extractAsText(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("rejects illegal payload column name at construction")
  void rejectsIllegalPayloadColumn() {
    assertThatThrownBy(() -> new JsonbPathExtractor("payload; DROP TABLE t"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JsonbPathExtractor(""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JsonbPathExtractor(null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
