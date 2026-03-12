package org.opentmf.query.tmf630.filtering.predicate;

import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.springframework.core.convert.ConversionService;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ValueConverter {

  private static final Map<Class<?>, Class<?>> PRIMITIVE_TO_WRAPPER = new HashMap<>();
  static final Map<Class<?>, FormatHint> TYPE_FORMAT_HINTS = new HashMap<>();

  static {
    PRIMITIVE_TO_WRAPPER.put(boolean.class, Boolean.class);
    PRIMITIVE_TO_WRAPPER.put(byte.class, Byte.class);
    PRIMITIVE_TO_WRAPPER.put(short.class, Short.class);
    PRIMITIVE_TO_WRAPPER.put(int.class, Integer.class);
    PRIMITIVE_TO_WRAPPER.put(long.class, Long.class);
    PRIMITIVE_TO_WRAPPER.put(float.class, Float.class);
    PRIMITIVE_TO_WRAPPER.put(double.class, Double.class);
    PRIMITIVE_TO_WRAPPER.put(char.class, Character.class);

    TYPE_FORMAT_HINTS.put(LocalDate.class,
        new FormatHint("yyyy-MM-dd", "1990-06-15"));
    TYPE_FORMAT_HINTS.put(LocalTime.class,
        new FormatHint("HH:mm:ss[.SSS]", "14:30:00"));
    TYPE_FORMAT_HINTS.put(LocalDateTime.class,
        new FormatHint("yyyy-MM-dd'T'HH:mm:ss[.SSS]", "1990-06-15T14:30:00"));
    TYPE_FORMAT_HINTS.put(OffsetDateTime.class,
        new FormatHint("yyyy-MM-dd'T'HH:mm:ssXXX", "1990-06-15T14:30:00+03:00"));
    TYPE_FORMAT_HINTS.put(ZonedDateTime.class,
        new FormatHint("yyyy-MM-dd'T'HH:mm:ssXXX'['VV']'", "1990-06-15T14:30:00+03:00[Europe/Istanbul]"));
    TYPE_FORMAT_HINTS.put(Instant.class,
        new FormatHint("yyyy-MM-dd'T'HH:mm:ssX (ISO-8601 UTC)", "1990-06-15T11:30:00Z"));
  }

  record FormatHint(String format, String example) {}

  private final ConversionService conversionService;
  private final Map<Class<?>, List<Method>> enumFactoryCache = new ConcurrentHashMap<>();

  public ValueConverter(ConversionService conversionService) {
    this.conversionService = conversionService;
  }

  public Object convert(String rawValue, Class<?> targetType) {
    return convert(rawValue, targetType, null);
  }

  public Object convert(String rawValue, Class<?> targetType, String fieldName) {
    Class<?> boxedType = targetType.isPrimitive() ? PRIMITIVE_TO_WRAPPER.get(targetType) : targetType;
    if (boxedType == null) {
      boxedType = targetType;
    }

    if (boxedType.isEnum()) {
      return convertEnum(rawValue, boxedType, fieldName);
    }

    if (!conversionService.canConvert(String.class, boxedType)) {
      throw new TmfFilteringException(
          buildCannotConvertMessage(fieldName, boxedType));
    }
    try {
      return conversionService.convert(rawValue, boxedType);
    } catch (Exception ex) {
      throw new TmfFilteringException(
          buildConversionFailedMessage(rawValue, fieldName, boxedType), ex);
    }
  }

  private static String buildCannotConvertMessage(String fieldName, Class<?> type) {
    String base = fieldName != null
        ? "Field \"" + fieldName + "\" has type " + type.getSimpleName() + " which cannot be converted from a String."
        : "Cannot convert value to type: " + type.getName();
    FormatHint hint = TYPE_FORMAT_HINTS.get(type);
    return hint != null ? base + " Expected format: " + hint.format() + ", example: " + hint.example() : base;
  }

  private static String buildConversionFailedMessage(String rawValue, String fieldName, Class<?> type) {
    String base = fieldName != null
        ? "Field \"" + fieldName + "\" (" + type.getSimpleName() + ") could not be parsed from value \"" + rawValue + "\"."
        : "Failed to convert value '" + rawValue + "' to " + type.getSimpleName() + ".";
    FormatHint hint = TYPE_FORMAT_HINTS.get(type);
    return hint != null ? base + " Expected format: " + hint.format() + ", example: " + hint.example() : base;
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private Object convertEnum(String rawValue, Class<?> enumType, String fieldName) {
    List<Method> factories = enumFactoryCache.computeIfAbsent(enumType, this::discoverFactoryMethods);

    for (Method factory : factories) {
      try {
        Object result = factory.invoke(null, rawValue);
        if (result != null) {
          return result;
        }
      } catch (Exception ignored) {
      }
    }

    try {
      return Enum.valueOf((Class<Enum>) enumType, rawValue);
    } catch (IllegalArgumentException ex) {
      String msg = fieldName != null
          ? "Field \"" + fieldName + "\" (" + enumType.getSimpleName() + ") could not be parsed from value \"" + rawValue + "\"."
          : "Failed to convert value '" + rawValue + "' to " + enumType.getSimpleName() + ".";
      throw new TmfFilteringException(msg, ex);
    }
  }

  private List<Method> discoverFactoryMethods(Class<?> enumType) {
    List<Method> result = new ArrayList<>();
    for (Method method : enumType.getDeclaredMethods()) {
      if (Modifier.isPublic(method.getModifiers())
          && Modifier.isStatic(method.getModifiers())
          && method.getParameterCount() == 1
          && method.getParameterTypes()[0] == String.class
          && method.getReturnType() == enumType
          && !"valueOf".equals(method.getName())) {
        result.add(method);
      }
    }
    return result;
  }
}
