package org.opentmf.query.tmf630.mongo;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "opentmf.tmf630.mongo-aggregation")
public class Tmf630MongoAggregationProperties {

  private SimpleRich simpleRich = new SimpleRich();

  public SimpleRich getSimpleRich() {
    return simpleRich;
  }

  public void setSimpleRich(SimpleRich simpleRich) {
    this.simpleRich = simpleRich;
  }

  public static class SimpleRich {

    private String defaultKey = "id";

    public String getDefaultKey() {
      return defaultKey;
    }

    public void setDefaultKey(String defaultKey) {
      this.defaultKey = defaultKey;
    }
  }
}
