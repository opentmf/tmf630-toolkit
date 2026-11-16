package org.opentmf.query.tmf630.filtering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Operation;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.mongodb.MongodbOps;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.filtering.config.AllowlistMode;
import org.opentmf.query.tmf630.filtering.config.CombineMode;
import org.opentmf.query.tmf630.filtering.config.IsnullSemantics;
import org.opentmf.query.tmf630.filtering.config.PredicateLimits;
import org.opentmf.query.tmf630.filtering.config.Tmf630FilterSettings;
import org.opentmf.query.tmf630.filtering.config.UnknownParamBehavior;
import org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver;
import org.opentmf.query.tmf630.filtering.predicate.PredicateFactory;
import org.opentmf.query.tmf630.filtering.predicate.ValueConverter;
import org.springframework.format.support.DefaultFormattingConversionService;

/**
 * {@link JsonPathFilterPredicateBuilder#elementMatch}: how an accepted array match is represented
 * on a root that is not a JPA entity. The default is Mongo's {@code ELEM_MATCH}, and without
 * querydsl-mongodb on the classpath an array match is the named 400, as before the hook existed. A
 * terminal that only validates with the builder (the JSONB terminal) overrides it; the inner
 * comparisons are built, and so checked, before the hook is called, so an override never changes
 * which filters are accepted.
 */
class JsonPathFilterPredicateBuilderElementMatchTest {

  private static final String ARRAY_MATCH = "$[?(@.items[?(@.b == 'x' && @.i > 1)])]";

  /** A document root with an array of elements. */
  public record Doc(String s, List<Item> items) {}

  /** An array element. */
  public record Item(String b, Integer i) {}

  @Test
  void theDefaultIsMongosElemMatch() {
    Predicate predicate = build(newBuilder(), ARRAY_MATCH);

    Operation<?> operation = assertInstanceOf(Operation.class, predicate);
    assertEquals(MongodbOps.ELEM_MATCH, operation.getOperator());
  }

  @Test
  void aTerminalRepresentsAnAcceptedArrayMatchItsOwnWay() {
    List<Predicate> inner = new ArrayList<>();
    JsonPathFilterPredicateBuilder representing =
        new JsonPathFilterPredicateBuilder(
            new FieldPathResolver(), converter(), new PredicateFactory(true, 64)) {
          @Override
          protected Predicate elementMatch(Expression<?> collection, Predicate checked) {
            inner.add(checked);
            return checked;
          }
        };

    Predicate predicate = build(representing, ARRAY_MATCH);

    assertEquals(1, inner.size());
    assertSame(inner.get(0), predicate);
    String rendered = predicate.toString();
    assertTrue(rendered.contains("b") && rendered.contains("i"), rendered);
  }

  @Test
  void aRefusedInnerComparisonIsRefusedBeforeTheHook() {
    List<Predicate> inner = new ArrayList<>();
    JsonPathFilterPredicateBuilder representing =
        new JsonPathFilterPredicateBuilder(
            new FieldPathResolver(), converter(), new PredicateFactory(true, 64)) {
          @Override
          protected Predicate elementMatch(Expression<?> collection, Predicate checked) {
            inner.add(checked);
            return checked;
          }
        };

    TmfFilteringException refused =
        assertThrows(
            TmfFilteringException.class,
            () -> build(representing, "$[?(@.items[?(@.i =~ /5/)])]"));
    assertEquals(
        "Regex only supported for String or polymorphic (Object/Serializable) fields: i",
        refused.getMessage());
    assertTrue(inner.isEmpty());
  }

  @Test
  void withoutQuerydslMongodbAnArrayMatchIsTheNamed400() throws Exception {
    ClassLoader isolated = new WithoutQuerydslMongodb(getClass().getClassLoader());
    Class<?> probe = isolated.loadClass(NoMongoProbe.class.getName());

    Object answer = probe.getMethod("answer", String.class).invoke(null, ARRAY_MATCH);

    assertEquals("Array correlation requires querydsl-mongodb on the classpath.", answer);
  }

  /** Builds the array match in whatever class loader defined this class. */
  public static final class NoMongoProbe {

    private NoMongoProbe() {}

    /** The builder's answer: "accepted", or the refusal's message. */
    public static String answer(String expression) {
      try {
        build(newBuilder(), expression);
        return "accepted";
      } catch (TmfFilteringException refused) {
        return refused.getMessage();
      }
    }
  }

  /**
   * Defines this module's classes itself (child-first) and hides querydsl-mongodb, so the builder's
   * {@code Class.forName} of {@code MongodbOps} fails as it does where the jar is absent.
   */
  private static final class WithoutQuerydslMongodb extends ClassLoader {

    private static final String OWN = "org.opentmf.query.tmf630.filtering.";

    WithoutQuerydslMongodb(ClassLoader parent) {
      super(parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (name.startsWith("com.querydsl.mongodb.")) {
        throw new ClassNotFoundException(name);
      }
      if (!name.startsWith(OWN)) {
        return super.loadClass(name, resolve);
      }
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
          loaded = defineOwn(name);
        }
        if (resolve) {
          resolveClass(loaded);
        }
        return loaded;
      }
    }

    private Class<?> defineOwn(String name) throws ClassNotFoundException {
      try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
        if (in == null) {
          throw new ClassNotFoundException(name);
        }
        byte[] bytes = in.readAllBytes();
        return defineClass(name, bytes, 0, bytes.length);
      } catch (IOException e) {
        throw new ClassNotFoundException(name, e);
      }
    }
  }

  private static JsonPathFilterPredicateBuilder newBuilder() {
    return new JsonPathFilterPredicateBuilder(
        new FieldPathResolver(), converter(), new PredicateFactory(true, 64));
  }

  private static ValueConverter converter() {
    return new ValueConverter(new DefaultFormattingConversionService());
  }

  private static Predicate build(JsonPathFilterPredicateBuilder builder, String expression) {
    return builder.build(
        Doc.class, new PathBuilder<>(Doc.class, "doc"), expression, Set.of(), settings(), true);
  }

  private static Tmf630FilterSettings settings() {
    return new Tmf630FilterSettings(
        true,
        true,
        true,
        CombineMode.OR,
        true,
        true,
        true,
        new PredicateLimits(50, 50, 64),
        AllowlistMode.ALLOW_ALL,
        UnknownParamBehavior.REJECT,
        UnknownParamBehavior.REJECT,
        true,
        2048,
        UnknownParamBehavior.REJECT,
        IsnullSemantics.MISSING_ONLY,
        false);
  }
}
