package org.opentmf.query.tmf630.paging;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;

public class TmfSortParser {

  private final Set<String> allowlist;
  private final boolean allowNestedProperties;

  public TmfSortParser(Collection<String> allowlist, boolean allowNestedProperties) {
    this.allowlist = allowlist == null ? Set.of() : new HashSet<>(allowlist);
    this.allowNestedProperties = allowNestedProperties;
  }

  public Sort parse(List<String> sortParams) {
    if (sortParams == null || sortParams.isEmpty()) {
      return Sort.unsorted();
    }

    List<Sort.Order> orders = new ArrayList<>();
    for (String sortParam : sortParams) {
      if (!StringUtils.hasText(sortParam)) {
        continue;
      }
      String[] tokens = sortParam.split(",");
      for (String rawToken : tokens) {
        String token = rawToken.trim();
        if (token.isEmpty()) {
          continue;
        }

        Sort.Direction direction = Sort.Direction.ASC;
        if (token.startsWith("-")) {
          direction = Sort.Direction.DESC;
          token = token.substring(1);
        } else if (token.startsWith("+")) {
          token = token.substring(1);
        }

        String property = token.trim();
        if (!StringUtils.hasText(property)) {
          continue;
        }
        validateProperty(property);
        orders.add(new Sort.Order(direction, property));
      }
    }

    return orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
  }

  private void validateProperty(String property) {
    if (!allowNestedProperties && property.contains(".")) {
      throw new IllegalArgumentException("Nested sort properties are not allowed: " + property);
    }
    if (!allowlist.isEmpty() && !allowlist.contains(property)) {
      throw new IllegalArgumentException("Sort property is not allowed: " + property);
    }
  }
}
