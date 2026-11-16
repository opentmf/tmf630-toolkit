package org.opentmf.query.tmf630.jsonb.combo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * The scalar fields of {@link ComboDomain}, at the top level and nested, for the declared twin
 * ({@link DeclaredComboRow}): the same payloads, with every scalar also in a declared column.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeclaredComboDomain(
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
    ComboLeaf n) {}
