package org.opentmf.query.tmf630.filtering.predicate;

import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.springframework.core.convert.ConversionService;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ValueConverter {

  private static final Map<Class<?>, Class<?>> PRIMITIVE_TO_WRAPPER = new HashMap<>();

  static {
    PRIMITIVE_TO_WRAPPER.put(boolean.class, Boolean.class);
    PRIMITIVE_TO_WRAPPER.put(byte.class, Byte.class);
    PRIMITIVE_TO_WRAPPER.put(short.class, Short.class);
    PRIMITIVE_TO_WRAPPER.put(int.class, Integer.class);
    PRIMITIVE_TO_WRAPPER.put(long.class, Long.class);
    PRIMITIVE_TO_WRAPPER.put(float.class, Float.class);
    PRIMITIVE_TO_WRAPPER.put(double.class, Double.class);
    PRIMITIVE_TO_WRAPPER.put(char.class, Character.class);
  }

  private final ConversionService conversionService;
  private final Map<Class<?>, List<Method>> enumFactoryCache = new ConcurrentHashMap<>();

  public ValueConverter(ConversionService conversionService) {
    this.conversionService = conversionService;
  }

  public Object convert(String rawValue, Class<?> targetType) {
    Class<?> boxedType = targetType.isPrimitive() ? PRIMITIVE_TO_WRAPPER.get(targetType) : targetType;
    if (boxedType == null) {
      boxedType = targetType;
    }

    if (boxedType.isEnum()) {
      return convertEnum(rawValue, boxedType);
    }

    if (!conversionService.canConvert(String.class, boxedType)) {
      throw new TmfFilteringException("Cannot convert value to type: " + boxedType.getName());
    }
    try {
      return conversionService.convert(rawValue, boxedType);
    } catch (Exception ex) {
      throw new TmfFilteringException(
          "Failed to convert value '" + rawValue + "' to " + boxedType.getSimpleName(), ex);
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private Object convertEnum(String rawValue, Class<?> enumType) {
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
      throw new TmfFilteringException(
          "Failed to convert value '" + rawValue + "' to " + enumType.getSimpleName(), ex);
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
