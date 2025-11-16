package parser.core

// ============================================================================
// TOP-LEVEL FUNCTIONS - Core Combinators
// ============================================================================

// Construction

/**
 * Creates a parser that always succeeds with the given value.
 *
 * Does not consume any input.
 *
 * @param value The value to return
 * @return A parser that always succeeds with value
 *
 * Example:
 * {{{
 * succeed(42).run("anything")  // Success(42, 0)
 * }}}
 */
inline def succeed[A](value: A): Parser[Nothing, A] =
  Parser.Succeed(value)

/**
 * Creates a parser that always fails with the given error.
 *
 * Does not consume any input.
 *
 * @param error The error to report
 * @return A parser that always fails with error
 *
 * Example:
 * {{{
 * fail("oops").run("anything")  // Failure(List("oops"), ...)
 * }}}
 */
inline def fail[E](error: E): Parser[E, Nothing] =
  Parser.Fail(error)

// Sequencing

/**
 * Transforms the result of a parser using a function.
 *
 * Functor map operation.
 *
 * @param p The parser to transform
 * @param f The transformation function
 * @return A parser that applies f to the result of p
 *
 * Example:
 * {{{
 * map(digit, _.toString.toInt).run("5")  // Success(5, 1)
 * }}}
 */
inline def map[E, A, B](p: Parser[E, A], f: A => B): Parser[E, B] =
  Parser.Map(p, f)

/**
 * Sequences two parsers where the second depends on the first's result.
 *
 * Monad flatMap operation. Enables sequencing parsers where the choice
 * of continuation depends on the value parsed.
 *
 * @param p The first parser
 * @param f Function from first result to second parser
 * @return A parser that sequences p and f(result)
 *
 * Example:
 * {{{
 * flatMap(digit, n => count(n.toString.toInt, char('x')))
 * // Parses a digit then that many 'x' characters
 * }}}
 */
inline def flatMap[E, A, B](p: Parser[E, A], f: A => Parser[E, B]): Parser[E, B] =
  Parser.FlatMap(p, f)

/**
 * Sequences two parsers, keeping both results as a tuple.
 *
 * Applicative product operation. Runs p1 then p2, combining results.
 *
 * @param p1 First parser
 * @param p2 Second parser
 * @return A parser that runs both and returns (result1, result2)
 *
 * Example:
 * {{{
 * zip(char('a'), char('b')).run("ab")  // Success(('a', 'b'), 2)
 * }}}
 */
def zip[E, A, B](p1: Parser[E, A], p2: Parser[E, B]): Parser[E, (A, B)] =
  flatMap(p1, (a: A) => map(p2, (b: B) => (a, b)))

/**
 * Sequences two parsers, keeping only the left result.
 *
 * Runs p1 then p2, but discards p2's result.
 *
 * @param p1 Parser whose result to keep
 * @param p2 Parser whose result to discard
 * @return A parser that runs both but returns only p1's result
 *
 * Example:
 * {{{
 * zipLeft(char('a'), char('b')).run("ab")  // Success('a', 2)
 * }}}
 */
def zipLeft[E, A, B](p1: Parser[E, A], p2: Parser[E, B]): Parser[E, A] =
  flatMap(p1, (a: A) => map(p2, (_: B) => a))

/**
 * Sequences two parsers, keeping only the right result.
 *
 * Runs p1 then p2, but discards p1's result.
 *
 * @param p1 Parser whose result to discard
 * @param p2 Parser whose result to keep
 * @return A parser that runs both but returns only p2's result
 *
 * Example:
 * {{{
 * zipRight(char('a'), char('b')).run("ab")  // Success('b', 2)
 * }}}
 */
def zipRight[E, A, B](p1: Parser[E, A], p2: Parser[E, B]): Parser[E, B] =
  flatMap(p1, (_: A) => p2)

// Choice

/**
 * Tries the left parser, and if it fails, tries the right parser.
 *
 * Backtracking alternative combinator. If left fails without consuming
 * input, tries right. Collects errors from the furthest parse point.
 *
 * @param left First parser to try
 * @param right Alternative parser if left fails
 * @return A parser that tries left, then right if left fails
 *
 * Example:
 * {{{
 * or(char('a'), char('b')).run("b")  // Success('b', 1)
 * }}}
 */
inline def or[E, A](left: Parser[E, A], right: Parser[E, A]): Parser[E, A] =
  Parser.Or(left, right)

/**
 * Tries a list of parsers in order, succeeding with the first success.
 *
 * @param parsers Non-empty list of parsers to try
 * @return A parser that tries each parser in order
 *
 * Example:
 * {{{
 * choice(List(char('a'), char('b'), char('c'))).run("c")
 * // Success('c', 1)
 * }}}
 */
def choice[E, A](parsers: List[Parser[E, A]]): Parser[E, A] =
  parsers.reduceLeft(or)

// Repetition

/**
 * Parses zero or more occurrences of p.
 *
 * Greedy - consumes as much as possible. Always succeeds.
 *
 * @param p The parser to repeat
 * @return A parser that returns a list of results
 *
 * Example:
 * {{{
 * many(char('a')).run("aaab")  // Success(List('a', 'a', 'a'), 3)
 * many(char('a')).run("b")     // Success(List(), 0)
 * }}}
 */
inline def many[E, A](p: Parser[E, A]): Parser[E, List[A]] =
  Parser.Many(p)

/**
 * Parses one or more occurrences of p.
 *
 * Greedy - consumes as much as possible. Fails if zero matches.
 *
 * @param p The parser to repeat
 * @return A parser that returns a non-empty list of results
 *
 * Example:
 * {{{
 * manyNonEmpty(char('a')).run("aaab")  // Success(List('a', 'a', 'a'), 3)
 * manyNonEmpty(char('a')).run("b")     // Failure
 * }}}
 */
inline def manyNonEmpty[E, A](p: Parser[E, A]): Parser[E, List[A]] =
  Parser.Many1(p)

/**
 * Parses zero or one occurrence of p.
 *
 * Always succeeds - returns Some(value) if p succeeds, None otherwise.
 *
 * @param p The parser to try
 * @return A parser that returns Option[A]
 *
 * Example:
 * {{{
 * optional(char('a')).run("ab")  // Success(Some('a'), 1)
 * optional(char('a')).run("b")   // Success(None, 0)
 * }}}
 */
inline def optional[E, A](p: Parser[E, A]): Parser[E, Option[A]] =
  Parser.Optional(p)

/**
 * Parses zero or more occurrences of p separated by sep.
 *
 * Does not require trailing separator. Always succeeds.
 *
 * @param p The element parser
 * @param sep The separator parser
 * @return A parser that returns a list of elements
 *
 * Example:
 * {{{
 * separatedBy(digit, char(',')).run("1,2,3")  // Success(List('1','2','3'), 5)
 * separatedBy(digit, char(',')).run("")       // Success(List(), 0)
 * }}}
 */
def separatedBy[E, A, Sep](p: Parser[E, A], sep: Parser[E, Sep]): Parser[E, List[A]] =
  or(separatedByNonEmpty(p, sep), succeed(List.empty))

/**
 * Parses one or more occurrences of p separated by sep.
 *
 * Does not require trailing separator. Fails if zero matches.
 *
 * @param p The element parser
 * @param sep The separator parser
 * @return A parser that returns a non-empty list of elements
 *
 * Example:
 * {{{
 * separatedByNonEmpty(digit, char(',')).run("1,2,3")  // Success(List('1','2','3'), 5)
 * separatedByNonEmpty(digit, char(',')).run("")       // Failure
 * }}}
 */
def separatedByNonEmpty[E, A, Sep](p: Parser[E, A], sep: Parser[E, Sep]): Parser[E, List[A]] =
  flatMap(p, (head: A) => map(many(zipRight(sep, p)), (tail: List[A]) => head :: tail))

/**
 * Parses zero or more occurrences of p, each terminated by end.
 *
 * Requires trailing terminator. Always succeeds.
 *
 * @param p The element parser
 * @param end The terminator parser
 * @return A parser that returns a list of elements
 *
 * Example:
 * {{{
 * endedBy(digit, char(';')).run("1;2;3;")  // Success(List('1','2','3'), 6)
 * }}}
 */
def endedBy[E, A, End](p: Parser[E, A], end: Parser[E, End]): Parser[E, List[A]] =
  many(zipLeft(p, end))

/**
 * Parses exactly n occurrences of p.
 *
 * Fails if fewer than n matches found.
 *
 * @param n Number of times to repeat (must be >= 0)
 * @param p The parser to repeat
 * @return A parser that returns a list of exactly n elements
 *
 * Example:
 * {{{
 * count(3, char('a')).run("aaa")  // Success(List('a','a','a'), 3)
 * count(3, char('a')).run("aa")   // Failure
 * }}}
 */
def count[E, A](n: Int, p: Parser[E, A]): Parser[E, List[A]] =
  if (n <= 0) {
    succeed(List.empty)
  } else {
    flatMap(p, (head: A) => map(count(n - 1, p), (tail: List[A]) => head :: tail))
  }

// Lookahead

/**
 * Parses p without consuming input.
 *
 * Succeeds if p would succeed, but resets input position. Useful for
 * checking what comes next without committing.
 *
 * @param p The parser to look ahead with
 * @return A parser that succeeds like p but consumes nothing
 *
 * Example:
 * {{{
 * lookAhead(char('a')).run("ab")  // Success('a', 0)
 * // Note: consumed = 0
 * }}}
 */
inline def lookAhead[E, A](p: Parser[E, A]): Parser[E, A] =
  Parser.LookAhead(p)

/**
 * Succeeds only if p would fail.
 *
 * Negative lookahead - ensures p does not match without consuming input.
 *
 * @param p The parser that must NOT match
 * @return A parser that succeeds with () if p fails
 *
 * Example:
 * {{{
 * notFollowedBy(char('a')).run("b")  // Success((), 0)
 * notFollowedBy(char('a')).run("a")  // Failure
 * }}}
 */
inline def notFollowedBy[A](p: Parser[ParseError, A]): Parser[ParseError, Unit] =
  Parser.NotFollowedBy(p)

// Error handling

/**
 * Captures the result of p as a value instead of propagating errors.
 *
 * Reifies the parse result - turns a Parser[E, A] into Parser[Nothing, Result[E, A]].
 * Always succeeds, returning either Success or Failure as a value.
 *
 * @param p The parser to attempt
 * @return A parser that always succeeds with Result[E, A]
 *
 * Example:
 * {{{
 * attempt(char('a')).run("b")
 * // Success(Failure(...), 0)  -- note: Success wrapping Failure!
 * }}}
 */
inline def attempt[E, A](p: Parser[E, A]): Parser[Nothing, Result[E, A]] =
  Parser.Attempt(p)

/**
 * Recovers from parse failures by providing a default value.
 *
 * If p succeeds, returns its value. If p fails, applies f to the error
 * to produce a fallback value. Always succeeds.
 *
 * @param p The parser to try
 * @param f Function to produce fallback value from error
 * @return A parser that always succeeds
 *
 * Example:
 * {{{
 * recover(digit)(_ => '0').run("x")  // Success('0', 0)
 * }}}
 */
def recover[E, A](p: Parser[E, A])(f: E => A): Parser[Nothing, A] =
  map(
    attempt(p),
    (result: Result[E, A]) =>
      result match {
        case Result.Success(value, _)    => value
        case Result.Partial(value, _, _) => value
        case Result.Failure(errors, _)   => f(errors.head)
      }
  )

/**
 * Recovers from parse failures by providing a fallback parser.
 *
 * If p succeeds, returns its value. If p fails, runs f(error) to try
 * an alternative parser. Useful for error correction.
 *
 * @param p The parser to try
 * @param f Function to produce fallback parser from error
 * @return A parser that tries p then f(error) if p fails
 *
 * Example:
 * {{{
 * recoverWith(digit)(_ => char('?')).run("x")  // tries digit, then '?'
 * }}}
 */
def recoverWith[E, E2, A](p: Parser[E, A])(f: E => Parser[E2, A]): Parser[E2, A] =
  flatMap(
    attempt(p),
    (result: Result[E, A]) =>
      result match {
        case Result.Success(value, _)    => succeed(value)
        case Result.Partial(value, _, _) => succeed(value)
        case Result.Failure(errors, _)   => f(errors.head)
      }
  )

/**
 * Labels a parser with a name for better error messages.
 *
 * When the parser fails, the name will appear in the expected set.
 *
 * @param p The parser to label
 * @param name The label to use in error messages
 * @return A parser with enhanced error reporting
 *
 * Example:
 * {{{
 * named(digit, "number").run("x")
 * // Failure: expected number, got 'x'
 * }}}
 */
inline def named[A](p: Parser[ParseError, A], name: String): Parser[ParseError, A] =
  Parser.Named(p, name)

// Debugging

/**
 * Adds tracing output to a parser for debugging.
 *
 * Prints trace messages to stderr showing parse attempts, successes,
 * and failures. Does not modify the parser's behavior, only adds
 * logging side effects.
 *
 * @param p The parser to trace
 * @param label The label to include in trace messages
 * @return A parser with identical behavior but trace output
 *
 * Example:
 * {{{
 * trace(digit, "number").run("5")
 * // [TRACE] number: trying at offset 0
 * // [TRACE] number: success, consumed 1 chars
 * // Success('5', 1)
 * }}}
 */
inline def trace[E, A](p: Parser[E, A], label: String): Parser[E, A] =
  Parser.Trace(p, label)

/**
 * Adds debug output to a parser showing parsed values.
 *
 * Prints debug messages to stderr showing parse attempts with offsets,
 * successful values, and error details. Does not modify the parser's
 * behavior, only adds logging side effects.
 *
 * @param p The parser to debug
 * @param label The label to include in debug messages
 * @return A parser with identical behavior but debug output
 *
 * Example:
 * {{{
 * debug(digit, "number").run("5")
 * // [DEBUG] number: trying at offset 0
 * // [DEBUG] number: success, parsed '5'
 * // Success('5', 1)
 * }}}
 */
inline def debug[E, A](p: Parser[E, A], label: String): Parser[E, A] =
  Parser.Debug(p, label)

// Operators

/**
 * Parses one or more occurrences of p separated by op, left-associative.
 *
 * Used for parsing left-associative binary operators. Applies operators
 * from left to right: ((a op b) op c) op d
 *
 * @param p The parser for operands
 * @param op The parser for operators, returns a binary function
 * @return A parser that builds left-associative parse tree
 *
 * Example:
 * {{{
 * val num = digit.map(_.toString.toInt)
 * val minus = char('-').as((a: Int, b: Int) => a - b)
 * chainLeft1(num, minus).run("5-3-1")  // Success(1, 5)  // (5-3)-1 = 1
 * }}}
 */
def chainLeft1[E, A](p: Parser[E, A], op: Parser[E, (A, A) => A]): Parser[E, A] = {
  def rest(acc: A): Parser[E, A] =
    or(
      flatMap(op, (f: (A, A) => A) => flatMap(p, (right: A) => rest(f(acc, right)))),
      succeed(acc)
    )

  flatMap(p, (initial: A) => rest(initial))
}

/**
 * Parses one or more occurrences of p separated by op, right-associative.
 *
 * Used for parsing right-associative binary operators. Applies operators
 * from right to left: a op (b op (c op d))
 *
 * @param p The parser for operands
 * @param op The parser for operators, returns a binary function
 * @return A parser that builds right-associative parse tree
 *
 * Example:
 * {{{
 * val num = digit.map(_.toString.toInt)
 * val power = char('^').as((a: Int, b: Int) => Math.pow(a, b).toInt)
 * chainRight1(num, power).run("2^3^2")  // Success(512, 5)  // 2^(3^2) = 512
 * }}}
 */
def chainRight1[E, A](p: Parser[E, A], op: Parser[E, (A, A) => A]): Parser[E, A] =
  flatMap(
    p,
    (left: A) =>
      or(
        flatMap(op, (f: (A, A) => A) => map(chainRight1(p, op), (right: A) => f(left, right))),
        succeed(left)
      )
  )

/**
 * Parses zero or more occurrences of p separated by op, left-associative.
 *
 * This is the zero-or-more version of chainLeft1.
 * Returns the default value if no occurrences are found.
 *
 * @param p The parser for operands
 * @param op The parser for operators, returns a binary function
 * @param default The value to return if no occurrences are found
 * @return A parser that builds left-associative parse tree
 *
 * Example:
 * {{{
 * val num = digit.map(_.toString.toInt)
 * val plus = char('+').as((a: Int, b: Int) => a + b)
 * chainLeft(num, plus, 0).run("1+2+3")  // Success(6, 5)
 * chainLeft(num, plus, 0).run("")       // Success(0, 0)
 * }}}
 */
def chainLeft[E, A](p: Parser[E, A], op: Parser[E, (A, A) => A], default: A): Parser[E, A] =
  or(chainLeft1(p, op), succeed(default))

/**
 * Parses zero or more occurrences of p separated by op, right-associative.
 *
 * This is the zero-or-more version of chainRight1.
 * Returns the default value if no occurrences are found.
 *
 * @param p The parser for operands
 * @param op The parser for operators, returns a binary function
 * @param default The value to return if no occurrences are found
 * @return A parser that builds right-associative parse tree
 *
 * Example:
 * {{{
 * val num = digit.map(_.toString.toInt)
 * val power = char('^').as((a: Int, b: Int) => Math.pow(a, b).toInt)
 * chainRight(num, power, 1).run("2^3")  // Success(8, 3)
 * chainRight(num, power, 1).run("")     // Success(1, 0)
 * }}}
 */
def chainRight[E, A](p: Parser[E, A], op: Parser[E, (A, A) => A], default: A): Parser[E, A] =
  or(chainRight1(p, op), succeed(default))

// Convenience Combinators

/**
 * Parse p surrounded by open and close.
 *
 * Equivalent to: open *> p <* close
 *
 * Common for parsing delimited expressions like:
 * - Parenthesized expressions: between(char('('), char(')'))(expr)
 * - Quoted strings: between(char('"'), char('"'))(stringContent)
 * - Bracketed lists: between(char('['), char(']'))(items)
 *
 * @param open Parser for opening delimiter
 * @param close Parser for closing delimiter
 * @param p The parser to run between delimiters
 * @return A parser that parses p between open and close
 *
 * Example:
 * {{{
 * val parens = between(char('('), char(')'))(digit.map(_.toString.toInt))
 * parens.run("(5)")  // Success(5, 3)
 * }}}
 */
def between[E, Open, Close, A](
  open: Parser[E, Open],
  close: Parser[E, Close]
)(p: Parser[E, A]): Parser[E, A] =
  zipRight(open, zipLeft(p, close))

/**
 * Parse p surrounded by the same delimiter on both sides.
 *
 * Equivalent to: between(delim, delim)(p)
 *
 * @param delim Parser for the delimiter (used for both sides)
 * @param p The parser to run between delimiters
 * @return A parser that parses p surrounded by delim
 *
 * Example:
 * {{{
 * val quoted = surroundedBy(char('"'))(takeWhile(_ != '"'))
 * quoted.run("\"hello\"")  // Success("hello", 7)
 * }}}
 */
def surroundedBy[E, Delim, A](
  delim: Parser[E, Delim]
)(p: Parser[E, A]): Parser[E, A] =
  between(delim, delim)(p)

/**
 * Parse zero or more occurrences of p, discarding results.
 *
 * More efficient than many when you don't need the results,
 * as it doesn't build a list.
 *
 * @param p The parser to repeat
 * @return A parser that succeeds with Unit
 *
 * Example:
 * {{{
 * val skipSpaces = skipMany(char(' '))
 * skipSpaces.run("   hello")  // Success((), 3)
 * }}}
 */
def skipMany[E, A](p: Parser[E, A]): Parser[E, Unit] =
  map(many(p), (_: List[A]) => ())

/**
 * Parse one or more occurrences of p, discarding results.
 *
 * More efficient than manyNonEmpty when you don't need the results.
 *
 * @param p The parser to repeat
 * @return A parser that succeeds with Unit if at least one match
 *
 * Example:
 * {{{
 * val skipDigits = skipManyNonEmpty(digit)
 * skipDigits.run("123abc")  // Success((), 3)
 * skipDigits.run("abc")     // Failure
 * }}}
 */
def skipManyNonEmpty[E, A](p: Parser[E, A]): Parser[E, Unit] =
  map(manyNonEmpty(p), (_: List[A]) => ())

/**
 * Parse at least n occurrences of p.
 *
 * Fails if fewer than n matches are found.
 *
 * @param n Minimum number of occurrences (must be >= 0)
 * @param p The parser to repeat
 * @return A parser that returns a list of at least n elements
 *
 * Example:
 * {{{
 * val atLeast3Digits = manyAtLeast(3)(digit)
 * atLeast3Digits.run("12")    // Failure
 * atLeast3Digits.run("1234")  // Success(List('1','2','3','4'), 4)
 * }}}
 */
def manyAtLeast[E, A](n: Int)(p: Parser[E, A]): Parser[E, List[A]] =
  flatMap(
    count(n, p),
    (required: List[A]) => map(many(p), (optional: List[A]) => required ++ optional))

// Left Recursion

/**
 * Marks a parser as potentially left-recursive.
 *
 * Enables memoization and the Warth et al. seed-growth algorithm to handle
 * left-recursive grammars. Use this for any parser that directly or indirectly
 * references itself.
 *
 * The parser parameter is by-name (lazy) to allow recursive definitions.
 *
 * @param parser A lazy parser (use `=> Parser` for recursive definitions)
 * @return A parser with left-recursion support
 *
 * Example - Direct left recursion:
 * {{{
 * lazy val expr: Parser[ParseError, Expr] = recursive {
 *   (expr ~ char('+') ~ term).map { case ((l, _), r) => Add(l, r) } |
 *   (expr ~ char('-') ~ term).map { case ((l, _), r) => Sub(l, r) } |
 *   term
 * }
 * }}}
 *
 * Example - Indirect left recursion:
 * {{{
 * lazy val a: Parser[ParseError, String] = recursive {
 *   (b ~ char('x')).map { case (s, c) => s + c } | char('y').map(_.toString)
 * }
 * lazy val b: Parser[ParseError, String] = recursive {
 *   (a ~ char('z')).map { case (s, c) => s + c } | char('w').map(_.toString)
 * }
 * }}}
 */
def recursive[E, A](parser: => Parser[E, A]): Parser[E, A] = {
  val id = _root_.parser.runtime.getNextParserId()
  Parser.Recursive(id, () => parser)
}
