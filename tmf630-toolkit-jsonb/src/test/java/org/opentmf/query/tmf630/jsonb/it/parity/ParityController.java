package org.opentmf.query.tmf630.jsonb.it.parity;

import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.jpa.impl.JPAQuery;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.opentmf.query.tmf630.jsonb.JsonbClause;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbFilter;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbFilterExecutor;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.springframework.data.domain.Pageable;
import org.springframework.data.querydsl.binding.QuerydslPredicate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two endpoints, one URL grammar: {@code /parity/jpa} binds through the QueryDSL terminal
 * ({@code @QuerydslPredicate}), {@code /parity/jsonb} through the JSONB terminal
 * ({@code @Tmf630JsonbFilter}). Both return the matching ids sorted, so the parity IT can
 * compare raw response bodies.
 */
@RestController
public class ParityController {

  private final EntityManager entityManager;
  private final Tmf630JsonbFilterExecutor jsonbExecutor;

  public ParityController(EntityManager entityManager, Tmf630JsonbFilterExecutor jsonbExecutor) {
    this.entityManager = entityManager;
    this.jsonbExecutor = jsonbExecutor;
  }

  @GetMapping("/parity/jpa")
  public List<String> searchJpa(@QuerydslPredicate(root = ParityEntity.class) Predicate predicate) {
    PathBuilder<ParityEntity> root = new PathBuilder<>(ParityEntity.class, "parityEntity");
    return new JPAQuery<ParityEntity>(entityManager).from(root).where(predicate).fetch().stream()
        .map(ParityEntity::getId)
        .sorted()
        .toList();
  }

  @GetMapping("/parity/jsonb")
  public List<String> searchJsonb(
      @Tmf630JsonbFilter(root = ParityDomain.class) JsonbClause clause) {
    return jsonbExecutor
        .findAll(
            ParityDomain.class, clause, TmfSort.empty(), Pageable.unpaged(), field -> String.class)
        .getContent()
        .stream()
        .map(ParityDomain::getId)
        .sorted()
        .toList();
  }
}
