package org.opentmf.query.tmf630.paging;

import org.opentmf.query.tmf630.exception.TmfPagingException;
import org.opentmf.query.tmf630.paging.config.Tmf630PagingSettings;
import org.springframework.util.StringUtils;

final class OffsetLimitParser {

  private static final String OFFSET = "offset";
  private static final String LIMIT = "limit";

  private OffsetLimitParser() {}

  static long parseOffset(String raw, Tmf630PagingSettings settings) {
    if (!StringUtils.hasText(raw)) {
      return 0L;
    }
    try {
      long value = Long.parseLong(raw);
      if (value < 0) {
        throw new TmfPagingException(OFFSET + " must be >= 0");
      }
      return value;
    } catch (NumberFormatException e) {
      if (settings.strictMode()) {
        throw new TmfPagingException(OFFSET + " must be numeric", e);
      }
      return 0L;
    }
  }

  static int parseLimit(String raw, Tmf630PagingSettings settings) {
    int limit = settings.defaultLimit();
    if (StringUtils.hasText(raw)) {
      try {
        int value = Integer.parseInt(raw);
        if (value <= 0) {
          throw new TmfPagingException(LIMIT + " must be > 0");
        }
        limit = value;
      } catch (NumberFormatException e) {
        if (settings.strictMode()) {
          throw new TmfPagingException(LIMIT + " must be numeric", e);
        }
      }
    }
    limit = Math.min(limit, settings.maxLimit());
    if (limit <= 0) {
      throw new TmfPagingException(LIMIT + " must be > 0");
    }
    return limit;
  }
}
