package org.opentmf.query.tmf630.jsonb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.opentmf.query.tmf630.paging.TmfSortTerm;
import org.springframework.data.domain.Sort;

/**
 * {@link Tmf630JsonbColumn} (ledger #4256): discovery and startup validation on the row entity, the
 * column's SQL, the sort on it, and {@link Tmf630JsonbColumns#copy}.
 */
@SuppressWarnings("unused")
class JsonbDeclaredColumnTest {

  // ---- discovery ----------------------------------------------------------------------------

  @Test
  void aDeclaredFieldIsDiscoveredWithItsColumnNameAndType() {
    JsonbEntityMetadata metadata = JsonbEntityMetadata.of(MessageRow.class);

    assertThat(metadata.declaredColumns())
        .containsExactlyInAnyOrder(
            new JsonbDeclaredColumn("sendTime", "sent_at", "sentAt", OffsetDateTime.class),
            new JsonbDeclaredColumn("state", "state", "state", String.class),
            new JsonbDeclaredColumn("receiver.city", "receiver_city", "receiverCity", String.class),
            new JsonbDeclaredColumn("priority", "priority", "priority", Integer.class));
    assertThat(metadata.declaredColumn("sendTime")).map(JsonbDeclaredColumn::column).hasValue("sent_at");
    assertThat(metadata.declaredColumn("payload")).isEmpty();
  }

  @Test
  void anEntityThatDeclaresNothingHasNoColumns() {
    assertThat(JsonbEntityMetadata.of(PlainRow.class).declaredColumns()).isEmpty();
  }

  @Test
  void onlyATextColumnTakesTheStringOperators() {
    assertThat(new JsonbDeclaredColumn("s", "s", "s", String.class).isText()).isTrue();
    assertThat(new JsonbDeclaredColumn("t", "t", "t", OffsetDateTime.class).isText()).isFalse();
  }

  @Test
  void aPathThroughAListIsRefused() {
    assertThatThrownBy(() -> JsonbEntityMetadata.of(ThroughListRow.class))
        .isInstanceOf(Tmf630JsonbConfigurationException.class)
        .hasMessageContaining("@Tmf630JsonbColumn(path = \"tags\")")
        .hasMessageContaining("a column holds one value");
  }

  @Test
  void aMapIsRefused() {
    assertThatThrownBy(() -> JsonbEntityMetadata.of(MapRow.class))
        .isInstanceOf(Tmf630JsonbConfigurationException.class)
        .hasMessageContaining("is not a scalar (Map)");
  }

  @Test
  void anUnknownPathIsRefused() {
    assertThatThrownBy(() -> JsonbEntityMetadata.of(UnknownPathRow.class))
        .isInstanceOf(Tmf630JsonbConfigurationException.class)
        .hasMessageContaining("names no field of Message");
  }

  @Test
  void aFieldOfAnotherTypeIsRefused() {
    assertThatThrownBy(() -> JsonbEntityMetadata.of(WrongTypeRow.class))
        .isInstanceOf(Tmf630JsonbConfigurationException.class)
        .hasMessageContaining("is a OffsetDateTime in the domain type, but field 'sentAt' is a Instant");
  }

  @Test
  void aPrimitiveFieldIsRefused() {
    assertThatThrownBy(() -> JsonbEntityMetadata.of(PrimitiveRow.class))
        .isInstanceOf(Tmf630JsonbConfigurationException.class)
        .hasMessageContaining("is on the primitive field 'priority': use Integer");
  }

  @Test
  void aPathDeclaredTwiceIsRefused() {
    assertThatThrownBy(() -> JsonbEntityMetadata.of(TwiceRow.class))
        .isInstanceOf(Tmf630JsonbConfigurationException.class)
        .hasMessageContaining("is declared by two fields");
  }

  @Test
  void aColumnNameThatIsNoPlainIdentifierIsRefused() {
    assertThatThrownBy(() -> JsonbEntityMetadata.of(QuotedColumnRow.class))
        .isInstanceOf(Tmf630JsonbConfigurationException.class)
        .hasMessageContaining("names the column 'state = state OR true --'")
        .hasMessageContaining("is not a plain SQL identifier");
  }

  @Test
  void aTableNameThatIsNoPlainIdentifierIsRefusedWhenAColumnIsDeclared() {
    assertThatThrownBy(() -> JsonbEntityMetadata.of(SpacedTableRow.class))
        .isInstanceOf(Tmf630JsonbConfigurationException.class)
        .hasMessageContaining("its table name must be a plain SQL identifier")
        .hasMessageContaining("'message row' is not");
  }

  @Test
  void aSchemaQualifiedTableIsAccepted() {
    assertThat(JsonbEntityMetadata.of(SchemaTableRow.class).tableName()).isEqualTo("dnms.message_row");
  }

  @Test
  void anEnumMayBeCopiedIntoAStringColumn() {
    assertThat(JsonbEntityMetadata.of(EnumRow.class).declaredColumns())
        .containsExactly(new JsonbDeclaredColumn("stage", "stage", "stage", String.class));
  }

  // ---- the column's SQL ---------------------------------------------------------------------------

  @Test
  void valueOperatorsCompareTheColumnWithATypedBind() {
    OffsetDateTime t = OffsetDateTime.parse("2025-06-01T00:00:00Z");

    assertThat(JsonbColumnPredicates.single(TmfOperator.GTE, "sent_at", t).sql())
        .isEqualTo("sent_at >= ?");
    assertThat(JsonbColumnPredicates.single(TmfOperator.GTE, "sent_at", t).params()).containsExactly(t);
    assertThat(JsonbColumnPredicates.multi(TmfOperator.IN, "state", "state", List.of("A", "B")).sql())
        .isEqualTo("state IN (?, ?)");
    assertThat(
            JsonbColumnPredicates.multi(TmfOperator.BETWEEN, "priority", "priority", List.of(1, 9))
                .sql())
        .isEqualTo("priority BETWEEN ? AND ?");
    assertThat(JsonbColumnPredicates.single(TmfOperator.NE, "state", "A").sql()).isEqualTo("state <> ?");
  }

  @Test
  void anInstantIsBoundAsAnOffsetDateTimeInUtc() {
    Instant instant = Instant.parse("2025-06-01T10:00:00Z");

    assertThat(JsonbColumnPredicates.single(TmfOperator.EQ, "sent_at", instant).params())
        .containsExactly(instant.atOffset(ZoneOffset.UTC));
  }

  @Test
  void stringOperatorsOnATextColumnMatchLiterally() {
    JsonbClause contains = JsonbColumnPredicates.single(TmfOperator.CONTAINS, "state", "50%");

    assertThat(contains.sql()).isEqualTo("state LIKE '%' || ? || '%' ESCAPE '!'");
    assertThat(contains.params()).containsExactly("50!%");
    assertThat(JsonbColumnPredicates.single(TmfOperator.EQI, "state", "a").sql())
        .isEqualTo("LOWER(state) = LOWER(?)");
  }

  @Test
  void theNullTestsReadTheColumn() {
    assertThat(JsonbColumnPredicates.noValue(TmfOperator.IS_NULL, "sent_at").sql())
        .isEqualTo("sent_at IS NULL");
    assertThat(JsonbColumnPredicates.noValue(TmfOperator.IS_NOT_NULL, "sent_at").sql())
        .isEqualTo("sent_at IS NOT NULL");
  }

  @Test
  void betweenNeedsTwoValues() {
    List<Object> one = List.of(1);

    assertThatThrownBy(() -> JsonbColumnPredicates.multi(TmfOperator.BETWEEN, "p", "priority", one))
        .hasMessage("BETWEEN requires exactly two values for field: priority");
  }

  // ---- the sort -------------------------------------------------------------------------------------

  @Test
  void aSortOnADeclaredPathOrdersByTheColumn() {
    JsonbSortBuilder builder = new JsonbSortBuilder(new JsonbPathExtractor("payload"), null, "payload", false);
    TmfSort sort =
        new TmfSort(
            List.of(
                new TmfSortTerm(Sort.Direction.DESC, TmfSortTerm.Kind.PLAIN, "sendTime"),
                new TmfSortTerm(Sort.Direction.ASC, TmfSortTerm.Kind.PLAIN, "id")));

    JsonbClause orderBy =
        builder.buildOrderByClause(
            sort,
            f -> "sendTime".equals(f) ? OffsetDateTime.class : String.class,
            path -> "sendTime".equals(path) ? Optional.of("sent_at") : Optional.empty());

    assertThat(orderBy.sql()).isEqualTo("sent_at DESC, payload->>'id' ASC");
  }

  // ---- copy ---------------------------------------------------------------------------------------

  @Test
  void copySetsEveryDeclaredFieldFromTheDomainObject() {
    OffsetDateTime t = OffsetDateTime.parse("2025-06-01T00:00:00Z");
    Message message = new Message(t, "SENT", new Receiver("Izmir"), 3, List.of(), Map.of());

    MessageRow row = Tmf630JsonbColumns.copy(message, new MessageRow());

    assertThat(row.sentAt).isEqualTo(t);
    assertThat(row.state).isEqualTo("SENT");
    assertThat(row.receiverCity).isEqualTo("Izmir");
    assertThat(row.priority).isEqualTo(3);
  }

  @Test
  void copyGivesNullForANullOnThePath() {
    Message message = new Message(null, null, null, null, List.of(), Map.of());
    MessageRow row = new MessageRow();
    row.state = "stale";

    Tmf630JsonbColumns.copy(message, row);

    assertThat(row.state).isNull();
    assertThat(row.receiverCity).isNull();
  }

  @Test
  void copyWritesAnEnumAsTheMapperWritesIt() {
    EnumRow row = Tmf630JsonbColumns.copy(new Staged(Stage.IN_PROGRESS), new EnumRow());

    assertThat(row.stage).isEqualTo("inProgress");
  }

  @Test
  void copyReadsAPlainObjectThroughItsGetters() {
    LetterRow row = Tmf630JsonbColumns.copy(new Letter("Ankara"), new LetterRow());

    assertThat(row.recipient).isEqualTo("Ankara");
  }

  @Test
  void copyNeedsAPublicSetterOnTheRow() {
    Letter letter = new Letter("Ankara");
    SetterlessRow row = new SetterlessRow();

    assertThatThrownBy(() -> Tmf630JsonbColumns.copy(letter, row))
        .isInstanceOf(Tmf630JsonbConfigurationException.class)
        .hasMessageContaining("needs a public setter for the declared field 'recipient'");
  }

  @Test
  void copyNeedsAPublicAccessorOnTheDomainObject() {
    Sealed sealed = new Sealed("Ankara");
    SealedRow row = new SealedRow();

    assertThatThrownBy(() -> Tmf630JsonbColumns.copy(sealed, row))
        .isInstanceOf(Tmf630JsonbConfigurationException.class)
        .hasMessageContaining("No public accessor for 'recipient' of path 'recipient'");
  }

  // ---- fixtures -------------------------------------------------------------------------------------

  record Receiver(String city) {}

  record Message(
      OffsetDateTime sendTime,
      String state,
      Receiver receiver,
      Integer priority,
      List<String> tags,
      Map<String, Object> extra) {}

  @Tmf630JsonbBacked(domainType = Message.class)
  static class MessageRow {
    String id;
    String payload;

    @Tmf630JsonbColumn(path = "sendTime")
    @Column(name = "sent_at")
    OffsetDateTime sentAt;

    @Tmf630JsonbColumn(path = "state")
    String state;

    @Tmf630JsonbColumn(path = "receiver.city")
    String receiverCity;

    @Tmf630JsonbColumn(path = "priority")
    Integer priority;

    public void setSentAt(OffsetDateTime sentAt) {
      this.sentAt = sentAt;
    }

    public void setState(String state) {
      this.state = state;
    }

    public void setReceiverCity(String receiverCity) {
      this.receiverCity = receiverCity;
    }

    public void setPriority(Integer priority) {
      this.priority = priority;
    }
  }

  /** A domain object read through JavaBeans getters rather than a record's accessors. */
  public static class Letter {
    private final String recipient;

    public Letter(String recipient) {
      this.recipient = recipient;
    }

    public String getRecipient() {
      return recipient;
    }
  }

  @Tmf630JsonbBacked(domainType = Letter.class)
  public static class LetterRow {
    String payload;

    @Tmf630JsonbColumn(path = "recipient")
    String recipient;

    public void setRecipient(String recipient) {
      this.recipient = recipient;
    }
  }

  /** Declares a column but offers no setter for it. */
  @Tmf630JsonbBacked(domainType = Letter.class)
  static class SetterlessRow {
    String payload;

    @Tmf630JsonbColumn(path = "recipient")
    String recipient;
  }

  /** A domain class with the field the row declares, but no public getter for it. */
  public static class Sealed {
    private final String recipient;

    public Sealed(String recipient) {
      this.recipient = recipient;
    }

    @Override
    public String toString() {
      return recipient;
    }
  }

  @Tmf630JsonbBacked(domainType = Sealed.class)
  public static class SealedRow {
    String payload;

    @Tmf630JsonbColumn(path = "recipient")
    String recipient;

    public void setRecipient(String recipient) {
      this.recipient = recipient;
    }
  }

  @Tmf630JsonbBacked(domainType = Message.class)
  static class QuotedColumnRow {
    String payload;

    @Tmf630JsonbColumn(path = "state")
    @Column(name = "state = state OR true --")
    String state;
  }

  @Table(name = "message row")
  @Tmf630JsonbBacked(domainType = Message.class)
  static class SpacedTableRow {
    String payload;

    @Tmf630JsonbColumn(path = "state")
    String state;
  }

  @Table(name = "dnms.message_row")
  @Tmf630JsonbBacked(domainType = Message.class)
  static class SchemaTableRow {
    String payload;

    @Tmf630JsonbColumn(path = "state")
    String state;
  }

  @Tmf630JsonbBacked(domainType = Message.class)
  static class PrimitiveRow {
    String payload;

    @Tmf630JsonbColumn(path = "priority")
    int priority;
  }

  @Tmf630JsonbBacked(domainType = Message.class)
  static class PlainRow {
    String payload;
  }

  @Tmf630JsonbBacked(domainType = Message.class)
  static class ThroughListRow {
    String payload;

    @Tmf630JsonbColumn(path = "tags")
    String tags;
  }

  @Tmf630JsonbBacked(domainType = Message.class)
  static class MapRow {
    String payload;

    @Tmf630JsonbColumn(path = "extra")
    String extra;
  }

  @Tmf630JsonbBacked(domainType = Message.class)
  static class UnknownPathRow {
    String payload;

    @Tmf630JsonbColumn(path = "sendTimes")
    OffsetDateTime sentAt;
  }

  @Tmf630JsonbBacked(domainType = Message.class)
  static class WrongTypeRow {
    String payload;

    @Tmf630JsonbColumn(path = "sendTime")
    Instant sentAt;
  }

  @Tmf630JsonbBacked(domainType = Message.class)
  static class TwiceRow {
    String payload;

    @Tmf630JsonbColumn(path = "state")
    String a;

    @Tmf630JsonbColumn(path = "state")
    String b;
  }

  enum Stage {
    IN_PROGRESS("inProgress"),
    DONE("done");

    private final String wire;

    Stage(String wire) {
      this.wire = wire;
    }

    @JsonValue
    String wire() {
      return wire;
    }
  }

  record Staged(Stage stage) {}

  @Tmf630JsonbBacked(domainType = Staged.class)
  static class EnumRow {
    String payload;

    @Tmf630JsonbColumn(path = "stage")
    String stage;

    public void setStage(String stage) {
      this.stage = stage;
    }
  }
}
