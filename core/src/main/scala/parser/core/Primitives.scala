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

/**
 * Parses one of several strings using radix tree for O(m) matching.
 *
 * This is more efficient than chaining multiple `string()` parsers with `|`
 * when there are 3+ alternatives, as it uses a radix tree internally.
 *
 * @param strings The strings to match (at least one required)
 * @return A parser that matches any of the input strings
 *
 * Example:
 * {{{
 * stringIn("true", "false", "null").run("true")  // Success("true", 4)
 * stringIn("yes", "no").run("no")                // Success("no", 2)
 * }}}
 */
def stringIn(strings: String*): Parser[ParseError, String] =
  if (strings.isEmpty) {
    throw new IllegalArgumentException("stringIn requires at least one string")
  } else if (strings.size == 1) {
    Parser.StringMatch(strings.head)
  } else {
    val targets = strings.toArray
    val radix   = RadixNode.fromStrings(targets)
    Parser.StringChoice(radix, targets)
  }

/**
 * Parses one of several keyword-value pairs using radix tree for O(m) matching.
 *
 * Maps each matched keyword to its corresponding value. Uses radix tree
 * internally for efficient matching regardless of the number of keywords.
 *
 * @param mappings Map of keyword strings to their corresponding values
 * @return A parser that matches any keyword and returns its mapped value
 *
 * Example:
 * {{{
 * keywords(Map("true" -> true, "false" -> false)).run("true")  // Success(true, 4)
 * keywords(Map("yes" -> 1, "no" -> 0)).run("no")               // Success(0, 2)
 * }}}
 */
def keywords[A](mappings: Map[String, A]): Parser[ParseError, A] =
  if (mappings.isEmpty) {
    throw new IllegalArgumentException("keywords requires at least one mapping")
  } else {
    val targets = mappings.keys.toArray
    val radix   = RadixNode.fromStrings(targets)
    Parser.Map(Parser.StringChoice(radix, targets), mappings)
  }

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
 * ⚠️ **PERFORMANCE WARNING** ⚠️
 *
 * `rule` has significant overhead (~479% slower than unmemoized for cache misses).
 * ONLY use it when you need:
 * 1. Left-recursive grammars (e.g., `expr -> expr '+' term`)
 * 2. Named recursive definitions with memoization
 *
 * For non-recursive parsers or simple right-recursion, use:
 * - `lazy val` for simple recursive definitions
 * - `.memoize` for caching WITHOUT left-recursion support (2-3x less overhead)
 * - `defer` when you only need to break initialization cycles
 *
 * See docs/memoization-performance-analysis.md for detailed benchmarks.
 *
 * == When to use `rule` vs alternatives ==
 *
 * Use `defer` when:
 * - Parser is right-recursive or non-recursive
 * - You just need to break the initialization cycle
 *
 * Use `.memoize` when:
 * - Parser is expensive with backtracking
 * - NOT left-recursive
 *
 * Use `rule` when:
 * - Parser is left-recursive (e.g., `expr -> expr '+' term`)
 * - You're implementing expression grammars with precedence
 *
 * == How it works ==
 *
 * The seed-growth algorithm (Warth et al.) handles left recursion by:
 * 1. Detecting when a rule calls itself at the same position
 * 2. Starting with a "seed" (failure or base case match)
 * 3. Re-parsing until no more progress is made
 * 4. Returning the longest successful match
 *
 * This gives left-associative results naturally:
 * - `1+2+3` parses as `(1+2)+3`, not `1+(2+3)`
 *
 * == Examples ==
 *
 * Direct left recursion (arithmetic expressions):
 * {{{
 * // expr -> expr '+' digit | digit
 * lazy val expr: Parser[ParseError, Int] = rule {
 *   val add = for {
 *     left  <- expr
 *     _     <- char('+')
 *     right <- digit
 *   } yield left + (right - '0')
 *   add | digit.map(_ - '0')
 * }
 * expr.run("1+2+3")  // Success(6, 5) - left associative: (1+2)+3
 * }}}
 *
 * Traditional approach with chainl1 (still works, no rule needed):
 * {{{
 * val expr = digit.map(_ - '0').chainl1(char('+').as(_ + _))
 * }}}
 *
 * @param p The parser to memoize (by-name parameter)
 * @return A memoized parser with left recursion support
 */
def rule[E, A](p: => Parser[E, A]): Parser[E, A] = {
  // Each call to rule creates a unique typed key for memoization
  // The key carries type parameters [E, A] ensuring type-safe retrieval
  val key = MemoKey[E, A]()
  Parser.Memo(Parser.Defer(() => p), key, enableLR = true)
}

/**
 * Memoizes a parser for improved performance on expensive parsers.
 *
 * Caches parse results by position to avoid redundant work. Unlike `rule`,
 * this uses simple caching without left-recursion support for better performance.
 *
 * Performance characteristics:
 * - ~50% faster than `rule` for cache hits (no LR overhead)
 * - No lrStack manipulation
 * - No heads.get(pos) lookup
 * - Direct result storage without Either wrapping
 *
 * Use this for:
 * - Expensive parsers that are NOT left-recursive
 * - Inline combinations that you want to cache
 * - Performance-critical sections without LR needs
 *
 * Use `rule` instead if you need left-recursion support.
 *
 * Example:
 * {{{
 * val identifier = (letter ~ alphaNum.many).map { case (h, t) => (h :: t).mkString }
 * val memoizedId = memoize(identifier)  // Cache results by position
 * }}}
 *
 * @param p The parser to memoize
 * @return A memoized parser with simple caching
 */
def memoize[E, A](p: Parser[E, A]): Parser[E, A] = {
  val key = MemoKey[E, A]()
  Parser.Memo(p, key, enableLR = false)
}
