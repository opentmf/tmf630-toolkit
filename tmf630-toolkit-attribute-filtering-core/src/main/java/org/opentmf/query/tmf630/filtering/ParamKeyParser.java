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
      Optional<TmfOperator> resolvedOperator = operatorRegistry.resolveSuffix(suffix);
      if (resolvedOperator.isPresent()) {
        return Optional.of(new ParsedParamKey(fieldPath, resolvedOperator.get(), false));
      }
      if (!implicitEqEnabled) {
        return Optional.empty();
      }
      // For dotted keys without an explicit known operator suffix, fallback to implicit EQ
      // and treat the complete key as the field path.
      return Optional.of(new ParsedParamKey(rawKey, TmfOperator.EQ, true));
    }

    if (!implicitEqEnabled) {
      return Optional.empty();
    }
    return Optional.of(new ParsedParamKey(rawKey, TmfOperator.EQ, true));
  }
}
