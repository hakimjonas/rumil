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
 * Interprets a parser against mutable state.
 *
 * This is the core interpreter that executes parser descriptions.
 * Most users should use `run` instead.
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

    case Parser.StringMatch(target) =>
      val startLoc = state.location
      val len      = target.length
      // Check if we have enough input remaining
      if (state.offset + len > state.input.length) {
        Result.Failure(
          List(ParseError.EndOfInput(s"\"$target\"", startLoc)),
          startLoc
        )
      } else {
        // Compare substring directly (O(n) but no allocations in tight loop)
        var i       = 0
        var matched = true
        while (i < len && matched) {
          if (state.input.charAt(state.offset + i) != target.charAt(i)) {
            matched = false
          }
          i += 1
        }
        if (matched) {
          state.advanceN(len)
          Result.Success(target, len)
        } else {
          val found =
            state.input.substring(state.offset, math.min(state.offset + len, state.input.length))
          Result.Failure(
            List(ParseError.Unexpected(found, Set(s"\"$target\""), startLoc)),
            startLoc
          )
        }
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

    case Parser.Defer(thunk) =>
      interpret(thunk(), state)

    case Parser.Eof() =>
      if (state.atEnd) {
        Result.Success((), 0)
      } else {
        Result.Failure(
          List(ParseError.Custom("Expected end of input", state.location)),
          state.location
        )
      }

    case Parser.RecoverWith(p, recovery) =>
      val snapshot = state.save
      interpret(p, state) match {
        case success @ Result.Success(_, _)    => success
        case partial @ Result.Partial(_, _, _) => partial
        case Result.Failure(errors, furthest) =>
          state.restore(snapshot)
          interpret(recovery, state) match {
            case Result.Success(value, consumed) =>
              // Recovered successfully, but note the original errors
              Result.Partial(value, errors, consumed)
            case Result.Partial(value, recoveryErrors, consumed) =>
              // Recovery was partial, combine all errors
              Result.Partial(value, errors ++ recoveryErrors, consumed)
            case Result.Failure(recoveryErrors, recoveryFurthest) =>
              // Both failed, combine errors and use furthest location
              val combinedErrors = errors ++ recoveryErrors
              val finalFurthest =
                if (furthest.offset > recoveryFurthest.offset) furthest
                else recoveryFurthest
              Result.Failure(combinedErrors, finalFurthest)
          }
      }

    case Parser.Expect(p, message) =>
      interpret(p, state) match {
        case success @ Result.Success(_, _)    => success
        case partial @ Result.Partial(_, _, _) => partial
        case Result.Failure(_, furthest)       =>
          // Replace the error with a custom message
          Result.Failure(
            List(ParseError.Custom(message, furthest)),
            furthest
          )
      }

    case Parser.Memo(inner, key) =>
      if (DEBUG_LR)
        System.err.println(s"[LR] Parser.Memo: key=$key, lrStack size before=${state.lrStack.size}")
      val result = interpretMemo(inner, key, state)
      if (DEBUG_LR)
        System.err.println(
          s"[LR] Parser.Memo: key=$key done, lrStack size after=${state.lrStack.size}")
      result
  }
}

/**
 * Interprets a memoized parser with left recursion support.
 *
 * Implements the seed-growth algorithm from Warth et al.:
 * 1. Check memo table for cached result
 * 2. If not cached, mark as "in progress" with LR marker
 * 3. If LR detected, return seed and setup head
 * 4. Otherwise, parse and cache result
 * 5. If this is the head of a left-recursive cycle, grow the seed
 *
 * @param inner The inner parser to interpret
 * @param key Type-safe memo key for this parser rule
 * @param state Mutable parse state with memo tables
 * @return Parse result
 */
// Debug flag - set to true to trace indirect left recursion
private val DEBUG_LR = false

private def interpretMemo[E, A](
  inner: Parser[E, A],
  key: MemoKey[E, A],
  state: ParserState): Result[E, A] = {
  val pos           = state.offset
  val startSnapshot = state.save // Capture line/column for seed growth

  if (DEBUG_LR) {
    val headInfo = state.heads
      .get(pos)
      .map(h => s"head=${h.rule}, involved=${h.involvedSet}, eval=${h.evalSet}")
      .getOrElse("no head")
    System.err.println(s"[LR] interpretMemo key=$key pos=$pos $headInfo")
  }

  // RECALL check for indirect left recursion (Warth et al.)
  // If we're inside a grow-LR phase and this rule is in the evalSet,
  // we need to re-evaluate it instead of returning the cached result.
  state.heads.get(pos) match {
    case Some(head) if head.evalSet.contains(key) =>
      if (DEBUG_LR) System.err.println(s"[LR]   -> in evalSet, re-evaluating fresh")
      // This rule needs re-evaluation during seed growth
      head.evalSet.remove(key)
      // Re-evaluate this rule FRESH - bypass memo entirely and parse inner directly
      // This is necessary for indirect left recursion where the involved rule
      // has a stale cached result from the initial parse
      val result = interpret(inner, state)
      val endPos = state.offset
      // Update memo with fresh result
      state.memo.put(key, pos, result, endPos)
      result

    case Some(head) if head.rule eq key =>
      // This is the HEAD rule during grow phase - return current seed
      if (DEBUG_LR) System.err.println(s"[LR]   -> this IS the head, returning seed")
      state.memo.getRaw(key, pos) match {
        case Some(Left(lr)) =>
          castSeed[E, A](lr.seed)
        case Some(Right(entry)) =>
          // Return the cached/grown result
          state.restore((offset = entry.pos, line = state.line, column = state.column))
          state.memo.getResult(key, pos).get
        case None =>
          // Shouldn't happen but fall back to normal evaluation
          evaluateMemo(inner, key, pos, startSnapshot, state)
      }

    case Some(head) if head.involvedSet.contains(key) =>
      if (DEBUG_LR) System.err.println(s"[LR]   -> in involvedSet but not evalSet")
      // Rule is involved in cycle but not in evalSet - return cached/LR result
      state.memo.getRaw(key, pos) match {
        case Some(Left(lr)) =>
          if (DEBUG_LR) System.err.println(s"[LR]   -> returning seed: ${lr.seed}")
          // Return the seed for this rule
          castSeed[E, A](lr.seed)
        case Some(Right(entry)) =>
          if (DEBUG_LR) System.err.println(s"[LR]   -> returning cached result")
          state.restore((offset = entry.pos, line = state.line, column = state.column))
          state.memo.getResult(key, pos).get
        case None =>
          if (DEBUG_LR) System.err.println(s"[LR]   -> no entry, evaluating normally")
          // No entry yet - evaluate normally
          evaluateMemo(inner, key, pos, startSnapshot, state)
      }

    case _ =>
      // Normal case - not involved in current head's cycle
      evaluateMemo(inner, key, pos, startSnapshot, state)
  }
}

/**
 * Core memoization logic, separated for RECALL handling.
 */
private def evaluateMemo[E, A](
  inner: Parser[E, A],
  key: MemoKey[E, A],
  pos: Int,
  startSnapshot: StateSnapshot,
  state: ParserState): Result[E, A] =
  state.memo.getRaw(key, pos) match {
    case Some(Left(lr)) =>
      if (DEBUG_LR) System.err.println(s"[LR]   evaluateMemo: found LR marker, calling setupLR")
      // Left recursion detected - mark this LR as having a head (we're in a cycle)
      setupLR(key, lr, state)
      // LR.seed is type-erased but we know it matches our key's type
      castSeed[E, A](lr.seed)

    case Some(Right(entry)) =>
      if (DEBUG_LR) System.err.println(s"[LR]   evaluateMemo: returning cached result")
      // Cached result - restore position and return
      state.restore((offset = entry.pos, line = state.line, column = state.column))
      // Use type-safe retrieval through MemoTable
      state.memo.getResult(key, pos).get

    case None =>
      // First time seeing this parser at this position
      if (DEBUG_LR) System.err.println(s"[LR]   evaluateMemo: first time, pushing LR for $key")
      val lr = LR(
        seed = Result.Failure(List.empty, state.location),
        rule = key,
        head = None
      )
      state.lrStack.append(lr)
      state.memo.putLR(key, pos, lr)
      if (DEBUG_LR)
        System.err.println(s"[LR]   evaluateMemo: lrStack now has ${state.lrStack.size} items")

      // Parse the inner parser
      val result = interpret(inner, state)
      val endPos = state.offset

      if (DEBUG_LR)
        System.err.println(
          s"[LR]   evaluateMemo: popping LR for $key, lrStack had ${state.lrStack.size} items")
      state.lrStack.remove(state.lrStack.length - 1)

      // Check if left recursion was detected during parsing
      lr.head match {
        case None =>
          // No left recursion - just cache and return
          state.memo.put(key, pos, result, endPos)
          result

        case Some(head) if !(head.rule eq key) =>
          // Left recursion detected, but we're not the head - just return result
          state.memo.put(key, pos, result, endPos)
          result

        case Some(_) =>
          // We are the head of the left-recursive cycle - grow the seed
          result match {
            case _: Result.Failure[?, ?] =>
              // Base case failed, cache and return
              state.memo.put(key, pos, result, endPos)
              result
            case _ =>
              // Base case succeeded - now grow it
              lr.seed = eraseSeed(result)
              growLR(inner, key, startSnapshot, lr, endPos, state)
          }
      }
  }

// =============================================================================
// Seed Type Erasure Helpers
// =============================================================================
// These are the ONLY casts in the interpreter, isolated here with safety proofs.

/**
 * Erase seed type for storage in LR marker.
 *
 * SAFETY: The LR marker is keyed by the same MemoKey[E, A] that will be used
 * to retrieve it, so the type is recoverable through castSeed.
 */
private def eraseSeed[E, A](result: Result[E, A]): Result[Any, Any] =
  result.asInstanceOf[Result[Any, Any]]

/**
 * Cast erased seed back to typed result.
 *
 * SAFETY: This cast is safe because:
 * 1. The seed was stored with eraseSeed for a specific MemoKey[E, A]
 * 2. The same MemoKey[E, A] is used to retrieve it
 * 3. Therefore the erased type matches [E, A]
 */
private def castSeed[E, A](result: Result[Any, Any]): Result[E, A] =
  result.asInstanceOf[Result[E, A]]

/**
 * Sets up the left recursion head when a cycle is detected.
 *
 * When we detect LR (by finding our own LR marker on the stack), we need to:
 * 1. Find or create the HEAD for this cycle
 * 2. Mark all rules between the head and this LR as "involved" in the cycle
 *
 * The HEAD should be the OUTERMOST left-recursive rule (first on stack).
 * When there are nested LR rules (like expr -> term where both are LR),
 * the outermost rule (expr) should be the head.
 */
private def setupLR(key: AnyRef, lr: LR, state: ParserState): Unit = {
  if (DEBUG_LR) {
    System.err.println(s"[LR] setupLR: key=$key, lrStack size=${state.lrStack.size}")
    state.lrStack.foreach(slr =>
      System.err.println(s"[LR]   stack item: ${slr.rule}, head=${slr.head.map(_.rule)}"))
  }

  // Find if there's an existing head on the stack that should be the actual head
  // Look for the outermost LR that already has a head set
  val existingHead = state.lrStack.find(_.head.isDefined).flatMap(_.head)

  val actualHead = existingHead match {
    case Some(h) =>
      // Reuse existing head (the outermost LR rule)
      if (DEBUG_LR) System.err.println(s"[LR] setupLR: reusing existing head ${h.rule}")
      lr.head = Some(h)
      // When we're an inner LR rule and find an existing outer head,
      // we (key) should be added to the head's involvedSet - but NOT if we ARE the head
      if (!(key eq h.rule)) {
        h.involvedSet.add(key)
        if (DEBUG_LR) System.err.println(s"[LR] setupLR: added $key to involvedSet of ${h.rule}")
      }
      h
    case None =>
      // Create new head for this rule
      if (lr.head.isEmpty) {
        lr.head = Some(
          new LRHead(key, scala.collection.mutable.Set.empty, scala.collection.mutable.Set.empty))
        if (DEBUG_LR) System.err.println(s"[LR] setupLR: created head for $key")
      }
      lr.head.get
  }

  // Mark all LRs on the stack (between us and the head) as involved in this cycle
  // This handles the case where there are intermediate rules
  for (stackLr <- state.lrStack.reverseIterator
    if !(stackLr.rule eq key) && !(stackLr.rule eq actualHead.rule)) {
    stackLr.head = Some(actualHead)
    actualHead.involvedSet.add(stackLr.rule)
    if (DEBUG_LR)
      System.err.println(
        s"[LR] setupLR: added ${stackLr.rule} to involvedSet of ${actualHead.rule}")
  }
}

/**
 * Grows the seed for a left-recursive rule until no more progress is made.
 *
 * This is the core of the seed-growth algorithm. We repeatedly:
 * 1. Reset position to start
 * 2. Update memo with current seed
 * 3. Re-parse the rule
 * 4. If we made progress (consumed more input), update seed and continue
 * 5. Stop when no more progress is made
 *
 * Type safety: The key carries type parameters [E, A] ensuring all operations
 * maintain type consistency throughout the seed growth process.
 *
 * @param startSnapshot The saved state (offset, line, column) at rule start,
 *                      used to correctly restore position with accurate line/column
 */
private def growLR[E, A](
  inner: Parser[E, A],
  key: MemoKey[E, A],
  startSnapshot: StateSnapshot,
  lr: LR,
  seedEndPos: Int,
  state: ParserState
): Result[E, A] = {
  val pos = startSnapshot.offset
  state.heads.put(pos, lr.head.get)

  var lastResult: Result[E, A] = castSeed[E, A](lr.seed)
  var lastPos                  = seedEndPos
  // Track the ending line/column for accurate restoration
  var lastLine   = state.line
  var lastColumn = state.column

  // Keep growing while we make progress
  var continue = true
  while (continue) {
    // Reset position to start of this rule with correct line/column
    state.restore(startSnapshot)

    // Update memo with current seed so recursive calls see it
    state.memo.put(key, pos, lastResult, lastPos)

    lr.head.get.evalSet = lr.head.get.involvedSet.clone()

    val result    = interpret(inner, state)
    val resultPos = state.offset

    result match {
      case _: Result.Failure[?, ?] =>
        // Failed - stop growing
        continue = false
      case _ if resultPos <= lastPos =>
        // No progress - stop growing
        continue = false
      case _ =>
        // Made progress - update seed and continue
        lastResult = result
        lastPos = resultPos
        lastLine = state.line
        lastColumn = state.column
        lr.seed = eraseSeed(result)
    }
  }

  state.heads.remove(pos)
  // Restore to final position with accurate line/column
  state.restore((offset = lastPos, line = lastLine, column = lastColumn))
  state.memo.put(key, pos, lastResult, lastPos)
  lastResult
}

/**
 * Interprets the Many combinator - zero or more repetitions.
 *
 * Uses ArrayBuffer for O(1) append, converts to List at end.
 * This is significantly faster than prepend-then-reverse for long sequences.
 *
 * @param p The parser to repeat
 * @param state Mutable parse state
 * @return Success with list of all parsed values
 */
private def interpretMany[E, A](p: Parser[E, A], state: ParserState): Result[E, List[A]] = {
  val acc       = scala.collection.mutable.ArrayBuffer.empty[A]
  var accErrors = List.empty[E]
  var totalConsumed = 0
  var continue  = true

  while (continue) {
    val snapshot = state.save
    interpret(p, state) match {
      case Result.Success(value, consumed) =>
        acc += value
        totalConsumed += consumed
      case Result.Partial(value, errors, consumed) =>
        acc += value
        accErrors = accErrors ++ errors
        totalConsumed += consumed
      case Result.Failure(_, _) =>
        state.restore(snapshot)
        continue = false
    }
  }

  if (accErrors.isEmpty) {
    Result.Success(acc.toList, totalConsumed)
  } else {
    Result.Partial(acc.toList, accErrors, totalConsumed)
  }
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
