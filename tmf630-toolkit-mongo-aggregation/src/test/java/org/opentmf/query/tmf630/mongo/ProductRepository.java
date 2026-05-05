package org.opentmf.query.tmf630.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

interface ProductRepository extends MongoRepository<Product, String> {}
