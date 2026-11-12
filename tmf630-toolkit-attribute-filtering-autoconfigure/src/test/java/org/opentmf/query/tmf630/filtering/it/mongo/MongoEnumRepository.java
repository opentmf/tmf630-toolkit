package org.opentmf.query.tmf630.filtering.it.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

public interface MongoEnumRepository
    extends MongoRepository<MongoEnumEntity, String>, QuerydslPredicateExecutor<MongoEnumEntity> {}
