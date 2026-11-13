package org.opentmf.query.tmf630.jsonb.combo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * One field per declared type of the combination suite. The same set sits at the top level of
 * {@link ComboDomain}, in its nested object, in its array elements, in the arrays of its array
 * elements, and in the split items of {@link ComboSplitDomain}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ComboLeaf(
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
    List<ComboTag> lo) {}
