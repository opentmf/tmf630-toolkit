package org.opentmf.query.tmf630.filtering;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.querydsl.core.types.Predicate;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.exception.TmfPagingException;
import org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.querydsl.binding.QuerydslPredicate;

class FilterRootSortKeyValidatorTest {

  private final FilterRootSortKeyValidator validator =
      new FilterRootSortKeyValidator(
          new FieldPathResolver(), List.of(new QuerydslPredicateRootLocator()));

  @Test
  void acceptsDeclaredKeysIncludingNestedAndInheritedFields() throws Exception {
    MethodParameter pageable = pageableOf(Handlers.class, "rooted");

    assertDoesNotThrow(
        () -> validator.validate(pageable, List.of("name", "ref.code", "createdOn")));
  }

  @Test
  void rejectsAKeyTheRootDoesNotDeclareWithThe400Exception() throws Exception {
    MethodParameter pageable = pageableOf(Handlers.class, "rooted");
    List<String> keys = List.of("name", "nosuch");

    TmfPagingException ex =
        assertThrows(TmfPagingException.class, () -> validator.validate(pageable, keys));

    assertEquals("Unknown sort property: nosuch", ex.getMessage());
    assertTrue(ex.getCause() instanceof TmfFilteringException);
  }

  @Test
  void rejectsTheSameKeyOnEveryCallOnceTheRootIsCached() throws Exception {
    MethodParameter pageable = pageableOf(Handlers.class, "rooted");
    List<String> keys = List.of("nosuch");

    assertThrows(TmfPagingException.class, () -> validator.validate(pageable, keys));
    assertThrows(TmfPagingException.class, () -> validator.validate(pageable, keys));
  }

  @Test
  void aHandlerWithoutAFilterRootIsNotValidated() throws Exception {
    MethodParameter pageable = pageableOf(Handlers.class, "unrooted");

    assertDoesNotThrow(() -> validator.validate(pageable, List.of("nosuch")));
  }

  @Test
  void aQuerydslPredicateWithoutAnExplicitRootIsNotAFilterRoot() throws Exception {
    MethodParameter pageable = pageableOf(Handlers.class, "objectRoot");

    assertDoesNotThrow(() -> validator.validate(pageable, List.of("nosuch")));
  }

  @Test
  void aRootDeclaredOnTheApiInterfaceParameterCounts() throws Exception {
    Method implementation =
        RootedApiImpl.class.getMethod("search", Predicate.class, Pageable.class);
    MethodParameter pageable = new MethodParameter(implementation, 1);
    List<String> keys = List.of("nosuch");

    assertThrows(TmfPagingException.class, () -> validator.validate(pageable, keys));
    assertDoesNotThrow(() -> validator.validate(pageable, List.of("name")));
  }

  @Test
  void consultsEveryLocatorForTheRoot() throws Exception {
    FilterRootSortKeyValidator withCustomLocator =
        new FilterRootSortKeyValidator(
            new FieldPathResolver(),
            List.of(
                new QuerydslPredicateRootLocator(),
                parameter ->
                    Optional.ofNullable(parameter.getParameterAnnotation(CustomFilter.class))
                        .map(annotation -> Entity.class)));
    MethodParameter pageable = pageableOf(Handlers.class, "customRooted");
    List<String> keys = List.of("nosuch");

    assertThrows(TmfPagingException.class, () -> withCustomLocator.validate(pageable, keys));
  }

  @Test
  void noKeysAndMethodlessParametersAreNoOps() throws Exception {
    MethodParameter pageable = pageableOf(Handlers.class, "rooted");
    MethodParameter constructorParameter =
        new MethodParameter(ConstructorHandler.class.getDeclaredConstructor(Pageable.class), 0);

    assertDoesNotThrow(() -> validator.validate(pageable, List.of()));
    assertDoesNotThrow(() -> validator.validate(constructorParameter, List.of("nosuch")));
  }

  @Test
  void querydslRootLocatorIgnoresOtherParameters() throws Exception {
    Method rooted = Handlers.class.getDeclaredMethod("rooted", Predicate.class, Pageable.class);
    QuerydslPredicateRootLocator locator = new QuerydslPredicateRootLocator();

    assertEquals(Optional.of(Entity.class), locator.locate(new MethodParameter(rooted, 0)));
    assertEquals(Optional.empty(), locator.locate(new MethodParameter(rooted, 1)));
  }

  private static MethodParameter pageableOf(Class<?> type, String methodName) throws Exception {
    for (Method method : type.getDeclaredMethods()) {
      if (method.getName().equals(methodName)) {
        Class<?>[] types = method.getParameterTypes();
        return new MethodParameter(method, types.length - 1);
      }
    }
    throw new IllegalArgumentException(methodName);
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.PARAMETER)
  @interface CustomFilter {}

  @SuppressWarnings("unused")
  private static class Handlers {
    void rooted(@QuerydslPredicate(root = Entity.class) Predicate predicate, Pageable pageable) {
      // signature-only stub for MethodParameter reflection
    }

    void unrooted(Pageable pageable) {
      // signature-only stub for MethodParameter reflection
    }

    void objectRoot(@QuerydslPredicate Predicate predicate, Pageable pageable) {
      // signature-only stub for MethodParameter reflection
    }

    void customRooted(@CustomFilter String filter, Pageable pageable) {
      // signature-only stub for MethodParameter reflection
    }
  }

  interface RootedApi {
    void search(@QuerydslPredicate(root = Entity.class) Predicate predicate, Pageable pageable);
  }

  static class RootedApiImpl implements RootedApi {
    @Override
    public void search(Predicate predicate, Pageable pageable) {
      // signature-only stub for MethodParameter reflection
    }
  }

  @SuppressWarnings("unused")
  private static class ConstructorHandler {
    ConstructorHandler(Pageable pageable) {
      // signature-only stub for MethodParameter reflection
    }
  }

  @SuppressWarnings("unused")
  private static class BaseEntity {
    private Instant createdOn;
  }

  @SuppressWarnings("unused")
  private static class Entity extends BaseEntity {
    private String name;
    private Ref ref;
  }

  @SuppressWarnings("unused")
  private static class Ref {
    private String code;
  }
}
