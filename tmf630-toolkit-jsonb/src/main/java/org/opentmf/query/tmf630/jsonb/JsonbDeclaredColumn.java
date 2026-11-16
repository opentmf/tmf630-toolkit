package org.opentmf.query.tmf630.jsonb;

/**
 * A column of a JSONB-backed row entity that copies one value of the payload
 * ({@link Tmf630JsonbColumn}).
 *
 * @param path the domain field's dotted path
 * @param column the column's name
 * @param rowField the row entity's field holding it
 * @param type the field's Java type
 */
public record JsonbDeclaredColumn(String path, String column, String rowField, Class<?> type) {

  /** Whether the column holds text, so the string operators can compare it. */
  public boolean isText() {
    return type == String.class;
  }
}
