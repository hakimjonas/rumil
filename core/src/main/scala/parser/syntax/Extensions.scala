package parser.syntax

import parser.core._

// ============================================================================
// EXTENSION METHODS - Ergonomic API
// ============================================================================

extension [E, A](p: Parser[E, A]) {

  // Functor
  inline def map[B](f: A => B): Parser[E, B] =
    parser.core.map(p, f)

  inline def as[B](value: B): Parser[E, B] =
    p.map(_ => value)

  inline def void: Parser[E, Unit] =
    p.as(())

  // Monad
  inline def flatMap[B](f: A => Parser[E, B]): Parser[E, B] =
    parser.core.flatMap(p, f)

  inline def >>[B](f: A => Parser[E, B]): Parser[E, B] =
    flatMap(f)

  // Choice
  inline def |(other: Parser[E, A]): Parser[E, A] =
    parser.core.or(p, other)

  inline def or(other: Parser[E, A]): Parser[E, A] =
    parser.core.or(p, other)

  // Sequencing
  inline def ~[B](that: Parser[E, B]): Parser[E, (A, B)] =
    parser.core.zip(p, that)

  inline def *>[B](that: Parser[E, B]): Parser[E, B] =
    parser.core.zipRight(p, that)

  inline def <*[B](that: Parser[E, B]): Parser[E, A] =
    parser.core.zipLeft(p, that)

  inline def between[L, R](left: Parser[E, L], right: Parser[E, R]): Parser[E, A] =
    parser.core.between(p, left, right)

  // Repetition
  inline def many: Parser[E, List[A]] =
    parser.core.many(p)

  inline def many1: Parser[E, List[A]] =
    parser.core.many1(p)

  /** Alias for many1 with a clearer name */
  inline def manyNonEmpty: Parser[E, List[A]] =
    parser.core.many1(p)

  inline def optional: Parser[E, Option[A]] =
    parser.core.optional(p)

  inline def sepBy[Sep](sep: Parser[E, Sep]): Parser[E, List[A]] =
    parser.core.sepBy(p, sep)

  /** Alias for sepBy with a clearer name */
  inline def separatedBy[Sep](sep: Parser[E, Sep]): Parser[E, List[A]] =
    parser.core.sepBy(p, sep)

  inline def sepBy1[Sep](sep: Parser[E, Sep]): Parser[E, List[A]] =
    parser.core.sepBy1(p, sep)

  /** Alias for sepBy1 with a clearer name */
  inline def separatedByNonEmpty[Sep](sep: Parser[E, Sep]): Parser[E, List[A]] =
    parser.core.sepBy1(p, sep)

  inline def endBy[End](end: Parser[E, End]): Parser[E, List[A]] =
    parser.core.endBy(p, end)

  /** Alias for endBy with a clearer name */
  inline def endedBy[End](end: Parser[E, End]): Parser[E, List[A]] =
    parser.core.endBy(p, end)

  inline def count(n: Int): Parser[E, List[A]] =
    parser.core.count(n, p)

  inline def times(n: Int): Parser[E, List[A]] =
    parser.core.times(n, p)

  /** Parses zero or more, discarding results */
  inline def skipMany: Parser[E, Unit] =
    parser.core.skipMany(p)

  /** Parses one or more, discarding results */
  inline def skipManyNonEmpty: Parser[E, Unit] =
    parser.core.skipManyNonEmpty(p)

  /** Parses at least n occurrences */
  inline def manyAtLeast(n: Int): Parser[E, List[A]] =
    parser.core.manyAtLeast(n)(p)

  /** Parses p between same delimiter on both sides */
  inline def surroundedBy[Delim](delim: Parser[E, Delim]): Parser[E, A] =
    parser.core.surroundedBy(delim)(p)

  // Operators
  inline def chainl1(op: Parser[E, (A, A) => A]): Parser[E, A] =
    parser.core.chainl1(p, op)

  /** Alias for chainl1 with a clearer name */
  inline def chainLeft1(op: Parser[E, (A, A) => A]): Parser[E, A] =
    parser.core.chainl1(p, op)

  inline def chainr1(op: Parser[E, (A, A) => A]): Parser[E, A] =
    parser.core.chainr1(p, op)

  /** Alias for chainr1 with a clearer name */
  inline def chainRight1(op: Parser[E, (A, A) => A]): Parser[E, A] =
    parser.core.chainr1(p, op)

  /** Left-associative chain with default value */
  inline def chainLeft(op: Parser[E, (A, A) => A], default: A): Parser[E, A] =
    parser.core.chainLeft(p, op, default)

  /** Right-associative chain with default value */
  inline def chainRight(op: Parser[E, (A, A) => A], default: A): Parser[E, A] =
    parser.core.chainRight(p, op, default)

  // Lookahead
  inline def lookAhead: Parser[E, A] =
    parser.core.lookAhead(p)

  // Error handling
  inline def attempt: Parser[Nothing, Result[E, A]] =
    parser.core.attempt(p)

  inline def recover(f: E => A): Parser[Nothing, A] =
    parser.core.recover(p)(f)

  inline def recoverWith[E2](f: E => Parser[E2, A]): Parser[E2, A] =
    parser.core.recoverWith(p)(f)

  /**
   * Tries this parser, falling back to another if it fails (fast alternation).
   *
   * This is simple alternation without error tracking. When this parser fails,
   * the fallback is tried. If fallback succeeds, returns Success with no error
   * information from the failed primary parser.
   *
   * Use `orElse` for simple alternation. Use `recover` when you need error tracking.
   *
   * Example:
   * {{{
   * val letter = char('a').orElse(char('b')).orElse(char('c'))
   * letter.run("b")  // Success('b', 1) - fast, no error tracking
   * }}}
   */
  inline def orElse(fallback: Parser[E, A]): Parser[E, A] =
    parser.core.orElse(p, fallback)

  /**
   * Tries this parser, falling back to another with error tracking (resilient parsing).
   *
   * When this parser fails, the fallback runs and the result is `Partial`
   * to preserve the original error information. This enables resilient
   * parsing where errors are accumulated but parsing continues.
   *
   * Use `recover` for error recovery with tracking. Use `orElse` for fast alternation.
   *
   * Example:
   * {{{
   * val number = digit.many1.map(_.mkString.toInt)
   * number.recover(succeed(0)).run("abc")  // Partial(0, errors, 0) - has errors!
   * }}}
   */
  inline def recover(fallback: Parser[E, A]): Parser[E, A] =
    parser.core.recover(p, fallback)

  // Debugging
  inline def trace(label: String): Parser[E, A] =
    parser.core.trace(p, label)

  inline def debug(label: String): Parser[E, A] =
    parser.core.debug(p, label)

  // Memoization
  /**
   * Memoizes parser results for improved performance on expensive parsers.
   *
   * Caches parse results by position to avoid redundant work. Unlike `rule`,
   * this does NOT support left-recursion - use `rule` for left-recursive grammars.
   *
   * Performance: Simple caching is ~50% faster than LR-capable memoization.
   *
   * Use cases:
   * - Expensive inline combinations: `(p1 ~ p2 ~ p3).memoize`
   * - Performance-critical parsers that are not left-recursive
   * - Avoiding the overhead of defining named rules
   *
   * Example:
   * {{{
   * val expensiveParser = (complexRegex ~ validation ~ transformation).memoize
   *
   * // Without memoize: parser runs multiple times at same position
   * // With memoize: cached after first successful parse
   * }}}
   *
   * @return A memoized parser with cached results
   */
  inline def memoize: Parser[E, A] =
    parser.core.memoize(p)

  // Execution
  inline def run(input: String): Result[E, A] =
    parser.runtime.run(p, input)
}

// ParseError-specific extensions
extension [A](p: Parser[ParseError, A]) {
  inline def notFollowedBy: Parser[ParseError, Unit] =
    parser.core.notFollowedBy(p)

  inline def named(name: String): Parser[ParseError, A] =
    parser.core.named(p, name)

  inline def label(name: String): Parser[ParseError, A] =
    named(name)

  /**
   * Replaces parser errors with a custom message.
   *
   * When the parser fails, all errors are replaced with a single
   * `ParseError.Custom` containing the provided message.
   *
   * Example:
   * {{{
   * val email = emailParser.expect("valid email address required")
   * email.run("invalid")  // Failure: "valid email address required"
   * }}}
   */
  inline def expect(message: String): Parser[ParseError, A] =
    parser.core.expect(p, message)
}

extension [E, A](result: Result[E, A]) {
  inline def isSuccess: Boolean = result match {
    case Result.Success(_, _)    => true
    case Result.Partial(_, _, _) => false
    case Result.Failure(_, _)    => false
  }

  inline def isPartial: Boolean = result match {
    case Result.Partial(_, _, _) => true
    case _                       => false
  }

  inline def isFailure: Boolean = result match {
    case Result.Failure(_, _) => true
    case _                    => false
  }

  inline def toEither: Either[List[E], A] = result match {
    case Result.Success(value, _)    => Right(value)
    case Result.Partial(value, _, _) => Right(value)
    case Result.Failure(errors, _)   => Left(errors)
  }

  inline def toOption: Option[A] = result match {
    case Result.Success(value, _)    => Some(value)
    case Result.Partial(value, _, _) => Some(value)
    case Result.Failure(_, _)        => None
  }

  inline def errors: List[E] = result match {
    case Result.Success(_, _)       => List.empty
    case Result.Partial(_, errs, _) => errs
    case Result.Failure(errs, _)    => errs
  }
}
