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

  // Repetition
  inline def many: Parser[E, List[A]] =
    parser.core.many(p)

  inline def many1: Parser[E, List[A]] =
    parser.core.many1(p)

  inline def optional: Parser[E, Option[A]] =
    parser.core.optional(p)

  inline def sepBy[Sep](sep: Parser[E, Sep]): Parser[E, List[A]] =
    parser.core.sepBy(p, sep)

  inline def sepBy1[Sep](sep: Parser[E, Sep]): Parser[E, List[A]] =
    parser.core.sepBy1(p, sep)

  inline def endBy[End](end: Parser[E, End]): Parser[E, List[A]] =
    parser.core.endBy(p, end)

  inline def count(n: Int): Parser[E, List[A]] =
    parser.core.count(n, p)

  // Operators
  inline def chainl1(op: Parser[E, (A, A) => A]): Parser[E, A] =
    parser.core.chainl1(p, op)

  inline def chainr1(op: Parser[E, (A, A) => A]): Parser[E, A] =
    parser.core.chainr1(p, op)

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

  // Debugging
  inline def trace(label: String): Parser[E, A] =
    parser.core.trace(p, label)

  inline def debug(label: String): Parser[E, A] =
    parser.core.debug(p, label)

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

  /**
   * Maps the success value using the provided function.
   *
   * Enables functional transformations and for-comprehension support.
   *
   * @param f The transformation function
   * @return A new Result with the transformed value
   */
  inline def map[B](f: A => B): Result[E, B] = result match {
    case Result.Success(value, consumed) =>
      Result.Success(f(value), consumed)
    case Result.Partial(value, errors, consumed) =>
      Result.Partial(f(value), errors, consumed)
    case Result.Failure(errors, furthest) =>
      Result.Failure(errors, furthest)
  }

  /**
   * Chains this result with another result-producing computation.
   *
   * Enables monadic composition and for-comprehension support.
   * Combines consumed counts and accumulates errors properly.
   *
   * @param f Function producing the next Result
   * @return Combined result with accumulated consumption and errors
   *
   * Example:
   * {{{
   * val result1: Result[ParseError, Int] = Result.Success(42, 2)
   * val result2 = result1.flatMap(n => Result.Success(n * 2, 3))
   * // Result.Success(84, 5)
   * }}}
   */
  inline def flatMap[B](f: A => Result[E, B]): Result[E, B] = result match {
    case Result.Success(value, consumed1) =>
      f(value) match {
        case Result.Success(value2, consumed2) =>
          Result.Success(value2, consumed1 + consumed2)
        case Result.Partial(value2, errors2, consumed2) =>
          Result.Partial(value2, errors2, consumed1 + consumed2)
        case Result.Failure(errors, furthest) =>
          Result.Failure(errors, furthest)
      }
    case Result.Partial(value, errors1, consumed1) =>
      f(value) match {
        case Result.Success(value2, consumed2) =>
          Result.Partial(value2, errors1, consumed1 + consumed2)
        case Result.Partial(value2, errors2, consumed2) =>
          Result.Partial(value2, errors1 ++ errors2, consumed1 + consumed2)
        case Result.Failure(errors2, furthest) =>
          Result.Failure(errors1 ++ errors2, furthest)
      }
    case Result.Failure(errors, furthest) =>
      Result.Failure(errors, furthest)
  }
}
