package org.opentmf.query.commons.fieldselection;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

public final class FieldSelectionUtil {

  private static final FieldHelper FIELD_HELPER = FieldHelperProvider.getFieldHelper();

  private FieldSelectionUtil() {}

  public static Map<String, Object> fieldsToMap(Object obj) {
    return convertToMapList(List.of(obj), null, 0).get(0);
  }

  public static Map<String, Object> fieldsToMap(Object obj, int depth) {
    return convertToMapList(List.of(obj), null, depth).get(0);
  }

  public static Map<String, Object> fieldsToMap(Object obj, String fields) {
    return convertToMapList(List.of(obj), fields, 0).get(0);
  }

  public static Map<String, Object> fieldsToMap(Object obj, String fields, int depth) {
    return convertToMapList(List.of(obj), fields, depth).get(0);
  }

  public static List<Map<String, Object>> fieldsToMapList(List<?> objects) {
    return convertToMapList(objects, null, 0);
  }

  public static List<Map<String, Object>> fieldsToMapList(List<?> objects, int depth) {
    return convertToMapList(objects, null, depth);
  }

  public static List<Map<String, Object>> fieldsToMapList(List<?> objects, String fields) {
    return convertToMapList(objects, fields, 0);
  }

  public static List<Map<String, Object>> fieldsToMapList(List<?> objects, String fields, int depth) {
    return convertToMapList(objects, fields, depth);
  }

  private static List<Map<String, Object>> convertToMapList(
      List<?> objects, String fields, int depth) {
    if (objects == null || objects.isEmpty()) {
      return Collections.emptyList();
    }
    Class<?> beanClass = objects.get(0).getClass();
    Map<String, FieldNode> fieldsMap;
    if (fields == null || fields.isEmpty()) {
      fieldsMap = resolveProperties(beanClass, depth);
    } else {
      fieldsMap = parseFields(beanClass, fields, depth);
    }
    List<Map<String, Object>> mappedElements = new ArrayList<>();
    for (Object element : objects) {
      Map<String, Object> objectMap = new LinkedHashMap<>();
      assignValues(fieldsMap, objectMap, element);
      mappedElements.add(objectMap);
    }
    return mappedElements;
  }

  static List<PropertyDescriptor> getProperties(Class<?> clazz) {
    if (shouldExclude(clazz)) {
      return Collections.emptyList();
    }
    if (clazz.isRecord()) {
      return getRecordProperties(clazz);
    }
    List<PropertyDescriptor> readWriteProperties = new ArrayList<>();
    try {
      BeanInfo beanInfo = Introspector.getBeanInfo(clazz);
      PropertyDescriptor[] descriptors = beanInfo.getPropertyDescriptors();
      for (PropertyDescriptor pd : descriptors) {
        if (pd.getReadMethod() != null && pd.getWriteMethod() != null) {
          readWriteProperties.add(pd);
        }
      }
    } catch (Exception e) {
      throw new TmfFieldSelectionInternalException("Error getting properties of class " + clazz.getName(), e);
    }
    return readWriteProperties;
  }

  private static List<PropertyDescriptor> getRecordProperties(Class<?> recordClass) {
    List<PropertyDescriptor> result = new ArrayList<>();
    for (RecordComponent rc : recordClass.getRecordComponents()) {
      try {
        PropertyDescriptor pd = new PropertyDescriptor(rc.getName(), rc.getAccessor(), null);
        result.add(pd);
      } catch (IntrospectionException e) {
        throw new TmfFieldSelectionInternalException(
            "Error creating descriptor for record component " + rc.getName(), e);
      }
    }
    return result;
  }

  private static final Set<String> EXCLUDED_PACKAGES =
      new TreeSet<>(
          Set.of("java.lang", "java.time", "java.util", "java.math", "java.io", "java.net",
              "java.sql"));

  private static boolean shouldExclude(Class<?> clazz) {
    Package p = clazz.getPackage();
    String packageName = p == null ? "" : p.getName();
    return clazz.isPrimitive() || EXCLUDED_PACKAGES.contains(packageName);
  }

  private static Class<?> getType(PropertyDescriptor pd) {
    Class<?> type = pd.getPropertyType();
    if (type.isArray()) {
      return type.getComponentType();
    } else if (pd.getReadMethod().getGenericReturnType() instanceof ParameterizedType pType) {
      Type[] types = pType.getActualTypeArguments();
      Type target = types.length == 1 ? types[0] : types[1];
      Class<?> resolved = resolveClass(target);
      return resolved != null ? resolved : type;
    }
    return type;
  }

  private static Class<?> resolveClass(Type type) {
    if (type instanceof Class<?> clazz) {
      return clazz;
    }
    if (type instanceof WildcardType wt) {
      Type[] upper = wt.getUpperBounds();
      if (upper.length > 0 && upper[0] instanceof Class<?> clazz) {
        return clazz;
      }
      return Object.class;
    }
    if (type instanceof TypeVariable<?> tv) {
      Type[] bounds = tv.getBounds();
      if (bounds.length > 0 && bounds[0] instanceof Class<?> clazz) {
        return clazz;
      }
      return Object.class;
    }
    if (type instanceof ParameterizedType pt) {
      Type rawType = pt.getRawType();
      if (rawType instanceof Class<?> clazz) {
        return clazz;
      }
    }
    return null;
  }

  private static void assignValues(
      Map<String, FieldNode> fieldsMap, Map<String, Object> objectMap, Object element) {
    for (Map.Entry<String, FieldNode> entry : fieldsMap.entrySet()) {
      String fieldName = entry.getKey();
      Map<String, FieldNode> fieldDefinition = entry.getValue().getFields();

      Object value;
      if (entry.getValue().getEmbeddedIdName() != null) {
        Object idValue = getValue(element.getClass(), entry.getValue().getEmbeddedIdName(), element);
        value = idValue == null ? null : getValue(idValue.getClass(), fieldName, idValue);
      } else {
        value = getValue(element.getClass(), fieldName, element);
      }

      if (fieldDefinition != null && value != null) {
        assignNestedValues(objectMap, value, fieldDefinition, fieldName);
      } else {
        objectMap.put(fieldName, value);
      }
    }
  }

  private static void assignNestedValues(
      Map<String, Object> objectMap,
      Object value,
      Map<String, FieldNode> fieldDefinition,
      String fieldName) {
    if (value instanceof Iterable<?> iterable) {
      List<Object> nestedList = new ArrayList<>();
      for (Object item : iterable) {
        Map<String, Object> nestedMap = new LinkedHashMap<>();
        assignValues(fieldDefinition, nestedMap, item);
        nestedList.add(nestedMap);
      }
      objectMap.put(fieldName, nestedList);
    } else if (value instanceof Map<?, ?> map) {
      Map<String, Object> keyMap = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : map.entrySet()) {
        Map<String, Object> nestedMap = new LinkedHashMap<>();
        assignValues(fieldDefinition, nestedMap, e.getValue());
        keyMap.put(e.getKey().toString(), nestedMap);
      }
      objectMap.put(fieldName, keyMap);
    } else {
      Map<String, Object> nestedMap = new LinkedHashMap<>();
      assignValues(fieldDefinition, nestedMap, value);
      objectMap.put(fieldName, nestedMap);
    }
  }

  private static Object getValue(Class<?> elementClass, String fieldName, Object element) {
    try {
      if (elementClass.isRecord()) {
        for (RecordComponent rc : elementClass.getRecordComponents()) {
          if (rc.getName().equals(fieldName)) {
            return rc.getAccessor().invoke(element);
          }
        }
        return null;
      }
      Object value = null;
      BeanInfo beanInfo = Introspector.getBeanInfo(elementClass);
      for (PropertyDescriptor pd : beanInfo.getPropertyDescriptors()) {
        if (pd.getName().equals(fieldName) && pd.getReadMethod() != null) {
          value = pd.getReadMethod().invoke(element);
          break;
        }
      }
      return value;
    } catch (IntrospectionException | InvocationTargetException | IllegalAccessException e) {
      throw new TmfFieldSelectionInternalException("Error getting value of field " + fieldName, e);
    }
  }

  static Map<String, FieldNode> resolveProperties(Class<?> clazz, int depth) {
    return resolveProperties(clazz, depth, new HashSet<>());
  }

  /**
   * Recursion-safe overload. {@code visited} tracks types currently on the recursion
   * stack; if we would re-enter a type we're already resolving, we return a scalar
   * placeholder instead of recursing again. This bounds the walk on cyclic type graphs
   * (e.g. {@code Person.friend: Person}) that would otherwise recurse down to the
   * {@code depth} limit — same terminal behaviour, but no wasted work AND no
   * {@link StackOverflowError} risk if {@code depth} is set high.
   */
  private static Map<String, FieldNode> resolveProperties(
      Class<?> clazz, int depth, Set<Class<?>> visited) {
    if (depth < 0) {
      return Collections.emptyMap();
    }
    if (!visited.add(clazz)) {
      // Cycle: this type is already being resolved on the current stack. Stop
      // expanding — the caller will place a scalar FieldNode instead.
      return Collections.emptyMap();
    }
    try {
      Map<String, FieldNode> fieldMap = new LinkedHashMap<>();
      List<PropertyDescriptor> props = getProperties(clazz);

      for (PropertyDescriptor pd : props) {
        String fieldName = pd.getName();
        Class<?> aClass = getType(pd);
        if (FIELD_HELPER.isEmbeddedId(clazz, pd)) {
          Map<String, FieldNode> idFieldMap = resolveProperties(aClass, 0, visited);
          idFieldMap.forEach(
              (k, v) -> {
                v.setEmbeddedIdName(fieldName);
                fieldMap.put(k, v);
              });
        } else {
          List<PropertyDescriptor> properties = getProperties(aClass);
          if (properties.isEmpty()) {
            fieldMap.put(fieldName, new FieldNode());
          } else if (visited.contains(aClass)) {
            // Cycle: `aClass` is already being resolved on the current stack. Include
            // the field as a scalar reference instead of expanding it again — the
            // response then carries the raw value (Jackson serialises whatever is
            // there) rather than a fully-walked cycle. Prevents runaway expansion on
            // types like `Person.friend: Person`.
            fieldMap.put(fieldName, new FieldNode());
          } else {
            Map<String, FieldNode> map = resolveProperties(aClass, depth - 1, visited);
            if (!map.isEmpty()) {
              fieldMap.put(fieldName, new FieldNode(map));
            }
          }
        }
      }

      return fieldMap;
    } finally {
      visited.remove(clazz);
    }
  }

  static Map<String, FieldNode> parseFields(Class<?> beanClass, String fieldsParam, int depth) {
    Map<String, FieldNode> root = new LinkedHashMap<>();

    if (fieldsParam == null || fieldsParam.isEmpty()) {
      return root;
    }

    String[] fields = fieldsParam.split(",");
    SortedSet<String> set = new TreeSet<>();

    for (String field : fields) {
      // TMF630 Part 1 §4.3: "fields=none" selects no resource properties; the
      // mandatory identity fields injected below are then the entire response.
      if (field.trim().isEmpty() || "none".equals(field.trim())) {
        continue;
      }
      set.add(field.trim());
    }

    parseFieldsRecursive(beanClass, set, root, "", 0, null, depth);
    includeMandatoryIdentityFields(beanClass, root);

    return root;
  }

  /**
   * TMF630 Part 1 §4.3: {@code id} and {@code href} are always present in a partial
   * representation, whether requested or not (and are the only content of
   * {@code fields=none}). Injected only when the type exposes them as scalar properties;
   * types without them are unaffected.
   */
  private static void includeMandatoryIdentityFields(
      Class<?> beanClass, Map<String, FieldNode> map) {
    for (PropertyDescriptor pd : getProperties(beanClass)) {
      String name = pd.getName();
      if (("id".equals(name) || "href".equals(name))
          && !map.containsKey(name)
          && getProperties(getType(pd)).isEmpty()) {
        map.put(name, new FieldNode());
      }
    }
  }

  private static void parseFieldsRecursive(
      Class<?> beanClass,
      SortedSet<String> fields,
      Map<String, FieldNode> map,
      String base,
      int level,
      String embeddedIdFieldName,
      int depth) {
    if (fields.isEmpty() || level > 10) {
      return;
    }
    for (PropertyDescriptor pd : getProperties(beanClass)) {
      if (FIELD_HELPER.isEmbeddedId(beanClass, pd)) {
        parseFieldsRecursive(getType(pd), fields, map, base, level, pd.getName(), depth);
      } else {
        parseNonEmbeddedProperty(pd, fields, map, base, level, embeddedIdFieldName, depth);
      }
    }
  }

  private static void parseNonEmbeddedProperty(
      PropertyDescriptor pd,
      SortedSet<String> fields,
      Map<String, FieldNode> map,
      String base,
      int level,
      String embeddedIdFieldName,
      int depth) {
    String name = pd.getName();
    Class<?> propertyType = getType(pd);
    String path = base + name;
    List<PropertyDescriptor> nestedProperties = getProperties(propertyType);
    if (fields.contains(path)) {
      map.put(name, nodeForRequestedField(propertyType, nestedProperties, embeddedIdFieldName, depth));
      fields.remove(path);
    } else if (!nestedProperties.isEmpty()) {
      recurseIntoNested(propertyType, fields, map, name, path, level, depth);
    }
  }

  private static FieldNode nodeForRequestedField(
      Class<?> propertyType,
      List<PropertyDescriptor> nestedProperties,
      String embeddedIdFieldName,
      int depth) {
    if (nestedProperties.isEmpty()) {
      return new FieldNode(embeddedIdFieldName);
    }
    Map<String, FieldNode> nestedMap = resolveProperties(propertyType, depth - 1);
    return nestedMap.isEmpty() ? new FieldNode() : new FieldNode(nestedMap);
  }

  private static void recurseIntoNested(
      Class<?> propertyType,
      SortedSet<String> fields,
      Map<String, FieldNode> map,
      String name,
      String path,
      int level,
      int depth) {
    Map<String, FieldNode> nestedMap = new LinkedHashMap<>();
    parseFieldsRecursive(propertyType, fields, nestedMap, path + ".", level + 1, null, depth);
    if (!nestedMap.isEmpty()) {
      map.put(name, new FieldNode(nestedMap));
    }
  }

  static class FieldNode {
    private Map<String, FieldNode> fields;
    private String embeddedIdName;

    FieldNode() {}

    FieldNode(Map<String, FieldNode> fields) {
      this.fields = fields;
    }

    FieldNode(String embeddedIdName) {
      this.embeddedIdName = embeddedIdName;
    }

    public Map<String, FieldNode> getFields() {
      return fields;
    }

    public String getEmbeddedIdName() {
      return embeddedIdName;
    }

    public void setEmbeddedIdName(String embeddedIdName) {
      this.embeddedIdName = embeddedIdName;
    }
  }
}
