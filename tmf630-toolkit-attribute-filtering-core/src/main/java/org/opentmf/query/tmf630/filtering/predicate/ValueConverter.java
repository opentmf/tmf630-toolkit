package org.opentmf.query.tmf630.filtering.predicate;

import org.opentmf.query.tmf630.filtering.TmfFilteringException;
import org.springframework.core.convert.ConversionService;

import java.util.HashMap;
import java.util.Map;

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

  public ValueConverter(ConversionService conversionService) {
    this.conversionService = conversionService;
  }

  public Object convert(String rawValue, Class<?> targetType) {
    Class<?> boxedType = targetType.isPrimitive() ? PRIMITIVE_TO_WRAPPER.get(targetType) : targetType;
    if (boxedType == null) {
      boxedType = targetType;
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
}
