package parser.typeclasses

import parser.core.*

// ============================================================================
// TYPE CLASS INSTANCES
// ============================================================================

// Parser is a Monad (for fixed error type)
given [E]: Monad[[A] =>> Parser[E, A]] with {
  def pure[A](a: A): Parser[E, A] = {
    Parser.Succeed(a)
  }

  def flatMap[A, B](fa: Parser[E, A])(f: A => Parser[E, B]): Parser[E, B] = {
    Parser.FlatMap(fa, f)
  }
}

// Result is a Monad (for fixed error type)
given [E]: Monad[[A] =>> Result[E, A]] with {
  def pure[A](a: A): Result[E, A] = {
    Result.Success(a, 0)
  }

  def flatMap[A, B](fa: Result[E, A])(f: A => Result[E, B]): Result[E, B] = {
    fa match {
      case Result.Success(value, consumed1) => {
        f(value) match {
          case Result.Success(value2, consumed2) => {
            Result.Success(value2, consumed1 + consumed2)
          }
          case Result.Failure(errors, furthest) => {
            Result.Failure(errors, furthest)
          }
        }
      }
      case Result.Failure(errors, furthest) => {
        Result.Failure(errors, furthest)
      }
    }
  }
}

// Show instances
given Show[Location] with {
  def show(loc: Location): String = {
    s"line ${loc.line}, column ${loc.column} (offset ${loc.offset})"
  }
}

given Show[ParseError] with {
  def show(error: ParseError): String = {
    error match {
      case ParseError.Unexpected(found, expected, loc) => {
        val expStr = expected.mkString(", ")
        s"Unexpected '$found' (expected: $expStr) at ${summon[Show[Location]].show(loc)}"
      }
      case ParseError.EndOfInput(expected, loc) => {
        s"Unexpected end of input (expected: $expected) at ${summon[Show[Location]].show(loc)}"
      }
      case ParseError.Custom(message, loc) => {
        s"$message at ${summon[Show[Location]].show(loc)}"
      }
    }
  }
}

given [E: Show, A: Show]: Show[Result[E, A]] with {
  def show(result: Result[E, A]): String = {
    result match {
      case Result.Success(value, consumed) => {
        s"Success(${summon[Show[A]].show(value)}, consumed=$consumed)"
      }
      case Result.Failure(errors, furthest) => {
        val errStrs = errors.map(summon[Show[E]].show).mkString("\n  ")
        s"Failure at ${summon[Show[Location]].show(furthest)}:\n  $errStrs"
      }
    }
  }
}

// Eq instances
given Eq[Location] with {
  def eqv(a: Location, b: Location): Boolean = {
    a.line == b.line && a.column == b.column && a.offset == b.offset
  }
}
