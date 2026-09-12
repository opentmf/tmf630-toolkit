package org.opentmf.query.tmf630.jsonb.it.parity;

import com.querydsl.core.types.Predicate;
import java.util.List;
import org.opentmf.query.tmf630.filtering.Tmf630PassThrough;
import org.opentmf.query.tmf630.jsonb.JsonbClause;
import org.opentmf.query.tmf630.jsonb.Tmf630JsonbFilter;
import org.opentmf.query.tmf630.paging.TmfSort;
import org.springframework.data.domain.Pageable;
import org.springframework.data.querydsl.binding.QuerydslPredicate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The parity endpoints in the API-INTERFACE form consumers use (mappings, bindings and
 * {@code @Tmf630PassThrough} all declared here, the controller only implements): pins that the
 * pass-through allowance is found on the interface method on BOTH filter terminals. The
 * mandatory {@code version} selector is not a property of either root type.
 */
public interface ScopedParityApi {

  @GetMapping("/parity/scoped/jpa")
  @Tmf630PassThrough({"version"})
  ScopedIds searchJpa(
      @RequestParam(name = "version") String version,
      @QuerydslPredicate(root = ParityEntity.class) Predicate predicate);

  @GetMapping("/parity/scoped/jsonb")
  @Tmf630PassThrough({"version"})
  ScopedIds searchJsonb(
      @RequestParam(name = "version") String version,
      @Tmf630JsonbFilter(root = ParityDomain.class) JsonbClause clause);

  @GetMapping("/parity/scoped/sorted/jpa")
  @Tmf630PassThrough({"version"})
  ScopedIds sortedJpa(
      @RequestParam(name = "version") String version,
      @QuerydslPredicate(root = ParityEntity.class) Predicate predicate,
      Pageable pageable);

  @GetMapping("/parity/scoped/sorted/jsonb")
  @Tmf630PassThrough({"version"})
  ScopedIds sortedJsonb(
      @RequestParam(name = "version") String version,
      @Tmf630JsonbFilter(root = ParityDomain.class) JsonbClause clause,
      TmfSort sort,
      Pageable pageable);

  /** Echoes the bound selector beside the matching ids. */
  record ScopedIds(String version, List<String> ids) {}
}
