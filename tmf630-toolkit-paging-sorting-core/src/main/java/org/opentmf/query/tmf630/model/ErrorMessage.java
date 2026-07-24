package org.opentmf.query.tmf630.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * TMF-630 Part 1 §3.4 Error Representation. The mandatory pair is {@code code} + {@code reason};
 * every other component is optional and omitted from the JSON body when {@code null}.
 *
 * <p>The {@code type} and {@code schemaLocation} components serialize as {@code @type} and
 * {@code @schemaLocation} per the spec's runtime-extension convention (see Part 1 §3.4 example).
 * The compact four-argument constructor is preserved so existing call sites keep compiling.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorMessage(
    String code,
    String status,
    String reason,
    String message,
    String referenceError,
    @JsonProperty("@type") String type,
    @JsonProperty("@schemaLocation") String schemaLocation) {

  public ErrorMessage(String code, String status, String reason, String message) {
    this(code, status, reason, message, null, null, null);
  }
}
