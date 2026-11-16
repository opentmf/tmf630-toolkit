package org.opentmf.query.tmf630.jsonb;

import java.util.List;

/**
 * Whether a {@code GIN (payload jsonb_path_ops)} index has a key for a translated SQL/JSON path
 * {@code $ ? (<predicate>)}, by the rules PostgreSQL extracts keys with: an equality of a path of
 * plain keys and {@code [*]} (filters allowed) with a literal is a key; {@code &&} has one when any
 * operand has; {@code ||} only when every branch has; {@code exists(path)} when a filter in the
 * path has. A negation, any other comparison, a method, a positional subscript and anything this
 * reader does not know have none.
 *
 * <p>The answer errs one way only: a path read as keyed really has a key. A path PostgreSQL could
 * key but this reader does not is emitted in the function form, which scans the table, as every
 * {@code filter=} did before 3.6.0; a keyless path in the operator form would read the whole index
 * under a generic plan.
 */
final class JsonbJsonPathIndexKey {

  /** The longer operators first, so {@code <=} is not read as {@code <}. */
  private static final List<String> COMPARISON_OPERATORS =
      List.of("==", "!=", "<>", "<=", ">=", "<", ">");

  private final String text;
  private int pos;

  private JsonbJsonPathIndexKey(String text) {
    this.text = text;
  }

  /** Whether the index has a key for {@code jsonPath}, a {@code $ ? (<predicate>)} path. */
  static boolean hasIndexKey(String jsonPath) {
    if (jsonPath == null) {
      return false;
    }
    try {
      return new JsonbJsonPathIndexKey(jsonPath).root();
    } catch (Unrecognised e) {
      return false;
    }
  }

  private boolean root() {
    expect("$");
    expect("?");
    expect("(");
    boolean keyed = or();
    expect(")");
    skipSpaces();
    if (pos != text.length()) {
      throw new Unrecognised();
    }
    return keyed;
  }

  private boolean or() {
    boolean keyed = and();
    while (accept("||")) {
      keyed &= and();
    }
    return keyed;
  }

  private boolean and() {
    boolean keyed = unary();
    while (accept("&&")) {
      keyed |= unary();
    }
    return keyed;
  }

  private boolean unary() {
    if (accept("!")) {
      unary();
      return false;
    }
    if (accept("(")) {
      boolean keyed = or();
      expect(")");
      return keyed;
    }
    if (acceptWord("exists")) {
      expect("(");
      Path path = path();
      expect(")");
      return path.filterKeyed();
    }
    return comparison();
  }

  private boolean comparison() {
    Operand left = operand();
    if (acceptWord("like_regex")) {
      stringLiteral();
      if (acceptWord("flag")) {
        stringLiteral();
      }
      return false;
    }
    if (acceptWord("starts")) {
      expectWord("with");
      operand();
      return false;
    }
    String operator = comparisonOperator();
    Operand right = operand();
    return "==".equals(operator) && (left.keyedAgainst(right) || right.keyedAgainst(left));
  }

  private String comparisonOperator() {
    for (String operator : COMPARISON_OPERATORS) {
      if (accept(operator)) {
        return operator;
      }
    }
    throw new Unrecognised();
  }

  private Operand operand() {
    skipSpaces();
    char c = peek();
    if (c == '@' || c == '$') {
      return path();
    }
    if (c == '"') {
      stringLiteral();
      return new Literal();
    }
    if (c == '-' || Character.isDigit(c)) {
      number();
      return new Literal();
    }
    if (acceptWord("true") || acceptWord("false") || acceptWord("null")) {
      return new Literal();
    }
    throw new Unrecognised();
  }

  /** {@code @} or {@code $}, then {@code .key}, {@code [*]}, {@code [N]}, {@code .method()}, filters. */
  private Path path() {
    skipSpaces();
    if (peek() != '@' && peek() != '$') {
      throw new Unrecognised();
    }
    pos++;
    boolean plain = true;
    boolean filterKeyed = false;
    while (true) {
      if (text.startsWith(".", pos)) {
        pos++;
        identifier();
        if (accept("(")) {
          expect(")");
          plain = false;
        }
      } else if (text.startsWith("[*]", pos)) {
        pos += 3;
      } else if (text.startsWith("[", pos)) {
        positional();
        plain = false;
      } else if (accept("?")) {
        expect("(");
        filterKeyed |= or();
        expect(")");
      } else {
        return new Path(plain, filterKeyed);
      }
    }
  }

  private void identifier() {
    int start = pos;
    while (pos < text.length() && isIdentifierChar(text.charAt(pos))) {
      pos++;
    }
    if (pos == start) {
      throw new Unrecognised();
    }
  }

  private void positional() {
    pos++;
    int start = pos;
    while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
      pos++;
    }
    if (pos == start || !text.startsWith("]", pos)) {
      throw new Unrecognised();
    }
    pos++;
  }

  private void stringLiteral() {
    expect("\"");
    while (pos < text.length() && text.charAt(pos) != '"') {
      pos += text.charAt(pos) == '\\' ? 2 : 1;
    }
    if (pos >= text.length()) {
      throw new Unrecognised();
    }
    pos++;
  }

  private void number() {
    int start = pos;
    if (peek() == '-') {
      pos++;
    }
    while (pos < text.length() && isNumberChar(text.charAt(pos))) {
      pos++;
    }
    if (pos == start || !Character.isDigit(text.charAt(pos - 1))) {
      throw new Unrecognised();
    }
  }

  private static boolean isIdentifierChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  private static boolean isNumberChar(char c) {
    return Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
  }

  private boolean accept(String token) {
    skipSpaces();
    if (text.startsWith(token, pos)) {
      pos += token.length();
      return true;
    }
    return false;
  }

  private boolean acceptWord(String word) {
    skipSpaces();
    int end = pos + word.length();
    boolean whole =
        text.startsWith(word, pos) && (end == text.length() || !isIdentifierChar(text.charAt(end)));
    if (whole) {
      pos = end;
    }
    return whole;
  }

  private void expect(String token) {
    if (!accept(token)) {
      throw new Unrecognised();
    }
  }

  private void expectWord(String word) {
    if (!acceptWord(word)) {
      throw new Unrecognised();
    }
  }

  private char peek() {
    if (pos >= text.length()) {
      throw new Unrecognised();
    }
    return text.charAt(pos);
  }

  private void skipSpaces() {
    while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
      pos++;
    }
  }

  /** One side of a comparison. */
  private sealed interface Operand permits Path, Literal {

    /** Whether this side, compared by {@code ==} with {@code other}, gives the index a key. */
    boolean keyedAgainst(Operand other);
  }

  /** A path: plain when it holds only keys, {@code [*]} and filters. */
  private record Path(boolean plain, boolean filterKeyed) implements Operand {

    @Override
    public boolean keyedAgainst(Operand other) {
      return plain && other instanceof Literal;
    }
  }

  /** A string, number, boolean or null literal. */
  private record Literal() implements Operand {

    @Override
    public boolean keyedAgainst(Operand other) {
      return false;
    }
  }

  /** A shape this reader does not know: no key. */
  private static final class Unrecognised extends RuntimeException {

    private static final long serialVersionUID = 1L;

    Unrecognised() {
      super(null, null, false, false);
    }
  }
}
