package org.opentmf.query.tmf630.jsonb;

import java.util.List;

/**
 * The shape of a resolved filter field path: its segments, and which of them are collections. A
 * collection step before the leaf is an array the path crosses; a collection leaf is a list whose
 * elements are the values.
 */
record JsonbFieldPath(List<String> segments, List<Boolean> arrays) {

  JsonbFieldPath {
    segments = List.copyOf(segments);
    arrays = List.copyOf(arrays);
  }

  int size() {
    return segments.size();
  }

  /** Whether a step before the leaf is a collection. */
  boolean crossesArray() {
    return arrays.subList(0, size() - 1).contains(Boolean.TRUE);
  }

  boolean leafIsCollection() {
    return Boolean.TRUE.equals(arrays.get(size() - 1));
  }

  /**
   * The first array step: before the leaf, or the leaf itself when {@code intoLeaf} and the leaf
   * is a collection; -1 when there is none.
   */
  int firstArray(boolean intoLeaf) {
    int last = intoLeaf ? size() : size() - 1;
    for (int i = 0; i < last; i++) {
      if (Boolean.TRUE.equals(arrays.get(i))) {
        return i;
      }
    }
    return -1;
  }

  /** The path's first {@code count} segments, dotted. */
  String head(int count) {
    return String.join(".", segments.subList(0, count));
  }

  String dotted() {
    return String.join(".", segments);
  }

  /** The path after its first {@code from} segments. */
  JsonbFieldPath tail(int from) {
    return new JsonbFieldPath(segments.subList(from, size()), arrays.subList(from, size()));
  }

  String[] segmentArray() {
    return segments.toArray(String[]::new);
  }

  boolean[] arrayFlags() {
    boolean[] flags = new boolean[size()];
    for (int i = 0; i < flags.length; i++) {
      flags[i] = Boolean.TRUE.equals(arrays.get(i));
    }
    return flags;
  }
}
