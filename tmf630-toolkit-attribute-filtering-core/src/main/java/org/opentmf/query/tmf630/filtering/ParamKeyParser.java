package org.opentmf.query.tmf630.filtering;

import java.util.Optional;

public class ParamKeyParser {

  private final OperatorRegistry operatorRegistry;
  private final boolean implicitEqEnabled;

  public ParamKeyParser(OperatorRegistry operatorRegistry, boolean implicitEqEnabled) {
    this.operatorRegistry = operatorRegistry;
    this.implicitEqEnabled = implicitEqEnabled;
  }

  public Optional<ParsedParamKey> parse(String rawKey) {
    if (rawKey == null || rawKey.isBlank()) {
      return Optional.empty();
    }

    int separatorIndex = rawKey.lastIndexOf('.');
    if (separatorIndex > 0 && separatorIndex < rawKey.length() - 1) {
      String fieldPath = rawKey.substring(0, separatorIndex);
      String suffix = rawKey.substring(separatorIndex + 1);
      return operatorRegistry.resolveSuffix(suffix).map(op -> new ParsedParamKey(fieldPath, op));
    }

    if (!implicitEqEnabled) {
      return Optional.empty();
    }
    return Optional.of(new ParsedParamKey(rawKey, TmfOperator.EQ));
  }
}
