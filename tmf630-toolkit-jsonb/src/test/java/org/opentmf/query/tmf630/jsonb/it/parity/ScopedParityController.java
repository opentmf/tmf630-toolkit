package org.opentmf.query.tmf630.jsonb.it.parity;

import com.querydsl.core.types.Predicate;
import org.opentmf.query.tmf630.jsonb.JsonbClause;
import org.springframework.web.bind.annotation.RestController;

/** Implements {@link ScopedParityApi}; the query work is the plain parity endpoints'. */
@RestController
public class ScopedParityController implements ScopedParityApi {

  private final ParityController parity;

  public ScopedParityController(ParityController parity) {
    this.parity = parity;
  }

  @Override
  public ScopedIds searchJpa(String version, Predicate predicate) {
    return new ScopedIds(version, parity.searchJpa(predicate));
  }

  @Override
  public ScopedIds searchJsonb(String version, JsonbClause clause) {
    return new ScopedIds(version, parity.searchJsonb(clause));
  }
}
