package org.opentmf.query.tmf630.filtering;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PropertyFieldAllowlistProvider implements FieldAllowlistProvider {

  private final Map<String, List<String>> entityAllowlist;

  public PropertyFieldAllowlistProvider(Map<String, List<String>> entityAllowlist) {
    this.entityAllowlist = entityAllowlist;
  }

  @Override
  public Set<String> allowedFields(Class<?> rootEntity) {
    return new HashSet<>(
        entityAllowlist.getOrDefault(rootEntity.getSimpleName(), Collections.emptyList()));
  }
}
