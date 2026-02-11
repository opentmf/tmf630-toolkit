package org.opentmf.query.tmf630.filtering;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

interface MongoSearchRepository
    extends MongoRepository<MongoSearchEntity, String>, QuerydslPredicateExecutor<MongoSearchEntity> {}
