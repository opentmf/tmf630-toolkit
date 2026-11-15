package org.opentmf.query.tmf630.jsonb.it;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;

/** Domain of {@link DeclaredMessageRow}: a message with a send time, an owner and a state. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeclaredMessage(String id, OffsetDateTime sendTime, String owner, String state) {}
