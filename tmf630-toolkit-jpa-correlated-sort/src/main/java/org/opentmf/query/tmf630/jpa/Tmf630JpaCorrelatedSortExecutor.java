package org.opentmf.query.tmf630.jpa;

import com.querydsl.core.QueryResults;
import com.querydsl.core.types.CollectionExpression;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.ComparableExpressionBase;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.ListPath;
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
import org.opentmf.query.tmf630.jpa.SimpleRichSortTermParser.Aggregator;
import org.opentmf.query.tmf630.jpa.SimpleRichSortTermParser.Hop;
import org.opentmf.query.tmf630.jpa.SimpleRichSortTermParser.ParsedTerm;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.opentmf.query.tmf630.paging.TmfSortTerm;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * JPA correlated-sort executor.
 *
 * <p>Consumes a {@link TmfSort} whose terms may mix {@code PLAIN} and {@code SIMPLE_RICH}
 * kinds. Plain terms render as ordinary {@link OrderSpecifier}s over the parent path;
 * rich terms compile to a correlated subquery in ORDER BY, chaining JPQL joins for each
 * hop:
 *
 * <pre>{@code
 * ORDER BY (
 *   SELECT [MIN|MAX](aliasN.leafField)
 *   FROM parent.hopField0 alias0
 *   JOIN alias0.hopField1 alias1
 *   ...
 *   WHERE alias0.matchKey0 = 'val0' AND alias1.matchKey1 = 'val1' AND ...
 * ) ASC|DESC [NULLS LAST]
 * }</pre>
 *
 * <p>The subquery always aggregates the leaf via MIN (ASC direction) or MAX (DESC
 * direction) when the caller does not specify an aggregator explicitly. This mirrors the
 * Mongo executor's direction-aware {@code $min}/{@code $max} reducer and gracefully
 * handles hop chains where more than one child row matches the key — the sort picks the
 * extreme value that would land at the top for that direction. An explicit
 * {@code min(...)} / {@code max(...)} wrapper overrides the direction-derived choice.
 *
 * <p>Rejected at parse time with actionable messages: JsonPath sort grammar, wildcards,
 * positional {@code [N]}, and coercions {@code num()}/{@code str()}/{@code date()} — see
 * {@link SimpleRichSortTermParser}.
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
   * Runs a paged, filtered, correlated-sort query. The predicate is filtered against the
   * parent entity; the sort mixes plain dotted terms (rendered as standard {@link
   * OrderSpecifier}s over the parent path) and rich terms (rendered as correlated scalar
   * subqueries).
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

  private <T> List<OrderSpecifier<?>> buildOrderSpecifiers(
      PathBuilder<T> root, Class<T> rootType, TmfSort tmfSort) {
    List<OrderSpecifier<?>> orders = new ArrayList<>();
    for (TmfSortTerm term : tmfSort.terms()) {
      Order direction = term.direction().isAscending() ? Order.ASC : Order.DESC;
      OrderSpecifier<?> specifier =
          switch (term.kind()) {
            case PLAIN -> plainOrderSpecifier(root, direction, term.expression());
            case SIMPLE_RICH -> richOrderSpecifier(root, rootType, direction, term.expression());
            case JSONPATH ->
                throw new TmfPagingException(
                    "JsonPath sort grammar is not supported on JPA correlated sort — use the"
                        + " rich form 'field[key=value].leaf' (with optional min()/max() and"
                        + " multi-hop chains) or run against a JSONB-backed entity. Term: "
                        + term.expression());
          };
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
  private OrderSpecifier<?> richOrderSpecifier(
      PathBuilder<?> root, Class<?> rootType, Order direction, String expression) {
    ParsedTerm parsed = SimpleRichSortTermParser.parse(expression);
    Aggregator aggregator = effectiveAggregator(parsed.aggregator(), direction);

    Hop firstHop = parsed.hops().get(0);
    Field firstField = requireCollectionField(rootType, firstHop.hopField(), expression);
    Class<?> firstElement = resolveCollectionElementType(firstField);
    PathBuilder<?> firstAlias = newAlias(firstElement);
    ListPath<?, ?> firstCollection =
        root.getList(firstHop.hopField(), (Class) firstElement);

    com.querydsl.core.types.dsl.BooleanExpression whereExpr =
        firstAlias
            .getString(firstHop.matchKey())
            .eq(Expressions.constant(firstHop.matchValue()));

    PathBuilder<?> currentAlias = firstAlias;
    Class<?> currentElement = firstElement;

    List<JoinStep> additionalJoins = new ArrayList<>();
    for (int i = 1; i < parsed.hops().size(); i++) {
      Hop nextHop = parsed.hops().get(i);
      Field nextField = requireCollectionField(currentElement, nextHop.hopField(), expression);
      Class<?> nextElement = resolveCollectionElementType(nextField);
      PathBuilder<?> nextAlias = newAlias(nextElement);
      ListPath<?, ?> nextCollection =
          currentAlias.getList(nextHop.hopField(), (Class) nextElement);
      additionalJoins.add(new JoinStep(nextCollection, nextAlias));
      whereExpr =
          whereExpr.and(
              nextAlias
                  .getString(nextHop.matchKey())
                  .eq(Expressions.constant(nextHop.matchValue())));
      currentAlias = nextAlias;
      currentElement = nextElement;
    }

    ComparableExpressionBase<Comparable> leafExpr =
        currentAlias.getComparable(parsed.leafField(), Comparable.class);
    Expression<?> selectExpr =
        switch (aggregator) {
          case MIN -> leafExpr.min();
          case MAX -> leafExpr.max();
          case NONE -> leafExpr;
        };

    JPQLQuery<?> subquery =
        JPAExpressions.select(selectExpr)
            .from(
                (CollectionExpression<?, Object>) firstCollection,
                (Path<Object>) firstAlias);
    for (JoinStep step : additionalJoins) {
      subquery =
          subquery.innerJoin(
              (CollectionExpression<?, Object>) step.collection(),
              (Path<Object>) step.alias());
    }
    subquery = subquery.where(whereExpr);

    return new OrderSpecifier(direction, subquery);
  }

  private static Aggregator effectiveAggregator(Aggregator declared, Order direction) {
    if (declared != Aggregator.NONE) {
      return declared;
    }
    return direction == Order.ASC ? Aggregator.MIN : Aggregator.MAX;
  }

  private PathBuilder<?> newAlias(Class<?> elementType) {
    return new PathBuilder<>(elementType, "_jpaSort_" + aliasCounter.incrementAndGet());
  }

  private static Field requireCollectionField(Class<?> owner, String name, String expression) {
    Field field = findField(owner, name);
    if (field == null) {
      throw new TmfPagingException(
          "Unknown correlated-sort hop field '" + name + "' on " + owner.getSimpleName()
              + " in: " + expression);
    }
    if (!Collection.class.isAssignableFrom(field.getType())) {
      throw new TmfPagingException(
          "Correlated sort hop must target a collection field, got '"
              + name + "' of type " + field.getType().getSimpleName() + " in: " + expression);
    }
    if (!isJoinMappedField(field)) {
      throw new TmfPagingException(
          "Correlated sort on JPA requires the collection field to be JOIN-mapped via"
              + " @OneToMany, @ManyToMany, or @ElementCollection. Field: "
              + name
              + " in: "
              + expression);
    }
    return field;
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

  private record JoinStep(ListPath<?, ?> collection, PathBuilder<?> alias) {}
}
