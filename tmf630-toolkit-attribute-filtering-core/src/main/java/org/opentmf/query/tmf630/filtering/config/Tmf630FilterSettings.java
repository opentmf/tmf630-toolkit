package org.opentmf.query.tmf630.filtering.config;

public record Tmf630FilterSettings(
    boolean implicitEqEnabled,
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
