package org.opentmf.query.tmf630.filtering.it.sql;

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
public class SqlSearchController {

  private final EntityManager entityManager;

  public SqlSearchController(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @GetMapping("/sql-search")
  public List<SqlSearchEntity> search(
      @QuerydslPredicate(root = SqlSearchEntity.class) Predicate predicate) {
    PathBuilder<SqlSearchEntity> root =
        new PathBuilder<>(
            SqlSearchEntity.class, Introspector.decapitalize(SqlSearchEntity.class.getSimpleName()));

    return new JPAQuery<SqlSearchEntity>(entityManager).from(root).where(predicate).fetch();
  }
}
