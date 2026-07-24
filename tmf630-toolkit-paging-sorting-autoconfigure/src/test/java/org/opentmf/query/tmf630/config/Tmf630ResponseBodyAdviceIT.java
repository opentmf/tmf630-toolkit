package org.opentmf.query.tmf630.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.annotation.Tmf630Response;
import org.opentmf.query.tmf630.paging.OffsetLimitPageRequest;
import org.opentmf.query.tmf630.util.Tmf630Util;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(classes = Tmf630ResponseBodyAdviceIT.TestApp.class)
@AutoConfigureMockMvc
class Tmf630ResponseBodyAdviceIT {

  @Autowired private MockMvc mockMvc;

  @Test
  void fullyTransparentPageReturnsOkWithHeaders() throws Exception {
    mockMvc
        .perform(get("/transparent/ok"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "2"))
        .andExpect(header().string("X-Result-Count", "2"))
        .andExpect(header().string("Content-Range", "items 1-2/2"))
        .andExpect(jsonPath("$[0].name").value("Alice"))
        .andExpect(jsonPath("$[1].name").value("Bob"));
  }

  @Test
  void fullyTransparentPageReturnsPartialContent() throws Exception {
    mockMvc
        .perform(get("/transparent/partial"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string("X-Total-Count", "5"))
        .andExpect(header().string("Content-Range", "items 1-2/5"))
        .andExpect(jsonPath("$[0].name").value("Alice"));
  }

  @Test
  void fullyTransparentPageReturns416ForOutOfRange() throws Exception {
    mockMvc
        .perform(get("/transparent/416"))
        .andExpect(status().isRequestedRangeNotSatisfiable())
        .andExpect(header().string("Content-Range", "items */3"))
        .andExpect(jsonPath("$.code").value("416"));
  }

  @Test
  void fullyTransparentPageAppliesFieldSelection() throws Exception {
    mockMvc
        .perform(get("/transparent/ok").param("fields", "name"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Alice"))
        .andExpect(jsonPath("$[0].age").doesNotExist());
  }

  @Test
  void tmfPageWithAnnotationAppliesFieldSelectionOnly() throws Exception {
    mockMvc
        .perform(get("/manual/page").param("fields", "name"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "2"))
        .andExpect(jsonPath("$[0].name").value("Alice"))
        .andExpect(jsonPath("$[0].age").doesNotExist());
  }

  @Test
  void tmfPageWithAnnotationWithoutFieldsReturnsAllFields() throws Exception {
    mockMvc
        .perform(get("/manual/page"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Alice"))
        .andExpect(jsonPath("$[0].age").value(30));
  }

  @Test
  void classLevelAnnotationApplies() throws Exception {
    mockMvc
        .perform(get("/class-level/page"))
        .andExpect(status().isPartialContent())
        .andExpect(header().string("X-Total-Count", "10"))
        .andExpect(jsonPath("$[0].name").value("Alice"));
  }

  @Test
  void classLevelFieldSelectionWorks() throws Exception {
    mockMvc
        .perform(get("/class-level/page").param("fields", "age"))
        .andExpect(status().isPartialContent())
        .andExpect(jsonPath("$[0].age").value(30))
        .andExpect(jsonPath("$[0].name").doesNotExist());
  }

  @Test
  void emptyPageReturnsOk() throws Exception {
    mockMvc
        .perform(get("/transparent/empty"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Total-Count", "0"))
        .andExpect(content().string("[]"));
  }

  @Test
  void nonAnnotatedEndpointIsNotAffected() throws Exception {
    mockMvc
        .perform(get("/plain").param("fields", "name"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Plain"))
        .andExpect(jsonPath("$.age").value(99));
  }

  @Test
  @SuppressWarnings("java:S125") // depth semantics notes reference field names Sonar mis-detects as code
  void methodLevelDepthOneExpandsFirstLevelButNotSecond() throws Exception {
    // depth=1: address is mapped explicitly → only its scalar fields (city);
    // country (a nested complex field inside address) is NOT included.
    mockMvc
        .perform(get("/depth/shallow").param("fields", "address"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].address.city").value("Istanbul"))
        .andExpect(jsonPath("$[0].address.country").doesNotExist());
  }

  @Test
  void methodLevelDepthTwoExpandsTwoLevels() throws Exception {
    // depth=2: address is expanded with resolveProperties(Address, 1),
    // which includes both city and country (country is a one-field record).
    mockMvc
        .perform(get("/depth/deep").param("fields", "address"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].address.city").value("Istanbul"))
        .andExpect(jsonPath("$[0].address.country.code").value("TR"));
  }

  @Test
  void classLevelDepthIsInheritedByMethodWithNoDepth() throws Exception {
    // class-level depth=2, method has no depth → inherits class depth=2
    mockMvc
        .perform(get("/depth/class-inherited").param("fields", "address"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].address.city").value("Istanbul"))
        .andExpect(jsonPath("$[0].address.country.code").value("TR"));
  }

  @Test
  void methodLevelDepthOverridesClassLevelDepth() throws Exception {
    // class-level depth=2, method overrides to depth=1 → country is excluded
    mockMvc
        .perform(get("/depth/class-overridden").param("fields", "address"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].address.city").value("Istanbul"))
        .andExpect(jsonPath("$[0].address.country").doesNotExist());
  }

  @SpringBootApplication
  @Import({TransparentController.class, ManualController.class,
      ClassLevelController.class, PlainController.class, DepthController.class})
  static class TestApp {}

  @RestController
  static class TransparentController {

    @GetMapping("/transparent/ok")
    @Tmf630Response
    public Page<Person> fullPage() {
      return new PageImpl<>(
          List.of(new Person("Alice", 30), new Person("Bob", 25)),
          new OffsetLimitPageRequest(0, 10, Sort.unsorted()),
          2);
    }

    @GetMapping("/transparent/partial")
    @Tmf630Response
    public Page<Person> partialPage() {
      return new PageImpl<>(
          List.of(new Person("Alice", 30), new Person("Bob", 25)),
          new OffsetLimitPageRequest(0, 2, Sort.unsorted()),
          5);
    }

    @GetMapping("/transparent/416")
    @Tmf630Response
    public Page<Person> outOfRange() {
      return new PageImpl<>(
          List.of(), new OffsetLimitPageRequest(10, 5, Sort.unsorted()), 3);
    }

    @GetMapping("/transparent/empty")
    @Tmf630Response
    public Page<Person> emptyPage() {
      return new PageImpl<>(
          List.of(), new OffsetLimitPageRequest(0, 10, Sort.unsorted()), 0);
    }
  }

  @RestController
  static class ManualController {
    @GetMapping("/manual/page")
    @Tmf630Response
    public ResponseEntity<List<Person>> manualPage() {
      Page<Person> page =
          new PageImpl<>(
              List.of(new Person("Alice", 30), new Person("Bob", 25)),
              new OffsetLimitPageRequest(0, 10, Sort.unsorted()),
              2);
      return Tmf630Util.tmfPage(page);
    }
  }

  @RestController
  @Tmf630Response
  static class ClassLevelController {
    @GetMapping("/class-level/page")
    public Page<Person> page() {
      return new PageImpl<>(
          List.of(new Person("Alice", 30)),
          new OffsetLimitPageRequest(0, 1, Sort.unsorted()),
          10);
    }
  }

  @RestController
  static class PlainController {
    @GetMapping("/plain")
    public Person plain() {
      return new Person("Plain", 99);
    }
  }

  @RestController
  @Tmf630Response(depth = 2)
  static class DepthController {

    private static Page<NestedPerson> istanbul() {
      return new PageImpl<>(
          List.of(new NestedPerson("Alice", new Address("Istanbul", new Country("TR")))),
          new OffsetLimitPageRequest(0, 10, Sort.unsorted()),
          1);
    }

    // depth=1: address mapped with resolveProperties(Address, 0) → scalars of Address only
    @GetMapping("/depth/shallow")
    @Tmf630Response(depth = 1)
    public Page<NestedPerson> shallow() { return istanbul(); }

    // depth=2: address mapped with resolveProperties(Address, 1) → city + country
    @GetMapping("/depth/deep")
    @Tmf630Response(depth = 2)
    public Page<NestedPerson> deep() { return istanbul(); }

    // no method depth → inherits class depth=2
    @GetMapping("/depth/class-inherited")
    public Page<NestedPerson> classInherited() { return istanbul(); }

    // method depth=1 overrides class depth=2
    @GetMapping("/depth/class-overridden")
    @Tmf630Response(depth = 1)
    public Page<NestedPerson> classOverridden() { return istanbul(); }
  }

  public static class Person {
    private String name;
    private int age;

    public Person(String name, int age) {
      this.name = name;
      this.age = age;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getAge() { return age; }
    public void setAge(int age) { this.age = age; }
  }

  public static class NestedPerson {
    private String name;
    private Address address;

    public NestedPerson(String name, Address address) {
      this.name = name;
      this.address = address;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Address getAddress() { return address; }
    public void setAddress(Address address) { this.address = address; }
  }

  public static class Address {
    private String city;
    private Country country;

    public Address(String city, Country country) {
      this.city = city;
      this.country = country;
    }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public Country getCountry() { return country; }
    public void setCountry(Country country) { this.country = country; }
  }

  public static class Country {
    private String code;

    public Country(String code) { this.code = code; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
  }
}
