package org.opentmf.query.tmf630.filtering.it.mongo;

import com.querydsl.core.types.Predicate;
import java.util.List;
import java.util.stream.StreamSupport;
import org.springframework.data.querydsl.binding.QuerydslPredicate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Returns the sorted ids of the {@link MongoEnumEntity} documents the URL grammar selects. */
@RestController
public class MongoEnumController {

  private final MongoEnumRepository repository;

  public MongoEnumController(MongoEnumRepository repository) {
    this.repository = repository;
  }

  @GetMapping("/mongo-enum")
  public List<String> search(@QuerydslPredicate(root = MongoEnumEntity.class) Predicate predicate) {
    Iterable<MongoEnumEntity> found =
        predicate == null ? repository.findAll() : repository.findAll(predicate);
    return StreamSupport.stream(found.spliterator(), false)
        .map(MongoEnumEntity::getId)
        .sorted()
        .toList();
  }
}
