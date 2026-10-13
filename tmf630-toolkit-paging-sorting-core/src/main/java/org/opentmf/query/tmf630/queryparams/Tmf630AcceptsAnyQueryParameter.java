package org.opentmf.query.tmf630.queryparams;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exempts a handler — or every handler of a type — from the undeclared-query-parameter guard.
 *
 * <p>For routes whose callers the service does not control and that must not fail on an extra
 * name: a delivery-report callback from an external gateway that sends whatever its vendor adds
 * next release. Prefer {@link Tmf630AcceptsQueryParameters} wherever the vocabulary is known —
 * this annotation gives up the check entirely.
 *
 * <p>Found on the handler method or its type, on the implementing class or on the API
 * interface.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Tmf630AcceptsAnyQueryParameter {}
