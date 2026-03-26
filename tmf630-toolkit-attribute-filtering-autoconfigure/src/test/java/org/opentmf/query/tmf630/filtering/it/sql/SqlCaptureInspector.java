package org.opentmf.query.tmf630.filtering.it.sql;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hibernate.resource.jdbc.spi.StatementInspector;

public class SqlCaptureInspector implements StatementInspector {

  private static final List<String> QUERIES = Collections.synchronizedList(new ArrayList<>());

  @Override
  public String inspect(String sql) {
    QUERIES.add(sql);
    return sql;
  }

  public static void clear() {
    QUERIES.clear();
  }

  public static List<String> snapshot() {
    synchronized (QUERIES) {
      return List.copyOf(QUERIES);
    }
  }
}
