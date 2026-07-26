package org.opentmf.query.tmf630.mongo.split;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Type-level opt-in that a Mongo document has one or more
 * {@link Tmf630MongoSplitCollection}-annotated fields whose contents should be routed
 * into split child collections at write time and merged back at read time.
 *
 * <p>Only documents carrying this annotation are inspected by
 * {@link MongoSplitEntityRegistry}. Documents without it stay untouched — Spring Data
 * Mongo's default embedding behavior applies. This mirrors {@code @Tmf630JsonbBacked}:
 * being explicit about opt-in prevents the module from silently changing serialization
 * for domains that never asked for the split treatment.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Tmf630MongoSplitBacked {}
