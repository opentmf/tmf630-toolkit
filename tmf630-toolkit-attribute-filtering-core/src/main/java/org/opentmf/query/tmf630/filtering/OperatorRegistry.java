package org.opentmf.query.tmf630.filtering;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class OperatorRegistry {

  private final Map<String, TmfOperator> bySuffix;

  public OperatorRegistry() {
    Map<String, TmfOperator> mapping = new HashMap<>();
    for (TmfOperator operator : TmfOperator.values()) {
      mapping.put(operator.suffix(), operator);
    }
    this.bySuffix = Collections.unmodifiableMap(mapping);
  }

  public Optional<TmfOperator> resolveSuffix(String suffix) {
    return Optional.ofNullable(bySuffix.get(suffix));
  }

  public boolean isRegistered(String suffix) {
    return bySuffix.containsKey(suffix);
  }
}
