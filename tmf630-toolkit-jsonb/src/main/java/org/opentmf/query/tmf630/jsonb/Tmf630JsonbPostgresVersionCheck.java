package org.opentmf.query.tmf630.jsonb;

import java.util.function.IntSupplier;
import org.springframework.beans.factory.InitializingBean;

/**
 * Refuses to start the JSONB module on a PostgreSQL older than 16: filters and sorts on date and
 * time fields read stored text with {@code pg_input_is_valid} (PostgreSQL 16), so that a value no
 * cast reads is no match instead of an error that fails the whole query. Below 16 that function
 * does not exist; rather than fall back silently to a plain cast, the application does not start,
 * and says why.
 *
 * <p>The check is replaceable (it is an ordinary bean), the requirement is not: an application that
 * replaces it and runs below 16 starts, and then every filter and sort on a date or time field
 * fails with "function pg_input_is_valid does not exist".
 */
public class Tmf630JsonbPostgresVersionCheck implements InitializingBean {

  /** {@code server_version_num} of PostgreSQL 16.0. */
  public static final int MINIMUM_SERVER_VERSION_NUM = 160_000;

  private final IntSupplier serverVersionNum;

  /** A check of {@code serverVersionNum}, PostgreSQL's {@code server_version_num} setting. */
  public Tmf630JsonbPostgresVersionCheck(IntSupplier serverVersionNum) {
    this.serverVersionNum = serverVersionNum;
  }

  @Override
  public void afterPropertiesSet() {
    int version = serverVersionNum.getAsInt();
    if (version < MINIMUM_SERVER_VERSION_NUM) {
      throw new Tmf630JsonbConfigurationException(
          "tmf630-toolkit-jsonb needs PostgreSQL 16 or later: filters and sorts on date and time"
              + " fields read stored text with pg_input_is_valid (PostgreSQL 16), so a value no"
              + " cast reads is no match instead of an error; this server reports"
              + " server_version_num "
              + version
              + ".");
    }
  }
}
