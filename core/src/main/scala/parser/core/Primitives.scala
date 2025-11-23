package parser.core

// ============================================================================
// TOP-LEVEL FUNCTIONS - Primitive Parsers
// ============================================================================

// Character-level

/**
 * Parses a specific character.
 *
 * @param c The character to match
 * @return A parser that succeeds if the next character is c
 *
 * Example:
 * {{{
 * char('a').run("abc")  // Success('a', 1)
 * char('a').run("xyz")  // Failure
 * }}}
 */
def char(c: Char): Parser[ParseError, Char] =
  Parser.Satisfy(_ == c, s"'$c'")

/**
 * Parses a character that satisfies a predicate.
 *
 * @param pred Predicate function to test characters
 * @param expected Description of expected input for error messages
 * @return A parser that succeeds if the predicate returns true
 *
 * Example:
 * {{{
 * satisfy(_.isDigit, "digit").run("5")  // Success('5', 1)
 * }}}
 */
def satisfy(pred: Char => Boolean, expected: String): Parser[ParseError, Char] =
  Parser.Satisfy(pred, expected)

/**
 * Parses any single character.
 *
 * Fails only at end of input.
 *
 * Example:
 * {{{
 * anyChar.run("x")  // Success('x', 1)
 * anyChar.run("")   // Failure(EndOfInput)
 * }}}
 */
def anyChar: Parser[ParseError, Char] =
  satisfy(_ => true, "any character")

/**
 * Parses any character from a given string.
 *
 * @param chars String containing valid characters
 * @return A parser that succeeds if the next character is in chars
 *
 * Example:
 * {{{
 * oneOf("aeiou").run("a")  // Success('a', 1)
 * oneOf("aeiou").run("x")  // Failure
 * }}}
 */
def oneOf(chars: String): Parser[ParseError, Char] =
  satisfy(chars.contains, s"one of '$chars'")

/**
 * Parses any character NOT in a given string.
 *
 * @param chars String containing forbidden characters
 * @return A parser that succeeds if the next character is not in chars
 *
 * Example:
 * {{{
 * noneOf("aeiou").run("x")  // Success('x', 1)
 * noneOf("aeiou").run("a")  // Failure
 * }}}
 */
def noneOf(chars: String): Parser[ParseError, Char] =
  satisfy(!chars.contains(_), s"none of '$chars'")

// String-level

/**
 * Parses an exact string.
 *
 * @param s The string to match
 * @return A parser that succeeds if the input starts with s
 *
 * Example:
 * {{{
 * string("hello").run("hello world")  // Success("hello", 5)
 * string("hello").run("goodbye")      // Failure
 * }}}
 */
def string(s: String): Parser[ParseError, String] =
  if (s.isEmpty) succeed("") else Parser.StringMatch(s)

// Common character classes

/**
 * Parses a single digit (0-9).
 *
 * Example:
 * {{{
 * digit.run("5")  // Success('5', 1)
 * digit.run("x")  // Failure
 * }}}
 */
def digit: Parser[ParseError, Char] =
  satisfy(_.isDigit, "digit")

/**
 * Parses a single letter (a-z, A-Z).
 *
 * Example:
 * {{{
 * letter.run("a")  // Success('a', 1)
 * letter.run("5")  // Failure
 * }}}
 */
def letter: Parser[ParseError, Char] =
  satisfy(_.isLetter, "letter")

/**
 * Parses a single alphanumeric character.
 *
 * Example:
 * {{{
 * alphaNum.run("a")  // Success('a', 1)
 * alphaNum.run("5")  // Success('5', 1)
 * alphaNum.run("_")  // Failure
 * }}}
 */
def alphaNum: Parser[ParseError, Char] =
  satisfy(_.isLetterOrDigit, "letter or digit")

/**
 * Parses a single whitespace character.
 *
 * Example:
 * {{{
 * whitespace.run(" ")   // Success(' ', 1)
 * whitespace.run("\n")  // Success('\n', 1)
 * }}}
 */
def whitespace: Parser[ParseError, Char] =
  satisfy(_.isWhitespace, "whitespace")

/**
 * Parses zero or more whitespace characters.
 *
 * Always succeeds, consuming as much whitespace as possible.
 *
 * Example:
 * {{{
 * spaces.run("   x")  // Success(List(' ', ' ', ' '), 3)
 * spaces.run("x")     // Success(List(), 0)
 * }}}
 */
def spaces: Parser[ParseError, List[Char]] =
  many(whitespace)

/**
 * Parses one or more whitespace characters.
 *
 * Fails if no whitespace is present.
 *
 * Example:
 * {{{
 * spaces1.run("   x")  // Success(List(' ', ' ', ' '), 3)
 * spaces1.run("x")     // Failure
 * }}}
 */
def spaces1: Parser[ParseError, List[Char]] =
  many1(whitespace)

// Lexeme combinators

/**
 * Parses a value and consumes trailing whitespace.
 *
 * Useful for tokenizing - automatically handles whitespace after tokens.
 *
 * @param p The parser to run
 * @return A parser that runs p then consumes trailing whitespace
 *
 * Example:
 * {{{
 * lexeme(string("foo")).run("foo   bar")  // Success("foo", 6)
 * }}}
 */
def lexeme[E, A](p: Parser[E, A]): Parser[E | ParseError, A] =
  flatMap(p, (result: A) => map(spaces, (_: List[Char]) => result))

/**
 * Parses a symbol (string with trailing whitespace consumed).
 *
 * Convenient shorthand for lexeme(string(s)).
 *
 * @param s The symbol to parse
 * @return A parser that matches s and consumes trailing whitespace
 *
 * Example:
 * {{{
 * symbol("=").run("= 42")  // Success("=", 2)
 * }}}
 */
def symbol(s: String): Parser[ParseError, String] =
  lexeme(string(s))

// End of input

/**
 * Succeeds only at the end of input.
 *
 * Useful for ensuring entire input is consumed.
 *
 * Example:
 * {{{
 * eof.run("")    // Success((), 0)
 * eof.run("x")   // Failure
 * }}}
 */
def eof: Parser[ParseError, Unit] =
  Parser.Eof()

/**
 * Defers parser construction until needed.
 *
 * Essential for defining recursive parsers without stack overflow.
 * The thunk is evaluated lazily each time the parser is used.
 *
 * Example:
 * {{{
 * lazy val expr: Parser[ParseError, Expr] =
 *   number | defer(expr).between(char('('), char(')'))
 * }}}
 *
 * @param p The parser to defer (by-name parameter)
 * @return A parser that lazily evaluates p
 */
def defer[E, A](p: => Parser[E, A]): Parser[E, A] =
  Parser.Defer(() => p)

/**
 * Creates a memoized parser rule that supports left recursion.
 *
 * Unlike `defer`, which only provides lazy evaluation, `rule` enables:
 * - Memoization of parse results at each position
 * - Automatic left recursion handling via seed-growth algorithm
 * - Direct left-recursive grammars that "just work"
 *
 * Use `rule` for any recursive parser that might be left-recursive.
 * The algorithm detects left recursion cycles and uses seed-growth
 * to find the longest match.
 *
 * Example:
 * {{{{
 * // Direct left recursion - now works!
 * lazy val expr: Parser[ParseError, Int] = rule {
 *   (expr ~ char('+') ~ term).map { case ((a, _), b) => a + b } | term
 * }
 * }}}}
 *
 * @param p The parser to memoize (by-name parameter)
 * @return A memoized parser with left recursion support
 */
def rule[E, A](p: => Parser[E, A]): Parser[E, A] = {
  // Each call to rule creates a unique typed key for memoization
  // The key carries type parameters [E, A] ensuring type-safe retrieval
  val key = MemoKey[E, A]()
  Parser.Memo(Parser.Defer(() => p), key)
}
