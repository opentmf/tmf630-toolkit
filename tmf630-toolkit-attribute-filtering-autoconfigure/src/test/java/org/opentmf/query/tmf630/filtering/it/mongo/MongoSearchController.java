package org.opentmf.query.tmf630.filtering.it.mongo;

import com.querydsl.core.types.Predicate;
import java.util.List;
import java.util.stream.StreamSupport;
import org.springframework.data.querydsl.binding.QuerydslPredicate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MongoSearchController {

  private final MongoSearchRepository repository;

  public MongoSearchController(MongoSearchRepository repository) {
    this.repository = repository;
  }

  @GetMapping("/mongo-search")
  public List<MongoSearchEntity> search(
      @QuerydslPredicate(root = MongoSearchEntity.class) Predicate predicate) {
    if (predicate == null) {
      return repository.findAll();
    }
    return StreamSupport.stream(repository.findAll(predicate).spliterator(), false).toList();
  }
}
