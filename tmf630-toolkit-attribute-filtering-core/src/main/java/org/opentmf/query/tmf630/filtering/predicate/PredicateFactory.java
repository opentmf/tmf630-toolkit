package org.opentmf.query.tmf630.filtering.predicate;

import com.querydsl.core.types.Ops;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.PathBuilder;
import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.opentmf.query.tmf630.filtering.TmfOperator;

import java.util.List;

@SuppressWarnings({"rawtypes", "unchecked"})
public class PredicateFactory {

  private final boolean regexEnabled;
  private final int maxRegexLength;

  public PredicateFactory(boolean regexEnabled, int maxRegexLength) {
    this.regexEnabled = regexEnabled;
    this.maxRegexLength = maxRegexLength;
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

  public Predicate buildNoValue(PathBuilder<?> root, ResolvedField field, TmfOperator operator) {
    String fieldPath = field.fieldPath();
    Class<?> type = box(field.javaType());
    return switch (operator) {
      case IS_NULL -> root.getSimple(fieldPath, (Class) type).isNull();
      case IS_NOT_NULL -> root.getSimple(fieldPath, (Class) type).isNotNull();
      default ->
          throw new TmfFilteringException(
              "Unsupported no-value operator: " + operator.suffix());
    };
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
