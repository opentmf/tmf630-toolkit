package org.opentmf.query.tmf630.jpa;

import com.querydsl.core.QueryResults;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.JPQLQuery;
import com.querydsl.jpa.impl.JPAQuery;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.EntityManager;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import java.beans.Introspector;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.opentmf.query.tmf630.exception.TmfPagingException;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.opentmf.query.tmf630.paging.TmfSortTerm;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Phase (a.4) — JPA correlated-sort executor. Consumes a {@link TmfSort} whose terms may
 * mix {@code PLAIN} and {@code SIMPLE_RICH} kinds, translates each simple-rich term to a
 * correlated scalar subquery via QueryDSL's {@link JPAExpressions}, and executes the
 * combined query through the injected {@link EntityManager}. Multi-hop simple-rich chains,
 * JsonPath sort grammar, positional {@code [N]}, wildcards {@code [*]}, aggregators, and
 * coercions are rejected at parse time with actionable messages (see
 * {@link SimpleRichSortTermParser}).
 *
 * <p>Simple-rich terms compile to:
 *
 * <pre>{@code
 * ORDER BY (
 *   SELECT alias.leafField
 *   FROM parent.hopField alias
 *   WHERE alias.matchKey = 'matchValue'
 * ) ASC|DESC [NULLS LAST]
 * }</pre>
 *
 * <p>Bounded to a single row per outer parent by the semantics of the correlated
 * key-value pair; if a parent has more than one child matching the key, Hibernate/JDBC
 * will raise the standard "scalar subquery returned more than one row" error, which is
 * the honest signal that the caller needs multi-value handling (deferred to a future
 * release). This first-cut deliberately does not silently pick the min/max.
 */
public class Tmf630JpaCorrelatedSortExecutor {

  private final EntityManager entityManager;
  private final boolean nullsLast;
  private final AtomicInteger aliasCounter = new AtomicInteger();

  public Tmf630JpaCorrelatedSortExecutor(EntityManager entityManager, boolean nullsLast) {
    this.entityManager = entityManager;
    this.nullsLast = nullsLast;
  }

  /**
   * Runs a paged, filtered, correlated-sort query. The predicate is filtered against
   * the parent entity; the sort mixes plain dotted terms (rendered as standard
   * {@link OrderSpecifier}s over the parent path) and simple-rich terms (rendered as
   * correlated scalar subqueries).
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public <T> Page<T> findAll(
      Class<T> rootType, Predicate predicate, TmfSort tmfSort, Pageable pageable) {
    if (rootType == null) {
      throw new IllegalArgumentException("rootType must not be null");
    }
    if (tmfSort == null) {
      tmfSort = TmfSort.empty();
    }
    if (pageable == null) {
      pageable = Pageable.unpaged();
    }
    PathBuilder<T> root =
        new PathBuilder<>(rootType, Introspector.decapitalize(rootType.getSimpleName()));
    List<OrderSpecifier<?>> orders = buildOrderSpecifiers(root, rootType, tmfSort);
    JPAQuery<T> query = new JPAQuery<T>(entityManager).select(root).from(root);
    if (predicate != null) {
      query.where(predicate);
    }
    if (!orders.isEmpty()) {
      query.orderBy(orders.toArray(new OrderSpecifier[0]));
    }
    if (pageable.isPaged()) {
      query.offset(pageable.getOffset()).limit(pageable.getPageSize());
    }
    QueryResults<T> results = query.fetchResults();
    return new PageImpl<>(results.getResults(), pageable, results.getTotal());
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private <T> List<OrderSpecifier<?>> buildOrderSpecifiers(
      PathBuilder<T> root, Class<T> rootType, TmfSort tmfSort) {
    List<OrderSpecifier<?>> orders = new ArrayList<>();
    for (TmfSortTerm term : tmfSort.terms()) {
      Order direction = term.direction().isAscending() ? Order.ASC : Order.DESC;
      OrderSpecifier<?> specifier;
      switch (term.kind()) {
        case PLAIN -> specifier = plainOrderSpecifier(root, direction, term.expression());
        case SIMPLE_RICH ->
            specifier = simpleRichOrderSpecifier(root, rootType, direction, term.expression());
        case JSONPATH ->
            throw new TmfPagingException(
                "JsonPath sort grammar is not yet supported on JPA correlated sort"
                    + " (Phase a.4 first cut). Term: "
                    + term.expression());
        default ->
            throw new TmfPagingException(
                "Unsupported sort term kind: " + term.kind() + " for " + term.expression());
      }
      if (nullsLast) {
        specifier = specifier.nullsLast();
      }
      orders.add(specifier);
    }
    return orders;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private OrderSpecifier<?> plainOrderSpecifier(
      PathBuilder<?> root, Order direction, String expression) {
    ComparableExpressionBase<?> path = root.getComparable(expression, Comparable.class);
    return new OrderSpecifier(direction, path);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private OrderSpecifier<?> simpleRichOrderSpecifier(
      PathBuilder<?> root, Class<?> rootType, Order direction, String expression) {
    SimpleRichSortTermParser.ParsedTerm parsed = SimpleRichSortTermParser.parse(expression);
    Field collectionField = findField(rootType, parsed.hopField());
    if (collectionField == null) {
      throw new TmfPagingException(
          "Unknown correlated-sort hop field '"
              + parsed.hopField()
              + "' on "
              + rootType.getSimpleName());
    }
    if (!Collection.class.isAssignableFrom(collectionField.getType())) {
      throw new TmfPagingException(
          "Correlated sort hop must target a collection field, got '"
              + parsed.hopField()
              + "' of type "
              + collectionField.getType().getSimpleName());
    }
    if (!isJoinMappedField(collectionField)) {
      throw new TmfPagingException(
          "Correlated sort on JPA requires the collection field to be JOIN-mapped via"
              + " @OneToMany, @ManyToMany, or @ElementCollection. Field: "
              + parsed.hopField());
    }
    Class<?> elementType = resolveCollectionElementType(collectionField);
    PathBuilder<?> childAlias =
        new PathBuilder<>(
            elementType, "_jpaSort_" + aliasCounter.incrementAndGet());
    com.querydsl.core.types.dsl.ListPath<?, ?> collectionPath =
        root.getList(parsed.hopField(), (Class) elementType);
    ComparableExpressionBase<Comparable> leafExpr =
        childAlias.getComparable(parsed.leafField(), Comparable.class);
    JPQLQuery<?> subquery =
        JPAExpressions.select(leafExpr)
            .from(
                (com.querydsl.core.types.CollectionExpression<?, Object>) collectionPath,
                (com.querydsl.core.types.Path<Object>) childAlias)
            .where(
                childAlias
                    .getString(parsed.matchKey())
                    .eq(Expressions.constant(parsed.matchValue())));
    return new OrderSpecifier(direction, subquery);
  }

  private static Field findField(Class<?> type, String name) {
    Class<?> cursor = type;
    while (cursor != null && cursor != Object.class) {
      try {
        return cursor.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        cursor = cursor.getSuperclass();
      }
    }
    return null;
  }

  private static Class<?> resolveCollectionElementType(Field field) {
    Type genericType = field.getGenericType();
    if (genericType instanceof ParameterizedType parameterizedType) {
      Type[] args = parameterizedType.getActualTypeArguments();
      if (args.length == 1 && args[0] instanceof Class<?> elementType) {
        return elementType;
      }
    }
    return Object.class;
  }

  private static boolean isJoinMappedField(Field field) {
    return field.isAnnotationPresent(OneToMany.class)
        || field.isAnnotationPresent(ManyToMany.class)
        || field.isAnnotationPresent(ElementCollection.class);
  }
}
