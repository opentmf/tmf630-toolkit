package org.opentmf.query.tmf630.jsonb.combo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** An array element that holds an array: the "array inside an array" position. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ComboOuter(List<ComboLeaf> inner) {}
