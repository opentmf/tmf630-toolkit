package org.opentmf.query.tmf630.filtering;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.opentmf.query.tmf630.filtering.config.AllowlistMode;
import org.opentmf.query.tmf630.filtering.config.CombineMode;
import org.opentmf.query.tmf630.filtering.config.PredicateLimits;
import org.opentmf.query.tmf630.filtering.config.Tmf630FilterSettings;
import org.opentmf.query.tmf630.filtering.config.UnknownParamBehavior;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opentmf.tmf630.attribute-filtering")
public class Tmf630AttributeFilteringProperties {

  private boolean enabled = true;
  private boolean implicitEqEnabled = true;
  private CombineMode combineRepeatedValues = CombineMode.OR;
  private boolean allowNestedPathsJpa = false;
  private boolean allowNestedPathsDocdb = true;
  private Regex regex = new Regex();
  private JsonPathFilter jsonPathFilter = new JsonPathFilter();
  private Limits limits = new Limits();
  private Allowlist allowlist = new Allowlist();
  private UnknownParamBehavior onUnknownField = UnknownParamBehavior.REJECT;
  private UnknownParamBehavior onUnknownOperator = UnknownParamBehavior.REJECT;

  public Tmf630FilterSettings toSettings() {
    return new Tmf630FilterSettings(
        implicitEqEnabled,
        combineRepeatedValues,
        allowNestedPathsJpa,
        allowNestedPathsDocdb,
        regex.enabled,
        new PredicateLimits(limits.maxClauses, limits.maxValuesPerKey, regex.maxLength),
        allowlist.mode,
        onUnknownField,
        onUnknownOperator,
        jsonPathFilter.enabled,
        jsonPathFilter.maxLength);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isImplicitEqEnabled() {
    return implicitEqEnabled;
  }

  public void setImplicitEqEnabled(boolean implicitEqEnabled) {
    this.implicitEqEnabled = implicitEqEnabled;
  }

  public CombineMode getCombineRepeatedValues() {
    return combineRepeatedValues;
  }

  public void setCombineRepeatedValues(CombineMode combineRepeatedValues) {
    this.combineRepeatedValues = combineRepeatedValues;
  }

  public boolean isAllowNestedPathsJpa() {
    return allowNestedPathsJpa;
  }

  public void setAllowNestedPathsJpa(boolean allowNestedPathsJpa) {
    this.allowNestedPathsJpa = allowNestedPathsJpa;
  }

  public boolean isAllowNestedPathsDocdb() {
    return allowNestedPathsDocdb;
  }

  public void setAllowNestedPathsDocdb(boolean allowNestedPathsDocdb) {
    this.allowNestedPathsDocdb = allowNestedPathsDocdb;
  }

  public Regex getRegex() {
    return regex;
  }

  public void setRegex(Regex regex) {
    this.regex = regex;
  }

  public Limits getLimits() {
    return limits;
  }

  public void setLimits(Limits limits) {
    this.limits = limits;
  }

  public JsonPathFilter getJsonPathFilter() {
    return jsonPathFilter;
  }

  public void setJsonPathFilter(JsonPathFilter jsonPathFilter) {
    this.jsonPathFilter = jsonPathFilter;
  }

  public Allowlist getAllowlist() {
    return allowlist;
  }

  public void setAllowlist(Allowlist allowlist) {
    this.allowlist = allowlist;
  }

  public UnknownParamBehavior getOnUnknownField() {
    return onUnknownField;
  }

  public void setOnUnknownField(UnknownParamBehavior onUnknownField) {
    this.onUnknownField = onUnknownField;
  }

  public UnknownParamBehavior getOnUnknownOperator() {
    return onUnknownOperator;
  }

  public void setOnUnknownOperator(UnknownParamBehavior onUnknownOperator) {
    this.onUnknownOperator = onUnknownOperator;
  }

  public static class Regex {
    private boolean enabled = false;
    private int maxLength = 256;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getMaxLength() {
      return maxLength;
    }

    public void setMaxLength(int maxLength) {
      this.maxLength = maxLength;
    }
  }

  public static class Limits {
    private int maxClauses = 50;
    private int maxValuesPerKey = 20;

    public int getMaxClauses() {
      return maxClauses;
    }

    public void setMaxClauses(int maxClauses) {
      this.maxClauses = maxClauses;
    }

    public int getMaxValuesPerKey() {
      return maxValuesPerKey;
    }

    public void setMaxValuesPerKey(int maxValuesPerKey) {
      this.maxValuesPerKey = maxValuesPerKey;
    }
  }

  public static class JsonPathFilter {
    private boolean enabled = true;
    private int maxLength = 2048;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public int getMaxLength() {
      return maxLength;
    }

    public void setMaxLength(int maxLength) {
      this.maxLength = maxLength;
    }
  }

  public static class Allowlist {
    private AllowlistMode mode = AllowlistMode.ALLOW_ALL;
    private Map<String, List<String>> entities = new HashMap<>();

    public AllowlistMode getMode() {
      return mode;
    }

    public void setMode(AllowlistMode mode) {
      this.mode = mode;
    }

    public Map<String, List<String>> getEntities() {
      return entities;
    }

    public void setEntities(Map<String, List<String>> entities) {
      this.entities = entities;
    }
  }
}
