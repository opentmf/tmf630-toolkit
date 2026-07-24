package org.opentmf.query.tmf630.paging;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.opentmf.query.tmf630.exception.TmfPagingException;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;

public class TmfSortParser {

  private final Set<String> allowlist;
  private final boolean allowNestedProperties;

  public TmfSortParser(Collection<String> allowlist, boolean allowNestedProperties) {
    this.allowlist = allowlist == null ? Set.of() : new HashSet<>(allowlist);
    this.allowNestedProperties = allowNestedProperties;
  }

  public Sort parse(List<String> sortParams) {
    return parseInternal(sortParams, false).toPlainSort();
  }

  public TmfSort parseRich(List<String> sortParams) {
    return parseInternal(sortParams, true);
  }

  private TmfSort parseInternal(List<String> sortParams, boolean acceptCorrelated) {
    if (sortParams == null || sortParams.isEmpty()) {
      return TmfSort.empty();
    }

    List<TmfSortTerm> termList = new ArrayList<>();
    for (String sortParam : sortParams) {
      if (!StringUtils.hasText(sortParam)) {
        continue;
      }
      for (String rawToken : splitTopLevel(sortParam)) {
        TmfSortTerm term = parseToken(rawToken, acceptCorrelated);
        if (term != null) {
          termList.add(term);
        }
      }
    }

    return new TmfSort(termList);
  }

  private TmfSortTerm parseToken(String rawToken, boolean acceptCorrelated) {
    String token = rawToken.trim();
    if (token.isEmpty()) {
      return null;
    }

    Sort.Direction direction = Sort.Direction.ASC;
    if (token.startsWith("-")) {
      direction = Sort.Direction.DESC;
      token = token.substring(1);
    } else if (token.startsWith("+")) {
      token = token.substring(1);
    }

    String expression = token.trim();
    if (!StringUtils.hasText(expression)) {
      return null;
    }

    TmfSortTerm.Kind kind = classify(expression);
    if (kind != TmfSortTerm.Kind.PLAIN && !acceptCorrelated) {
      throw new TmfPagingException(
          "Correlated sort terms ("
              + kind.name().toLowerCase().replace('_', '-')
              + ") are not supported in this context: "
              + expression);
    }

    if (kind == TmfSortTerm.Kind.PLAIN) {
      validateProperty(expression);
    }
    return new TmfSortTerm(direction, kind, expression);
  }

  private void validateProperty(String property) {
    if (!allowNestedProperties && property.contains(".")) {
      throw new TmfPagingException("Nested sort properties are not allowed: " + property);
    }
    if (!allowlist.isEmpty() && !allowlist.contains(property)) {
      throw new TmfPagingException("Sort property is not allowed: " + property);
    }
  }

  static List<String> splitTopLevel(String input) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    boolean inQuotes = false;
    int start = 0;
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (inQuotes) {
        if (c == '\'') {
          inQuotes = false;
        }
        continue;
      }
      switch (c) {
        case '\'' -> inQuotes = true;
        case '[', '(' -> depth++;
        case ']', ')' -> {
          if (depth > 0) {
            depth--;
          }
        }
        case ',' -> {
          if (depth == 0) {
            parts.add(input.substring(start, i));
            start = i + 1;
          }
        }
        default -> {
          // no-op
        }
      }
    }
    parts.add(input.substring(start));
    return parts;
  }

  static TmfSortTerm.Kind classify(String token) {
    if (token.startsWith("$.")) {
      return TmfSortTerm.Kind.JSONPATH;
    }
    // The leading `$.` is optional per the TMF630 recommendation. JsonPath-specific
    // constructs anywhere in the term — `[?(...)]` predicates or `[*]` projection —
    // unambiguously identify a JSONPATH expression even without the prefix.
    if (token.contains("[?(") || token.contains("[*]")) {
      return TmfSortTerm.Kind.JSONPATH;
    }
    if (token.indexOf('[') >= 0) {
      return TmfSortTerm.Kind.SIMPLE_RICH;
    }
    return TmfSortTerm.Kind.PLAIN;
  }
}
