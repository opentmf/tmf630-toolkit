package org.opentmf.query.tmf630.jsonb.combo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** An element of a collection of objects. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ComboTag(String code) {}
