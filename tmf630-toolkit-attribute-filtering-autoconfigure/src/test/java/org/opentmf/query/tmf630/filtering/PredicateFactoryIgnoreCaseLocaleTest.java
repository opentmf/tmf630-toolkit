package org.opentmf.query.tmf630.filtering;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.core.types.dsl.StringPath;
import com.querydsl.jpa.JPQLSerializer;
import com.querydsl.jpa.JPQLTemplates;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opentmf.query.tmf630.filtering.it.sql.SqlSearchEntity;
import org.opentmf.query.tmf630.filtering.predicate.PredicateFactory;
import org.opentmf.query.tmf630.filtering.predicate.ResolvedField;

/**
 * The case-insensitive operators on a JPA root must not depend on the JVM's default locale.
 * Querydsl's JPQL templates ({@code {0l} = {1l}}, {@code {%%1%%}}, …) lower-case the constant in
 * Java with {@link String#toLowerCase()}, so under a Turkish default locale a value with a capital
 * {@code I} became a dotless {@code ı} while the database lowered the column to {@code i}: the
 * filter found nothing. The toolkit lower-cases the operand itself with {@link Locale#ROOT}.
 *
 * <p>The compatibility cells: under every locale whose lower-casing is not language-specific in
 * Java (all but Turkish, Azerbaijani and Lithuanian) the JPQL text and its constants are exactly
 * the ones Querydsl's own call builds from the raw value, which is what the released toolkit
 * sends; a Mongo root is not touched at all.
 */
class PredicateFactoryIgnoreCaseLocaleTest {

  private static final String VALUE = "ACTIVE ITEM";
  private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");
  private static final Set<String> LANGUAGE_SPECIFIC_LOWER_CASE = Set.of("tr", "az", "lt");

  private final PredicateFactory factory = new PredicateFactory(true, 256);
  private final PathBuilder<SqlSearchEntity> jpaRoot =
      new PathBuilder<>(SqlSearchEntity.class, "sqlSearchEntity");
  private final PathBuilder<MongoRow> mongoRoot = new PathBuilder<>(MongoRow.class, "mongoRow");

  /** The case-insensitive operators that reach Querydsl's Java-side lower-casing on JPA. */
  enum Operator {
    EQI(TmfOperator.EQI, VALUE, StringPath::equalsIgnoreCase),
    NEI(TmfOperator.NEI, VALUE, (p, v) -> p.equalsIgnoreCase(v).not()),
    LIKEI(TmfOperator.LIKEI, "ACTIVE%", StringPath::likeIgnoreCase),
    CONTAINSI(TmfOperator.CONTAINSI, "VE IT", StringPath::containsIgnoreCase),
    STARTS_WITHI(TmfOperator.STARTS_WITHI, "ACTIVE I", StringPath::startsWithIgnoreCase),
    ENDS_WITHI(TmfOperator.ENDS_WITHI, "E ITEM", StringPath::endsWithIgnoreCase);

    final TmfOperator operator;
    final String value;
    final BiFunction<StringPath, String, Predicate> released;

    Operator(
        TmfOperator operator, String value, BiFunction<StringPath, String, Predicate> released) {
      this.operator = operator;
      this.value = value;
      this.released = released;
    }
  }

  @ParameterizedTest(name = "{0}: a capital I is lowered to i under a Turkish default locale")
  @EnumSource(Operator.class)
  void jpaOperandIsLoweredWithTheRootLocaleUnderTurkish(Operator op) {
    Rendered english = inLocale(Locale.ENGLISH, () -> renderJpa(toolkit(jpaRoot, op)));
    Rendered turkish = inLocale(TURKISH, () -> renderJpa(toolkit(jpaRoot, op)));
    assertEquals(english, turkish);
  }

  @ParameterizedTest(name = "{0}: byte-identical to the released form under every other locale")
  @EnumSource(Operator.class)
  void jpaFormIsUnchangedUnderEveryLocaleWithoutLanguageSpecificLowerCase(Operator op) {
    List<String> differing = new ArrayList<>();
    for (Locale locale : Locale.getAvailableLocales()) {
      if (LANGUAGE_SPECIFIC_LOWER_CASE.contains(locale.getLanguage())) {
        continue;
      }
      Rendered now = inLocale(locale, () -> renderJpa(toolkit(jpaRoot, op)));
      Rendered released = inLocale(locale, () -> renderJpa(released(jpaRoot, op)));
      if (!now.equals(released)) {
        differing.add(locale.toLanguageTag() + ": " + now + " vs " + released);
      }
    }
    assertEquals(List.of(), differing);
  }

  @ParameterizedTest(name = "{0}: a Mongo root keeps the released form under a Turkish locale")
  @EnumSource(Operator.class)
  void mongoFormIsUntouched(Operator op) {
    Predicate now = inLocale(TURKISH, () -> toolkit(mongoRoot, op));
    Predicate released = inLocale(TURKISH, () -> released(mongoRoot, op));
    assertEquals(released, now);
  }

  @Test
  void theTurkishLocaleIsRestored() {
    Locale before = Locale.getDefault();
    inLocale(TURKISH, () -> "");
    assertEquals(before, Locale.getDefault());
  }

  private Predicate toolkit(PathBuilder<?> root, Operator op) {
    return factory.build(root, new ResolvedField("status", String.class), op.operator, op.value);
  }

  private static Predicate released(PathBuilder<?> root, Operator op) {
    return op.released.apply(root.getString("status"), op.value);
  }

  private static Rendered renderJpa(Predicate predicate) {
    JPQLSerializer serializer = new JPQLSerializer(JPQLTemplates.DEFAULT);
    serializer.handle(predicate);
    return new Rendered(serializer.toString(), List.copyOf(serializer.getConstants()));
  }

  private static <T> T inLocale(Locale locale, Supplier<T> action) {
    Locale before = Locale.getDefault();
    Locale.setDefault(locale);
    try {
      return action.get();
    } finally {
      Locale.setDefault(before);
    }
  }

  private record Rendered(String jpql, List<Object> constants) {}

  /** A root without {@code @Entity}: the toolkit treats it as a Mongo document. */
  static class MongoRow {
    String status;
  }
}
