package org.opentmf.query.tmf630.jsonb.it;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Domain POJO of {@link JsonbIndexedRow}: a nested three-level path ({@code
 * receiver.postalAddress.city}) and an array of name/value pairs ({@code characteristic}), the
 * two shapes whose {@code filter=} queries the GIN index must serve.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record JsonbIndexedDomain(
    String id,
    String status,
    Integer priority,
    Receiver receiver,
    List<Characteristic> characteristic) {

  /** The receiver of the resource. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Receiver(PostalAddress postalAddress) {}

  /** The receiver's address. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PostalAddress(String city, Integer houseNumber) {}

  /** One name/value pair. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Characteristic(String name, String value) {}
}
