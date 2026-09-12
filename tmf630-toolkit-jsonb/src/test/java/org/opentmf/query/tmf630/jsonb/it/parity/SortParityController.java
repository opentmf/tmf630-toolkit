package org.opentmf.query.tmf630.jsonb.it.parity;

import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.jpa.JPQLQuery;
import com.querydsl.jpa.impl.JPAQuery;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.function.Function;
import org.opentmf.query.tmf630.jsonb.JsonbClause;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbFilter;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbFilterExecutor;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.support.Querydsl;
import org.springframework.data.querydsl.binding.QuerydslPredicate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sorted counterparts of the parity endpoints. The rooted pair binds a filter root beside the
 * sort (the shape whose plain sort keys the toolkit validates); the unrooted pair binds sort and
 * paging only, where no root is known and behaviour is deliberately unchanged. The JPA side sorts
 * through Spring Data's own {@link Querydsl} helper — the exact path of
 * {@code QuerydslPredicateExecutor#findAll(predicate, pageable)} — and returns ids in result
 * order (never re-sorted), so the IT can assert ordering.
 */
@RestController
public class SortParityController {

  private static final Function<String, Class<?>> FIELD_TYPES =
      field -> "priority".equals(field) ? Integer.class : String.class;

  private final EntityManager entityManager;
  private final ParityEntityRepository entityRepository;
  private final Tmf630JsonbFilterExecutor jsonbExecutor;

  public SortParityController(
      EntityManager entityManager,
      ParityEntityRepository entityRepository,
      Tmf630JsonbFilterExecutor jsonbExecutor) {
    this.entityManager = entityManager;
    this.entityRepository = entityRepository;
    this.jsonbExecutor = jsonbExecutor;
  }

  @GetMapping("/parity/sorted/jpa")
  public List<String> sortedJpa(
      @QuerydslPredicate(root = ParityEntity.class) Predicate predicate, Pageable pageable) {
    PathBuilder<ParityEntity> root = new PathBuilder<>(ParityEntity.class, "parityEntity");
    JPQLQuery<ParityEntity> query =
        new JPAQuery<ParityEntity>(entityManager).from(root).where(predicate);
    return new Querydsl(entityManager, root)
        .applyPagination(pageable, query).fetch().stream()
        .map(ParityEntity::getId)
        .toList();
  }

  @GetMapping("/parity/sorted/jsonb")
  public List<String> sortedJsonb(
      @Tmf630JsonbFilter(root = ParityDomain.class) JsonbClause clause,
      TmfSort sort,
      Pageable pageable) {
    return ids(jsonbExecutor.findAll(ParityDomain.class, clause, sort, pageable, FIELD_TYPES));
  }

  @GetMapping("/parity/sorted/unrooted/jpa")
  public List<String> unrootedJpa(Pageable pageable) {
    return entityRepository.findAll(pageable).map(ParityEntity::getId).getContent();
  }

  @GetMapping("/parity/sorted/unrooted/jsonb")
  public List<String> unrootedJsonb(TmfSort sort, Pageable pageable) {
    return ids(
        jsonbExecutor.findAll(
            ParityDomain.class, JsonbClause.alwaysTrue(), sort, pageable, FIELD_TYPES));
  }

  private static List<String> ids(Page<ParityDomain> page) {
    return page.getContent().stream().map(ParityDomain::getId).toList();
  }
}
