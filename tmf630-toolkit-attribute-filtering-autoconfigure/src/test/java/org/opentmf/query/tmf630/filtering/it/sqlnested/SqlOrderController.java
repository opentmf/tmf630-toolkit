package org.opentmf.query.tmf630.filtering.it.sqlnested;

import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.jpa.impl.JPAQuery;
import jakarta.persistence.EntityManager;
import java.beans.Introspector;
import java.util.List;
import org.springframework.data.querydsl.binding.QuerydslPredicate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SqlOrderController {

  private final EntityManager entityManager;

  public SqlOrderController(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @GetMapping("/sql-orders")
  public List<SqlOrderEntity> search(
      @QuerydslPredicate(root = SqlOrderEntity.class) Predicate predicate) {
    PathBuilder<SqlOrderEntity> root =
        new PathBuilder<>(
            SqlOrderEntity.class, Introspector.decapitalize(SqlOrderEntity.class.getSimpleName()));
    return new JPAQuery<SqlOrderEntity>(entityManager)
        .from(root)
        .where(predicate)
        .distinct()
        .fetch();
  }
}
