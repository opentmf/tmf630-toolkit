package org.opentmf.query.tmf630.mongo;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;

@AutoConfiguration(
    afterName = "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration")
@ConditionalOnClass(MongoTemplate.class)
@ConditionalOnBean(MongoTemplate.class)
@EnableConfigurationProperties(Tmf630MongoAggregationProperties.class)
public class Tmf630MongoAggregationAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public Tmf630MongoCorrelatedSortExecutor tmf630MongoCorrelatedSortExecutor(
      MongoTemplate mongoTemplate, Tmf630MongoAggregationProperties properties) {
    return new Tmf630MongoCorrelatedSortExecutor(
        mongoTemplate, properties.getSimpleRich().getDefaultKey());
  }
}
