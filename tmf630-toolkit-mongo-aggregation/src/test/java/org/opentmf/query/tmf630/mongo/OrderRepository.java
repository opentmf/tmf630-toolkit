package org.opentmf.query.tmf630.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

interface OrderRepository extends MongoRepository<Order, String> {}
