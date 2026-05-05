package org.opentmf.query.tmf630.mongo;

import com.mongodb.DBRef;
import com.querydsl.core.types.Path;
import com.querydsl.mongodb.document.MongodbDocumentSerializer;

final class NoRefDocumentSerializer extends MongodbDocumentSerializer {

  @Override
  protected DBRef asReference(Object value) {
    throw new UnsupportedOperationException("DBRef references are not supported");
  }

  @Override
  protected boolean isReference(Path<?> path) {
    return false;
  }
}
