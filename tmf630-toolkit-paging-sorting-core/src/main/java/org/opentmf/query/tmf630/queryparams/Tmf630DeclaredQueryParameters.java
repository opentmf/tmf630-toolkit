package org.opentmf.query.tmf630.queryparams;

import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.OutputStream;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.security.Principal;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.opentmf.query.tmf630.advice.Tmf630ResponseBodyAdvice;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.opentmf.query.tmf630.versioning.TmfVersionedId;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpMethod;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.MatrixVariable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.SessionAttribute;
import org.springframework.web.method.HandlerMethod;

/**
 * The query parameters a handler declares — read off its signature, so the guard and the
 * binding cannot disagree — or empty when the handler is OPEN and must not be judged.
 *
 * <p><b>Declared:</b>
 *
 * <ul>
 *   <li>every {@code @RequestParam} by its {@code name}/{@code value}, or by its compiled name;
 *       a non-annotated simple-typed argument too, which Spring binds as a request parameter by
 *       that name;
 *   <li>{@code offset}, {@code limit} and {@code sort} for a {@link Pageable} — what the toolkit's
 *       paging resolvers read; {@code sort} for a {@link Sort} or {@link TmfSort};
 *   <li>{@code version} for a {@link TmfVersionedId}, whose resolver falls back to the query;
 *   <li>{@code fields} wherever the {@code @Tmf630Response} projection runs — on the method or
 *       its type, on the implementing class or the API interface;
 *   <li>the names of {@code @Tmf630PassThrough} and {@link Tmf630AcceptsQueryParameters}.
 * </ul>
 *
 * <p><b>Reading nothing from the query:</b> path variables, matrix variables, headers, the body
 * and its parts, cookies, request and session attributes, {@code @Value}, {@code
 * Authentication}/{@code Principal} (and Spring Security's {@code @AuthenticationPrincipal} /
 * {@code @CurrentSecurityContext}), the servlet response and its streams, the session, {@code
 * Locale}, {@code TimeZone}, {@code ZoneId}, {@code HttpMethod} — plus whatever a {@link
 * Tmf630QueryArgumentClassifier} says.
 *
 * <p><b>OPEN (empty result):</b>
 *
 * <ul>
 *   <li>a TMF-630 list — a handler with a filter terminal ({@code Predicate}, {@code
 *       @QuerydslPredicate}, {@code JsonbClause}, {@code @Tmf630JsonbFilter}), whose unknown
 *       filters the filter grammar refuses itself (but see {@link #listRefusals}). Matched by
 *       class name, so this module pulls neither QueryDSL nor the jsonb module;
 *   <li>a handler with an argument that may read names the guard cannot see — a catch-all
 *       {@code @RequestParam Map}/{@code MultiValueMap}, the raw request, a model attribute, an
 *       argument of an unrecognised type — unless it states its vocabulary with {@link
 *       Tmf630AcceptsQueryParameters};
 *   <li>a handler annotated {@link Tmf630AcceptsAnyQueryParameter}.
 * </ul>
 *
 * <p><b>A TMF-630 list is judged for Spring's {@code page} and {@code size} only (3.5.1):</b> the
 * filter grammar skips those two names as reserved, so a list would otherwise serve them silently
 * — neither is a TMF-630 name, and neither is a filter without an explicit operator ({@code
 * page.eq=2} stays a filter). {@link #listRefusals(HandlerMethod)} names them, minus any the
 * handler binds itself.
 *
 * <p>The derivation is cached per handler method. {@link #of(HandlerMethod)} applies the built-in
 * rules alone; the auto-configured instance also consults the application's classifiers and
 * {@code non-query-arguments}, and is the one the interceptor and {@link
 * Tmf630QueryParameterContract} use.
 */
public final class Tmf630DeclaredQueryParameters {

  /** The paging names a {@link Pageable} argument reads. */
  public static final List<String> PAGING = List.of("offset", "limit", "sort");

  /**
   * Spring Data's paging names. Never declared by a {@code Pageable} — the TMF-630 contract pages
   * with {@code offset}/{@code limit} — and refused on a TMF-630 list that does not bind them.
   */
  public static final List<String> SPRING_PAGING = List.of("page", "size");

  /** The sort name a {@link Sort} or {@link TmfSort} argument reads. */
  public static final String SORT = "sort";

  /** The attribute selection the {@code @Tmf630Response} projection reads. */
  public static final String FIELDS = "fields";

  /** The query fallback of a {@link TmfVersionedId}. */
  public static final String VERSION = "version";

  static final List<String> FILTER_TYPES =
      List.of("com.querydsl.core.types.Predicate", "org.opentmf.query.tmf630.jsonb.JsonbClause");

  static final List<String> FILTER_ANNOTATIONS =
      List.of(
          "org.springframework.data.querydsl.binding.QuerydslPredicate",
          "org.opentmf.query.tmf630.jsonb.Tmf630JsonbFilter");

  static final String PASS_THROUGH = "org.opentmf.query.tmf630.filtering.Tmf630PassThrough";

  private static final List<Class<? extends Annotation>> NON_QUERY_ANNOTATIONS =
      List.of(
          PathVariable.class,
          MatrixVariable.class,
          RequestHeader.class,
          RequestBody.class,
          RequestPart.class,
          CookieValue.class,
          RequestAttribute.class,
          SessionAttribute.class,
          Value.class);

  private static final List<String> NON_QUERY_ANNOTATION_NAMES =
      List.of(
          "org.springframework.security.core.annotation.AuthenticationPrincipal",
          "org.springframework.security.core.annotation.CurrentSecurityContext");

  private static final List<Class<?>> NON_QUERY_TYPES =
      List.of(
          Principal.class,
          ServletResponse.class,
          OutputStream.class,
          Writer.class,
          HttpSession.class,
          Locale.class,
          TimeZone.class,
          ZoneId.class,
          HttpMethod.class);

  /** Annotations that constrain or document a value without changing how Spring binds it. */
  private static final List<String> NEUTRAL_ANNOTATION_PACKAGES =
      List.of("jakarta.validation.", "org.jspecify.", "org.springframework.lang.", "io.swagger.");

  private static final ParameterNameDiscoverer NAMES = new DefaultParameterNameDiscoverer();

  private static final Tmf630DeclaredQueryParameters BUILT_IN =
      new Tmf630DeclaredQueryParameters(List.of());

  private final List<Tmf630QueryArgumentClassifier> classifiers;
  private final Map<HandlerKey, Derivation> cache = new ConcurrentHashMap<>();

  /**
   * @param classifiers consulted in order before the built-in rules; the first non-null answer
   *     wins
   */
  public Tmf630DeclaredQueryParameters(List<? extends Tmf630QueryArgumentClassifier> classifiers) {
    this.classifiers = List.copyOf(classifiers);
  }

  /**
   * The query parameters {@code handler} declares under the built-in rules alone, or empty when
   * it is open.
   *
   * @throws IllegalStateException when a query binding has no name to read — no annotation name
   *     and no {@code -parameters} compiler flag
   */
  public static Optional<Set<String>> of(HandlerMethod handler) {
    return BUILT_IN.declaredBy(handler);
  }

  /**
   * The query parameters {@code handler} declares, sorted, or empty when it is open.
   *
   * @throws IllegalStateException when a query binding has no name to read — no annotation name
   *     and no {@code -parameters} compiler flag
   */
  public Optional<Set<String>> declaredBy(HandlerMethod handler) {
    return Optional.ofNullable(derivation(handler).declared());
  }

  /**
   * The names a TMF-630 list refuses although its filter grammar would let them pass: {@link
   * #SPRING_PAGING}, minus any the handler binds itself ({@code @RequestParam}, {@code
   * @Tmf630PassThrough}, {@link Tmf630AcceptsQueryParameters}, a classifier). Empty for every
   * handler that is not a list, and for a list with an argument the guard cannot see into unless
   * it states its vocabulary.
   *
   * @throws IllegalStateException when a query binding has no name to read — no annotation name
   *     and no {@code -parameters} compiler flag
   */
  public Set<String> listRefusals(HandlerMethod handler) {
    return derivation(handler).listRefusals();
  }

  private Derivation derivation(HandlerMethod handler) {
    return cache.computeIfAbsent(
        new HandlerKey(handler.getBeanType(), handler.getMethod()), key -> derive(handler));
  }

  private Derivation derive(HandlerMethod handler) {
    if (hasHandlerAnnotation(handler, Tmf630AcceptsAnyQueryParameter.class)) {
      return Derivation.OPEN;
    }
    Signature signature = readSignature(handler);
    Optional<List<String>> accepted = acceptedNames(handler);
    if (signature.unseen() && accepted.isEmpty()) {
      return Derivation.OPEN;
    }
    SortedSet<String> declared = new TreeSet<>(signature.named());
    accepted.ifPresent(declared::addAll);
    declared.addAll(passThroughNames(handler));
    if (signature.filter()) {
      return Derivation.list(declared);
    }
    if (Tmf630ResponseBodyAdvice.appliesTo(handler.getReturnType())) {
      declared.add(FIELDS);
    }
    return Derivation.closed(declared);
  }

  /** What the handler's arguments read, one classification per argument. */
  private Signature readSignature(HandlerMethod handler) {
    SortedSet<String> named = new TreeSet<>();
    boolean unseen = false;
    boolean filter = false;
    for (MethodParameter shared : handler.getMethodParameters()) {
      // a copy: the handler's own parameters are shared with every request's invocation
      MethodParameter parameter = shared.clone();
      parameter.initParameterNameDiscovery(NAMES);
      Tmf630QueryArgument argument = classify(parameter);
      switch (argument.kind()) {
        case FILTER -> filter = true;
        case UNKNOWN -> unseen = true;
        case NAMED -> named.addAll(argument.names());
        case NONE -> {
          // reads nothing from the query string
        }
      }
    }
    return new Signature(named, unseen, filter);
  }

  private Tmf630QueryArgument classify(MethodParameter parameter) {
    for (Tmf630QueryArgumentClassifier classifier : classifiers) {
      Tmf630QueryArgument argument = classifier.classify(parameter);
      if (argument != null) {
        return argument;
      }
    }
    return builtIn(parameter);
  }

  /** The built-in rules for one argument — see the class Javadoc. */
  static Tmf630QueryArgument builtIn(MethodParameter parameter) {
    RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
    if (requestParam != null) {
      return requestParam(requestParam, parameter);
    }
    if (isFilterTerminal(parameter)) {
      return Tmf630QueryArgument.filter();
    }
    Tmf630QueryArgument toolkit = toolkitArgument(parameter.getParameterType());
    if (toolkit != null) {
      return toolkit;
    }
    if (readsNoQuery(parameter)) {
      return Tmf630QueryArgument.none();
    }
    if (isDefaultRequestParam(parameter)) {
      return Tmf630QueryArgument.named(requireName(parameter.getParameterName(), parameter));
    }
    return Tmf630QueryArgument.unknown();
  }

  /**
   * A named {@code @RequestParam} reads its name. An unnamed one on a {@code Map} is Spring's
   * catch-all binding of the whole query string ({@code RequestParamMapMethodArgumentResolver}),
   * which declares no names at all — the java identifier is not one.
   */
  private static Tmf630QueryArgument requestParam(RequestParam annotation, MethodParameter param) {
    String name = StringUtils.hasText(annotation.name()) ? annotation.name() : annotation.value();
    if (StringUtils.hasText(name)) {
      return Tmf630QueryArgument.named(name);
    }
    if (Map.class.isAssignableFrom(param.getParameterType())) {
      return Tmf630QueryArgument.unknown();
    }
    return Tmf630QueryArgument.named(requireName(param.getParameterName(), param));
  }

  private static boolean isFilterTerminal(MethodParameter parameter) {
    Class<?> type = parameter.getParameterType();
    return FILTER_TYPES.stream().anyMatch(name -> isAssignableTo(type, name))
        || hasAnnotationNamed(parameter, FILTER_ANNOTATIONS);
  }

  private static @Nullable Tmf630QueryArgument toolkitArgument(Class<?> type) {
    if (Pageable.class.isAssignableFrom(type)) {
      return Tmf630QueryArgument.named(PAGING.toArray(String[]::new));
    }
    if (Sort.class.isAssignableFrom(type) || TmfSort.class.isAssignableFrom(type)) {
      return Tmf630QueryArgument.named(SORT);
    }
    if (TmfVersionedId.class.equals(type)) {
      return Tmf630QueryArgument.named(VERSION);
    }
    return null;
  }

  private static boolean readsNoQuery(MethodParameter parameter) {
    Class<?> type = parameter.getParameterType();
    return NON_QUERY_ANNOTATIONS.stream().anyMatch(parameter::hasParameterAnnotation)
        || hasAnnotationNamed(parameter, NON_QUERY_ANNOTATION_NAMES)
        || NON_QUERY_TYPES.stream().anyMatch(nonQuery -> nonQuery.isAssignableFrom(type));
  }

  /**
   * Spring's last-resort binding: a simple-typed argument without a binding annotation is a
   * request parameter named after the argument. Any annotation that is not merely a constraint
   * or documentation may belong to an application resolver, so it leaves the argument unknown.
   */
  private static boolean isDefaultRequestParam(MethodParameter parameter) {
    return BeanUtils.isSimpleProperty(parameter.nestedIfOptional().getNestedParameterType())
        && Arrays.stream(parameter.getParameterAnnotations())
            .map(annotation -> annotation.annotationType().getName())
            .allMatch(
                name -> NEUTRAL_ANNOTATION_PACKAGES.stream().anyMatch(name::startsWith));
  }

  private static String requireName(@Nullable String name, MethodParameter parameter) {
    if (name == null) {
      throw new IllegalStateException(
          "Cannot name the query parameter bound by argument "
              + parameter.getParameterIndex()
              + " of "
              + parameter.getExecutable()
              + " — name it explicitly or compile with -parameters");
    }
    return name;
  }

  private static boolean hasAnnotationNamed(MethodParameter parameter, List<String> names) {
    return Arrays.stream(parameter.getParameterAnnotations())
        .anyMatch(annotation -> names.contains(annotation.annotationType().getName()));
  }

  /** Whether {@code type} is, extends or implements the type named {@code typeName}. */
  static boolean isAssignableTo(Class<?> type, String typeName) {
    Deque<Class<?>> pending = new ArrayDeque<>(List.of(type));
    while (!pending.isEmpty()) {
      Class<?> current = pending.pop();
      if (current.getName().equals(typeName)) {
        return true;
      }
      if (current.getSuperclass() != null) {
        pending.push(current.getSuperclass());
      }
      pending.addAll(Arrays.asList(current.getInterfaces()));
    }
    return false;
  }

  private static boolean hasHandlerAnnotation(
      HandlerMethod handler, Class<? extends Annotation> annotation) {
    return handler.hasMethodAnnotation(annotation)
        || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), annotation);
  }

  private static Optional<List<String>> acceptedNames(HandlerMethod handler) {
    Tmf630AcceptsQueryParameters onMethod =
        handler.getMethodAnnotation(Tmf630AcceptsQueryParameters.class);
    Tmf630AcceptsQueryParameters onType =
        AnnotatedElementUtils.findMergedAnnotation(
            handler.getBeanType(), Tmf630AcceptsQueryParameters.class);
    if (onMethod == null && onType == null) {
      return Optional.empty();
    }
    return Optional.of(
        Stream.of(onMethod, onType)
            .filter(Objects::nonNull)
            .flatMap(annotation -> Arrays.stream(annotation.value()))
            .toList());
  }

  /**
   * {@code @Tmf630PassThrough} lives in the attribute-filtering module; read by name, from the
   * method or the API interface method it implements — where {@code Tmf630PassThroughNames}
   * finds it.
   */
  private static List<String> passThroughNames(HandlerMethod handler) {
    MergedAnnotation<Annotation> passThrough =
        MergedAnnotations.from(handler.getMethod(), SearchStrategy.TYPE_HIERARCHY)
            .get(PASS_THROUGH);
    return passThrough.isPresent()
        ? Arrays.asList(passThrough.getStringArray("value"))
        : List.of();
  }

  /** The cache key: a method inherited by two controllers may differ by type-level annotation. */
  private record HandlerKey(Class<?> beanType, Method method) {}

  /**
   * @param named the names the arguments bind
   * @param unseen an argument may read names the guard cannot see
   * @param filter an argument is a TMF-630 filter terminal
   */
  private record Signature(Set<String> named, boolean unseen, boolean filter) {}

  /**
   * One handler's judgement.
   *
   * @param declared the declared names of a judged handler, sorted; {@code null} when it is open
   * @param listRefusals the names a TMF-630 list refuses; empty for every other handler
   */
  private record Derivation(@Nullable Set<String> declared, Set<String> listRefusals) {

    static final Derivation OPEN = new Derivation(null, Set.of());

    static Derivation closed(SortedSet<String> declared) {
      return new Derivation(Collections.unmodifiableSortedSet(declared), Set.of());
    }

    static Derivation list(Set<String> bound) {
      SortedSet<String> refused = new TreeSet<>(SPRING_PAGING);
      refused.removeAll(bound);
      return new Derivation(null, Collections.unmodifiableSortedSet(refused));
    }
  }
}
