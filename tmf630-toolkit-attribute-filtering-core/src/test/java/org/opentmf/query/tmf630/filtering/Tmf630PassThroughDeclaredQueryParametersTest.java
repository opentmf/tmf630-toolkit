package org.opentmf.query.tmf630.filtering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.querydsl.core.types.Predicate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.opentmf.query.tmf630.queryparams.Tmf630DeclaredQueryParameters;
import org.springframework.data.querydsl.binding.QuerydslPredicate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;

/**
 * The undeclared-query-parameter guard reads this module's {@link Tmf630PassThrough} and filter
 * terminals by class name — core depends on neither. Pinned here, where the real types exist.
 */
class Tmf630PassThroughDeclaredQueryParametersTest {

  interface ReadApi {
    @Tmf630PassThrough({"version"})
    String read(@PathVariable("id") String id, @RequestParam("lang") String lang);

    String list(@QuerydslPredicate(root = Object.class) Predicate predicate);

    @Tmf630PassThrough({"version"})
    String scopedList(@RequestParam("version") String version, Predicate predicate);

    @Tmf630PassThrough({"size"})
    String sizedList(@QuerydslPredicate(root = Object.class) Predicate predicate);
  }

  static class ReadController implements ReadApi {
    @Override
    public String read(String id, String lang) {
      return id + lang;
    }

    @Override
    public String list(Predicate predicate) {
      return String.valueOf(predicate);
    }

    @Override
    public String scopedList(String version, Predicate predicate) {
      return version;
    }

    @Override
    public String sizedList(Predicate predicate) {
      return String.valueOf(predicate);
    }
  }

  private static HandlerMethod handler(String name) throws NoSuchMethodException {
    for (var method : ReadController.class.getMethods()) {
      if (method.getName().equals(name)) {
        return new HandlerMethod(new ReadController(), method);
      }
    }
    throw new NoSuchMethodException(name);
  }

  private static Optional<Set<String>> declared(String name) throws NoSuchMethodException {
    return Tmf630DeclaredQueryParameters.of(handler(name));
  }

  private static Set<String> listRefusals(String name) throws NoSuchMethodException {
    return new Tmf630DeclaredQueryParameters(List.of()).listRefusals(handler(name));
  }

  @Test
  void passThroughNamesAreDeclared_foundOnTheApiInterface() throws Exception {
    assertEquals(Optional.of(Set.of("lang", "version")), declared("read"));
  }

  @Test
  void aQuerydslListIsOpen_withOrWithoutPassThrough() throws Exception {
    assertTrue(declared("list").isEmpty());
    assertTrue(declared("scopedList").isEmpty());
  }

  @Test
  void aQuerydslListRefusesPageAndSize_unlessItPassesOneThrough() throws Exception {
    assertEquals(Set.of("page", "size"), listRefusals("list"));
    assertEquals(Set.of("page", "size"), listRefusals("scopedList"));
    assertEquals(Set.of("page"), listRefusals("sizedList"));
  }
}
