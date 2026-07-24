package org.opentmf.query.tmf630.filtering.predicate;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Ops;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.core.types.dsl.SimplePath;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.List;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.TmfOperator;
import org.opentmf.query.tmf630.filtering.config.IsnullSemantics;

@SuppressWarnings({"rawtypes", "unchecked"})
public class PredicateFactory {

  private final boolean regexEnabled;
  private final int maxRegexLength;
  private final IsnullSemantics isnullSemantics;

  public PredicateFactory(boolean regexEnabled, int maxRegexLength) {
    this(regexEnabled, maxRegexLength, IsnullSemantics.MISSING_ONLY);
  }

  public PredicateFactory(
      boolean regexEnabled, int maxRegexLength, IsnullSemantics isnullSemantics) {
    this.regexEnabled = regexEnabled;
    this.maxRegexLength = maxRegexLength;
    this.isnullSemantics = isnullSemantics;
  }

  public Predicate build(
      PathBuilder<?> root, ResolvedField field, TmfOperator operator, Object typedValue) {
    String fieldPath = field.fieldPath();
    Class<?> type = box(field.javaType());

    return switch (operator) {
      case EQ -> root.getSimple(fieldPath, (Class) type).eq(typedValue);
      case NE -> root.getSimple(fieldPath, (Class) type).ne(typedValue);
      case EQI -> equalsIgnoreCase(root, fieldPath, type, typedValue);
      case NEI -> equalsIgnoreCase(root, fieldPath, type, typedValue).not();
      case GT -> {
        requireComparable(fieldPath, type);
        yield root.getComparable(fieldPath, (Class) type).gt((Comparable) typedValue);
      }
      case GTE -> {
        requireComparable(fieldPath, type);
        yield root.getComparable(fieldPath, (Class) type).goe((Comparable) typedValue);
      }
      case LT -> {
        requireComparable(fieldPath, type);
        yield root.getComparable(fieldPath, (Class) type).lt((Comparable) typedValue);
      }
      case LTE -> {
        requireComparable(fieldPath, type);
        yield root.getComparable(fieldPath, (Class) type).loe((Comparable) typedValue);
      }
      case LIKE -> string(root, fieldPath, type).like(String.valueOf(typedValue));
      case LIKEI -> string(root, fieldPath, type).likeIgnoreCase(String.valueOf(typedValue));
      case CONTAINS -> string(root, fieldPath, type).contains(String.valueOf(typedValue));
      case CONTAINSI -> string(root, fieldPath, type).containsIgnoreCase(String.valueOf(typedValue));
      case STARTS_WITH -> string(root, fieldPath, type).startsWith(String.valueOf(typedValue));
      case STARTS_WITHI ->
          string(root, fieldPath, type).startsWithIgnoreCase(String.valueOf(typedValue));
      case ENDS_WITH -> string(root, fieldPath, type).endsWith(String.valueOf(typedValue));
      case ENDS_WITHI ->
          string(root, fieldPath, type).endsWithIgnoreCase(String.valueOf(typedValue));
      case REGEX -> regex(root, fieldPath, type, String.valueOf(typedValue));
      case REGEXI -> regexIgnoreCase(root, fieldPath, type, String.valueOf(typedValue));
      case BETWEEN, IN, NIN, IS_NULL, IS_NOT_NULL ->
          throw new TmfFilteringException(
              "Operator requires dedicated handler: " + operator.suffix());
    };
  }

  /**
   * TMF630 Part 6 {@code length()} function on collection fields. Emits the standard
   * QueryDSL {@code Ops.COL_SIZE} equality shape, which both querydsl-jpa (as
   * {@code SIZE(coll) = N}) and querydsl-mongodb (as {@code {field: {$size: N}}})
   * serialize natively. Reading-A scope: only the {@code == N} case; non-equality
   * comparators on {@code length()} would require raw {@code $expr} emission on
   * Mongo (rejected upstream in the parser).
   */
  public Predicate buildLength(PathBuilder<?> root, ResolvedField field, int size) {
    Class<?> elementType = field.javaType();
    String[] segments = field.fieldPath().split("\\.");
    PathBuilder<?> current = root;
    for (int i = 0; i < segments.length - 1; i++) {
      current = current.get(segments[i]);
    }
    return Expressions.numberOperation(
            Integer.class,
            Ops.COL_SIZE,
            current.getCollection(segments[segments.length - 1], (Class) elementType))
        .eq(size);
  }

  public Predicate buildNoValue(PathBuilder<?> root, ResolvedField field, TmfOperator operator) {
    String fieldPath = field.fieldPath();
    Class<?> type = box(field.javaType());
    return switch (operator) {
      case IS_NULL -> nullish(root, fieldPath, type, false);
      case IS_NOT_NULL -> nullish(root, fieldPath, type, true);
      default ->
          throw new TmfFilteringException(
              "Unsupported no-value operator: " + operator.suffix());
    };
  }

  /**
   * IS_NULL / IS_NOT_NULL emission driven by {@link IsnullSemantics}.
   *
   * <p>Under {@code MISSING_ONLY} (the default, preserved for back-compat) we emit
   * {@code path.isNull()} / {@code path.isNotNull()} exactly as before — on Mongo this
   * serializes to {@code {field: {$exists: false}}} / {@code {$exists: true}} and on
   * JPA to {@code IS NULL} / {@code IS NOT NULL}.
   *
   * <p>{@code NULLISH} widens IS_NULL to also match an explicit {@code null} value on
   * Mongo. The widening is intentionally scoped to Mongo {@code @Document} roots because
   * on JPA:
   * <ul>
   *   <li>SQL {@code IS NULL} already captures the only "no value" state for scalar
   *       columns — the widening would be a no-op.
   *   <li>Applying the OR arm via {@code IN (NULL)} on IS_NULL would inflate the SQL
   *       harmlessly, but the complementary {@code NOT IN (NULL)} on IS_NOT_NULL is
   *       UNKNOWN under SQL trilean logic and would poison the AND, returning zero
   *       rows for every {@code .isnotnull=} query.
   * </ul>
   * So the widening is a runtime backend-detect: NULLISH-and-Mongo widens; every other
   * combination degrades to the plain IS_NULL/IS_NOT_NULL that MISSING_ONLY emits.
   *
   * <p>On Mongo the widened forms are exact complements of each other:
   * <ul>
   *   <li>IS_NULL under NULLISH: {@code IS_NULL OR IN [null]} → serializes to
   *       {@code $or:[{$exists:false},{$in:[null]}]}, matching missing OR explicit null.
   *       Mongo's {@code $in:[null]} already covers both states, so the {@code $exists:false}
   *       branch is redundant but kept for correctness against serializers that may
   *       normalize either shape.
   *   <li>IS_NOT_NULL under NULLISH: {@code IS_NOT_NULL AND NOT_IN [null]} → serializes
   *       to {@code $exists:true,$nin:[null]}, the boolean complement.
   * </ul>
   *
   * <p>Empty-array widening ({@code {$size:0}} / {@code {$eq:[]}}) is intentionally NOT
   * added here: Spring Data's {@code QueryMapper} strips size/typed-empty-list clauses
   * from the OR during its post-serialization type-mapping pass, so any clause we emit
   * silently disappears on the plain find path. Callers that need to also match
   * {@code []} should compose the widened {@code .isnull} with an explicit
   * {@code .size().eq(0)} predicate at the repository level, until a future revision
   * addresses the QueryMapper interaction.
   */
  private Predicate nullish(
      PathBuilder<?> root, String fieldPath, Class<?> type, boolean negate) {
    SimplePath<?> path = root.getSimple(fieldPath, (Class) type);
    boolean widen = isnullSemantics == IsnullSemantics.NULLISH && isMongoRoot(root);
    if (!widen) {
      return negate ? path.isNotNull() : path.isNull();
    }
    if (!negate) {
      BooleanBuilder w = new BooleanBuilder(path.isNull());
      w.or(
          Expressions.predicate(
              Ops.IN, path, Expressions.constant(Collections.singletonList(null))));
      return w.getValue();
    }
    // Exact complement: never wrap the widened IS_NULL with Ops.NOT — SQL trilean makes
    // NOT (IS NULL OR IN (NULL)) collapse to UNKNOWN. Build the AND of per-branch
    // complements instead so each clause is independently well-formed.
    BooleanBuilder w = new BooleanBuilder(path.isNotNull());
    w.and(
        Expressions.predicate(
            Ops.NOT_IN, path, Expressions.constant(Collections.singletonList(null))));
    return w.getValue();
  }

  private static boolean isMongoRoot(PathBuilder<?> root) {
    Class<?> type = root.getType();
    if (type == null) {
      return false;
    }
    for (Annotation annotation : type.getAnnotations()) {
      if ("org.springframework.data.mongodb.core.mapping.Document"
          .equals(annotation.annotationType().getName())) {
        return true;
      }
    }
    return false;
  }

  public Predicate buildMulti(
      PathBuilder<?> root, ResolvedField field, TmfOperator operator, List<Object> typedValues) {
    String fieldPath = field.fieldPath();
    Class<?> type = box(field.javaType());
    return switch (operator) {
      case IN -> root.getSimple(fieldPath, (Class) type).in(typedValues);
      case NIN -> root.getSimple(fieldPath, (Class) type).notIn(typedValues);
      case BETWEEN -> between(root, fieldPath, type, typedValues);
      default ->
          throw new TmfFilteringException(
              "Unsupported multi-value operator: " + operator.suffix());
    };
  }

  private void requireComparable(String fieldPath, Class<?> type) {
    if (!Comparable.class.isAssignableFrom(type)) {
      throw new TmfFilteringException(
          "Operator requires comparable type for field: " + fieldPath);
    }
  }

  private Predicate regex(PathBuilder<?> root, String fieldPath, Class<?> type, String pattern) {
    validateRegex(fieldPath, type, pattern);
    return root.getString(fieldPath).matches(pattern);
  }

  private Predicate regexIgnoreCase(
      PathBuilder<?> root, String fieldPath, Class<?> type, String pattern) {
    validateRegex(fieldPath, type, pattern);
    // Use Ops.MATCHES_IC directly rather than `lower().matches(lower(pattern))`.
    // QueryDSL's Mongo serializer translates MATCHES_IC into a $regex predicate
    // with $options:"i"; the old form emitted a standalone Ops.LOWER call which
    // the Mongo serializer rejects with `UnsupportedOperationException:
    // Illegal operation lower(...)`. JPA serializers handle MATCHES_IC too
    // (Hibernate translates it to `lower(field) LIKE lower(pattern)` or its
    // regex equivalent depending on the dialect), so this is a backend-agnostic
    // fix. Other case-insensitive operators (EQI/NEI/LIKEI/CONTAINSI/
    // STARTS_WITHI/ENDS_WITHI) already use QueryDSL's built-in ignore-case
    // builders that emit *_IC ops the Mongo serializer recognises.
    return Expressions.predicate(
        Ops.MATCHES_IC, root.getString(fieldPath), Expressions.constant(pattern));
  }

  private void validateRegex(String fieldPath, Class<?> type, String pattern) {
    if (!regexEnabled) {
      throw new TmfFilteringException("Regex operator is disabled.");
    }
    if (pattern.length() > maxRegexLength) {
      throw new TmfFilteringException("Regex pattern too long for field: " + fieldPath);
    }
    if (!String.class.equals(type)) {
      throw new TmfFilteringException("Regex only supported for String fields: " + fieldPath);
    }
  }

  private Predicate equalsIgnoreCase(
      PathBuilder<?> root, String fieldPath, Class<?> type, Object typedValue) {
    return string(root, fieldPath, type).equalsIgnoreCase(String.valueOf(typedValue));
  }

  private Predicate between(
      PathBuilder<?> root, String fieldPath, Class<?> type, List<Object> typedValues) {
    requireComparable(fieldPath, type);
    if (typedValues.size() != 2) {
      throw new TmfFilteringException(
          "between operator expects exactly 2 values for field: " + fieldPath);
    }
    Comparable first = (Comparable) typedValues.get(0);
    Comparable second = (Comparable) typedValues.get(1);
    return root.getComparable(fieldPath, (Class) type).between(first, second);
  }

  private com.querydsl.core.types.dsl.StringPath string(
      PathBuilder<?> root, String fieldPath, Class<?> type) {
    if (!String.class.equals(type)) {
      throw new TmfFilteringException("String operator requires String field: " + fieldPath);
    }
    return root.getString(fieldPath);
  }

  private Class<?> box(Class<?> type) {
    if (!type.isPrimitive()) {
      return type;
    }
    if (type == int.class) {
      return Integer.class;
    }
    if (type == long.class) {
      return Long.class;
    }
    if (type == double.class) {
      return Double.class;
    }
    if (type == float.class) {
      return Float.class;
    }
    if (type == short.class) {
      return Short.class;
    }
    if (type == byte.class) {
      return Byte.class;
    }
    if (type == boolean.class) {
      return Boolean.class;
    }
    if (type == char.class) {
      return Character.class;
    }
    return type;
  }
}
