package parser.runtime

import parser.core._

// ============================================================================
// INTERNAL RESULT TYPE - Lazy Error Construction
// ============================================================================

/**
 * Lazy failure wrapper - only this case differs from public Result.
 *
 * Holds a thunk `() => List[E]` instead of `List[E]`, allowing us to defer
 * error construction until we know the error is actually needed.
 *
 * During backtracking (Or, Choice, Optional), if one branch succeeds, the
 * failed branch's error thunk is never evaluated - saving significant allocation.
 */
final private[runtime] case class LazyFailure[+E](mkErrors: () => List[E], furthest: Location)

/**
 * Internal result type used during interpretation.
 *
 * This is a union of the public Result success/partial types with LazyFailure.
 * Success and Partial reuse Result types directly (no double allocation).
 * Only Failure uses a separate lazy wrapper.
 */
private[runtime] type IResult[+E, +A] = Result.Success[E, A] | Result.Partial[E, A] | LazyFailure[E]

/** Convert IResult to public Result */
private[runtime] def toResult[E, A](ir: IResult[E, A]): Result[E, A] = ir match {
  case s: Result.Success[?, ?]  => s.asInstanceOf[Result[E, A]]
  case p: Result.Partial[?, ?]  => p.asInstanceOf[Result[E, A]]
  case LazyFailure(mkErrs, loc) => Result.Failure(mkErrs(), loc)
}

// ============================================================================
// INTERPRETER - Executes Parser Descriptions
// ============================================================================

/**
 * Runs a parser on input, producing a result.
 *
 * This is the main entry point for executing parsers. Uses a stack-safe
 * trampolined interpreter that can handle arbitrarily deep FlatMap chains
 * without risk of stack overflow.
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
  // Use optimized trampolined interpreter for stack safety
  // TrampolineOpt is faster than Hybrid on most workloads (see benchmarks)
  toResult(TrampolineOpt.run(parser, state))
}

/**
 * Runs a parser using the recursive (non-stack-safe) interpreter.
 *
 * This version uses direct recursion and may stack overflow on deeply
 * nested FlatMap chains. Use this only for debugging or when you are
 * certain your parsers have bounded depth.
 *
 * @param parser The parser to execute
 * @param input The input string to parse
 * @return Result containing either success value or error list
 */
def runRecursive[E, A](parser: Parser[E, A], input: String): Result[E, A] = {
  val state = parserState(input)
  toResult(interpretI(parser, state))
}

/**
 * Runs a parser using the zero-cast experimental interpreter.
 *
 * This version achieves full type safety with minimal runtime casts by using
 * GADT Continuations and Scala's TailCalls trampoline. It's approximately
 * 2-3x slower than the optimized TrampolineOpt due to TailRec allocations.
 *
 * This implementation demonstrates that parser combinators can achieve
 * near-perfect type safety, with only 6 localized casts needed for dynamic
 * continuation composition. The performance penalty is due to TailRec overhead,
 * which could be eliminated in a language with proper tail call optimization.
 *
 * @param parser The parser to execute
 * @param input The input string to parse
 * @return Result containing either success value or error list
 */
def runZeroCast[E, A](parser: Parser[E, A], input: String): Result[E, A] = {
  val state = parserState(input)
  toResult(experimental.TrampolineZeroCast.run(parser, state))
}

/**
 * Public interpret function that returns Result directly.
 *
 * This is exposed for advanced use cases (e.g., pre-created state).
 * Most users should use `run` instead.
 */
def interpret[E, A](parser: Parser[E, A], state: ParserState): Result[E, A] =
  toResult(interpretI(parser, state))

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
 * Internal interpreter with lazy error construction.
 *
 * Returns IResult which defers error construction until needed.
 * This significantly reduces allocation during backtracking.
 *
 * @param parser The parser description to interpret
 * @param state Mutable state tracking parse position
 * @return Internal result with lazy errors
 */
private[runtime] def interpretI[E, A](parser: Parser[E, A], state: ParserState): IResult[E, A] = {
  parser match {

    case Parser.Succeed(value) =>
      Result.Success(value, 0)

    case Parser.Fail(error) =>
      val loc = state.location
      LazyFailure(() => List(error), loc)

    case Parser.Satisfy(pred, expected) =>
      // Use inline methods to avoid Option boxing on hot path
      if (state.hasChar) {
        val c = state.currentChar
        if (pred(c)) {
          state.advance()
          Result.Success(c, 1)
        } else {
          val loc   = state.location
          val found = c.toString // Capture for lazy thunk
          LazyFailure(
            () => List(ParseError.Unexpected(found, Set(expected), loc)),
            loc
          )
        }
      } else {
        val loc = state.location
        LazyFailure(
          () => List(ParseError.EndOfInput(expected, loc)),
          loc
        )
      }

    case Parser.StringMatch(target) =>
      val len = target.length
      // Check if we have enough input remaining
      if (state.offset + len > state.input.length) {
        val loc = state.location // Only compute location on failure
        LazyFailure(
          () => List(ParseError.EndOfInput(s"\"$target\"", loc)),
          loc
        )
      } else {
        // Use regionMatches for optimized string comparison (JVM intrinsic)
        if (state.input.regionMatches(state.offset, target, 0, len)) {
          state.advanceByString(target)
          Result.Success(target, len)
        } else {
          val loc = state.location // Only compute location on failure
          val found =
            state.input.substring(state.offset, math.min(state.offset + len, state.input.length))
          LazyFailure(
            () => List(ParseError.Unexpected(found, Set(s"\"$target\""), loc)),
            loc
          )
        }
      }

    case Parser.StringChoice(radix, targets) =>
      // Optimized choice of strings using radix tree - O(m) matching
      interpretStringChoice(radix, targets, state)

    case Parser.Map(source, f) =>
      interpretI(source, state) match {
        case Result.Success(value, consumed) =>
          Result.Success(f(value), consumed)
        case Result.Partial(value, errors, consumed) =>
          Result.Partial(f(value), errors, consumed)
        case LazyFailure(mkErrs, loc) =>
          LazyFailure(mkErrs, loc)
      }

    case Parser.FlatMap(source, f) =>
      interpretI(source, state) match {
        case Result.Success(value, consumed1) =>
          interpretI(f(value), state) match {
            case Result.Success(value2, consumed2) =>
              Result.Success(value2, consumed1 + consumed2)
            case Result.Partial(value2, errors2, consumed2) =>
              Result.Partial(value2, errors2, consumed1 + consumed2)
            case LazyFailure(mkErrs, loc) =>
              LazyFailure(mkErrs, loc)
          }
        case Result.Partial(value, errors1, consumed1) =>
          interpretI(f(value), state) match {
            case Result.Success(value2, consumed2) =>
              Result.Partial(value2, errors1, consumed1 + consumed2)
            case Result.Partial(value2, errors2, consumed2) =>
              Result.Partial(value2, errors1 ++ errors2, consumed1 + consumed2)
            case LazyFailure(mkErrors2, furthest) =>
              // Need to force errors here since we're combining with materialized errors1
              LazyFailure(() => errors1 ++ mkErrors2(), furthest)
          }
        case LazyFailure(mkErrs, loc) =>
          LazyFailure(mkErrs, loc)
      }

    case Parser.Or(left, right) =>
      val snapshot = state.save
      interpretI(left, state) match {
        case success @ Result.Success(_, _)          => success
        case partial @ Result.Partial(_, _, _)       => partial
        case LazyFailure(leftMkErrors, leftFurthest) =>
          // Left failed - try right. leftMkErrors NOT evaluated yet!
          state.restore(snapshot)
          interpretI(right, state) match {
            case success @ Result.Success(_, _)            => success // leftMkErrors never called!
            case partial @ Result.Partial(_, _, _)         => partial // leftMkErrors never called!
            case LazyFailure(rightMkErrors, rightFurthest) =>
              // Both failed - combine errors lazily
              if (leftFurthest.offset > rightFurthest.offset) {
                LazyFailure(leftMkErrors, leftFurthest) // rightMkErrors never called!
              } else if (rightFurthest.offset > leftFurthest.offset) {
                LazyFailure(rightMkErrors, rightFurthest) // leftMkErrors never called!
              } else {
                // Same position - combine both (still lazy)
                LazyFailure(() => leftMkErrors() ++ rightMkErrors(), leftFurthest)
              }
          }
      }

    case Parser.Choice(alternatives) =>
      interpretChoiceI(alternatives, state, state.save, () => Nil, state.location)

    case Parser.Many(p) =>
      interpretManyI(p, state)

    case Parser.Many1(p) =>
      interpretMany1I(p, state)

    case Parser.Optional(p) =>
      val snapshot = state.save
      interpretI(p, state) match {
        case Result.Success(value, consumed) =>
          Result.Success(Some(value), consumed)
        case Result.Partial(value, errors, consumed) =>
          Result.Partial(Some(value), errors, consumed)
        case LazyFailure(_, _) =>
          // Failure discarded - error thunk never called!
          state.restore(snapshot)
          Result.Success(None, 0)
      }

    case Parser.Attempt(p) =>
      val snapshot = state.save
      // Attempt needs to return Result, so we convert here
      interpretI(p, state) match {
        case Result.Success(v, c) =>
          Result.Success(Result.Success(v, c), 0)
        case Result.Partial(v, e, c) =>
          Result.Success(Result.Partial(v, e, c), 0)
        case LazyFailure(mkErrs, loc) =>
          state.restore(snapshot)
          // Force errors here since we're wrapping in Success
          Result.Success(Result.Failure(mkErrs(), loc), 0)
      }

    case Parser.LookAhead(p) =>
      val snapshot = state.save
      interpretI(p, state) match {
        case Result.Success(value, _) =>
          state.restore(snapshot)
          Result.Success(value, 0)
        case Result.Partial(value, errors, _) =>
          state.restore(snapshot)
          Result.Partial(value, errors, 0)
        case failure @ LazyFailure(_, _) =>
          state.restore(snapshot)
          failure
      }

    case Parser.NotFollowedBy(p) =>
      val snapshot = state.save
      interpretI(p, state) match {
        case Result.Success(_, _) =>
          state.restore(snapshot)
          val loc = state.location
          LazyFailure(
            () => List(ParseError.Custom("Unexpected success", loc)),
            loc
          )
        case Result.Partial(_, _, _) =>
          state.restore(snapshot)
          val loc = state.location
          LazyFailure(
            () => List(ParseError.Custom("Unexpected partial success", loc)),
            loc
          )
        case LazyFailure(_, _) =>
          // Failure discarded - error thunk never called!
          state.restore(snapshot)
          Result.Success((), 0)
      }

    case Parser.Named(p, name) =>
      interpretI(p, state) match {
        case success @ Result.Success(_, _)    => success
        case partial @ Result.Partial(_, _, _) => partial
        case LazyFailure(mkErrors, furthest)   =>
          // Defer enhancement until errors are needed
          LazyFailure(
            () =>
              mkErrors().map {
                case ParseError.Unexpected(found, expected, loc) =>
                  ParseError.Unexpected(found, expected + name, loc)
                case other => other
              },
            furthest
          )
      }

    case Parser.Trace(p, label) =>
      System.err.println(s"[TRACE] $label: trying at offset ${state.offset}")
      interpretI(p, state) match {
        case success @ Result.Success(_, consumed) =>
          System.err.println(s"[TRACE] $label: success, consumed $consumed chars")
          success
        case partial @ Result.Partial(_, errors, consumed) =>
          System.err.println(
            s"[TRACE] $label: partial success, consumed $consumed chars with ${errors.length} errors")
          partial
        case failure @ LazyFailure(_, _) =>
          System.err.println(s"[TRACE] $label: failed")
          failure
      }

    case Parser.Debug(p, label) =>
      System.err.println(s"[DEBUG] $label: trying at offset ${state.offset}")
      // Debug needs to force errors for printing
      interpretI(p, state) match {
        case success @ Result.Success(value, _) =>
          System.err.println(s"[DEBUG] $label: success, parsed $value")
          success
        case partial @ Result.Partial(value, errors, _) =>
          val errorList = errors.map(formatError).mkString(", ")
          System.err.println(
            s"[DEBUG] $label: partial success, parsed $value with errors: $errorList")
          partial
        case LazyFailure(mkErrors, loc) =>
          val errors = mkErrors() // Force for debug output
          val error  = errors.headOption.map(formatError).getOrElse("unknown error")
          System.err.println(s"[DEBUG] $label: failed with $error")
          LazyFailure(() => errors, loc) // Re-wrap as lazy (already evaluated)
      }

    case Parser.Defer(thunk) =>
      interpretI(thunk(), state)

    case Parser.Eof() =>
      if (state.atEnd) {
        Result.Success((), 0)
      } else {
        val loc = state.location
        LazyFailure(
          () => List(ParseError.Custom("Expected end of input", loc)),
          loc
        )
      }

    case Parser.RecoverWith(p, recovery) =>
      val snapshot = state.save
      interpretI(p, state) match {
        case success @ Result.Success(_, _)    => success
        case partial @ Result.Partial(_, _, _) => partial
        case LazyFailure(mkErrors, furthest) =>
          state.restore(snapshot)
          // Force errors here since recovery produces Partial which needs List[E]
          val errors = mkErrors()
          interpretI(recovery, state) match {
            case Result.Success(value, consumed) =>
              // Recovered successfully, but note the original errors
              Result.Partial(value, errors, consumed)
            case Result.Partial(value, recoveryErrors, consumed) =>
              // Recovery was partial, combine all errors
              Result.Partial(value, errors ++ recoveryErrors, consumed)
            case LazyFailure(mkRecoveryErrors, recoveryFurthest) =>
              // Both failed, combine errors lazily
              val finalFurthest =
                if (furthest.offset > recoveryFurthest.offset) furthest
                else recoveryFurthest
              LazyFailure(() => errors ++ mkRecoveryErrors(), finalFurthest)
          }
      }

    case Parser.Expect(p, message) =>
      interpretI(p, state) match {
        case success @ Result.Success(_, _)    => success
        case partial @ Result.Partial(_, _, _) => partial
        case LazyFailure(_, furthest)          =>
          // Replace the error with a custom message (still lazy)
          LazyFailure(
            () => List(ParseError.Custom(message, furthest)),
            furthest
          )
      }

    case Parser.Memo(inner, key, enableLR) =>
      if (enableLR) {
        // Full left-recursion support using seed-growth algorithm
        if (DEBUG_LR)
          System.err.println(
            s"[LR] Parser.Memo: key=$key, lrStack size before=${state.lrStack.size}")
        val result = interpretMemoI(inner, key, state)
        if (DEBUG_LR)
          System.err.println(
            s"[LR] Parser.Memo: key=$key done, lrStack size after=${state.lrStack.size}")
        result
      } else {
        // Fast path: simple caching without LR overhead
        interpretSimpleMemoI(inner, key, state)
      }
  }
}

/**
 * Interprets a memoized parser with left recursion support (returns IResult).
 *
 * Implements the seed-growth algorithm from Warth et al.:
 * 1. Check memo table for cached result
 * 2. If not cached, mark as "in progress" with LR marker
 * 3. If LR detected, return seed and setup head
 * 4. Otherwise, parse and cache result
 * 5. If this is the head of a left-recursive cycle, grow the seed
 *
 * Note: The memo table stores Result (not IResult) because:
 * - Seeds need to be materialized for the LR algorithm
 * - Cached results are already computed
 * We convert back to IResult at the boundary for consistency.
 *
 * @param inner The inner parser to interpret
 * @param key Type-safe memo key for this parser rule
 * @param state Mutable parse state with memo tables
 * @return Internal result (lazy errors)
 */
// Debug flag - set to true to trace indirect left recursion
private val DEBUG_LR = false

private def interpretMemoI[E, A](
  inner: Parser[E, A],
  key: MemoKey[E, A],
  state: ParserState): IResult[E, A] =
  // Delegate to Result-based implementation and convert
  resultToIResult(interpretMemoResult(inner, key, state))

/** Convert Result to IResult (wrap errors in thunk that returns them) */
private def resultToIResult[E, A](result: Result[E, A]): IResult[E, A] = result match {
  case Result.Success(v, c)      => Result.Success(v, c)
  case Result.Partial(v, e, c)   => Result.Partial(v, e, c)
  case Result.Failure(errs, loc) => LazyFailure(() => errs, loc)
}

/** The actual memo implementation, works with Result for LR seed storage */
private def interpretMemoResult[E, A](
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
      // Force to Result for memo storage
      val result = toResult(interpretI(inner, state))
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
          evaluateMemoResult(inner, key, pos, startSnapshot, state)
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
          evaluateMemoResult(inner, key, pos, startSnapshot, state)
      }

    case _ =>
      // Normal case - not involved in current head's cycle
      evaluateMemoResult(inner, key, pos, startSnapshot, state)
  }
}

/**
 * Core memoization logic, separated for RECALL handling.
 * Works with Result for LR seed storage.
 */
private def evaluateMemoResult[E, A](
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

      // Parse the inner parser - force to Result for memo storage
      val result = toResult(interpretI(inner, state))
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
              growLRResult(inner, key, startSnapshot, lr, endPos, state)
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
 * Works with Result for LR seed storage.
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
private def growLRResult[E, A](
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

    // Force to Result for memo storage
    val result    = toResult(interpretI(inner, state))
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

// =============================================================================
// Simple Memoization (Non-LR) - Fast Path
// =============================================================================

/**
 * Fast path for simple memoization without left-recursion support.
 *
 * Performance optimizations vs LR path:
 * - No heads.get(pos) lookup
 * - No lrStack manipulation
 * - No Either[LR, Entry] unpacking
 * - No Option[Result] wrapping
 * - Direct result storage and retrieval
 *
 * Approximately 50% faster than LR path for cache hits.
 *
 * Returns IResult (not Result) to match interpretI signature.
 */
private def interpretSimpleMemoI[E, A](
  inner: Parser[E, A],
  key: MemoKey[E, A],
  state: ParserState): IResult[E, A] = {
  val pos = state.offset

  // Check cache
  state.simpleCache.get(key, pos) match {
    case Some(entry) =>
      // Cache hit - restore position and return cached result
      state.restore((offset = entry.pos, line = state.line, column = state.column))
      // Convert cached Result back to IResult
      resultToIResult(castSimpleCacheResult[E, A](entry.result))

    case None =>
      // Cache miss - parse and cache result
      val result = interpretI(inner, state)
      val endPos = state.offset

      // Force to Result for cache storage (since cache stores Result, not IResult)
      val forcedResult = toResult(result)
      state.simpleCache.put(key, pos, forcedResult, endPos)

      // Return the original IResult (not the forced Result)
      result
  }
}

/**
 * Cast cached result back to typed result.
 *
 * SAFETY: This cast is safe because:
 * 1. The result was stored with a specific MemoKey[E, A]
 * 2. The same MemoKey[E, A] is used to retrieve it
 * 3. Therefore the erased type matches [E, A]
 */
private def castSimpleCacheResult[E, A](result: Result[Any, Any]): Result[E, A] =
  result.asInstanceOf[Result[E, A]]

/**
 * Interprets the Many combinator - zero or more repetitions (returns IResult).
 *
 * Uses ArrayBuffer for O(1) append, converts to List at end.
 * This is significantly faster than prepend-then-reverse for long sequences.
 *
 * @param p The parser to repeat
 * @param state Mutable parse state
 * @return Success with list of all parsed values
 */
private def interpretManyI[E, A](p: Parser[E, A], state: ParserState): IResult[E, List[A]] = {
  val acc           = scala.collection.mutable.ArrayBuffer.empty[A]
  var accErrors     = List.empty[E]
  var totalConsumed = 0
  var continue      = true

  while (continue) {
    val snapshot = state.save
    interpretI(p, state) match {
      case Result.Success(value, consumed) =>
        acc += value
        totalConsumed += consumed
      case Result.Partial(value, errors, consumed) =>
        acc += value
        accErrors = accErrors ++ errors
        totalConsumed += consumed
      case LazyFailure(_, _) =>
        // Failure discarded - error thunk never called!
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
 * Interprets the Many1 combinator - one or more repetitions (returns IResult).
 *
 * Requires at least one match. Implemented as one match followed
 * by Many (zero or more).
 *
 * @param p The parser to repeat
 * @param state Mutable parse state
 * @return Success with non-empty list, or Failure
 */
private def interpretMany1I[E, A](p: Parser[E, A], state: ParserState): IResult[E, List[A]] =
  interpretI(p, state) match {
    case Result.Success(head, consumed1) =>
      interpretManyI(p, state) match {
        case Result.Success(tail, consumed2) =>
          Result.Success(head :: tail, consumed1 + consumed2)
        case Result.Partial(tail, errors, consumed2) =>
          Result.Partial(head :: tail, errors, consumed1 + consumed2)
        case LazyFailure(mkErrs, loc) =>
          LazyFailure(mkErrs, loc)
      }
    case Result.Partial(head, errors1, consumed1) =>
      interpretManyI(p, state) match {
        case Result.Success(tail, consumed2) =>
          Result.Partial(head :: tail, errors1, consumed1 + consumed2)
        case Result.Partial(tail, errors2, consumed2) =>
          Result.Partial(head :: tail, errors1 ++ errors2, consumed1 + consumed2)
        case LazyFailure(mkErrors2, furthest) =>
          LazyFailure(() => errors1 ++ mkErrors2(), furthest)
      }
    case LazyFailure(mkErrs, loc) =>
      LazyFailure(mkErrs, loc)
  }

/**
 * Interprets the Choice combinator - try alternatives in sequence (returns IResult).
 *
 * Tail-recursive implementation that tries each alternative until one succeeds.
 * Tracks the furthest error location for good error messages.
 * Uses lazy error construction - errors from failed alternatives are only
 * materialized if ALL alternatives fail.
 *
 * @param remaining Alternatives left to try
 * @param state Mutable parse state
 * @param snapshot Saved state for backtracking
 * @param accMkErrors Accumulated error thunks from failed alternatives
 * @param furthest Furthest location reached by any alternative
 * @return First successful result, or failure with best error info
 */
@scala.annotation.tailrec
private def interpretChoiceI[E, A](
  remaining: List[Parser[E, A]],
  state: ParserState,
  snapshot: StateSnapshot,
  accMkErrors: () => List[E],
  furthest: Location
): IResult[E, A] = remaining match {
  case Nil =>
    LazyFailure(accMkErrors, furthest)
  case head :: tail =>
    interpretI(head, state) match {
      case success @ Result.Success(_, _)    => success // accMkErrors never called!
      case partial @ Result.Partial(_, _, _) => partial // accMkErrors never called!
      case LazyFailure(mkErrs, loc) =>
        state.restore(snapshot)
        val (newMkErrors, newFurthest) =
          if (loc.offset > furthest.offset) (mkErrs, loc) // accMkErrors never called!
          else if (loc.offset == furthest.offset) {
            // Same position - need to combine thunks lazily
            val prevMkErrors = accMkErrors
            (() => prevMkErrors() ++ mkErrs(), furthest)
          } else (accMkErrors, furthest) // mkErrs never called!
        interpretChoiceI(tail, state, snapshot, newMkErrors, newFurthest)
    }
}

/**
 * Optimized interpreter for StringChoice - choice of string literals.
 *
 * This avoids allocating intermediate IResult objects during backtracking.
 * Instead, we loop through the alternatives with simple string comparisons,
 * only allocating a result at the very end.
 *
 * @param targets Array of string alternatives to try
 * @param state Parser state
 * @return Success with matched string, or Failure
 */
private def interpretStringChoice(
  radix: RadixNode,
  targets: Array[String],
  state: ParserState
): IResult[ParseError, String] = {
  val input  = state.input
  val offset = state.offset

  // Use radix tree for O(m) matching where m = length of matched string
  val matched = radix.matchAtOrNull(input, offset)

  if (matched ne null) {
    state.advanceByString(matched)
    Result.Success(matched, matched.length)
  } else {
    // No match - construct error lazily
    val loc      = state.location
    val inputLen = input.length
    val maxLen   = targets.map(_.length).max
    val found    = input.substring(offset, math.min(offset + maxLen, inputLen))
    val expected = targets.map(s => s"\"$s\"").toSet
    LazyFailure(
      () => List(ParseError.Unexpected(found, expected, loc)),
      loc
    )
  }
}
