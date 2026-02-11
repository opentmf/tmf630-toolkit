package org.opentmf.query.tmf630.filtering;

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
class JpaServiceOrderController {

  private final EntityManager entityManager;

  JpaServiceOrderController(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @GetMapping("/jpa-search")
  List<JpaServiceOrderEntity> search(
      @QuerydslPredicate(root = JpaServiceOrderEntity.class) Predicate predicate) {
    PathBuilder<JpaServiceOrderEntity> root =
        new PathBuilder<>(
            JpaServiceOrderEntity.class,
            Introspector.decapitalize(JpaServiceOrderEntity.class.getSimpleName()));
    return new JPAQuery<JpaServiceOrderEntity>(entityManager).from(root).where(predicate).fetch();
  }
}
