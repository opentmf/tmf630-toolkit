package org.opentmf.query.tmf630.queryparams;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the query parameters a handler reads beyond what its signature shows, so the
 * undeclared-query-parameter guard can judge it.
 *
 * <p>The typical case is a pass-through handler that binds the whole query string as a
 * catch-all map and forwards it to a downstream that ignores names it does not know:
 *
 * <pre>{@code
 * @GetMapping("/ops/engine/incidents")
 * @Tmf630AcceptsQueryParameters({"processInstanceId", "incidentType", "limit"})
 * List<Incident> incidents(@RequestParam MultiValueMap<String, String> query);
 * }</pre>
 *
 * <p>Without the annotation such a handler is not judged at all — a catch-all map declares no
 * names, and inventing a set from the java identifier would refuse every real parameter. With
 * it, the named set is the handler's vocabulary, and anything else is undeclared. The same holds
 * for every other argument the guard cannot see into (a raw {@code HttpServletRequest}, a
 * {@code @ModelAttribute} object, an argument of an unknown type): the annotation states what
 * they read, and the handler becomes judged.
 *
 * <p>It never closes a TMF-630 list — a handler with a filter terminal ({@code Predicate},
 * {@code @QuerydslPredicate}, {@code JsonbClause}, {@code @Tmf630JsonbFilter}) — whose whole query
 * string is the filter grammar's to judge.
 *
 * <p>Found on the handler method or its type, on the implementing class or on the API interface;
 * method-level and type-level names add up. There is deliberately no global or property-based
 * equivalent, for the reason {@code @Tmf630PassThrough} has none.
 *
 * @see Tmf630AcceptsAnyQueryParameter
 * @see Tmf630DeclaredQueryParameters
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Tmf630AcceptsQueryParameters {

  /** Exact query-parameter names the handler accepts in addition to its own bindings. */
  String[] value();
}
