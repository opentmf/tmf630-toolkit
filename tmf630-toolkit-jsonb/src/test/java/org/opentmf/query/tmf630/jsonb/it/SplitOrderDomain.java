package org.opentmf.query.tmf630.jsonb.it;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbSplitCollection;

/**
 * Test domain modelling a TMF-style parent with a split child collection —
 * exercises {@link Tmf630JsonbSplitCollection}'s read-merge behavior against real
 * Postgres. The child collection ({@code items}) is stored in a companion table
 * ({@code split_order_item}) rather than inline in the parent's payload, and the
 * executor re-assembles it into the deserialized parent at read time.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SplitOrderDomain {

  private String id;
  private String status;

  @Tmf630JsonbSplitCollection(
      childTable = "split_order_item",
      childType = SplitOrderItem.class,
      maxInlineItems = 100)
  private List<SplitOrderItem> items;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public List<SplitOrderItem> getItems() {
    return items;
  }

  public void setItems(List<SplitOrderItem> items) {
    this.items = items;
  }
}
