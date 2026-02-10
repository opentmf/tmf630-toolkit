package org.opentmf.query.commons.fieldselection;

public final class FieldHelperProvider {

  private static final FieldHelper FIELD_HELPER;

  static {
    FieldHelper helper;
    try {
      Class.forName("jakarta.persistence.Entity", false, FieldHelperProvider.class.getClassLoader());
      helper = new PersistentFieldHelper();
    } catch (ClassNotFoundException e) {
      helper = new DefaultFieldHelper();
    }
    FIELD_HELPER = helper;
  }

  private FieldHelperProvider() {}

  public static FieldHelper getFieldHelper() {
    return FIELD_HELPER;
  }
}
