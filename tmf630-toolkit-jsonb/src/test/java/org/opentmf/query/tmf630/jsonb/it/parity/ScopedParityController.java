package org.opentmf.query.tmf630.jsonb.it.parity;

import com.querydsl.core.types.Predicate;
import org.opentmf.query.tmf630.jsonb.JsonbClause;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.RestController;

/** Implements {@link ScopedParityApi}; the query work is the plain parity endpoints'. */
@RestController
public class ScopedParityController implements ScopedParityApi {

  private final ParityController parity;
  private final SortParityController sorted;

  public ScopedParityController(ParityController parity, SortParityController sorted) {
    this.parity = parity;
    this.sorted = sorted;
  }

  @Override
  public ScopedIds searchJpa(String version, Predicate predicate) {
    return new ScopedIds(version, parity.searchJpa(predicate));
  }

  @Override
  public ScopedIds searchJsonb(String version, JsonbClause clause) {
    return new ScopedIds(version, parity.searchJsonb(clause));
  }

  @Override
  public ScopedIds sortedJpa(String version, Predicate predicate, Pageable pageable) {
    return new ScopedIds(version, sorted.sortedJpa(predicate, pageable));
  }

  @Override
  public ScopedIds sortedJsonb(
      String version, JsonbClause clause, TmfSort sort, Pageable pageable) {
    return new ScopedIds(version, sorted.sortedJsonb(clause, sort, pageable));
  }
}
