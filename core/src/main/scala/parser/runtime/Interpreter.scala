package parser.runtime

import parser.core._
import parser.core.given

// ============================================================================
// INTERPRETER - Executes Parser Descriptions
// ============================================================================

/**
 * Generates unique parser IDs for left recursion support.
 *
 * Thread-safe counter for assigning unique IDs to recursive parsers.
 */
private val nextParserId = new java.util.concurrent.atomic.AtomicInteger(0)

/**
 * Gets the next unique parser ID.
 *
 * This is used by the recursive() combinator to generate unique IDs.
 */
def getNextParserId(): Int = nextParserId.getAndIncrement()

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
 * Formats a ParseError for debug output.
 *
 * @param error The error to format
 * @return A human-readable string representation
 */
private def formatError(error: Any): String = error match {
  case ParseError.Unexpected(found, expected, loc) =>
    s"Unexpected('$found', expected: ${expected.mkString(", ")}, at $loc)"
  case ParseError.EndOfInput(expected, loc) =>
    s"EndOfInput(expected: $expected, at $loc)"
  case ParseError.Custom(msg, loc) =>
    s"$msg at $loc"
  case other =>
    other.toString
}

/**
 * Attempts to parse with memoization and left-recursion support.
 *
 * Implements the Warth et al. seed-growth algorithm for left recursion.
 *
 * Algorithm:
 * 1. Check memo table for cached result
 * 2. Detect left recursion (parser calling itself at same position)
 * 3. Start with failure seed for left-recursive parsers
 * 4. Grow the seed by repeatedly re-parsing until no more progress
 *
 * @param parserId Unique ID for this parser instance
 * @param parser The parser to execute (lazy)
 * @param state Mutable parse state
 * @return Parse result
 */
private def parseWithMemo[E, A](
  parserId: Int,
  parser: () => Parser[E, A],
  state: ParserState
): Result[E, A] = {
  val key: MemoKey = (parserId = parserId, position = state.offset)

  // Check memo table for cached result
  state.getMemo(key) match {
    case Some(MemoEntry.Completed(result, consumed)) =>
      // Cache hit: restore position and return cached result
      state.advanceN(consumed)
      return result.asInstanceOf[Result[E, A]]

    case Some(MemoEntry.InProgress) =>
      // Left recursion detected! Mark as growing with failure seed
      val seed = Result.Failure(List(), state.location)
      state.setMemo(key, MemoEntry.Growing(seed, 0))
      return seed

    case Some(MemoEntry.Growing(seed, consumed)) =>
      // Currently growing, return current seed
      state.advanceN(consumed)
      return seed.asInstanceOf[Result[E, A]]

    case None =>
    // Not memoized, continue with parsing
  }

  // Mark as in progress for cycle detection
  state.setMemo(key, MemoEntry.InProgress)
  state.enterRecursion(key)

  val startPos      = state.offset
  val startSnapshot = state.save
  val result        = interpret(parser(), state)
  val consumed      = state.offset - startPos

  state.exitRecursion(key)

  // Check if left recursion was actually detected
  state.getMemo(key) match {
    case Some(MemoEntry.InProgress) =>
      // Not left-recursive, just memoize the result
      state.setMemo(key, MemoEntry.Completed(result, consumed))
      result

    case _ =>
      // Was left-recursive (seed was used), grow it
      growSeed(parser, state, key, startSnapshot, result, consumed)
  }
}

/**
 * Grows a left-recursive parse using the seed-growth algorithm.
 *
 * Repeatedly re-parses while we make progress (consume more input).
 * Stops when no more progress is made and returns the largest parse.
 *
 * @param parser The parser to grow
 * @param state Mutable parse state
 * @param key Memoization key
 * @param startSnapshot State snapshot at start position
 * @param initialResult Initial seed result
 * @param initialConsumed Initial characters consumed
 * @return The grown result
 */
private def growSeed[E, A](
  parser: () => Parser[E, A],
  state: ParserState,
  key: MemoKey,
  startSnapshot: StateSnapshot,
  initialResult: Result[E, A],
  initialConsumed: Int
): Result[E, A] = {
  var seed         = initialResult
  var seedConsumed = initialConsumed

  // Keep growing while we make progress
  while (true) {
    // Reset to start position for re-parse
    state.restore(startSnapshot)

    // Mark current seed in memo table
    state.setMemo(key, MemoEntry.Growing(seed, seedConsumed))

    // Clear memoization for all parsers at the start position to allow growth
    // This ensures called parsers re-parse instead of returning stale results
    state.clearMemosAt(startSnapshot.offset, except = key)

    // Re-parse
    val result   = interpret(parser(), state)
    val consumed = state.offset - startSnapshot.offset

    // Check if we made progress
    val madeProgress = result match {
      case Result.Success(_, _) if consumed > seedConsumed    => true
      case Result.Partial(_, _, _) if consumed > seedConsumed => true
      case _                                                  => false
    }

    if (madeProgress) {
      // Made progress, update seed and continue
      seed = result
      seedConsumed = consumed
    } else {
      // No more progress, we're done
      state.setMemo(key, MemoEntry.Completed(seed, seedConsumed))
      state.restore(startSnapshot)
      state.advanceN(seedConsumed)
      return seed
    }
  }

  // Unreachable, but needed for type checker
  seed
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
        case Result.Partial(value, errors, consumed) =>
          Result.Partial(f(value), errors, consumed)
        case Result.Failure(errors, furthest) =>
          Result.Failure(errors, furthest)
      }

    case Parser.FlatMap(source, f) =>
      interpret(source, state) match {
        case Result.Success(value, consumed1) =>
          interpret(f(value), state) match {
            case Result.Success(value2, consumed2) =>
              Result.Success(value2, consumed1 + consumed2)
            case Result.Partial(value2, errors2, consumed2) =>
              Result.Partial(value2, errors2, consumed1 + consumed2)
            case Result.Failure(errors, furthest) =>
              Result.Failure(errors, furthest)
          }
        case Result.Partial(value, errors1, consumed1) =>
          interpret(f(value), state) match {
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

    case Parser.Or(left, right) =>
      val snapshot = state.save
      interpret(left, state) match {
        case success @ Result.Success(_, _)    => success
        case partial @ Result.Partial(_, _, _) => partial
        case Result.Failure(leftErrors, leftFurthest) =>
          state.restore(snapshot)
          interpret(right, state) match {
            case success @ Result.Success(_, _)    => success
            case partial @ Result.Partial(_, _, _) => partial
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
        case Result.Partial(value, errors, consumed) =>
          Result.Partial(Some(value), errors, consumed)
        case Result.Failure(_, _) =>
          state.restore(snapshot)
          Result.Success(None, 0)
      }

    case Parser.Attempt(p) =>
      val snapshot = state.save
      interpret(p, state) match {
        case success @ Result.Success(_, _) =>
          Result.Success(success, 0)
        case partial @ Result.Partial(_, _, _) =>
          Result.Success(partial, 0)
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
        case Result.Partial(value, errors, _) =>
          state.restore(snapshot)
          Result.Partial(value, errors, 0)
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
        case Result.Partial(_, _, _) =>
          state.restore(snapshot)
          Result.Failure(
            List(ParseError.Custom("Unexpected partial success", state.location)),
            state.location
          )
        case Result.Failure(_, _) =>
          state.restore(snapshot)
          Result.Success((), 0)
      }

    case Parser.Named(p, name) =>
      interpret(p, state) match {
        case success @ Result.Success(_, _)    => success
        case partial @ Result.Partial(_, _, _) => partial
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
        case partial @ Result.Partial(_, errors, consumed) =>
          System.err.println(
            s"[TRACE] $label: partial success, consumed $consumed chars with ${errors.length} errors")
          partial
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
        case partial @ Result.Partial(value, errors, _) =>
          val errorList = errors.map(formatError).mkString(", ")
          System.err.println(
            s"[DEBUG] $label: partial success, parsed $value with errors: $errorList")
          partial
        case failure @ Result.Failure(errors, _) =>
          val error = errors.headOption.map(formatError).getOrElse("unknown error")
          System.err.println(s"[DEBUG] $label: failed with $error")
          failure
      }

    case Parser.Custom(runFn) =>
      runFn(state)

    case Parser.Recursive(id, p) =>
      parseWithMemo(id, p, state)
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
  def loop(acc: List[A], accErrors: List[E], totalConsumed: Int): Result[E, List[A]] = {
    val snapshot = state.save
    interpret(p, state) match {
      case Result.Success(value, consumed) =>
        loop(value :: acc, accErrors, totalConsumed + consumed)
      case Result.Partial(value, errors, consumed) =>
        loop(value :: acc, accErrors ++ errors, totalConsumed + consumed)
      case Result.Failure(_, _) =>
        state.restore(snapshot)
        if (accErrors.isEmpty) {
          Result.Success(acc.reverse, totalConsumed)
        } else {
          Result.Partial(acc.reverse, accErrors, totalConsumed)
        }
    }
  }

  loop(Nil, Nil, 0)
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
        case Result.Partial(tail, errors, consumed2) =>
          Result.Partial(head :: tail, errors, consumed1 + consumed2)
        case Result.Failure(errors, furthest) =>
          Result.Failure(errors, furthest)
      }
    case Result.Partial(head, errors1, consumed1) =>
      interpretMany(p, state) match {
        case Result.Success(tail, consumed2) =>
          Result.Partial(head :: tail, errors1, consumed1 + consumed2)
        case Result.Partial(tail, errors2, consumed2) =>
          Result.Partial(head :: tail, errors1 ++ errors2, consumed1 + consumed2)
        case Result.Failure(errors2, furthest) =>
          Result.Failure(errors1 ++ errors2, furthest)
      }
    case Result.Failure(errors, furthest) =>
      Result.Failure(errors, furthest)
  }
