package parser.typeclasses

import parser.core.*

/** Type-class instances for the parser surface: `Parser` and `Result` as [[Monad]]s, and
  * [[Show]]/[[Eq]] rendering for locations and errors.
  *
  * The `Result` monad's `flatMap` propagates the left value's consumed count and merges error
  * lists, so sequencing two results accumulates both sides' errors — the same behavior the
  * resilient parsers rely on.
  */
// Parser is a Monad (for fixed error type)
given [E] => Monad[[A] =>> Parser[E, A]] {
  def pure[A](a: A): Parser[E, A] =
    Parser.Succeed(a)

  def flatMap[A, B](fa: Parser[E, A])(f: A => Parser[E, B]): Parser[E, B] =
    Parser.FlatMap(fa, f)
}

// Result is a Monad (for fixed error type)
given [E] => Monad[[A] =>> Result[E, A]] {
  def pure[A](a: A): Result[E, A] =
    Result.Success(a, 0)

  def flatMap[A, B](fa: Result[E, A])(f: A => Result[E, B]): Result[E, B] =
    fa match {
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

// Show instances
/** `line L, column C (offset O)` — line/column 1-based, offset 0-based. */
given Show[Location] {
  def show(loc: Location): String =
    s"line ${loc.line}, column ${loc.column} (offset ${loc.offset})"
}

given Show[ParseError] {
  def show(error: ParseError): String =
    error match {
      case ParseError.Unexpected(found, expected, loc) =>
        val expStr = expected.mkString(", ")
        s"Unexpected '$found' (expected: $expStr) at ${summon[Show[Location]].show(loc)}"
      case ParseError.EndOfInput(expected, loc) =>
        s"Unexpected end of input (expected: $expected) at ${summon[Show[Location]].show(loc)}"
      case ParseError.Custom(message, loc) =>
        s"$message at ${summon[Show[Location]].show(loc)}"
    }
}

given [E: Show, A: Show] => Show[Result[E, A]] {
  def show(result: Result[E, A]): String =
    result match {
      case Result.Success(value, consumed) =>
        s"Success(${summon[Show[A]].show(value)}, consumed=$consumed)"
      case Result.Partial(value, errors, consumed) =>
        val errStrs = errors.map(summon[Show[E]].show).mkString("\n  ")
        s"Partial(${summon[Show[A]].show(value)}, consumed=$consumed) with errors:\n  $errStrs"
      case Result.Failure(errors, furthest) =>
        val errStrs = errors.map(summon[Show[E]].show).mkString("\n  ")
        s"Failure at ${summon[Show[Location]].show(furthest)}:\n  $errStrs"
    }
}

// Eq instances
given Eq[Location] {
  def eqv(a: Location, b: Location): Boolean =
    a.line == b.line && a.column == b.column && a.offset == b.offset
}
