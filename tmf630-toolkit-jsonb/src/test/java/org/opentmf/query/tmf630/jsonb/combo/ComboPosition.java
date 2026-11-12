package org.opentmf.query.tmf630.jsonb.combo;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The field-position axis: where in the payload the field under test sits. Each position knows
 * its URL path prefix, its {@code filter=} path (object steps and array steps), how a row holds
 * one value there, and which objects of a payload hold the field.
 */
enum ComboPosition {
  TOP("", List.of()),
  NESTED("n.", List.of(new Step("n", false))),
  ARRAY("arr.", List.of(new Step("arr", true))),
  ARRAY_IN_ARRAY("arr2.inner.", List.of(new Step("arr2", true), new Step("inner", true))),
  SPLIT("items.", List.of(new Step("items", true)));

  /** One step of a {@code filter=} path: an object field, or an array to correlate into. */
  record Step(String name, boolean array) {}

  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private final String urlPrefix;
  private final List<Step> steps;

  ComboPosition(String urlPrefix, List<Step> steps) {
    this.urlPrefix = urlPrefix;
    this.steps = steps;
  }

  String urlPrefix() {
    return urlPrefix;
  }

  List<Step> steps() {
    return steps;
  }

  boolean isArray() {
    return this == ARRAY || this == ARRAY_IN_ARRAY || this == SPLIT;
  }

  /**
   * The logical payload of a row holding {@code leaf} at this position. An array position puts
   * an element WITHOUT the field first, so a match on element 0 only is caught. For {@link #SPLIT}
   * the {@code items} are what the child table holds.
   */
  ObjectNode payload(String id, ObjectNode leaf) {
    ObjectNode doc = NODES.objectNode().put("id", id);
    switch (this) {
      case TOP -> doc.setAll(leaf);
      case NESTED -> doc.set("n", leaf);
      case ARRAY, SPLIT -> doc.set(this == ARRAY ? "arr" : "items", pair(leaf));
      case ARRAY_IN_ARRAY -> {
        ArrayNode outer = NODES.arrayNode();
        outer.add(NODES.objectNode().set("inner", NODES.arrayNode()));
        outer.add(NODES.objectNode().set("inner", pair(leaf)));
        doc.set("arr2", outer);
      }
    }
    return doc;
  }

  private static ArrayNode pair(ObjectNode leaf) {
    return NODES.arrayNode().add(NODES.objectNode()).add(leaf);
  }

  /** The objects of {@code doc} at this position: those that may hold the field. */
  List<JsonNode> containers(JsonNode doc) {
    List<JsonNode> out = new ArrayList<>();
    switch (this) {
      case TOP -> out.add(doc);
      case NESTED -> addIfObject(out, doc.get("n"));
      case ARRAY -> addElements(out, doc.get("arr"));
      case SPLIT -> addElements(out, doc.get("items"));
      case ARRAY_IN_ARRAY -> {
        List<JsonNode> outer = new ArrayList<>();
        addElements(outer, doc.get("arr2"));
        for (JsonNode element : outer) {
          addElements(out, element.get("inner"));
        }
      }
    }
    return out;
  }

  private static void addIfObject(List<JsonNode> out, JsonNode node) {
    if (node != null && node.isObject()) {
      out.add(node);
    }
  }

  private static void addElements(List<JsonNode> out, JsonNode node) {
    if (node != null && node.isArray()) {
      node.valueStream().filter(JsonNode::isObject).forEach(out::add);
    }
  }
}
