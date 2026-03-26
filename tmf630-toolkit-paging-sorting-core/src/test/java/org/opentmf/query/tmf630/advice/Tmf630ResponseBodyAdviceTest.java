package org.opentmf.query.tmf630.advice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.annotation.Tmf630Response;
import org.opentmf.query.tmf630.model.ErrorMessage;
import org.opentmf.query.tmf630.paging.OffsetLimitPageRequest;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class Tmf630ResponseBodyAdviceTest {

  private final Tmf630ResponseBodyAdvice advice = new Tmf630ResponseBodyAdvice(3);

  @Test
  void supportsAnnotatedMethod() throws Exception {
    MethodParameter param = returnType(AnnotatedController.class, "annotatedMethod");
    assertTrue(advice.supports(param, JacksonJsonHttpMessageConverter.class));
  }

  @Test
  void supportsClassLevelAnnotation() throws Exception {
    MethodParameter param = returnType(ClassAnnotatedController.class, "method");
    assertTrue(advice.supports(param, JacksonJsonHttpMessageConverter.class));
  }

  @Test
  void doesNotSupportUnannotatedMethod() throws Exception {
    MethodParameter param = returnType(PlainController.class, "method");
    assertFalse(advice.supports(param, JacksonJsonHttpMessageConverter.class));
  }

  @Test
  void handlesPageWithOkStatus() throws Exception {
    Page<Person> page =
        new PageImpl<>(
            List.of(new Person("Alice", 30), new Person("Bob", 25)),
            new OffsetLimitPageRequest(0, 10, Sort.unsorted()),
            2);

    MethodParameter param = returnType(AnnotatedController.class, "annotatedMethod");
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    ServerHttpResponse response = new ServletServerHttpResponse(servletResponse);

    Object result =
        advice.beforeBodyWrite(
            page, param, MediaType.APPLICATION_JSON,
            JacksonJsonHttpMessageConverter.class,
            servletRequest("http://localhost/test"),
            response);

    assertInstanceOf(List.class, result);
    assertEquals(200, servletResponse.getStatus());
    assertEquals("2", response.getHeaders().getFirst("X-Total-Count"));
    assertEquals("items 1-2/2", response.getHeaders().getFirst("Content-Range"));
  }

  @Test
  void handlesPageWithPartialContent() throws Exception {
    Page<Person> page =
        new PageImpl<>(
            List.of(new Person("Alice", 30)),
            new OffsetLimitPageRequest(0, 1, Sort.unsorted()),
            5);

    MethodParameter param = returnType(AnnotatedController.class, "annotatedMethod");
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    ServerHttpResponse response = new ServletServerHttpResponse(servletResponse);

    advice.beforeBodyWrite(
        page, param, MediaType.APPLICATION_JSON,
        JacksonJsonHttpMessageConverter.class,
        servletRequest("http://localhost/test"),
        response);

    assertEquals(206, servletResponse.getStatus());
    assertEquals("items 1-1/5", response.getHeaders().getFirst("Content-Range"));
  }

  @Test
  void handlesPageWith416() throws Exception {
    Page<Person> page =
        new PageImpl<>(
            List.of(), new OffsetLimitPageRequest(10, 5, Sort.unsorted()), 3);

    MethodParameter param = returnType(AnnotatedController.class, "annotatedMethod");
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    ServerHttpResponse response = new ServletServerHttpResponse(servletResponse);

    Object result =
        advice.beforeBodyWrite(
            page, param, MediaType.APPLICATION_JSON,
            JacksonJsonHttpMessageConverter.class,
            servletRequest("http://localhost/test"),
            response);

    assertEquals(416, servletResponse.getStatus());
    assertInstanceOf(ErrorMessage.class, result);
    assertEquals("416", ((ErrorMessage) result).code());
  }

  @Test
  void appliesFieldSelectionOnPage() throws Exception {
    Page<Person> page =
        new PageImpl<>(
            List.of(new Person("Alice", 30)),
            new OffsetLimitPageRequest(0, 10, Sort.unsorted()),
            1);

    MethodParameter param = returnType(AnnotatedController.class, "annotatedMethod");
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    ServerHttpResponse response = new ServletServerHttpResponse(servletResponse);

    Object result =
        advice.beforeBodyWrite(
            page, param, MediaType.APPLICATION_JSON,
            JacksonJsonHttpMessageConverter.class,
            servletRequest("http://localhost/test?fields=name"),
            response);

    assertInstanceOf(List.class, result);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list = (List<Map<String, Object>>) result;
    assertEquals(1, list.size());
    assertTrue(list.get(0).containsKey("name"));
    assertFalse(list.get(0).containsKey("age"));
  }

  @Test
  void appliesFieldSelectionOnListBody() throws Exception {
    List<Person> body = List.of(new Person("Alice", 30));

    MethodParameter param = returnType(AnnotatedController.class, "listMethod");
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    ServerHttpResponse response = new ServletServerHttpResponse(servletResponse);

    Object result =
        advice.beforeBodyWrite(
            body, param, MediaType.APPLICATION_JSON,
            JacksonJsonHttpMessageConverter.class,
            servletRequest("http://localhost/test?fields=name"),
            response);

    assertInstanceOf(List.class, result);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list = (List<Map<String, Object>>) result;
    assertTrue(list.get(0).containsKey("name"));
    assertFalse(list.get(0).containsKey("age"));
  }

  @Test
  void appliesFieldSelectionOnSingleObject() throws Exception {
    Person body = new Person("Alice", 30);

    MethodParameter param = returnType(AnnotatedController.class, "singleMethod");
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    ServerHttpResponse response = new ServletServerHttpResponse(servletResponse);

    Object result =
        advice.beforeBodyWrite(
            body, param, MediaType.APPLICATION_JSON,
            JacksonJsonHttpMessageConverter.class,
            servletRequest("http://localhost/test?fields=name"),
            response);

    assertInstanceOf(Map.class, result);
    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) result;
    assertTrue(map.containsKey("name"));
    assertFalse(map.containsKey("age"));
  }

  @Test
  void skipsFieldSelectionWhenFieldsParamAbsent() throws Exception {
    List<Person> body = List.of(new Person("Alice", 30));

    MethodParameter param = returnType(AnnotatedController.class, "listMethod");
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    ServerHttpResponse response = new ServletServerHttpResponse(servletResponse);

    Object result =
        advice.beforeBodyWrite(
            body, param, MediaType.APPLICATION_JSON,
            JacksonJsonHttpMessageConverter.class,
            servletRequest("http://localhost/test"),
            response);

    assertEquals(body, result);
  }

  @Test
  void skipsStatusAndHeadersForResponseEntityWrapped() throws Exception {
    Page<Person> page =
        new PageImpl<>(
            List.of(new Person("Alice", 30)),
            new OffsetLimitPageRequest(0, 1, Sort.unsorted()),
            5);

    MethodParameter param = returnType(AnnotatedController.class, "responseEntityMethod");
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    servletResponse.setStatus(200);
    ServerHttpResponse response = new ServletServerHttpResponse(servletResponse);

    Object result =
        advice.beforeBodyWrite(
            page, param, MediaType.APPLICATION_JSON,
            JacksonJsonHttpMessageConverter.class,
            servletRequest("http://localhost/test"),
            response);

    assertInstanceOf(List.class, result);
    assertEquals(200, servletResponse.getStatus());
  }

  @Test
  void handlesNullBody() throws Exception {
    MethodParameter param = returnType(AnnotatedController.class, "annotatedMethod");
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    ServerHttpResponse response = new ServletServerHttpResponse(servletResponse);

    Object result =
        advice.beforeBodyWrite(
            null, param, MediaType.APPLICATION_JSON,
            JacksonJsonHttpMessageConverter.class,
            servletRequest("http://localhost/test?fields=name"),
            response);

    assertNull(result);
  }

  @Test
  void handlesEmptyPage() throws Exception {
    Page<Person> page =
        new PageImpl<>(
            List.of(), new OffsetLimitPageRequest(0, 10, Sort.unsorted()), 0);

    MethodParameter param = returnType(AnnotatedController.class, "annotatedMethod");
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    ServerHttpResponse response = new ServletServerHttpResponse(servletResponse);

    Object result =
        advice.beforeBodyWrite(
            page, param, MediaType.APPLICATION_JSON,
            JacksonJsonHttpMessageConverter.class,
            servletRequest("http://localhost/test"),
            response);

    assertInstanceOf(List.class, result);
    assertEquals(0, ((List<?>) result).size());
    assertEquals(200, servletResponse.getStatus());
  }

  @Test
  void defaultConstructorUsesDepthOne() {
    Tmf630ResponseBodyAdvice defaultAdvice = new Tmf630ResponseBodyAdvice();
    assertEquals(1, defaultAdvice.resolveDepth(
        returnTypeUnchecked(ClassAnnotatedController.class, "method")));
  }

  @Test
  void emptyListFieldSelectionIsPassedThrough() throws Exception {
    List<Person> body = List.of();

    MethodParameter param = returnType(AnnotatedController.class, "listMethod");
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    ServerHttpResponse response = new ServletServerHttpResponse(servletResponse);

    Object result =
        advice.beforeBodyWrite(
            body, param, MediaType.APPLICATION_JSON,
            JacksonJsonHttpMessageConverter.class,
            servletRequest("http://localhost/test?fields=name"),
            response);

    assertInstanceOf(List.class, result);
    assertEquals(0, ((List<?>) result).size());
  }

  // ---- depth resolution tests ----

  @Test
  void resolveDepthFallsBackToGlobalDefaultWhenAnnotationHasNoDepth() throws Exception {
    Tmf630ResponseBodyAdvice a = new Tmf630ResponseBodyAdvice(5);
    MethodParameter param = returnType(AnnotatedController.class, "annotatedMethod");
    assertEquals(5, a.resolveDepth(param));
  }

  @Test
  void resolveDepthUsesMethodLevelDepthWhenSet() throws Exception {
    Tmf630ResponseBodyAdvice a = new Tmf630ResponseBodyAdvice(5);
    MethodParameter param = returnType(DepthController.class, "depth2Method");
    assertEquals(2, a.resolveDepth(param));
  }

  @Test
  void resolveDepthUsesClassLevelDepthWhenMethodHasNone() throws Exception {
    Tmf630ResponseBodyAdvice a = new Tmf630ResponseBodyAdvice(5);
    MethodParameter param = returnType(ClassDepthController.class, "noDepthMethod");
    assertEquals(4, a.resolveDepth(param));
  }

  @Test
  void methodLevelDepthOverridesClassLevel() throws Exception {
    Tmf630ResponseBodyAdvice a = new Tmf630ResponseBodyAdvice(5);
    MethodParameter param = returnType(ClassDepthController.class, "overrideDepthMethod");
    assertEquals(1, a.resolveDepth(param));
  }

  @Test
  void resolveDepthZeroIsValidAndNotTreatedAsSentinel() throws Exception {
    Tmf630ResponseBodyAdvice a = new Tmf630ResponseBodyAdvice(5);
    MethodParameter param = returnType(DepthController.class, "depth0Method");
    assertEquals(0, a.resolveDepth(param));
  }

  @Test
  @SuppressWarnings("unchecked")
  void methodLevelDepthIsRespectedDuringFieldSelection() throws Exception {
    // depth=1: address is mapped with resolveProperties(Address, 0) → only scalars of Address
    // (city). country (a nested complex field) is excluded at depth=1.
    Page<NestedPerson> page =
        new PageImpl<>(
            List.of(new NestedPerson("Alice", new Address("Istanbul", new Country("TR")))),
            new OffsetLimitPageRequest(0, 10, Sort.unsorted()),
            1);

    MethodParameter param = returnType(DepthController.class, "depth1PageMethod");
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    ServerHttpResponse response = new ServletServerHttpResponse(servletResponse);

    Object result =
        advice.beforeBodyWrite(
            page, param, MediaType.APPLICATION_JSON,
            JacksonJsonHttpMessageConverter.class,
            servletRequest("http://localhost/test?fields=address"),
            response);

    assertInstanceOf(List.class, result);
    List<Map<String, Object>> list = (List<Map<String, Object>>) result;
    Map<String, Object> addrMap = (Map<String, Object>) list.get(0).get("address");
    assertEquals("Istanbul", addrMap.get("city"));
    assertFalse(addrMap.containsKey("country"),
        "depth=1 should not expand the nested Country inside Address");
  }

  private static ServletServerHttpRequest servletRequest(String url) {
    URI uri = URI.create(url);
    MockHttpServletRequest req = new MockHttpServletRequest("GET", uri.getPath());
    if (uri.getQuery() != null) {
      req.setQueryString(uri.getQuery());
      for (String pair : uri.getQuery().split("&")) {
        String[] kv = pair.split("=", 2);
        req.setParameter(kv[0], kv.length > 1 ? kv[1] : "");
      }
    }
    return new ServletServerHttpRequest(req);
  }

  private static MethodParameter returnType(Class<?> clazz, String methodName) throws Exception {
    for (Method m : clazz.getDeclaredMethods()) {
      if (m.getName().equals(methodName)) {
        return new MethodParameter(m, -1);
      }
    }
    throw new NoSuchMethodException(methodName);
  }

  private static MethodParameter returnTypeUnchecked(Class<?> clazz, String methodName) {
    try {
      return returnType(clazz, methodName);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  static class AnnotatedController {
    @Tmf630Response
    public Page<Person> annotatedMethod() {
      return null;
    }

    @Tmf630Response
    public List<Person> listMethod() {
      return null;
    }

    @Tmf630Response
    public Person singleMethod() {
      return null;
    }

    @Tmf630Response
    public ResponseEntity<Page<Person>> responseEntityMethod() {
      return null;
    }
  }

  @Tmf630Response
  static class ClassAnnotatedController {
    public Page<Person> method() {
      return null;
    }
  }

  static class PlainController {
    public Page<Person> method() {
      return null;
    }
  }

  static class DepthController {
    @Tmf630Response(depth = 2)
    public Page<Person> depth2Method() { return null; }

    @Tmf630Response(depth = 0)
    public Page<Person> depth0Method() { return null; }

    @Tmf630Response(depth = 1)
    public Page<NestedPerson> depth1PageMethod() { return null; }

    @Tmf630Response(depth = 2)
    public Page<NestedPerson> depth2PageMethod() { return null; }
  }

  @Tmf630Response(depth = 4)
  static class ClassDepthController {
    public Page<Person> noDepthMethod() { return null; }

    @Tmf630Response(depth = 1)
    public Page<Person> overrideDepthMethod() { return null; }
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

  public static class Person {
    private String name;
    private int age;

    public Person(String name, int age) {
      this.name = name;
      this.age = age;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public int getAge() {
      return age;
    }

    public void setAge(int age) {
      this.age = age;
    }
  }
}
