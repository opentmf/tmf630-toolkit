package org.opentmf.query.tmf630.jsonb.combo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbSplitCollection;

/** The split root of the combination suite: its {@code items} live in their own table. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ComboSplitDomain {

  private String id;

  @Tmf630JsonbSplitCollection(
      childTable = ComboSplitRow.CHILD_TABLE,
      childType = ComboLeaf.class,
      maxInlineItems = 100)
  private List<ComboLeaf> items;

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public List<ComboLeaf> getItems() {
    return items;
  }

  public void setItems(List<ComboLeaf> items) {
    this.items = items;
  }
}
