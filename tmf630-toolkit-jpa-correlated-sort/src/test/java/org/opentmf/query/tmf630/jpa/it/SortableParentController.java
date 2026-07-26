package org.opentmf.query.tmf630.jpa.it;

import org.opentmf.query.tmf630.jpa.Tmf630JpaCorrelatedSortExecutor;
import org.opentmf.query.tmf630.paging.TmfRichPageable;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test controller for {@link Tmf630JpaCorrelatedSortIT}. No {@code @QuerydslPredicate}
 * binding here — the module's IT infra does not run querydsl-apt, so Q-classes for the
 * test entities are not generated. The sort executor works against a runtime-constructed
 * {@code PathBuilder<SortableParent>}, which is orthogonal to Q-class availability.
 */
@RestController
public class SortableParentController {

  private final Tmf630JpaCorrelatedSortExecutor executor;

  public SortableParentController(Tmf630JpaCorrelatedSortExecutor executor) {
    this.executor = executor;
  }

  @GetMapping("/sortable-parents")
  public Page<SortableParent> search(TmfRichPageable pageable) {
    return executor.findAll(SortableParent.class, null, pageable.tmfSort(), pageable);
  }
}
