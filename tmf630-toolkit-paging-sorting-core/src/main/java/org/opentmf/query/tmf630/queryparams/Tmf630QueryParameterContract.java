package org.opentmf.query.tmf630.queryparams;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Test-side helper: the parity of what the undeclared-query-parameter guard derives from the
 * served GET handlers with what the OpenAPI document declares — so the guard can never refuse a
 * documented query parameter, nor let an undocumented one through.
 *
 * <p>Walks the application's {@link RequestMappingHandlerMapping}, derives each GET route's
 * declared set with the application's own {@link Tmf630DeclaredQueryParameters} (classifiers and
 * {@code non-query-arguments} included), and compares every <em>closed</em> route with the {@code
 * in: query} parameters of the OAS {@code get} operation on the same path — path-item level
 * included, local {@code $ref}s resolved. Open routes (TMF-630 lists, catch-alls) are not
 * compared; {@link #openRoutes()} lists them, so a test can pin that only the lists are open.
 *
 * <pre>{@code
 * @Autowired ApplicationContext context;
 *
 * @Test
 * void everyClosedReadDeclaresWhatItsOperationDeclares() throws IOException {
 *   try (InputStream oas = getClass().getResourceAsStream("/openapi/my-service.oas.yaml")) {
 *     assertThat(
 *             Tmf630QueryParameterContract.of(context)
 *                 .handlers(handler -> handler.getBeanType().getName().startsWith("com.acme."))
 *                 .drift(Tmf630QueryParameterContract.readYaml(oas)))
 *         .isEmpty();
 *   }
 * }
 * }</pre>
 *
 * <p>Paths are compared with their template variables erased ({@code /items/{id}} matches {@code
 * /items/{itemId}}, a {@code {id:regex}} constraint is ignored). Served patterns are relative to
 * the servlet context path, OAS paths to the server URL; when a class-level mapping repeats part
 * of the server URL, strip it with {@link #withPathPrefix(String)}.
 */
public final class Tmf630QueryParameterContract {

  private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{[^/]*}");
  private static final String LOCAL_REF = "#/";
  private static final int MAX_REF_HOPS = 16;

  private final RequestMappingHandlerMapping mapping;
  private final Tmf630DeclaredQueryParameters declaredQueryParameters;
  private final Predicate<HandlerMethod> handlerFilter;
  private final String pathPrefix;

  /**
   * One served GET route and what the guard derives for it.
   *
   * @param pattern the served path pattern
   * @param handler the handler serving it
   * @param declared the query parameters the handler declares; empty when {@code open}
   * @param open whether the guard leaves this route unjudged
   */
  public record Route(String pattern, HandlerMethod handler, Set<String> declared, boolean open) {

    /** {@code GET /items/{id} → ItemController#get}. */
    @Override
    public String toString() {
      return "GET "
          + pattern
          + " → "
          + handler.getBeanType().getSimpleName()
          + "#"
          + handler.getMethod().getName();
    }
  }

  private Tmf630QueryParameterContract(
      RequestMappingHandlerMapping mapping,
      Tmf630DeclaredQueryParameters declaredQueryParameters,
      Predicate<HandlerMethod> handlerFilter,
      String pathPrefix) {
    this.mapping = mapping;
    this.declaredQueryParameters = declaredQueryParameters;
    this.handlerFilter = handlerFilter;
    this.pathPrefix = pathPrefix;
  }

  /**
   * The contract of the application's {@code requestMappingHandlerMapping}, judged with its
   * {@link Tmf630DeclaredQueryParameters} bean (the built-in rules when there is none).
   */
  public static Tmf630QueryParameterContract of(ApplicationContext context) {
    RequestMappingHandlerMapping mapping =
        context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
    Tmf630DeclaredQueryParameters declared =
        context
            .getBeanProvider(Tmf630DeclaredQueryParameters.class)
            .getIfAvailable(() -> new Tmf630DeclaredQueryParameters(List.of()));
    return of(mapping, declared);
  }

  public static Tmf630QueryParameterContract of(
      RequestMappingHandlerMapping mapping, Tmf630DeclaredQueryParameters declared) {
    return new Tmf630QueryParameterContract(
        Objects.requireNonNull(mapping), Objects.requireNonNull(declared), handler -> true, "");
  }

  /**
   * Only the handlers {@code filter} accepts — e.g. the service's own controllers, not a
   * library's.
   */
  public Tmf630QueryParameterContract handlers(Predicate<HandlerMethod> filter) {
    return new Tmf630QueryParameterContract(
        mapping, declaredQueryParameters, handlerFilter.and(filter), pathPrefix);
  }

  /** Strip {@code prefix} from served patterns before matching them with OAS paths. */
  public Tmf630QueryParameterContract withPathPrefix(String prefix) {
    return new Tmf630QueryParameterContract(
        mapping, declaredQueryParameters, handlerFilter, Objects.requireNonNull(prefix));
  }

  /** Every served GET route the handler filter accepts, sorted by pattern. */
  public List<Route> routes() {
    List<Route> routes = new ArrayList<>();
    mapping
        .getHandlerMethods()
        .forEach(
            (info, handler) -> {
              if (servesGet(info) && handlerFilter.test(handler)) {
                Optional<Set<String>> declared = declaredQueryParameters.declaredBy(handler);
                Set<String> names = declared.orElse(Set.of());
                boolean open = declared.isEmpty();
                info.getPatternValues()
                    .forEach(pattern -> routes.add(new Route(pattern, handler, names, open)));
              }
            });
    routes.sort(Comparator.comparing(Route::toString));
    return routes;
  }

  /** The routes the guard leaves unjudged, as {@link Route#toString()}. */
  public SortedSet<String> openRoutes() {
    SortedSet<String> open = new TreeSet<>();
    routes().stream().filter(Route::open).forEach(route -> open.add(route.toString()));
    return open;
  }

  /**
   * Every closed route whose declared set differs from its OAS operation's query parameters, as
   * {@code route → "handler [..] vs contract [..]"}. Empty when the two agree everywhere. A closed
   * route with no documented GET operation compares with the empty set.
   *
   * @param openApi the OpenAPI document as generic maps and lists — see {@link #readYaml}
   */
  public SortedMap<String, String> drift(Map<String, ?> openApi) {
    Map<String, Set<String>> documented = new TreeMap<>();
    documentedQueryParameters(openApi)
        .forEach((path, names) -> documented.put(normalise(path), names));
    SortedMap<String, String> drift = new TreeMap<>();
    for (Route route : routes()) {
      if (route.open()) {
        continue;
      }
      String path = normalise(stripPrefix(route.pattern()));
      Set<String> contract = documented.getOrDefault(path, Set.of());
      if (!route.declared().equals(contract)) {
        drift.put(
            route.toString(),
            "handler " + new TreeSet<>(route.declared()) + " vs contract " + contract);
      }
    }
    return drift;
  }

  /**
   * The {@code in: query} parameter names of every documented GET operation, by OAS path —
   * path-item level and operation level together, local {@code $ref}s resolved.
   *
   * @throws IllegalArgumentException on a {@code $ref} that does not resolve within the document
   */
  public static SortedMap<String, Set<String>> documentedQueryParameters(Map<String, ?> openApi) {
    SortedMap<String, Set<String>> operations = new TreeMap<>();
    asMap(openApi.get("paths"))
        .forEach(
            (path, item) -> {
              Map<?, ?> pathItem = asMap(item);
              Object get = pathItem.get("get");
              if (get != null) {
                SortedSet<String> query = new TreeSet<>();
                Stream.of(pathItem.get("parameters"), asMap(get).get("parameters"))
                    .flatMap(parameters -> asList(parameters).stream())
                    .map(parameter -> resolve(asMap(parameter), openApi, 0))
                    .filter(parameter -> "query".equals(parameter.get("in")))
                    .forEach(parameter -> query.add(String.valueOf(parameter.get("name"))));
                operations.put(String.valueOf(path), query);
              }
            });
    return operations;
  }

  /**
   * Reads an OpenAPI YAML (or JSON) document into generic maps and lists, with SnakeYAML's safe
   * constructor. Needs {@code org.yaml:snakeyaml} on the test classpath — every Spring Boot
   * application has it.
   */
  public static Map<String, Object> readYaml(InputStream yaml) {
    LoaderOptions options = new LoaderOptions();
    options.setCodePointLimit(64 * 1024 * 1024);
    Map<String, Object> document = new Yaml(new SafeConstructor(options)).load(yaml);
    return document == null ? Map.of() : document;
  }

  private static boolean servesGet(RequestMappingInfo info) {
    Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
    return methods.isEmpty() || methods.contains(RequestMethod.GET);
  }

  private String stripPrefix(String pattern) {
    return !pathPrefix.isEmpty() && pattern.startsWith(pathPrefix)
        ? pattern.substring(pathPrefix.length())
        : pattern;
  }

  private static String normalise(String path) {
    return TEMPLATE_VARIABLE.matcher(path).replaceAll("{}");
  }

  /** A parameter object, or the one its local {@code $ref} points at. */
  private static Map<?, ?> resolve(Map<?, ?> parameter, Map<String, ?> openApi, int hops) {
    Object ref = parameter.get("$ref");
    if (ref == null) {
      return parameter;
    }
    String pointer = String.valueOf(ref);
    if (hops >= MAX_REF_HOPS) {
      throw new IllegalArgumentException("$ref chain too long (a cycle?): " + pointer);
    }
    if (!pointer.startsWith(LOCAL_REF)) {
      throw new IllegalArgumentException("Only local $refs are supported: " + pointer);
    }
    Object target = openApi;
    for (String token : pointer.substring(LOCAL_REF.length()).split("/")) {
      target = asMap(target).get(token.replace("~1", "/").replace("~0", "~"));
      if (target == null) {
        throw new IllegalArgumentException("Unresolvable $ref: " + pointer);
      }
    }
    return resolve(asMap(target), openApi, hops + 1);
  }

  private static Map<?, ?> asMap(Object value) {
    return value instanceof Map<?, ?> map ? map : Map.of();
  }

  private static List<?> asList(Object value) {
    return value instanceof List<?> list ? list : List.of();
  }
}
