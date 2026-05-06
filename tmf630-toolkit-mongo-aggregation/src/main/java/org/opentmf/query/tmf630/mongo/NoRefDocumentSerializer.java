package org.opentmf.query.tmf630.mongo;

import com.mongodb.DBRef;
import com.querydsl.core.types.Path;
import com.querydsl.core.types.PathMetadata;
import com.querydsl.mongodb.document.MongodbDocumentSerializer;
import org.jspecify.annotations.Nullable;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.mongodb.core.mapping.MongoPersistentEntity;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;

final class NoRefDocumentSerializer extends MongodbDocumentSerializer {

  private final @Nullable
      MappingContext<? extends MongoPersistentEntity<?>, MongoPersistentProperty> mappingContext;

  NoRefDocumentSerializer() {
    this(null);
  }

  NoRefDocumentSerializer(
      @Nullable MappingContext<? extends MongoPersistentEntity<?>, MongoPersistentProperty>
          mappingContext) {
    this.mappingContext = mappingContext;
  }

  @Override
  protected DBRef asReference(Object value) {
    throw new UnsupportedOperationException("DBRef references are not supported");
  }

  @Override
  protected boolean isReference(Path<?> path) {
    return false;
  }

  @Override
  protected String getKeyForPath(Path<?> expr, PathMetadata metadata) {
    if (mappingContext != null && metadata.getParent() != null) {
      Class<?> parentType = metadata.getParent().getType();
      MongoPersistentEntity<?> parentEntity = mappingContext.getPersistentEntity(parentType);
      if (parentEntity != null) {
        String javaName = metadata.getElement().toString();
        MongoPersistentProperty property = parentEntity.getPersistentProperty(javaName);
        if (property != null) {
          return property.getFieldName();
        }
      }
    }
    return super.getKeyForPath(expr, metadata);
  }
}
