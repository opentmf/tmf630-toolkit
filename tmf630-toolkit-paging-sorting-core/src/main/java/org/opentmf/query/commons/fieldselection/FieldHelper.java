package org.opentmf.query.commons.fieldselection;

import java.beans.PropertyDescriptor;

public interface FieldHelper {
  boolean isEmbeddedId(Class<?> clazz, PropertyDescriptor pd);
}
