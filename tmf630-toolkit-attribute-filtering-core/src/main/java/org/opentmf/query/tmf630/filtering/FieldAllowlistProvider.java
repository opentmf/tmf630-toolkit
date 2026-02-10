package org.opentmf.query.tmf630.filtering;

import java.util.Set;

public interface FieldAllowlistProvider {
  Set<String> allowedFields(Class<?> rootEntity);
}
