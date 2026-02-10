package org.opentmf.query.commons.fieldselection;

import jakarta.persistence.EmbeddedId;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;

public class PersistentFieldHelper implements FieldHelper {

  @Override
  public boolean isEmbeddedId(Class<?> clazz, PropertyDescriptor pd) {
    if (pd.getReadMethod().getAnnotation(EmbeddedId.class) != null) {
      return true;
    }
    try {
      Field field = clazz.getDeclaredField(pd.getName());
      return field.getAnnotation(EmbeddedId.class) != null;
    } catch (NoSuchFieldException e) {
      return false;
    }
  }
}
