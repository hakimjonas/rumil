package parser.runtime

import parser.core._

// ============================================================================
// INTERPRETER - Executes Parser Descriptions
// ============================================================================

/**
 * Runs a parser on input, producing a result.
 *
 * This is the main entry point for executing parsers.
 *
 * @param parser The parser to execute
 * @param input The input string to parse
 * @return Result containing either success value or error list
 *
 * Example:
 * {{{
 * val parser = char('a') ~ char('b')
 * run(parser, "ab")  // Success(('a', 'b'), 2)
 * }}}
 */
def run[E, A](parser: Parser[E, A], input: String): Result[E, A] = {
  val state = parserState(input)
  interpret(parser, state)
}

/**
 * Interprets a parser against mutable state.
 *
 * This function is public to enable advanced use cases like recursive
 * grammars with Parser.Custom. Most users should use `run` instead.
 *
 * @param parser The parser description to interpret
 * @param state Mutable state tracking parse position
 * @return Result of parsing
 */
def interpret[E, A](parser: Parser[E, A], state: ParserState): Result[E, A] = {
  parser match {

    case Parser.Succeed(value) =>
      Result.Success(value, 0)

    case Parser.Fail(error) =>
      Result.Failure(List(error), state.location)

    case Parser.Satisfy(pred, expected) =>
      state.current match {
        case Some(c) if pred(c) =>
          state.advance()
          Result.Success(c, 1)
        case Some(c) =>
          Result.Failure(
            List(ParseError.Unexpected(c.toString, Set(expected), state.location)),
            state.location
          )
        case None =>
          Result.Failure(
            List(ParseError.EndOfInput(expected, state.location)),
            state.location
          )
      }

    case Parser.Map(source, f) =>
      interpret(source, state) match {
        case Result.Success(value, consumed) =>
          Result.Success(f(value), consumed)
        case Result.Failure(errors, furthest) => Result.Failure(errors, furthest)
      }

    case Parser.FlatMap(source, f) =>
      interpret(source, state) match {
        case Result.Success(value, consumed1) =>
          interpret(f(value), state) match {
            case Result.Success(value2, consumed2) =>
              Result.Success(value2, consumed1 + consumed2)
            case Result.Failure(errors, furthest) => Result.Failure(errors, furthest)
          }
        case Result.Failure(errors, furthest) => Result.Failure(errors, furthest)
      }

    case Parser.Or(left, right) =>
      val snapshot = state.save
      interpret(left, state) match {
        case success @ Result.Success(_, _) => success
        case Result.Failure(leftErrors, leftFurthest) =>
          state.restore(snapshot)
          interpret(right, state) match {
            case success @ Result.Success(_, _) => success
            case Result.Failure(rightErrors, rightFurthest) =>
              if (leftFurthest.offset > rightFurthest.offset) {
                Result.Failure(leftErrors, leftFurthest)
              } else if (rightFurthest.offset > leftFurthest.offset) {
                Result.Failure(rightErrors, rightFurthest)
              } else {
                Result.Failure(leftErrors ++ rightErrors, leftFurthest)
              }
          }
      }

    case Parser.Many(p) =>
      interpretMany(p, state)

    case Parser.Many1(p) =>
      interpretMany1(p, state)

    case Parser.Optional(p) =>
      val snapshot = state.save
      interpret(p, state) match {
        case Result.Success(value, consumed) =>
          Result.Success(Some(value), consumed)
        case Result.Failure(_, _) =>
          state.restore(snapshot)
          Result.Success(None, 0)
      }

    case Parser.Attempt(p) =>
      val snapshot = state.save
      interpret(p, state) match {
        case success @ Result.Success(_, _) =>
          Result.Success(success, 0)
        case failure @ Result.Failure(_, _) =>
          state.restore(snapshot)
          Result.Success(failure, 0)
      }

    case Parser.LookAhead(p) =>
      val snapshot = state.save
      interpret(p, state) match {
        case Result.Success(value, _) =>
          state.restore(snapshot)
          Result.Success(value, 0)
        case failure @ Result.Failure(_, _) =>
          state.restore(snapshot)
          failure
      }

    case Parser.NotFollowedBy(p) =>
      val snapshot = state.save
      interpret(p, state) match {
        case Result.Success(_, _) =>
          state.restore(snapshot)
          Result.Failure(
            List(ParseError.Custom("Unexpected success", state.location)),
            state.location
          )
        case Result.Failure(_, _) =>
          state.restore(snapshot)
          Result.Success((), 0)
      }

    case Parser.Named(p, name) =>
      interpret(p, state) match {
        case success @ Result.Success(_, _) => success
        case Result.Failure(errors, furthest) =>
          val enhanced = errors.map {
            case ParseError.Unexpected(found, expected, loc) =>
              ParseError.Unexpected(found, expected + name, loc)
            case other => other
          }
          Result.Failure(enhanced, furthest)
      }

    case Parser.Trace(p, label) =>
      System.err.println(s"[TRACE] $label: trying at offset ${state.offset}")
      interpret(p, state) match {
        case success @ Result.Success(_, consumed) =>
          System.err.println(s"[TRACE] $label: success, consumed $consumed chars")
          success
        case failure @ Result.Failure(_, _) =>
          System.err.println(s"[TRACE] $label: failed")
          failure
      }

    case Parser.Debug(p, label) =>
      System.err.println(s"[DEBUG] $label: trying at offset ${state.offset}")
      interpret(p, state) match {
        case success @ Result.Success(value, _) =>
          System.err.println(s"[DEBUG] $label: success, parsed $value")
          success
        case failure @ Result.Failure(errors, _) =>
          System.err.println(
            s"[DEBUG] $label: failed with ${errors.headOption.getOrElse("unknown error")}")
          failure
      }

    case Parser.Custom(runFn) =>
      runFn(state)
  }
}

/**
 * Interprets the Many combinator - zero or more repetitions.
 *
 * Uses tail recursion to avoid stack overflow on long inputs.
 * Accumulates results in reverse, then reverses at end for efficiency.
 *
 * @param p The parser to repeat
 * @param state Mutable parse state
 * @return Success with list of all parsed values
 */
private def interpretMany[E, A](p: Parser[E, A], state: ParserState): Result[E, List[A]] = {
  @scala.annotation.tailrec
  def loop(acc: List[A], totalConsumed: Int): Result[E, List[A]] = {
    val snapshot = state.save
    interpret(p, state) match {
      case Result.Success(value, consumed) =>
        loop(value :: acc, totalConsumed + consumed)
      case Result.Failure(_, _) =>
        state.restore(snapshot)
        Result.Success(acc.reverse, totalConsumed)
    }
  }

  loop(Nil, 0)
}

/**
 * Interprets the Many1 combinator - one or more repetitions.
 *
 * Requires at least one match. Implemented as one match followed
 * by Many (zero or more).
 *
 * @param p The parser to repeat
 * @param state Mutable parse state
 * @return Success with non-empty list, or Failure
 */
private def interpretMany1[E, A](p: Parser[E, A], state: ParserState): Result[E, List[A]] =
  interpret(p, state) match {
    case Result.Success(head, consumed1) =>
      interpretMany(p, state) match {
        case Result.Success(tail, consumed2) =>
          Result.Success(head :: tail, consumed1 + consumed2)
        case Result.Failure(errors, furthest) =>
          Result.Failure(errors, furthest)
      }
    case Result.Failure(errors, furthest) =>
      Result.Failure(errors, furthest)
  }
