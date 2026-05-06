package org.opentmf.query.tmf630.filtering.it.mongo;

import com.querydsl.core.types.Predicate;
import java.util.List;
import java.util.stream.StreamSupport;
import org.opentmf.query.tmf630.mongo.Tmf630MongoCorrelatedSortExecutor;
import org.opentmf.query.tmf630.paging.TmfRichPageable;
import org.springframework.data.domain.Page;
import org.springframework.data.querydsl.binding.QuerydslPredicate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MongoSearchController {

  private final MongoSearchRepository repository;
  private final Tmf630MongoCorrelatedSortExecutor correlatedExecutor;

  public MongoSearchController(
      MongoSearchRepository repository, Tmf630MongoCorrelatedSortExecutor correlatedExecutor) {
    this.repository = repository;
    this.correlatedExecutor = correlatedExecutor;
  }

  @GetMapping("/mongo-search")
  public List<MongoSearchEntity> search(
      @QuerydslPredicate(root = MongoSearchEntity.class) Predicate predicate) {
    if (predicate == null) {
      return repository.findAll();
    }
    return StreamSupport.stream(repository.findAll(predicate).spliterator(), false).toList();
  }

  @GetMapping("/mongo-search-paged")
  public List<MongoSearchEntity> searchPaged(
      @QuerydslPredicate(root = MongoSearchEntity.class) Predicate predicate,
      TmfRichPageable pageable) {
    Page<MongoSearchEntity> page;
    if (pageable.tmfSort().requiresAggregation()) {
      page =
          correlatedExecutor.findAll(
              MongoSearchEntity.class, predicate, pageable.tmfSort(), pageable);
    } else if (predicate == null) {
      page = repository.findAll(pageable);
    } else {
      page = repository.findAll(predicate, pageable);
    }
    return page.getContent();
  }
}
