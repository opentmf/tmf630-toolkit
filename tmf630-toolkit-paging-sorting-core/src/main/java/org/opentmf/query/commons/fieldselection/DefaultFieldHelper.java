package org.opentmf.query.commons.fieldselection;

import java.beans.PropertyDescriptor;

public class DefaultFieldHelper implements FieldHelper {
  @Override
  public boolean isEmbeddedId(Class<?> clazz, PropertyDescriptor pd) {
    return false;
  }
}
