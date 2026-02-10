package org.opentmf.query.commons.fieldselection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EmbeddedId;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldSelectionUtilTest {

  @Test
  void mapsObjectWithImplicitDepth() {
    Parent parent = new Parent();
    parent.setId("p1");
    Child child = new Child();
    child.setName("child");
    parent.setChild(child);

    Map<String, Object> result = FieldSelectionUtil.fieldsToMap(parent);

    assertEquals("p1", result.get("id"));
    assertTrue(result.containsKey("child"));
  }

  @Test
  void mapsObjectWithExplicitFields() {
    Parent parent = new Parent();
    parent.setId("x");
    Child child = new Child();
    child.setName("nested");
    parent.setChild(child);

    Map<String, Object> result = FieldSelectionUtil.fieldsToMap(parent, "id,child.name");
    Map<?, ?> nested = (Map<?, ?>) result.get("child");

    assertEquals("x", result.get("id"));
    assertEquals("nested", nested.get("name"));
  }

  @Test
  void supportsListMappingAndNullSafePaths() {
    Parent parent = new Parent();
    parent.setId("l1");
    parent.setChildren(List.of());
    List<Map<String, Object>> mapped = FieldSelectionUtil.fieldsToMapList(List.of(parent), 2);
    assertEquals(1, mapped.size());
    assertEquals("l1", mapped.get(0).get("id"));
  }

  @Test
  void helperImplementationsBehaveAsExpected() throws Exception {
    FieldHelper defaultHelper = new DefaultFieldHelper();
    PropertyDescriptor pd = new PropertyDescriptor("id", Parent.class);
    assertFalse(defaultHelper.isEmbeddedId(Parent.class, pd));

    FieldHelper providerHelper = FieldHelperProvider.getFieldHelper();
    assertNotNull(providerHelper);

    PropertyDescriptor embeddedPd = new PropertyDescriptor("embeddedKey", WithEmbeddedId.class);
    FieldHelper persistent = new PersistentFieldHelper();
    assertTrue(persistent.isEmbeddedId(WithEmbeddedId.class, embeddedPd));

    PropertyDescriptor syntheticPd = new PropertyDescriptor("virtual", VirtualBean.class);
    assertFalse(persistent.isEmbeddedId(VirtualBean.class, syntheticPd));
  }

  @Test
  void handlesMapAndIterableNestedValues() {
    Parent parent = new Parent();
    parent.setId("m1");
    Child c1 = new Child();
    c1.setName("c1");
    Child c2 = new Child();
    c2.setName("c2");
    parent.setChildren(List.of(c1, c2));
    parent.setChildByKey(Map.of("k1", c1));

    Map<String, Object> result = FieldSelectionUtil.fieldsToMap(parent, "children.name,childByKey.name");

    assertTrue(result.containsKey("children"));
    assertTrue(result.containsKey("childByKey"));
  }

  @Test
  void handlesNegativeDepthAndMalformedFieldSelection() throws Exception {
    Parent parent = new Parent();
    parent.setId("d1");
    assertTrue(FieldSelectionUtil.resolveProperties(Parent.class, -1).isEmpty());
    Map<String, Object> result = FieldSelectionUtil.fieldsToMap(parent, "id,,");
    assertEquals("d1", result.get("id"));

    Constructor<FieldHelperProvider> ctor = FieldHelperProvider.class.getDeclaredConstructor();
    ctor.setAccessible(true);
    ctor.newInstance();
  }

  static class Parent {
    private String id;
    private Child child;
    private List<Child> children;
    private Map<String, Child> childByKey;

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public Child getChild() {
      return child;
    }

    public void setChild(Child child) {
      this.child = child;
    }

    public List<Child> getChildren() {
      return children;
    }

    public void setChildren(List<Child> children) {
      this.children = children;
    }

    public Map<String, Child> getChildByKey() {
      return childByKey;
    }

    public void setChildByKey(Map<String, Child> childByKey) {
      this.childByKey = childByKey;
    }
  }

  static class Child {
    private String name;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }

  static class EmbeddedKey {
    private String code;

    public String getCode() {
      return code;
    }

    public void setCode(String code) {
      this.code = code;
    }
  }

  static class WithEmbeddedId {
    @EmbeddedId private EmbeddedKey embeddedKey;

    public EmbeddedKey getEmbeddedKey() {
      return embeddedKey;
    }

    public void setEmbeddedKey(EmbeddedKey embeddedKey) {
      this.embeddedKey = embeddedKey;
    }
  }

  static class VirtualBean {
    public String getVirtual() {
      return "x";
    }

    public void setVirtual(String ignored) {}
  }
}
