package org.opentmf.query.tmf630.jsonb.combo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The Mongo twin of {@link ComboDomain}: the same fields and nested types on a {@code @Document}
 * root, so the QueryDSL terminal resolves the suite's URL and {@code filter=} paths on Mongo exactly
 * as the JSONB terminal resolves them on the payload. Its documents are the suite's seeded JSON,
 * inserted raw (a value of another JSON type than the field declares included).
 */
@Document(CompatMongoCombo.COLLECTION)
public record CompatMongoCombo(
    @Id String id,
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
    List<ComboOuter> arr2) {

  public static final String COLLECTION = "compat_combo";
}
