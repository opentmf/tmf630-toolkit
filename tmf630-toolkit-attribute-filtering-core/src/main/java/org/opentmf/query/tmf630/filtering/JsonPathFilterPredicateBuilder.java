package org.opentmf.query.tmf630.filtering;

import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Operator;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.PathBuilder;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.opentmf.query.tmf630.filtering.config.AllowlistMode;
import org.opentmf.query.tmf630.filtering.config.Tmf630FilterSettings;
import org.opentmf.query.tmf630.filtering.config.UnknownParamBehavior;
import org.opentmf.query.tmf630.filtering.predicate.FieldPathResolver;
import org.opentmf.query.tmf630.filtering.predicate.PredicateFactory;
import org.opentmf.query.tmf630.filtering.predicate.ResolvedField;
import org.opentmf.query.tmf630.filtering.predicate.ValueConverter;

/**
 * Builds QueryDSL predicates from restricted JsonPath filter expressions.
 *
 * <p>Supported subset:
 *
 * <ul>
 *   <li>Wrapper: {@code $[?( ... )]}
 *   <li>Logical operators: {@code &&}, {@code ||}
 *   <li>Grouping with parentheses
 *   <li>Comparisons: {@code ==}, {@code !=}, {@code >}, {@code >=}, {@code <}, {@code <=}
 *   <li>Field paths in comparison left-hand side: {@code @.field} or {@code @.nested.field}
 *   <li>Literals: single/double quoted strings, numbers, booleans, and {@code null}
 * </ul>
 */
public class JsonPathFilterPredicateBuilder {

  private static final Pattern FILTER_WRAPPER =
      Pattern.compile("^\\s*\\$\\s*\\[\\s*\\?\\s*\\((.*)\\)\\s*]\\s*$", Pattern.DOTALL);

  private final FieldPathResolver pathResolver;
  private final ValueConverter valueConverter;
  private final PredicateFactory predicateFactory;

  public JsonPathFilterPredicateBuilder(
      FieldPathResolver pathResolver, ValueConverter valueConverter, PredicateFactory predicateFactory) {
    this.pathResolver = pathResolver;
    this.valueConverter = valueConverter;
    this.predicateFactory = predicateFactory;
  }

  public Predicate build(
      Class<?> rootEntity,
      PathBuilder<?> rootPath,
      String expression,
      Set<String> allowlist,
      Tmf630FilterSettings settings) {
    return build(
        rootEntity,
        rootPath,
        expression,
        allowlist,
        settings,
        settings.allowNestedPathsFor(rootEntity));
  }

  public Predicate build(
      Class<?> rootEntity,
      PathBuilder<?> rootPath,
      String expression,
      Set<String> allowlist,
      Tmf630FilterSettings settings,
      boolean allowNestedPaths) {
    if (!settings.jsonPathFilterEnabled()) {
      throw new TmfFilteringException("jsonPath filter parameter is disabled.");
    }
    if (expression == null || expression.isBlank()) {
      return null;
    }
    if (expression.length() > settings.jsonPathMaxLength()) {
      throw new TmfFilteringException("jsonPath filter expression is too long.");
    }

    validateAsJsonPath(expression);
    String inner = unwrapFilterExpression(expression);

    Parser parser = new Parser(inner);
    Node rootNode = parser.parseExpression();
    parser.ensureEnd();

    return toPredicate(rootNode, rootEntity, rootPath, allowlist, settings, "", allowNestedPaths)
        .orElse(null);
  }

  private void validateAsJsonPath(String expression) {
    try {
      JsonPath.compile(expression);
    } catch (InvalidPathException ex) {
      throw new TmfFilteringException("jsonPath expression is invalid.", ex);
    }
  }

  private String unwrapFilterExpression(String expression) {
    Matcher matcher = FILTER_WRAPPER.matcher(expression);
    if (!matcher.matches()) {
      throw new TmfFilteringException("jsonPath expression must be a filter expression: $[?(...)].");
    }
    return matcher.group(1);
  }

  private Optional<Predicate> toPredicate(
      Node node,
      Class<?> rootEntity,
      PathBuilder<?> rootPath,
      Set<String> allowlist,
      Tmf630FilterSettings settings,
      String allowlistPrefix,
      boolean allowNestedPaths) {
    if (node instanceof LogicalNode logical) {
      Optional<Predicate> left =
          toPredicate(
              logical.left(),
              rootEntity,
              rootPath,
              allowlist,
              settings,
              allowlistPrefix,
              allowNestedPaths);
      Optional<Predicate> right =
          toPredicate(
              logical.right(),
              rootEntity,
              rootPath,
              allowlist,
              settings,
              allowlistPrefix,
              allowNestedPaths);
      if (left.isEmpty()) {
        return right;
      }
      if (right.isEmpty()) {
        return left;
      }

      BooleanBuilder builder = new BooleanBuilder();
      if (logical.operator() == LogicalOperator.AND) {
        builder.and(left.get()).and(right.get());
      } else {
        builder.or(left.get()).or(right.get());
      }
      return Optional.of(builder);
    }

    if (node instanceof ArrayMatchNode arrayMatchNode) {
      if (isJpaEntity(rootEntity)) {
        throw new TmfFilteringException(
            "Array correlation in jsonPath filter is supported only for document databases.");
      }
      ResolvedArrayPath resolvedArrayPath =
          resolveArrayPath(rootEntity, rootPath, arrayMatchNode.arrayPath(), allowNestedPaths);
      String nestedAllowlistPrefix =
          allowlistPrefix + normalizeArrayPathForAllowlist(arrayMatchNode.arrayPath()) + ".";
      PathBuilder<?> elementRootPath = pathResolver.createRootPath(resolvedArrayPath.elementType());
      Optional<Predicate> nested =
          toPredicate(
              arrayMatchNode.inner(),
              resolvedArrayPath.elementType(),
              elementRootPath,
              allowlist,
              settings,
              nestedAllowlistPrefix,
              allowNestedPaths);
      if (nested.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(buildElemMatchPredicate(resolvedArrayPath.collectionPath(), nested.get()));
    }

    if (!(node instanceof ComparisonNode comparison)) {
      throw new TmfFilteringException("Unsupported jsonPath filter expression.");
    }

    String effectiveFieldPath = allowlistPrefix + comparison.fieldPath();
    if (!isAllowedField(effectiveFieldPath, allowlist, settings.allowlistMode())) {
      if (settings.onUnknownField() == UnknownParamBehavior.REJECT) {
        throw new TmfFilteringException("Unknown or disallowed field: " + effectiveFieldPath);
      }
      return Optional.empty();
    }

    final ResolvedField resolvedField;
    try {
      resolvedField = pathResolver.resolve(rootEntity, comparison.fieldPath(), allowNestedPaths);
    } catch (TmfFilteringException ex) {
      if (settings.onUnknownField() == UnknownParamBehavior.REJECT) {
        throw ex;
      }
      return Optional.empty();
    }

    if (comparison.literal().kind() == LiteralKind.NULL) {
      if (comparison.operator() == ComparisonOperator.EQ) {
        return Optional.of(
            predicateFactory.buildNoValue(rootPath, resolvedField, TmfOperator.IS_NULL));
      }
      if (comparison.operator() == ComparisonOperator.NE) {
        return Optional.of(
            predicateFactory.buildNoValue(rootPath, resolvedField, TmfOperator.IS_NOT_NULL));
      }
      throw new TmfFilteringException("Null literal only supports == and != operators.");
    }

    Object typedValue =
        valueConverter.convert(comparison.literal().valueAsString(), resolvedField.javaType());

    TmfOperator tmfOperator =
        switch (comparison.operator()) {
          case EQ -> TmfOperator.EQ;
          case NE -> TmfOperator.NE;
          case GT -> TmfOperator.GT;
          case GTE -> TmfOperator.GTE;
          case LT -> TmfOperator.LT;
          case LTE -> TmfOperator.LTE;
        };

    return Optional.of(predicateFactory.build(rootPath, resolvedField, tmfOperator, typedValue));
  }

  private boolean isAllowedField(String fieldPath, Set<String> allowlist, AllowlistMode mode) {
    if (mode == AllowlistMode.ALLOW_ALL) {
      return allowlist.isEmpty() || allowlist.contains(fieldPath);
    }
    return allowlist.contains(fieldPath);
  }

  private boolean isJpaEntity(Class<?> type) {
    for (Annotation annotation : type.getAnnotations()) {
      if ("jakarta.persistence.Entity".equals(annotation.annotationType().getName())) {
        return true;
      }
    }
    return false;
  }

  private String normalizeArrayPathForAllowlist(String arrayPath) {
    return arrayPath.replace("[*]", "");
  }

  private ResolvedArrayPath resolveArrayPath(
      Class<?> rootEntity, PathBuilder<?> rootPath, String arrayPath, boolean allowNestedPaths) {
    String normalized = normalizeArrayPathForAllowlist(arrayPath);
    if (!allowNestedPaths && normalized.contains(".")) {
      throw new TmfFilteringException("Nested field paths are disabled: " + normalized);
    }
    if (normalized.isBlank()) {
      throw new TmfFilteringException("Array path must not be blank in jsonPath filter.");
    }

    PathBuilder<?> currentPath = rootPath;
    Class<?> currentType = rootEntity;
    String[] segments = normalized.split("\\.");
    for (int i = 0; i < segments.length; i++) {
      String segment = segments[i];
      Field field = findField(currentType, segment);
      if (field == null) {
        throw new TmfFilteringException("Unknown field path: " + normalized);
      }

      Class<?> fieldType = field.getType();
      boolean last = i == segments.length - 1;
      if (Collection.class.isAssignableFrom(fieldType)) {
        Class<?> elementType = resolveCollectionElementType(field);
        Expression<?> collectionPath = currentPath.get(segment, fieldType);
        PathBuilder<?> elementPath = currentPath.getCollection(segment, elementType).any();
        if (last) {
          return new ResolvedArrayPath(elementType, collectionPath);
        }
        currentType = elementType;
        currentPath = elementPath;
      } else {
        if (last) {
          throw new TmfFilteringException(
              "Array match requires a collection path but found scalar path: " + normalized);
        }
        currentType = fieldType;
        currentPath = currentPath.get(segment, fieldType);
      }
    }
    throw new TmfFilteringException("Invalid array path in jsonPath filter: " + normalized);
  }

  private Class<?> resolveCollectionElementType(Field field) {
    Type genericType = field.getGenericType();
    if (genericType instanceof ParameterizedType parameterizedType) {
      Type[] args = parameterizedType.getActualTypeArguments();
      if (args.length == 1 && args[0] instanceof Class<?> elementType) {
        return elementType;
      }
    }
    return Object.class;
  }

  private Field findField(Class<?> type, String name) {
    Class<?> cursor = type;
    while (cursor != null && cursor != Object.class) {
      try {
        return cursor.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        cursor = cursor.getSuperclass();
      }
    }
    return null;
  }

  private Predicate buildElemMatchPredicate(Expression<?> collectionPath, Predicate nestedPredicate) {
    return Expressions.predicate(resolveElemMatchOperator(), collectionPath, nestedPredicate);
  }

  private Operator resolveElemMatchOperator() {
    try {
      Class<?> mongoOpsClass = Class.forName("com.querydsl.mongodb.MongodbOps");
      @SuppressWarnings({"rawtypes", "unchecked"})
      Enum<?> elemMatch = Enum.valueOf((Class<? extends Enum>) mongoOpsClass, "ELEM_MATCH");
      return (Operator) elemMatch;
    } catch (ClassNotFoundException ex) {
      throw new TmfFilteringException(
          "Array correlation requires querydsl-mongodb on the classpath.", ex);
    }
  }

  private interface Node {}

  private record LogicalNode(Node left, LogicalOperator operator, Node right) implements Node {}

  private record ArrayMatchNode(String arrayPath, Node inner) implements Node {}

  private record ResolvedArrayPath(Class<?> elementType, Expression<?> collectionPath) {}

  private enum LogicalOperator {
    AND,
    OR
  }

  private record ComparisonNode(
      String fieldPath, ComparisonOperator operator, LiteralToken literal) implements Node {}

  private enum ComparisonOperator {
    EQ,
    NE,
    GT,
    GTE,
    LT,
    LTE
  }

  private enum LiteralKind {
    STRING,
    NUMBER,
    BOOLEAN,
    NULL
  }

  private record LiteralToken(LiteralKind kind, String rawValue) {
    String valueAsString() {
      return rawValue;
    }
  }

  private static final class Parser {
    private final List<Token> tokens;
    private int index;

    private Parser(String expression) {
      this.tokens = tokenize(expression);
      this.index = 0;
    }

    private Node parseExpression() {
      return parseOr();
    }

    private Node parseOr() {
      Node left = parseAnd();
      while (match(TokenType.OR)) {
        Node right = parseAnd();
        left = new LogicalNode(left, LogicalOperator.OR, right);
      }
      return left;
    }

    private Node parseAnd() {
      Node left = parsePrimary();
      while (match(TokenType.AND)) {
        Node right = parsePrimary();
        left = new LogicalNode(left, LogicalOperator.AND, right);
      }
      return left;
    }

    private Node parsePrimary() {
      if (match(TokenType.LPAREN)) {
        Node node = parseExpression();
        expect(TokenType.RPAREN, "Missing closing parenthesis in jsonPath filter.");
        return node;
      }

      Token fieldToken = expect(TokenType.FIELD, "Expected @.fieldPath in jsonPath filter.");
      if (fieldToken.text().contains("[?(")) {
        return parseArrayMatch(fieldToken.text());
      }
      Token operatorToken =
          expect(TokenType.OPERATOR, "Expected comparison operator in jsonPath filter.");
      Token literalToken = expect(TokenType.LITERAL, "Expected literal in jsonPath filter.");

      ComparisonOperator operator =
          switch (operatorToken.text()) {
            case "==" -> ComparisonOperator.EQ;
            case "!=" -> ComparisonOperator.NE;
            case ">" -> ComparisonOperator.GT;
            case ">=" -> ComparisonOperator.GTE;
            case "<" -> ComparisonOperator.LT;
            case "<=" -> ComparisonOperator.LTE;
            default -> throw new TmfFilteringException("Unsupported jsonPath operator: " + operatorToken.text());
          };

      return new ComparisonNode(
          fieldToken.text().substring(2), operator, parseLiteral(literalToken.text()));
    }

    private Node parseArrayMatch(String fieldToken) {
      int filterStart = fieldToken.indexOf("[?(");
      if (filterStart < 0 || !fieldToken.endsWith(")]")) {
        throw new TmfFilteringException("Invalid array match expression in jsonPath filter.");
      }
      String arrayPath = fieldToken.substring(2, filterStart);
      if (arrayPath.isBlank()) {
        throw new TmfFilteringException("Array path must not be blank in jsonPath filter.");
      }
      String innerExpression = fieldToken.substring(filterStart + 3, fieldToken.length() - 2);
      Parser nested = new Parser(innerExpression);
      Node nestedRoot = nested.parseExpression();
      nested.ensureEnd();
      return new ArrayMatchNode(arrayPath, nestedRoot);
    }

    private void ensureEnd() {
      if (!peek(TokenType.EOF)) {
        throw new TmfFilteringException("Invalid jsonPath filter expression.");
      }
    }

    private LiteralToken parseLiteral(String raw) {
      String value = raw.trim();
      if (value.equals("null")) {
        return new LiteralToken(LiteralKind.NULL, "null");
      }
      if (value.equals("true") || value.equals("false")) {
        return new LiteralToken(LiteralKind.BOOLEAN, value);
      }
      if ((value.startsWith("'") && value.endsWith("'"))
          || (value.startsWith("\"") && value.endsWith("\""))) {
        return new LiteralToken(LiteralKind.STRING, unquote(value));
      }
      if (isNumeric(value)) {
        return new LiteralToken(LiteralKind.NUMBER, value);
      }
      throw new TmfFilteringException("Unsupported literal in jsonPath filter: " + raw);
    }

    private String unquote(String value) {
      if (value.length() < 2) {
        return value;
      }
      String body = value.substring(1, value.length() - 1);
      return body.replace("\\'", "'").replace("\\\"", "\"");
    }

    private boolean isNumeric(String value) {
      try {
        Double.parseDouble(value);
        return true;
      } catch (NumberFormatException ex) {
        return false;
      }
    }

    private boolean match(TokenType type) {
      if (peek(type)) {
        index++;
        return true;
      }
      return false;
    }

    private boolean peek(TokenType type) {
      return tokens.get(index).type() == type;
    }

    private Token expect(TokenType type, String message) {
      Token token = tokens.get(index);
      if (token.type() != type) {
        throw new TmfFilteringException(message);
      }
      index++;
      return token;
    }

    private static List<Token> tokenize(String input) {
      List<Token> tokens = new ArrayList<>();
      int i = 0;

      while (i < input.length()) {
        char ch = input.charAt(i);
        if (Character.isWhitespace(ch)) {
          i++;
          continue;
        }
        if (ch == '(') {
          tokens.add(new Token(TokenType.LPAREN, "("));
          i++;
          continue;
        }
        if (ch == ')') {
          tokens.add(new Token(TokenType.RPAREN, ")"));
          i++;
          continue;
        }
        if (i + 1 < input.length()) {
          String two = input.substring(i, i + 2);
          if (two.equals("&&")) {
            tokens.add(new Token(TokenType.AND, "&&"));
            i += 2;
            continue;
          }
          if (two.equals("||")) {
            tokens.add(new Token(TokenType.OR, "||"));
            i += 2;
            continue;
          }
          if (two.equals("==")
              || two.equals("!=")
              || two.equals(">=")
              || two.equals("<=")) {
            tokens.add(new Token(TokenType.OPERATOR, two));
            i += 2;
            continue;
          }
        }
        if (ch == '>' || ch == '<') {
          tokens.add(new Token(TokenType.OPERATOR, String.valueOf(ch)));
          i++;
          continue;
        }
        if (ch == '@') {
          int start = i;
          i++;
          while (i < input.length()) {
            char c = input.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '@') {
              i++;
            } else if (c == '[' && input.startsWith("[?(", i)) {
              int depth = 1;
              i += 3;
              while (i < input.length() && depth > 0) {
                char current = input.charAt(i);
                if (current == '(') {
                  depth++;
                } else if (current == ')') {
                  depth--;
                }
                i++;
              }
              if (depth != 0 || i >= input.length() || input.charAt(i) != ']') {
                throw new TmfFilteringException("Invalid array filter syntax in jsonPath filter.");
              }
              i++;
            } else {
              break;
            }
          }
          String field = input.substring(start, i);
          if (!field.startsWith("@.")) {
            throw new TmfFilteringException("jsonPath field must start with @.: " + field);
          }
          tokens.add(new Token(TokenType.FIELD, field));
          continue;
        }
        if (ch == '\'' || ch == '"') {
          int start = i;
          char quote = ch;
          i++;
          boolean escaped = false;
          while (i < input.length()) {
            char c = input.charAt(i);
            if (c == '\\' && !escaped) {
              escaped = true;
              i++;
              continue;
            }
            if (c == quote && !escaped) {
              i++;
              break;
            }
            escaped = false;
            i++;
          }
          if (i > input.length() || input.charAt(i - 1) != quote) {
            throw new TmfFilteringException("Unterminated string literal in jsonPath filter.");
          }
          tokens.add(new Token(TokenType.LITERAL, input.substring(start, i)));
          continue;
        }

        if (Character.isDigit(ch) || ch == '-' || ch == 't' || ch == 'f' || ch == 'n') {
          int start = i;
          while (i < input.length()) {
            char c = input.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-') {
              i++;
            } else {
              break;
            }
          }
          String token = input.substring(start, i);
          String lowered = token.toLowerCase(Locale.ROOT);
          if (lowered.equals("true")
              || lowered.equals("false")
              || lowered.equals("null")
              || isNumericToken(token)) {
            tokens.add(new Token(TokenType.LITERAL, token));
            continue;
          }
        }

        throw new TmfFilteringException("Unsupported token in jsonPath filter near: " + input.substring(i));
      }

      tokens.add(new Token(TokenType.EOF, ""));
      return tokens;
    }

    private static boolean isNumericToken(String value) {
      try {
        Double.parseDouble(value);
        return true;
      } catch (NumberFormatException ex) {
        return false;
      }
    }
  }

  private record Token(TokenType type, String text) {}

  private enum TokenType {
    LPAREN,
    RPAREN,
    AND,
    OR,
    OPERATOR,
    FIELD,
    LITERAL,
    EOF
  }
}
