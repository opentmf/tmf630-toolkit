package org.opentmf.query.commons.fieldselection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EmbeddedId;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FieldSelectionUtilTest {

  @Test
  void defaultDepthZeroEmitsScalarsOnly() {
    Parent parent = new Parent();
    parent.setId("p1");
    Child child = new Child();
    child.setName("child");
    parent.setChild(child);

    Map<String, Object> result = FieldSelectionUtil.fieldsToMap(parent);

    assertEquals("p1", result.get("id"));
    assertFalse(result.containsKey("child"),
        "depth=0 should not include complex fields");
  }

  @Test
  void depthOneIncludesOneNestedLevel() {
    Parent parent = new Parent();
    parent.setId("p1");
    Child child = new Child();
    child.setName("child");
    parent.setChild(child);

    Map<String, Object> result = FieldSelectionUtil.fieldsToMap(parent, 1);

    assertEquals("p1", result.get("id"));
    assertTrue(result.containsKey("child"),
        "depth=1 should include direct complex fields");
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
  @SuppressWarnings("unchecked")
  void fieldsToMapWithFieldsAndDepthOneExpandsOnlyScalars() {
    Parent parent = createDeepParent();

    Map<String, Object> result = FieldSelectionUtil.fieldsToMap(parent, "child", 1);

    assertTrue(result.containsKey("child"));
    Map<String, Object> childMap = (Map<String, Object>) result.get("child");
    assertEquals("Alice", childMap.get("name"));
    assertFalse(childMap.containsKey("address"),
        "depth=1 should not auto-expand the nested Address object");
  }

  @Test
  @SuppressWarnings("unchecked")
  void fieldsToMapWithFieldsAndDepthTwoExpandsOneNestedLevel() {
    Parent parent = createDeepParent();

    Map<String, Object> result = FieldSelectionUtil.fieldsToMap(parent, "child", 2);

    Map<String, Object> childMap = (Map<String, Object>) result.get("child");
    assertEquals("Alice", childMap.get("name"));
    assertTrue(childMap.containsKey("address"),
        "depth=2 should auto-expand Address");
    Map<String, Object> addressMap = (Map<String, Object>) childMap.get("address");
    assertEquals("Istanbul", addressMap.get("city"));
    assertFalse(addressMap.containsKey("country"),
        "depth=2 should not reach the Country level inside Address");
  }

  @Test
  @SuppressWarnings("unchecked")
  void fieldsToMapWithFieldsAndDepthThreeExpandsFullHierarchy() {
    Parent parent = createDeepParent();

    Map<String, Object> result = FieldSelectionUtil.fieldsToMap(parent, "child", 3);

    Map<String, Object> childMap = (Map<String, Object>) result.get("child");
    Map<String, Object> addressMap = (Map<String, Object>) childMap.get("address");
    assertEquals("Istanbul", addressMap.get("city"));
    assertTrue(addressMap.containsKey("country"),
        "depth=3 should reach Country");
    Map<String, Object> countryMap = (Map<String, Object>) addressMap.get("country");
    assertEquals("TR", countryMap.get("code"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void fieldsToMapListWithFieldsAndDepthWorksForMultipleObjects() {
    Parent p1 = createDeepParent();
    Parent p2 = createDeepParent();
    p2.getChild().setName("Bob");
    p2.getChild().getAddress().setCity("Ankara");

    List<Map<String, Object>> result =
        FieldSelectionUtil.fieldsToMapList(List.of(p1, p2), "child", 2);

    assertEquals(2, result.size());
    Map<String, Object> child1 = (Map<String, Object>) result.get(0).get("child");
    Map<String, Object> child2 = (Map<String, Object>) result.get(1).get("child");
    assertEquals("Alice", child1.get("name"));
    assertEquals("Bob", child2.get("name"));
    Map<String, Object> addr1 = (Map<String, Object>) child1.get("address");
    Map<String, Object> addr2 = (Map<String, Object>) child2.get("address");
    assertEquals("Istanbul", addr1.get("city"));
    assertEquals("Ankara", addr2.get("city"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void explicitDotPathIsNotAffectedByDepth() {
    Parent parent = createDeepParent();

    Map<String, Object> result =
        FieldSelectionUtil.fieldsToMap(parent, "child.address.country.code", 1);

    assertTrue(result.containsKey("child"));
    Map<String, Object> childMap = (Map<String, Object>) result.get("child");
    Map<String, Object> addressMap = (Map<String, Object>) childMap.get("address");
    Map<String, Object> countryMap = (Map<String, Object>) addressMap.get("country");
    assertEquals("TR", countryMap.get("code"));
  }

  @Test
  void defaultDepthZeroMatchesExplicitDepthZeroForFields() {
    Parent parent = createDeepParent();

    Map<String, Object> withoutDepth = FieldSelectionUtil.fieldsToMap(parent, "child");
    Map<String, Object> withDepthZero = FieldSelectionUtil.fieldsToMap(parent, "child", 0);

    assertEquals(withoutDepth.keySet(), withDepthZero.keySet(),
        "no-depth overload should now behave as depth=0");
    Object childA = withoutDepth.get("child");
    Object childB = withDepthZero.get("child");
    assertEquals(childA.getClass(), childB.getClass());
  }

  private Parent createDeepParent() {
    Country country = new Country();
    country.setCode("TR");
    Address address = new Address();
    address.setCity("Istanbul");
    address.setCountry(country);
    Child child = new Child();
    child.setName("Alice");
    child.setAddress(address);
    Parent parent = new Parent();
    parent.setId("p1");
    parent.setChild(child);
    return parent;
  }

  @Test
  @SuppressWarnings("unchecked")
  void broadSelectorIsNotOverwrittenByNarrowDotPath() {
    Parent parent = createDeepParent();

    Map<String, Object> result = FieldSelectionUtil.fieldsToMap(parent, "child,child.name", 2);

    assertTrue(result.containsKey("child"));
    Map<String, Object> childMap = (Map<String, Object>) result.get("child");
    assertTrue(childMap.containsKey("name"),
        "broad selector 'child' should include 'name'");
    assertTrue(childMap.containsKey("address"),
        "broad selector 'child' with depth=2 should also include 'address'");
  }

  @Test
  void wildcardGenericTypeDoesNotThrowClassCastException() {
    WildcardHolder holder = new WildcardHolder();
    holder.setLabel("test");
    Child c = new Child();
    c.setName("wc");
    holder.setItems(List.of(c));
    holder.setBounded(List.of(c));

    Map<String, Object> result =
        assertDoesNotThrow(() -> FieldSelectionUtil.fieldsToMap(holder));

    assertEquals("test", result.get("label"));
    assertTrue(result.containsKey("items") || result.containsKey("bounded"),
        "wildcard collections should be processed without ClassCastException");
  }

  @Test
  void typeVariableGenericDoesNotThrowClassCastException() {
    GenericHolder<Child> holder = new GenericHolder<>();
    holder.setLabel("tv");
    Child c = new Child();
    c.setName("generic");
    holder.setEntries(List.of(c));

    Map<String, Object> result =
        assertDoesNotThrow(() -> FieldSelectionUtil.fieldsToMap(holder));

    assertEquals("tv", result.get("label"));
  }

  @Test
  void nestedParameterizedTypeDoesNotThrowClassCastException() {
    NestedGenericHolder holder = new NestedGenericHolder();
    holder.setLabel("nested");
    holder.setMappedChildren(List.of(Map.of("k", new Child())));

    Map<String, Object> result =
        assertDoesNotThrow(() -> FieldSelectionUtil.fieldsToMap(holder));

    assertEquals("nested", result.get("label"));
  }

  @Test
  void javaSqlTimestampIsTreatedAsScalar() {
    TimestampHolder holder = new TimestampHolder();
    holder.setName("event");
    holder.setCreatedAt(Timestamp.from(Instant.parse("2026-01-15T10:30:00Z")));

    Map<String, Object> result = FieldSelectionUtil.fieldsToMap(holder);

    assertEquals("event", result.get("name"));
    assertInstanceOf(
        Timestamp.class,
        result.get("createdAt"),
        "java.sql.Timestamp should be treated as a scalar, not expanded into nested properties");
  }

  @Test
  void mapsRecordWithAllComponents() {
    PersonRecord person = new PersonRecord("Alice", 30, "Istanbul");

    Map<String, Object> result = FieldSelectionUtil.fieldsToMap(person);

    assertEquals("Alice", result.get("name"));
    assertEquals(30, result.get("age"));
    assertEquals("Istanbul", result.get("city"));
    assertEquals(3, result.size(), "should contain exactly the three record components");
  }

  @Test
  void mapsRecordWithExplicitFieldSelection() {
    PersonRecord person = new PersonRecord("Bob", 25, "Ankara");

    Map<String, Object> result = FieldSelectionUtil.fieldsToMap(person, "name,city");

    assertEquals("Bob", result.get("name"));
    assertEquals("Ankara", result.get("city"));
    assertFalse(result.containsKey("age"), "age was not requested");
  }

  @Test
  @SuppressWarnings("unchecked")
  void mapsNestedRecordWithDepth() {
    AddressRecord addr = new AddressRecord("Istanbul", new CountryRecord("TR"));
    PersonWithAddressRecord person = new PersonWithAddressRecord("Alice", addr);

    Map<String, Object> shallow = FieldSelectionUtil.fieldsToMap(person, "address", 1);
    Map<String, Object> addrMapShallow = (Map<String, Object>) shallow.get("address");
    assertEquals("Istanbul", addrMapShallow.get("city"));
    assertFalse(addrMapShallow.containsKey("country"),
        "depth=1 should not expand CountryRecord inside AddressRecord");

    Map<String, Object> deep = FieldSelectionUtil.fieldsToMap(person, "address", 2);
    Map<String, Object> addrMapDeep = (Map<String, Object>) deep.get("address");
    assertTrue(addrMapDeep.containsKey("country"),
        "depth=2 should expand CountryRecord");
    Map<String, Object> countryMap = (Map<String, Object>) addrMapDeep.get("country");
    assertEquals("TR", countryMap.get("code"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void mapsRecordInsideBean() {
    Parent parent = new Parent();
    parent.setId("p1");
    parent.setLocation(new CountryRecord("DE"));

    Map<String, Object> withDepth = FieldSelectionUtil.fieldsToMap(parent, 1);

    assertEquals("p1", withDepth.get("id"));
    assertTrue(withDepth.containsKey("location"));
    Map<String, Object> locationMap = (Map<String, Object>) withDepth.get("location");
    assertEquals("DE", locationMap.get("code"));
  }

  @Test
  void mapsRecordInsideBeanDefaultDepthExcludesComplex() {
    Parent parent = new Parent();
    parent.setId("p1");
    parent.setLocation(new CountryRecord("DE"));

    Map<String, Object> result = FieldSelectionUtil.fieldsToMap(parent);

    assertEquals("p1", result.get("id"));
    assertFalse(result.containsKey("location"),
        "depth=0 should not include complex record fields");
  }

  @Test
  @SuppressWarnings("unchecked")
  void mapsBeanInsideRecord() {
    Child child = new Child();
    child.setName("nested-bean");
    RecordWithBean rec = new RecordWithBean("r1", child);

    Map<String, Object> result = FieldSelectionUtil.fieldsToMap(rec, 1);

    assertEquals("r1", result.get("label"));
    assertTrue(result.containsKey("child"));
    Map<String, Object> childMap = (Map<String, Object>) result.get("child");
    assertEquals("nested-bean", childMap.get("name"));
  }

  @Test
  void mapsBeanInsideRecordDefaultDepthExcludesComplex() {
    Child child = new Child();
    child.setName("nested-bean");
    RecordWithBean rec = new RecordWithBean("r1", child);

    Map<String, Object> result = FieldSelectionUtil.fieldsToMap(rec);

    assertEquals("r1", result.get("label"));
    assertFalse(result.containsKey("child"),
        "depth=0 should not include complex bean fields inside record");
  }

  @Test
  void mapsListOfRecords() {
    PersonRecord p1 = new PersonRecord("Alice", 30, "Istanbul");
    PersonRecord p2 = new PersonRecord("Bob", 25, "Ankara");

    List<Map<String, Object>> result =
        FieldSelectionUtil.fieldsToMapList(List.of(p1, p2), "name,age");

    assertEquals(2, result.size());
    assertEquals("Alice", result.get(0).get("name"));
    assertEquals(25, result.get(1).get("age"));
    assertFalse(result.get(0).containsKey("city"));
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

  record PersonRecord(String name, int age, String city) {}

  record AddressRecord(String city, CountryRecord country) {}

  record CountryRecord(String code) {}

  record PersonWithAddressRecord(String name, AddressRecord address) {}

  record RecordWithBean(String label, Child child) {}

  static class Parent {
    private String id;
    private Child child;
    private List<Child> children;
    private Map<String, Child> childByKey;
    private CountryRecord location;

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

    public CountryRecord getLocation() {
      return location;
    }

    public void setLocation(CountryRecord location) {
      this.location = location;
    }
  }

  static class Child {
    private String name;
    private Address address;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public Address getAddress() {
      return address;
    }

    public void setAddress(Address address) {
      this.address = address;
    }
  }

  static class Address {
    private String city;
    private Country country;

    public String getCity() {
      return city;
    }

    public void setCity(String city) {
      this.city = city;
    }

    public Country getCountry() {
      return country;
    }

    public void setCountry(Country country) {
      this.country = country;
    }
  }

  static class Country {
    private String code;

    public String getCode() {
      return code;
    }

    public void setCode(String code) {
      this.code = code;
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

  static class WildcardHolder {
    private String label;
    private List<?> items;
    private List<? extends Child> bounded;

    public String getLabel() {
      return label;
    }

    public void setLabel(String label) {
      this.label = label;
    }

    public List<?> getItems() {
      return items;
    }

    public void setItems(List<?> items) {
      this.items = items;
    }

    public List<? extends Child> getBounded() {
      return bounded;
    }

    public void setBounded(List<? extends Child> bounded) {
      this.bounded = bounded;
    }
  }

  static class GenericHolder<T> {
    private String label;
    private List<T> entries;

    public String getLabel() {
      return label;
    }

    public void setLabel(String label) {
      this.label = label;
    }

    public List<T> getEntries() {
      return entries;
    }

    public void setEntries(List<T> entries) {
      this.entries = entries;
    }
  }

  static class NestedGenericHolder {
    private String label;
    private List<Map<String, Child>> mappedChildren;

    public String getLabel() {
      return label;
    }

    public void setLabel(String label) {
      this.label = label;
    }

    public List<Map<String, Child>> getMappedChildren() {
      return mappedChildren;
    }

    public void setMappedChildren(List<Map<String, Child>> mappedChildren) {
      this.mappedChildren = mappedChildren;
    }
  }

  static class TimestampHolder {
    private String name;
    private Timestamp createdAt;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public Timestamp getCreatedAt() {
      return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
      this.createdAt = createdAt;
    }
  }
}
