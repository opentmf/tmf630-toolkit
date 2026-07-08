package org.opentmf.query.tmf630.filtering.config;

public record Tmf630FilterSettings(
    boolean implicitEqEnabled,
    boolean implicitEqCsvOr,
    boolean implicitEqSemicolonOr,
    CombineMode combineRepeatedValues,
    boolean allowNestedPathsJpa,
    boolean allowNestedPathsDocdb,
    boolean regexEnabled,
    PredicateLimits limits,
    AllowlistMode allowlistMode,
    UnknownParamBehavior onUnknownField,
    UnknownParamBehavior onUnknownOperator,
    boolean jsonPathFilterEnabled,
    int jsonPathMaxLength,
    UnknownParamBehavior onUnknownJsonPathField,
    IsnullSemantics isnullSemantics) {

  /**
   * Back-compat constructor without {@link IsnullSemantics} — used by callers that predate the
   * 2.1.4 nullish toggle. Defaults to {@link IsnullSemantics#MISSING_ONLY}, which preserves the
   * exact pre-toggle IS_NULL/IS_NOT_NULL behavior (Mongo {@code $exists:false}, JPA
   * {@code IS NULL}).
   */
  public Tmf630FilterSettings(
      boolean implicitEqEnabled,
      boolean implicitEqCsvOr,
      boolean implicitEqSemicolonOr,
      CombineMode combineRepeatedValues,
      boolean allowNestedPathsJpa,
      boolean allowNestedPathsDocdb,
      boolean regexEnabled,
      PredicateLimits limits,
      AllowlistMode allowlistMode,
      UnknownParamBehavior onUnknownField,
      UnknownParamBehavior onUnknownOperator,
      boolean jsonPathFilterEnabled,
      int jsonPathMaxLength,
      UnknownParamBehavior onUnknownJsonPathField) {
    this(
        implicitEqEnabled,
        implicitEqCsvOr,
        implicitEqSemicolonOr,
        combineRepeatedValues,
        allowNestedPathsJpa,
        allowNestedPathsDocdb,
        regexEnabled,
        limits,
        allowlistMode,
        onUnknownField,
        onUnknownOperator,
        jsonPathFilterEnabled,
        jsonPathMaxLength,
        onUnknownJsonPathField,
        IsnullSemantics.MISSING_ONLY);
  }

  public boolean allowNestedPathsFor(Class<?> rootEntity) {
    if (hasAnnotation(rootEntity, "jakarta.persistence.Entity")
        || hasAnnotation(rootEntity, "javax.persistence.Entity")) {
      return allowNestedPathsJpa;
    }
    if (hasAnnotation(rootEntity, "org.springframework.data.mongodb.core.mapping.Document")) {
      return allowNestedPathsDocdb;
    }
    // Conservative fallback for unknown/neutral root types.
    return allowNestedPathsJpa;
  }

  private static boolean hasAnnotation(Class<?> type, String annotationTypeName) {
    return java.util.Arrays.stream(type.getAnnotations())
        .anyMatch(annotation -> annotation.annotationType().getName().equals(annotationTypeName));
  }
}
