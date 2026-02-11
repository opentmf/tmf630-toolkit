package org.opentmf.query.tmf630.filtering.config;

public record Tmf630FilterSettings(
    boolean implicitEqEnabled,
    CombineMode combineRepeatedValues,
    boolean allowNestedPaths,
    boolean regexEnabled,
    PredicateLimits limits,
    AllowlistMode allowlistMode,
    UnknownParamBehavior onUnknownField,
    UnknownParamBehavior onUnknownOperator,
    boolean jsonPathFilterEnabled,
    int jsonPathMaxLength) {}
