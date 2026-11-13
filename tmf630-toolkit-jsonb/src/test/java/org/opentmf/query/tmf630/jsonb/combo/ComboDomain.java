package org.opentmf.query.tmf630.jsonb.combo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * The non-split root of the combination suite: the {@link ComboLeaf} fields at the top level, in
 * the nested object {@code n}, in the elements of {@code arr} and in the elements of the arrays
 * {@code arr2[].inner}. The suite selects ids only and never deserialises a payload, so rows may
 * hold values in another JSON type than declared.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ComboDomain(
    String id,
    String s,
    Integer i,
    Long l,
    BigDecimal d,
    Double f,
    Boolean b,
    LocalDate ld,
    OffsetDateTime t,
    ComboColor e,
    ComboStage w,
    Object o,
    Map<String, Object> m,
    List<String> ls,
    List<ComboTag> lo,
    ComboLeaf n,
    List<ComboLeaf> arr,
    List<ComboOuter> arr2) {}
