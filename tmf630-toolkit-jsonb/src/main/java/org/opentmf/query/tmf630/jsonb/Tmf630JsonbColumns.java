package org.opentmf.query.tmf630.jsonb;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fills the {@link Tmf630JsonbColumn declared columns} of a row entity from its domain object, so a
 * service that writes the row through JPA cannot forget one (since 3.6.0, ledger #4256). Call it
 * before each save, after the payload is set:
 *
 * <pre>{@code
 * row.setPayload(mapper.writeValueAsString(message));
 * Tmf630JsonbColumns.copy(message, row, mapper);
 * repository.save(row);
 * }</pre>
 *
 * <p>A hand-written SQL update of the payload ({@code jsonb_set}) is not a JPA save: it sets the
 * column in the same statement ({@code UPDATE t SET payload = jsonb_set(…), sent_at = ? WHERE …}).
 * {@link Tmf630JsonbColumnCheck#mismatches} finds a row where the two differ.
 *
 * <p>It reads the domain object through its public accessors (a record's components, or JavaBeans
 * getters) and sets the row through its public JavaBeans setters, as the toolkit's field selection
 * reads a response.
 */
public final class Tmf630JsonbColumns {

  private static final ObjectMapper DEFAULT_MAPPER = JsonMapper.builder().build();
  private static final Map<Class<?>, JsonbEntityMetadata> METADATA = new ConcurrentHashMap<>();

  private Tmf630JsonbColumns() {}

  /** As {@link #copy(Object, Object, ObjectMapper)} with a default mapper for enums. */
  public static <R> R copy(Object domainObject, R row) {
    return copy(domainObject, row, DEFAULT_MAPPER);
  }

  /**
   * Sets every declared field of {@code row} to the value at its path in {@code domainObject} (a
   * {@code null} on the way gives {@code null}). An enum copied into a {@code String} column is
   * written as {@code mapper} writes it, which is how the payload holds it.
   *
   * @return {@code row}
   */
  public static <R> R copy(Object domainObject, R row, ObjectMapper mapper) {
    JsonbEntityMetadata metadata = METADATA.computeIfAbsent(row.getClass(), JsonbEntityMetadata::of);
    for (JsonbDeclaredColumn declared : metadata.declaredColumns()) {
      Object value = read(domainObject, declared.path());
      if (value instanceof Enum<?> constant && declared.type() == String.class) {
        value = text(constant, mapper);
      }
      write(row, declared.rowField(), value);
    }
    return row;
  }

  private static Object read(Object domainObject, String path) {
    Object current = domainObject;
    for (String segment : path.split("\\.")) {
      if (current == null) {
        return null;
      }
      current = call(accessor(current.getClass(), segment, path), current);
    }
    return current;
  }

  /** The record component's accessor, or the JavaBeans getter, of {@code name} on {@code type}. */
  private static Method accessor(Class<?> type, String name, String path) {
    if (type.isRecord()) {
      for (RecordComponent component : type.getRecordComponents()) {
        if (component.getName().equals(name)) {
          return component.getAccessor();
        }
      }
    } else {
      Optional<Method> getter = property(type, name).map(PropertyDescriptor::getReadMethod);
      if (getter.isPresent()) {
        return getter.get();
      }
    }
    throw new Tmf630JsonbConfigurationException(
        "No public accessor for '" + name + "' of path '" + path + "' on " + type.getName());
  }

  private static void write(Object row, String fieldName, Object value) {
    Method setter =
        property(row.getClass(), fieldName)
            .map(PropertyDescriptor::getWriteMethod)
            .orElseThrow(
                () ->
                    new Tmf630JsonbConfigurationException(
                        "Tmf630JsonbColumns.copy needs a public setter for the declared field '"
                            + fieldName + "' of " + row.getClass().getName()));
    call(setter, row, value);
  }

  private static Optional<PropertyDescriptor> property(Class<?> type, String name) {
    try {
      for (PropertyDescriptor property :
          Introspector.getBeanInfo(type, Object.class).getPropertyDescriptors()) {
        if (property.getName().equals(name)) {
          return Optional.of(property);
        }
      }
      return Optional.empty();
    } catch (IntrospectionException e) {
      throw new Tmf630JsonbConfigurationException("Cannot introspect " + type.getName(), e);
    }
  }

  private static Object call(Method method, Object target, Object... arguments) {
    try {
      return method.invoke(target, arguments);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new Tmf630JsonbConfigurationException("Cannot call " + method, e);
    }
  }

  private static String text(Enum<?> constant, ObjectMapper mapper) {
    JsonNode written = mapper.valueToTree(constant);
    return written.isString() ? written.asString() : constant.name();
  }
}
