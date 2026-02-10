package org.opentmf.query.commons.fieldselection;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
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
    return convertToMapList(List.of(obj), null, 1).get(0);
  }

  public static Map<String, Object> fieldsToMap(Object obj, int depth) {
    return convertToMapList(List.of(obj), null, depth).get(0);
  }

  public static Map<String, Object> fieldsToMap(Object obj, String fields) {
    return convertToMapList(List.of(obj), fields, 1).get(0);
  }

  public static List<Map<String, Object>> fieldsToMapList(List<?> objects) {
    return convertToMapList(objects, null, 1);
  }

  public static List<Map<String, Object>> fieldsToMapList(List<?> objects, int depth) {
    return convertToMapList(objects, null, depth);
  }

  public static List<Map<String, Object>> fieldsToMapList(List<?> objects, String fields) {
    return convertToMapList(objects, fields, 1);
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
      fieldsMap = parseFields(beanClass, fields);
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
      throw new IllegalArgumentException("Error getting properties of class " + clazz.getName(), e);
    }
    return readWriteProperties;
  }

  private static final Set<String> EXCLUDED_PACKAGES =
      new TreeSet<>(
          Set.of("java.lang", "java.time", "java.util", "java.math", "java.io", "java.net"));

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
      if (types.length == 1) {
        return (Class<?>) types[0];
      } else {
        return (Class<?>) types[1];
      }
    }
    return type;
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
      throw new IllegalArgumentException("Error getting value of field " + fieldName, e);
    }
  }

  static Map<String, FieldNode> resolveProperties(Class<?> clazz, int depth) {
    if (depth < 0) {
      return Collections.emptyMap();
    }

    Map<String, FieldNode> fieldMap = new LinkedHashMap<>();
    List<PropertyDescriptor> props = getProperties(clazz);

    for (PropertyDescriptor pd : props) {
      String fieldName = pd.getName();
      Class<?> aClass = getType(pd);
      if (FIELD_HELPER.isEmbeddedId(clazz, pd)) {
        Map<String, FieldNode> idFieldMap = resolveProperties(aClass, 0);
        idFieldMap.forEach(
            (k, v) -> {
              v.setEmbeddedIdName(fieldName);
              fieldMap.put(k, v);
            });
      } else {
        List<PropertyDescriptor> properties = getProperties(aClass);
        if (properties.isEmpty()) {
          fieldMap.put(fieldName, new FieldNode());
        } else {
          Map<String, FieldNode> map = resolveProperties(aClass, depth - 1);
          if (!map.isEmpty()) {
            fieldMap.put(fieldName, new FieldNode(map));
          }
        }
      }
    }

    return fieldMap;
  }

  static Map<String, FieldNode> parseFields(Class<?> beanClass, String fieldsParam) {
    Map<String, FieldNode> root = new LinkedHashMap<>();

    if (fieldsParam == null || fieldsParam.isEmpty()) {
      return root;
    }

    String[] fields = fieldsParam.split(",");
    SortedSet<String> set = new TreeSet<>();

    for (String field : fields) {
      if (field.trim().isEmpty()) {
        continue;
      }
      set.add(field.trim());
    }

    parseFieldsRecursive(beanClass, set, root, "", 0, null);

    return root;
  }

  private static void parseFieldsRecursive(
      Class<?> beanClass,
      SortedSet<String> fields,
      Map<String, FieldNode> map,
      String base,
      int level,
      String embeddedIdFieldName) {
    if (fields.isEmpty() || level > 10) {
      return;
    }
    List<PropertyDescriptor> properties = getProperties(beanClass);
    for (PropertyDescriptor pd : properties) {
      String name = pd.getName();
      Class<?> propertyType = getType(pd);
      if (FIELD_HELPER.isEmbeddedId(beanClass, pd)) {
        parseFieldsRecursive(propertyType, fields, map, base, level, name);
      } else {
        String path = base + name;
        List<PropertyDescriptor> nestedProperties = getProperties(propertyType);
        if (fields.contains(path)) {
          if (nestedProperties.isEmpty()) {
            map.put(name, new FieldNode(embeddedIdFieldName));
          } else {
            Map<String, FieldNode> nestedMap = new LinkedHashMap<>();
            for (PropertyDescriptor nestedProp : nestedProperties) {
              if (getProperties(getType(nestedProp)).isEmpty()) {
                nestedMap.put(nestedProp.getName(), new FieldNode());
              }
            }
            map.put(name, new FieldNode(nestedMap));
          }
          fields.remove(path);
        }
        if (!nestedProperties.isEmpty()) {
          Map<String, FieldNode> nestedMap = new LinkedHashMap<>();
          parseFieldsRecursive(propertyType, fields, nestedMap, path + ".", level + 1, null);
          if (!nestedMap.isEmpty()) {
            map.put(name, new FieldNode(nestedMap));
          }
        }
      }
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
