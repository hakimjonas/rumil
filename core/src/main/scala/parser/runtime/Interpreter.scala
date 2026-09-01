package parser.runtime

import scala.compiletime.asMatchable

import parser.core.*

/** Lazy failure wrapper - defers error construction until needed.
  *
  * Holds a thunk `() => List[E]` instead of `List[E]`, allowing us to defer error construction
  * until we know the error is actually needed.
  *
  * During backtracking (Or, Choice, Optional), if one branch succeeds, the failed branch's error
  * thunk is never evaluated - saving significant allocation.
  */
final private[runtime] case class LazyFailure[+E](mkErrors: () => List[E], furthest: Location)

/** Lazy partial wrapper - defers error construction in partial results.
  *
  * Like LazyFailure, this holds a thunk `() => List[E]` to defer error construction. Used when
  * RecoverWith succeeds with fallback - we have a successful parse value but also accumulated
  * errors.
  *
  * During error accumulation (Many, sepBy), if many LazyPartial results are combined, the error
  * thunks remain unevaluated until the final toResult call. This significantly reduces allocation
  * in error-heavy parsing scenarios.
  *
  * Example: Many with orElse recovery parsing 1000 items with 900 errors:
  *   - Without LazyPartial: 900 error thunk evaluations during Many
  *   - With LazyPartial: 1 batch error evaluation at toResult
  */
final private[runtime] case class LazyPartial[+E, +A](
  value: A,
  mkErrors: () => List[E],
  consumed: Int
)

/** Internal result type used during interpretation.
  *
  * This is a union of the public Result success type with lazy wrappers for partial and failure
  * cases.
  *   - Success reuses Result.Success directly (no double allocation)
  *   - LazyPartial defers Partial error construction
  *   - LazyFailure defers Failure error construction
  */
private[runtime] type IResult[+E, +A] = Result.Success[E, A] | LazyPartial[E, A] | LazyFailure[E]

/** Shared empty-error thunk used by the sentinel `LazyFailure`. Reused across every discarded
  * failure so there is no per-iteration closure allocation.
  */
private val NilErrorsThunk: () => List[Nothing] = () => Nil

/** Location stored inside the sentinel `LazyFailure`. Nonsense coordinates — this sentinel's
  * `loc`/`mkErrs` are only read if something upstream decided NOT to discard after all, which is a
  * bug (the `errorsDiscarded` flag is the contract).
  */
private val SentinelLocation: Location =
  (line = -1, column = -1, offset = -1)

/** Pre-constructed sentinel returned by terminals when the interpreter is inside a context that
  * declared it will discard inner failures (`state.errorsDiscarded == true`). Zero allocation per
  * discarded-failure path: no ParseError, no `Set[String]`, no Location, no thunk.
  *
  * Contract: whoever observes a `SentinelLazyFailure` must discard it. `Many`/`SkipMany`/
  * `Optional`/`NotFollowedBy` are canonical consumers. Combinators that DO read inner errors
  * (`Attempt`, `RecoverWith`, `Memo`, `Named`, `Expect`) toggle the flag off around their inner
  * call to keep real error payloads flowing.
  */
private val SentinelLazyFailure: LazyFailure[Nothing] =
  LazyFailure(NilErrorsThunk, SentinelLocation)

/** Convert IResult to public Result */
private[runtime] def toResult[E, A](ir: IResult[E, A]): Result[E, A] = ir match {
  case s @ Result.Success(_, _) => s
  case LazyPartial(v, mkErrs, c) => Result.Partial(v, mkErrs(), c)
  case LazyFailure(mkErrs, loc) => Result.Failure(mkErrs(), loc)
}

/** Runs a parser on input, producing a result.
  *
  * This is the main entry point for executing parsers. Uses a stack-safe trampolined interpreter
  * that can handle arbitrarily deep FlatMap chains without risk of stack overflow.
  *
  * @param parser
  *   The parser to execute
  * @param input
  *   The input string to parse
  * @return
  *   Result containing either success value or error list
  *
  * Example:
  * {{{
  * val parser = char('a') ~ char('b')
  * run(parser, "ab")  // Success(('a', 'b'), 2)
  * }}}
  */
def run[E, A](parser: Parser[E, A], input: String): Result[E, A] = {
  val state = parserState(input)
  toResult(TrampolineOpt.run(parser, state))
}

/** Runs a parser using the recursive (non-stack-safe) interpreter.
  *
  * This version uses direct recursion and may stack overflow on deeply nested FlatMap chains. Use
  * this only for debugging or when you are certain your parsers have bounded depth.
  *
  * @param parser
  *   The parser to execute
  * @param input
  *   The input string to parse
  * @return
  *   Result containing either success value or error list
  */
def runRecursive[E, A](parser: Parser[E, A], input: String): Result[E, A] = {
  val state = parserState(input)
  toResult(interpretI(parser, state))
}

/** Public interpret function that returns Result directly.
  *
  * This is exposed for advanced use cases (e.g., pre-created state). Most users should use `run`
  * instead.
  */
def interpret[E, A](parser: Parser[E, A], state: ParserState): Result[E, A] =
  toResult(interpretI(parser, state))

/** Formats a ParseError for debug output.
  *
  * @param error
  *   The error to format
  * @return
  *   A human-readable string representation
  */
private def formatError(error: Matchable): String = error match {
  case ParseError.Unexpected(found, expected, loc) =>
    s"Unexpected('$found', expected: ${expected.mkString(", ")}, at $loc)"
  case ParseError.EndOfInput(expected, loc) =>
    s"EndOfInput(expected: $expected, at $loc)"
  case ParseError.Custom(msg, loc) =>
    s"$msg at $loc"
  case other =>
    other.toString
}

/** Internal interpreter with lazy error construction.
  *
  * Returns IResult which defers error construction until needed. This significantly reduces
  * allocation during backtracking.
  *
  * @param parser
  *   The parser description to interpret
  * @param state
  *   Mutable state tracking parse position
  * @return
  *   Internal result with lazy errors
  */
private[runtime] def interpretI[E, Elem, A](parser: ParserK[E, Elem, A], state: ParserState): IResult[E, A] = {
  parser match {

    case Parser.Succeed(value) =>
      Result.Success(value, 0)

    case Parser.Fail(error) =>
      if state.errorsDiscarded then SentinelLazyFailure
      else {
        val loc = state.location
        LazyFailure(() => List(error), loc)
      }

    case Parser.FailWith(message) =>
      if state.errorsDiscarded then SentinelLazyFailure
      else {
        val loc = state.location
        LazyFailure(() => List(ParseError.Custom(message, loc)), loc)
      }

    case Parser.GetOffset() =>
      Result.Success(state.offset, 0)

    case Parser.Satisfy(pred, expected) =>
      interpretSatisfyI(pred, expected, state)

    case Parser.StringMatch(target) =>
      interpretStringMatchI(target, state)

    case Parser.StringChoice(radix, targets) =>
      interpretStringChoice(radix, targets, state)

    case Parser.FirstCharChoice(table, expected, fallback) =>
      interpretFirstCharChoiceI(table, expected, fallback, state)

    case Parser.Map(source, f) =>
      interpretMapI(source, f, state)

    case Parser.FlatMap(source, f) =>
      interpretFlatMapI(source, f, state)

    case Parser.Zip(left, right) =>
      interpretZipI(left, right, state)

    case Parser.SkipLeft(left, right) =>
      interpretSkipLeftI(left, right, state)

    case Parser.SkipRight(left, right) =>
      interpretSkipRightI(left, right, state)

    case Parser.Or(left, right) =>
      interpretOrI(left, right, state)

    case Parser.Choice(alternatives) =>
      interpretChoiceI(alternatives, state, state.save, () => Nil, state.location)

    case Parser.Many(p) =>
      interpretManyI(p, state)

    case Parser.Many1(p) =>
      interpretMany1I(p, state)

    case Parser.SkipMany(p) =>
      interpretSkipManyI(p, state)

    case Parser.Capture(p) =>
      interpretCaptureI(p, state)

    case Parser.Optional(p) =>
      interpretOptionalI(p, state)

    case Parser.Attempt(p) =>
      interpretAttemptI(p, state)

    case Parser.LookAhead(p) =>
      interpretLookAheadI(p, state)

    case Parser.NotFollowedBy(p) =>
      interpretNotFollowedByI(p, state)

    case Parser.Named(p, name) =>
      interpretNamedI(p, name, state)

    case Parser.Trace(p, label) =>
      interpretTraceI(p, label, state)

    case Parser.Debug(p, label) =>
      interpretDebugI(p, label, state)

    case Parser.Defer(thunk) =>
      interpretI(thunk(), state)

    case Parser.Eof() =>
      interpretEofI(state)

    case Parser.RecoverWith(p, recovery) =>
      interpretRecoverWithI(p, recovery, state)

    case Parser.Expect(p, message) =>
      interpretExpectI(p, message, state)

    case Parser.Memo(inner, key, enableLR) =>
      interpretMemoArmI(inner, key, enableLR, state)

    case Parser.Pratt(nud, getOp, minBp, _) =>
      interpretPrattI(nud, getOp, minBp, state)

    case ig: Parser.InternedGreen[?, ?, ?, ?] =>
      interpretInternedGreenI(ig, state)
  }
}

/** Handler for [[Parser.Satisfy]] — the one generalized element-consuming primitive. Extracted from
  * `interpretI`'s dispatch so the dispatch match stays a lean delegate-per-arm shape (keeps
  * `interpretI` under C2's 8000-byte huge-method ceiling).
  *
  * `interpretI` is abstract over `Elem` and reads the next input element through the element cursor
  * (`hasElement`/`currentElement`), not the Char-specific accessors. Two casts bridge the
  * element-erased `ParserState` (Char-backed under route A) to the abstract `Elem`:
  *   - `pred` to `Any => Boolean`: the element source produces exactly the `Elem` this Satisfy was
  *     built for (one-element-per-parse invariant — same discipline as the GreenCache / MemoTable
  *     erasure casts);
  *   - the matched element to `A`: Satisfy's ADT case is `ParserK[ParseError, Elem, Elem]`, so the
  *     produced value type A IS Elem; the element is the value.
  * For the Char path (`Elem = Char`) `currentElement` is `charAt` and the `pred` apply boxes the
  * Char exactly as `Char => Boolean` does today — the specialization is free. The zero-alloc
  * char-scan fast paths (interpretManySatisfy etc.) bypass this handler entirely.
  */
private def interpretSatisfyI[Elem, A](
  pred: Elem => Boolean,
  expected: String,
  state: ParserState
): IResult[ParseError, A] = {
  val test = pred.asInstanceOf[Any => Boolean] // scalafix:ok DisableSyntax.asInstanceOf
  if state.hasElement then {
    val e = state.currentElement
    if test(e) then {
      state.advance()
      Result.Success(e.asInstanceOf[A], 1) // scalafix:ok DisableSyntax.asInstanceOf
    } else if state.errorsDiscarded then {
      SentinelLazyFailure
    } else {
      val loc = state.location
      LazyFailure(
        () => List(ParseError.Unexpected(e.toString, Set(expected), loc)),
        loc
      )
    }
  } else if state.errorsDiscarded then {
    SentinelLazyFailure
  } else {
    val loc = state.location
    LazyFailure(
      () => List(ParseError.EndOfInput(expected, loc)),
      loc
    )
  }
}

/** Handler for [[Parser.StringMatch]] — region-match against the raw input with EOI/mismatch
  * branches. Extracted from `interpretI`'s dispatch to keep the dispatch lean (huge-method
  * ceiling).
  */
private def interpretStringMatchI(target: String, state: ParserState): IResult[ParseError, String] = {
  val len = target.length
  if state.offset + len > state.input.length then {
    if state.errorsDiscarded then SentinelLazyFailure
    else {
      val loc = state.location
      LazyFailure(
        () => List(ParseError.EndOfInput(s"\"$target\"", loc)),
        loc
      )
    }
  } else {
    if state.input.regionMatches(state.offset, target, 0, len) then {
      state.advanceByString(target)
      Result.Success(target, len)
    } else if state.errorsDiscarded then {
      SentinelLazyFailure
    } else {
      val loc = state.location
      val input = state.input
      val start = state.offset
      val endOffset = math.min(start + len, input.length)
      LazyFailure(
        () => List(ParseError.Unexpected(input.substring(start, endOffset), Set(s"\"$target\""), loc)),
        loc
      )
    }
  }
}

/** Handler for [[Parser.FirstCharChoice]] — peek the next char and dispatch to the mapped parser
  * (or `fallback`). A pure "pick the next parser" decision: no snapshot, no backtracking. On a
  * dispatch miss with no fallback it synthesizes a `ParseError` (Unexpected / EndOfInput) whose
  * expected set names the concatenated dispatch chars, matching rumil-dart's "one of \"...\""
  * shape.
  */
private def interpretFirstCharChoiceI[A](
  table: Map[Char, Parser[ParseError, A]],
  expected: String,
  fallback: Option[Parser[ParseError, A]],
  state: ParserState
): IResult[ParseError, A] = {
  if state.hasChar then {
    table.get(state.currentChar) match {
      case Some(p) => interpretI(p, state)
      case None =>
        fallback match {
          case Some(fb) => interpretI(fb, state)
          case None =>
            if state.errorsDiscarded then SentinelLazyFailure
            else {
              val loc = state.location
              LazyFailure(
                () => List(ParseError.Unexpected(state.currentChar.toString, Set(s"one of \"$expected\""), loc)),
                loc
              )
            }
        }
    }
  } else {
    fallback match {
      case Some(fb) => interpretI(fb, state)
      case None =>
        if state.errorsDiscarded then SentinelLazyFailure
        else {
          val loc = state.location
          LazyFailure(
            () => List(ParseError.EndOfInput(s"one of \"$expected\"", loc)),
            loc
          )
        }
    }
  }
}

/** Handler for [[Parser.Or]] — the FIRST-set fast path, snapshot/backtrack, and furthest-error
  * merge. Extracted from `interpretI`'s dispatch to keep the dispatch lean (huge-method ceiling).
  *
  * FIRST-set check: when `left` begins with a shape whose failure at the current char is decidable
  * from a single-char peek, skip `interpretI(left)` and run `right` directly. Terminal cases
  * (Satisfy, StringMatch, Eof) and peelable wrappers (Map, Zip-left, LookAhead, FlatMap) are
  * recognised; anything else falls through to the full interpret.
  *
  * This gives up left-side error contribution on the fast path (same tradeoff the prior
  * FlatMap(simple, _) fast path already made), in exchange for skipping the LazyFailure + closure +
  * Location allocation per backtrack.
  */
private def interpretOrI[E, Elem, A](
  left: ParserK[E, Elem, A],
  right: ParserK[E, Elem, A],
  state: ParserState
): IResult[E, A] = {
  // The snapshot is taken only when the left branch is actually attempted: a lookahead-decided
  // skip (e.g. a StringMatch left whose prefix is absent) never needs a restore, and this path
  // runs ~900 times in the many-with-misses benchmark shape.
  if orLookaheadFails(left, state) then {
    interpretI(right, state)
  } else {
    val snapshot = state.save
    interpretI(left, state) match {
      case success @ Result.Success(_, _) => success
      case partial @ LazyPartial(_, _, _) => partial
      case leftFailure @ LazyFailure(leftMkErrors, leftFurthest) =>
        state.restore(snapshot)
        interpretI(right, state) match {
          case success @ Result.Success(_, _) => success
          case partial @ LazyPartial(_, _, _) => partial
          case rightFailure @ LazyFailure(rightMkErrors, rightFurthest) =>
            if state.errorsDiscarded then {
              // Outer context will drop the merged errors — return the left sentinel rather
              // than allocating a fresh LazyFailure + merge closure. Any LazyFailure value
              // satisfies the caller since it's about to be thrown away.
              leftFailure
            } else if leftFurthest.offset > rightFurthest.offset then {
              leftFailure
            } else if rightFurthest.offset > leftFurthest.offset then {
              rightFailure
            } else {
              LazyFailure(() => leftMkErrors() ++ rightMkErrors(), leftFurthest)
            }
        }
    }
  }
}

/** Handler for [[Parser.FlatMap]] — monadic sequencing with lazy error threading. Extracted from
  * `interpretI`'s dispatch to keep the dispatch lean (huge-method ceiling).
  */
private def interpretFlatMapI[E, Elem, A, B](
  source: ParserK[E, Elem, A],
  f: A => ParserK[E, Elem, B],
  state: ParserState
): IResult[E, B] =
  interpretI(source, state) match {
    case Result.Success(value, consumed1) =>
      interpretI(f(value), state) match {
        case Result.Success(value2, consumed2) =>
          Result.Success(value2, consumed1 + consumed2)
        case LazyPartial(value2, mkErrs2, consumed2) =>
          LazyPartial(value2, mkErrs2, consumed1 + consumed2)
        case LazyFailure(mkErrs, loc) =>
          LazyFailure(mkErrs, loc)
      }
    case LazyPartial(value, mkErrors1, consumed1) =>
      interpretI(f(value), state) match {
        case Result.Success(value2, consumed2) =>
          LazyPartial(value2, mkErrors1, consumed1 + consumed2)
        case LazyPartial(value2, mkErrors2, consumed2) =>
          LazyPartial(value2, () => mkErrors1() ++ mkErrors2(), consumed1 + consumed2)
        case LazyFailure(mkErrors2, furthest) =>
          LazyFailure(() => mkErrors1() ++ mkErrors2(), furthest)
      }
    case LazyFailure(mkErrs, loc) =>
      LazyFailure(mkErrs, loc)
  }

/** Handler for [[Parser.Trace]] — always-cold debug instrumentation. Extracted from `interpretI`'s
  * dispatch (never hot; freely extracted) to keep the dispatch lean.
  */
private def interpretTraceI[E, Elem, A](p: ParserK[E, Elem, A], label: String, state: ParserState): IResult[E, A] = {
  System.err.println(s"[TRACE] $label: trying at offset ${state.offset}")
  interpretI(p, state) match {
    case success @ Result.Success(_, consumed) =>
      System.err.println(s"[TRACE] $label: success, consumed $consumed chars")
      success
    case partial @ LazyPartial(_, mkErrs, consumed) =>
      val errors = mkErrs()
      System.err.println(s"[TRACE] $label: partial success, consumed $consumed chars with ${errors.length} errors")
      LazyPartial(partial.value, () => errors, consumed)
    case failure @ LazyFailure(_, _) =>
      System.err.println(s"[TRACE] $label: failed")
      failure
  }
}

/** Handler for [[Parser.Debug]] — always-cold debug instrumentation. Extracted from `interpretI`'s
  * dispatch (never hot; freely extracted) to keep the dispatch lean.
  */
private def interpretDebugI[E, Elem, A](p: ParserK[E, Elem, A], label: String, state: ParserState): IResult[E, A] = {
  System.err.println(s"[DEBUG] $label: trying at offset ${state.offset}")
  interpretI(p, state) match {
    case success @ Result.Success(value, _) =>
      System.err.println(s"[DEBUG] $label: success, parsed $value")
      success
    case LazyPartial(value, mkErrors, consumed) =>
      val errors = mkErrors()
      val errorList = errors.map(e => formatError(e.asMatchable)).mkString(", ")
      System.err.println(s"[DEBUG] $label: partial success, parsed $value with errors: $errorList")
      LazyPartial(value, () => errors, consumed)
    case LazyFailure(mkErrors, loc) =>
      val errors = mkErrors()
      val error = errors.headOption.map(e => formatError(e.asMatchable)).getOrElse("unknown error")
      System.err.println(s"[DEBUG] $label: failed with $error")
      LazyFailure(() => errors, loc)
  }
}

/** Handler for [[Parser.Map]] — pure function over the inner value (variant B full-decompose). */
private def interpretMapI[E, Elem, A, B](source: ParserK[E, Elem, A], f: A => B, state: ParserState): IResult[E, B] =
  interpretI(source, state) match {
    case Result.Success(value, consumed) =>
      Result.Success(f(value), consumed)
    case LazyPartial(value, mkErrs, consumed) =>
      LazyPartial(f(value), mkErrs, consumed)
    case LazyFailure(mkErrs, loc) =>
      LazyFailure(mkErrs, loc)
  }

/** Handler for [[Parser.Zip]] — sequence two parsers, pair their values (variant B full-decompose).
  */
private def interpretZipI[E, Elem, A, B](
  left: ParserK[E, Elem, A],
  right: ParserK[E, Elem, B],
  state: ParserState
): IResult[E, (A, B)] =
  interpretI(left, state) match {
    case Result.Success(a, c1) =>
      interpretI(right, state) match {
        case Result.Success(b, c2) => Result.Success((a, b), c1 + c2)
        case LazyPartial(b, mkE, c2) => LazyPartial((a, b), mkE, c1 + c2)
        case f: LazyFailure[?] => f
      }
    case LazyPartial(a, mkE1, c1) =>
      interpretI(right, state) match {
        case Result.Success(b, c2) => LazyPartial((a, b), mkE1, c1 + c2)
        case LazyPartial(b, mkE2, c2) =>
          LazyPartial((a, b), () => mkE1() ++ mkE2(), c1 + c2)
        case f: LazyFailure[?] => f
      }
    case f: LazyFailure[?] => f
  }

/** Handler for [[Parser.SkipLeft]] — sequence two parsers keeping only the right value (variant B
  * full-decompose). Mirrors [[interpretZipI]] minus the pair: `left`'s value is discarded,
  * `right`'s is returned directly.
  */
private def interpretSkipLeftI[E, Elem, A, B](
  left: ParserK[E, Elem, A],
  right: ParserK[E, Elem, B],
  state: ParserState
): IResult[E, B] =
  interpretI(left, state) match {
    case Result.Success(_, c1) =>
      interpretI(right, state) match {
        case Result.Success(b, c2) => Result.Success(b, c1 + c2)
        case LazyPartial(b, mkE, c2) => LazyPartial(b, mkE, c1 + c2)
        case f: LazyFailure[?] => f
      }
    case LazyPartial(_, mkE1, c1) =>
      interpretI(right, state) match {
        case Result.Success(b, c2) => LazyPartial(b, mkE1, c1 + c2)
        case LazyPartial(b, mkE2, c2) => LazyPartial(b, () => mkE1() ++ mkE2(), c1 + c2)
        case f: LazyFailure[?] => f
      }
    case f: LazyFailure[?] => f
  }

/** Handler for [[Parser.SkipRight]] — sequence two parsers keeping only the left value (variant B
  * full-decompose). Mirrors [[interpretZipI]] minus the pair: `right`'s value is discarded,
  * `left`'s is returned directly.
  */
private def interpretSkipRightI[E, Elem, A, B](
  left: ParserK[E, Elem, A],
  right: ParserK[E, Elem, B],
  state: ParserState
): IResult[E, A] =
  interpretI(left, state) match {
    case Result.Success(a, c1) =>
      interpretI(right, state) match {
        case Result.Success(_, c2) => Result.Success(a, c1 + c2)
        case LazyPartial(_, mkE, c2) => LazyPartial(a, mkE, c1 + c2)
        case f: LazyFailure[?] => f
      }
    case LazyPartial(a, mkE1, c1) =>
      interpretI(right, state) match {
        case Result.Success(_, c2) => LazyPartial(a, mkE1, c1 + c2)
        case LazyPartial(_, mkE2, c2) => LazyPartial(a, () => mkE1() ++ mkE2(), c1 + c2)
        case f: LazyFailure[?] => f
      }
    case f: LazyFailure[?] => f
  }

/** Handler for [[Parser.Capture]] — replace the inner value with the consumed input slice (variant
  * B full-decompose).
  */
private def interpretCaptureI[E, A](p: ParserK[E, Char, A], state: ParserState): IResult[E, String] = {
  val startOffset = state.offset
  interpretI(p, state) match {
    case Result.Success(_, consumed) =>
      Result.Success(state.slice(startOffset, startOffset + consumed), consumed)
    case LazyPartial(_, mkErrs, consumed) =>
      LazyPartial(state.slice(startOffset, startOffset + consumed), mkErrs, consumed)
    case f: LazyFailure[?] => f
  }
}

/** Handler for [[Parser.Optional]] — backtracking optional parse (variant B full-decompose). */
private def interpretOptionalI[E, Elem, A](p: ParserK[E, Elem, A], state: ParserState): IResult[E, Option[A]] = {
  val snapshot = state.save
  val prevDiscarded = state.errorsDiscarded
  state.setErrorsDiscarded(true)
  val r = interpretI(p, state)
  state.setErrorsDiscarded(prevDiscarded)
  r match {
    case Result.Success(value, consumed) =>
      Result.Success(Some(value), consumed)
    case LazyPartial(value, mkErrs, consumed) =>
      LazyPartial(Some(value), mkErrs, consumed)
    case LazyFailure(_, _) =>
      state.restore(snapshot)
      Result.Success(None, 0)
  }
}

/** Handler for [[Parser.Attempt]] — reify the inner outcome into a `Result` value (variant B
  * full-decompose).
  */
private def interpretAttemptI[E, Elem, A](
  p: ParserK[E, Elem, A],
  state: ParserState
): IResult[Nothing, Result[E, A]] = {
  val snapshot = state.save
  val prevDiscarded = state.errorsDiscarded
  state.setErrorsDiscarded(false)
  val r = interpretI(p, state)
  state.setErrorsDiscarded(prevDiscarded)
  r match {
    case Result.Success(v, c) =>
      Result.Success(Result.Success(v, c), 0)
    case LazyPartial(v, mkErrs, c) =>
      Result.Success(Result.Partial(v, mkErrs(), c), 0)
    case LazyFailure(mkErrs, loc) =>
      state.restore(snapshot)
      Result.Success(Result.Failure(mkErrs(), loc), 0)
  }
}

/** Handler for [[Parser.LookAhead]] — non-consuming positive lookahead (variant B full-decompose).
  */
private def interpretLookAheadI[E, Elem, A](p: ParserK[E, Elem, A], state: ParserState): IResult[E, A] = {
  val snapshot = state.save
  interpretI(p, state) match {
    case Result.Success(value, _) =>
      state.restore(snapshot)
      Result.Success(value, 0)
    case LazyPartial(value, mkErrs, _) =>
      state.restore(snapshot)
      LazyPartial(value, mkErrs, 0)
    case failure @ LazyFailure(_, _) =>
      state.restore(snapshot)
      failure
  }
}

/** Handler for [[Parser.NotFollowedBy]] — non-consuming negative lookahead (variant B
  * full-decompose).
  */
private def interpretNotFollowedByI[Elem, A](
  p: ParserK[ParseError, Elem, A],
  state: ParserState
): IResult[ParseError, Unit] = {
  val snapshot = state.save
  interpretI(p, state) match {
    case Result.Success(_, _) =>
      state.restore(snapshot)
      val loc = state.location
      LazyFailure(
        () => List(ParseError.Custom("Unexpected success", loc)),
        loc
      )
    case LazyPartial(_, _, _) =>
      state.restore(snapshot)
      val loc = state.location
      LazyFailure(
        () => List(ParseError.Custom("Unexpected partial success", loc)),
        loc
      )
    case LazyFailure(_, _) =>
      state.restore(snapshot)
      Result.Success((), 0)
  }
}

/** Handler for [[Parser.Named]] — rewrite failure expected-sets to add `name` (variant B
  * full-decompose).
  */
private def interpretNamedI[Elem, A](
  p: ParserK[ParseError, Elem, A],
  name: String,
  state: ParserState
): IResult[ParseError, A] =
  interpretI(p, state) match {
    case success @ Result.Success(_, _) => success
    case partial @ LazyPartial(_, _, _) => partial
    case failure @ LazyFailure(mkErrors, furthest) =>
      if state.errorsDiscarded then failure
      else
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

/** Handler for [[Parser.Eof]] — succeed only at end of input (variant B full-decompose). */
private def interpretEofI(state: ParserState): IResult[ParseError, Unit] =
  if state.atEnd then {
    Result.Success((), 0)
  } else if state.errorsDiscarded then {
    SentinelLazyFailure
  } else {
    val loc = state.location
    LazyFailure(
      () => List(ParseError.Custom("Expected end of input", loc)),
      loc
    )
  }

/** Handler for [[Parser.RecoverWith]] — panic-mode recovery (variant B full-decompose). */
private def interpretRecoverWithI[E, Elem, A](
  p: ParserK[E, Elem, A],
  recovery: ParserK[E, Elem, A],
  state: ParserState
): IResult[E, A] = {
  val snapshot = state.save
  val prevDiscarded = state.errorsDiscarded
  state.setErrorsDiscarded(false)
  val r = interpretI(p, state)
  state.setErrorsDiscarded(prevDiscarded)
  r match {
    case success @ Result.Success(_, _) => success
    case partial @ LazyPartial(_, _, _) => partial
    case LazyFailure(mkErrors, furthest) =>
      state.restore(snapshot)
      interpretI(recovery, state) match {
        case Result.Success(value, consumed) =>
          LazyPartial(value, mkErrors, consumed)
        case LazyPartial(value, mkRecoveryErrors, consumed) =>
          LazyPartial(value, () => mkErrors() ++ mkRecoveryErrors(), consumed)
        case LazyFailure(mkRecoveryErrors, recoveryFurthest) =>
          val finalFurthest =
            if furthest.offset > recoveryFurthest.offset then furthest
            else recoveryFurthest
          LazyFailure(() => mkErrors() ++ mkRecoveryErrors(), finalFurthest)
      }
  }
}

/** Handler for [[Parser.Expect]] — replace failure with a single `Custom` message (variant B
  * full-decompose).
  */
private def interpretExpectI[Elem, A](
  p: ParserK[ParseError, Elem, A],
  message: String,
  state: ParserState
): IResult[ParseError, A] =
  interpretI(p, state) match {
    case success @ Result.Success(_, _) => success
    case partial @ LazyPartial(_, _, _) => partial
    case failure @ LazyFailure(_, furthest) =>
      if state.errorsDiscarded then failure
      else
        LazyFailure(
          () => List(ParseError.Custom(message, furthest)),
          furthest
        )
  }

/** Handler for [[Parser.Memo]] — cache `Result[E, A]` keyed by `(key, pos)` (variant B
  * full-decompose).
  *
  * Memo caches Result[E, A] keyed by (key, pos) across the whole parse. If the first call happens
  * inside a discarded context the cache would store a sentinel-backed Failure and leak it to a
  * later non-discarded caller. Force full errors around the inner evaluation.
  */
private def interpretMemoArmI[E, Elem, A](
  inner: ParserK[E, Elem, A],
  key: MemoKey[E, A],
  enableLR: Boolean,
  state: ParserState
): IResult[E, A] = {
  val prevDiscarded = state.errorsDiscarded
  state.setErrorsDiscarded(false)
  val memoResult =
    if enableLR then {
      if DEBUG_LR then System.err.println(s"[LR] Parser.Memo: key=$key, lrStack size before=${state.lrStack.size}")
      val r = interpretMemoI(inner, key, state)
      if DEBUG_LR then System.err.println(s"[LR] Parser.Memo: key=$key done, lrStack size after=${state.lrStack.size}")
      r
    } else {
      interpretSimpleMemoI(inner, key, state)
    }
  state.setErrorsDiscarded(prevDiscarded)
  memoResult
}

/** Handler for [[Parser.InternedGreen]]. Runs `inner`, then on success/partial looks up the
  * produced green in the parse-scoped [[GreenCache]] and replaces the value with the canonical
  * instance. Structurally-equal greens collapse to one heap instance for the remainder of the
  * parse.
  *
  * Cast at the [[ParserState]] boundary: the cache is stored at `GreenCache[?, ?]` so `ParserState`
  * doesn't need language parameters threaded through every signature. One parse sees one `(Tok,
  * Syn)` pair, so treating the cache as `GreenCache[Tok, Syn]` at the single lookup site is
  * type-safe under that parse-level invariant. Same discipline as [[MemoTable]]'s Result erasure.
  *
  * Failure paths pass through untouched — interning only ever rewrites a successful green value.
  */
private def interpretInternedGreenI[E, Elem, Tok, Syn](
  ig: ParserK.InternedGreen[E, Elem, Tok, Syn],
  state: ParserState
): IResult[E, GreenNodeOf[Tok, Syn]] = {
  interpretI(ig.inner, state) match {
    case Result.Success(green, consumed) =>
      val cache = state.greenCache.asInstanceOf[GreenCache[Tok, Syn]] // scalafix:ok DisableSyntax.asInstanceOf
      val (updated, canonical) = cache.intern(green)
      state.setGreenCache(updated.asInstanceOf[GreenCache[Any, Any]]) // scalafix:ok DisableSyntax.asInstanceOf
      Result.Success(canonical, consumed)

    case LazyPartial(green, mkErrs, consumed) =>
      val cache = state.greenCache.asInstanceOf[GreenCache[Tok, Syn]] // scalafix:ok DisableSyntax.asInstanceOf
      val (updated, canonical) = cache.intern(green)
      state.setGreenCache(updated.asInstanceOf[GreenCache[Any, Any]]) // scalafix:ok DisableSyntax.asInstanceOf
      LazyPartial(canonical, mkErrs, consumed)

    case failure @ LazyFailure(_, _) => failure
  }
}

/** Interprets a memoized parser with left recursion support (returns IResult).
  *
  * Implements the seed-growth algorithm from Warth et al.:
  *   1. Check memo table for cached result
  *   2. If not cached, mark as "in progress" with LR marker
  *   3. If LR detected, return seed and setup head
  *   4. Otherwise, parse and cache result
  *   5. If this is the head of a left-recursive cycle, grow the seed
  *
  * Note: The memo table stores Result (not IResult) because:
  *   - Seeds need to be materialized for the LR algorithm
  *   - Cached results are already computed We convert back to IResult at the boundary for
  *     consistency.
  *
  * @param inner
  *   The inner parser to interpret
  * @param key
  *   Type-safe memo key for this parser rule
  * @param state
  *   Mutable parse state with memo tables
  * @return
  *   Internal result (lazy errors)
  */
private val DEBUG_LR = false

private def interpretMemoI[E, Elem, A](
  inner: ParserK[E, Elem, A],
  key: MemoKey[E, A],
  state: ParserState
): IResult[E, A] =
  resultToIResult(interpretMemoResult(inner, key, state))

/** Convert Result to IResult (wrap errors in thunk that returns them) */
private def resultToIResult[E, A](result: Result[E, A]): IResult[E, A] = result match {
  case Result.Success(v, c) => Result.Success(v, c)
  case Result.Partial(v, e, c) => LazyPartial(v, () => e, c)
  case Result.Failure(errs, loc) => LazyFailure(() => errs, loc)
}

/** The actual memo implementation, works with Result for LR seed storage */
private def interpretMemoResult[E, Elem, A](
  inner: ParserK[E, Elem, A],
  key: MemoKey[E, A],
  state: ParserState
): Result[E, A] = {
  val pos = state.offset
  val startSnapshot = state.save

  if DEBUG_LR then {
    val headInfo = state.heads
      .get(pos)
      .map(h => s"head=${h.rule}, involved=${h.involvedSet}, eval=${h.evalSet}")
      .getOrElse("no head")
    System.err.println(s"[LR] interpretMemo key=$key pos=$pos $headInfo")
  }

  state.heads.get(pos) match {
    case Some(head) if head.evalSet.contains(key) =>
      if DEBUG_LR then System.err.println("[LR]   -> in evalSet, re-evaluating fresh")
      head.evalSet.remove(key)
      val result = toResult(interpretI(inner, state))
      val endPos = state.offset
      state.memo.put(key, pos, result, endPos)
      result

    case Some(head) if head.rule eq key =>
      if DEBUG_LR then System.err.println("[LR]   -> this IS the head, returning seed")
      state.memo.getRaw(key, pos) match {
        case Some(Left(lr)) =>
          castSeed[E, A](lr.seed)
        case Some(Right(entry)) =>
          state.restore(StateSnapshot.of(entry.pos, state.line, state.column))
          state.memo.getResult(key, pos).get
        case None =>
          evaluateMemoResult(inner, key, pos, startSnapshot, state)
      }

    case Some(head) if head.involvedSet.contains(key) =>
      if DEBUG_LR then System.err.println("[LR]   -> in involvedSet but not evalSet")
      state.memo.getRaw(key, pos) match {
        case Some(Left(lr)) =>
          if DEBUG_LR then System.err.println(s"[LR]   -> returning seed: ${lr.seed}")
          castSeed[E, A](lr.seed)
        case Some(Right(entry)) =>
          if DEBUG_LR then System.err.println("[LR]   -> returning cached result")
          state.restore(StateSnapshot.of(entry.pos, state.line, state.column))
          state.memo.getResult(key, pos).get
        case None =>
          if DEBUG_LR then System.err.println("[LR]   -> no entry, evaluating normally")
          evaluateMemoResult(inner, key, pos, startSnapshot, state)
      }

    case _ =>
      evaluateMemoResult(inner, key, pos, startSnapshot, state)
  }
}

/** Core memoization logic, separated for RECALL handling. Works with Result for LR seed storage.
  */
private def evaluateMemoResult[E, Elem, A](
  inner: ParserK[E, Elem, A],
  key: MemoKey[E, A],
  pos: Int,
  startSnapshot: StateSnapshot,
  state: ParserState
): Result[E, A] =
  state.memo.getRaw(key, pos) match {
    case Some(Left(lr)) =>
      if DEBUG_LR then System.err.println("[LR]   evaluateMemo: found LR marker, calling setupLR")
      setupLR(key, lr, state)
      castSeed[E, A](lr.seed)

    case Some(Right(entry)) =>
      if DEBUG_LR then System.err.println("[LR]   evaluateMemo: returning cached result")
      state.restore(StateSnapshot.of(entry.pos, state.line, state.column))
      state.memo.getResult(key, pos).get

    case None =>
      if DEBUG_LR then System.err.println(s"[LR]   evaluateMemo: first time, pushing LR for $key")
      val lr = LR(
        seed = Result.Failure(List.empty, state.location),
        rule = key,
        head = None
      )
      state.lrStack.append(lr)
      state.memo.putLR(key, pos, lr)
      if DEBUG_LR then System.err.println(s"[LR]   evaluateMemo: lrStack now has ${state.lrStack.size} items")

      val result = toResult(interpretI(inner, state))
      val endPos = state.offset

      if DEBUG_LR then
        System.err.println(s"[LR]   evaluateMemo: popping LR for $key, lrStack had ${state.lrStack.size} items")
      state.lrStack.remove(state.lrStack.length - 1)

      lr.head match {
        case None =>
          state.memo.put(key, pos, result, endPos)
          result

        case Some(head) if !(head.rule eq key) =>
          state.memo.put(key, pos, result, endPos)
          result

        case Some(_) =>
          result match {
            case _: Result.Failure[?, ?] =>
              state.memo.put(key, pos, result, endPos)
              result
            case _ =>
              lr.seed = eraseSeed(result)
              growLRResult(inner, key, startSnapshot, lr, endPos, state)
          }
      }
  }

/** Erase seed type for storage in LR marker.
  *
  * SAFETY: The LR marker is keyed by the same MemoKey[E, A] that will be used to retrieve it, so
  * the type is recoverable through castSeed.
  */
private def eraseSeed[E, A](result: Result[E, A]): Result[Any, Any] =
  result.asInstanceOf[Result[Any, Any]] // scalafix:ok DisableSyntax.asInstanceOf

/** Cast erased seed back to typed result.
  *
  * SAFETY: This cast is safe because:
  *   1. The seed was stored with eraseSeed for a specific MemoKey[E, A]
  *   2. The same MemoKey[E, A] is used to retrieve it
  *   3. Therefore the erased type matches [E, A]
  */
private def castSeed[E, A](result: Result[Any, Any]): Result[E, A] =
  result.asInstanceOf[Result[E, A]] // scalafix:ok DisableSyntax.asInstanceOf

/** Sets up the left recursion head when a cycle is detected.
  *
  * When we detect LR (by finding our own LR marker on the stack), we need to:
  *   1. Find or create the HEAD for this cycle
  *   2. Mark all rules between the head and this LR as "involved" in the cycle
  *
  * The HEAD should be the OUTERMOST left-recursive rule (first on stack). When there are nested LR
  * rules (like expr -> term where both are LR), the outermost rule (expr) should be the head.
  */
private def setupLR(key: AnyRef, lr: LR, state: ParserState): Unit = {
  if DEBUG_LR then {
    System.err.println(s"[LR] setupLR: key=$key, lrStack size=${state.lrStack.size}")
    state.lrStack.foreach(slr => System.err.println(s"[LR]   stack item: ${slr.rule}, head=${slr.head.map(_.rule)}"))
  }

  val existingHead = state.lrStack.find(_.head.isDefined).flatMap(_.head)

  val actualHead = existingHead match {
    case Some(h) =>
      if DEBUG_LR then System.err.println(s"[LR] setupLR: reusing existing head ${h.rule}")
      lr.head = Some(h)
      if !(key eq h.rule) then {
        h.involvedSet.add(key)
        if DEBUG_LR then System.err.println(s"[LR] setupLR: added $key to involvedSet of ${h.rule}")
      }
      h
    case None =>
      if lr.head.isEmpty then {
        lr.head = Some(new LRHead(key, scala.collection.mutable.Set.empty, scala.collection.mutable.Set.empty))
        if DEBUG_LR then System.err.println(s"[LR] setupLR: created head for $key")
      }
      lr.head.get
  }

  for
    stackLr <- state.lrStack.reverseIterator
    if !(stackLr.rule eq key) && !(stackLr.rule eq actualHead.rule)
  do {
    stackLr.head = Some(actualHead)
    actualHead.involvedSet.add(stackLr.rule)
    if DEBUG_LR then System.err.println(s"[LR] setupLR: added ${stackLr.rule} to involvedSet of ${actualHead.rule}")
  }
}

/** Grows the seed for a left-recursive rule until no more progress is made. Works with Result for
  * LR seed storage.
  *
  * This is the core of the seed-growth algorithm. We repeatedly:
  *   1. Reset position to start
  *   2. Update memo with current seed
  *   3. Re-parse the rule
  *   4. If we made progress (consumed more input), update seed and continue
  *   5. Stop when no more progress is made
  *
  * Type safety: The key carries type parameters [E, A] ensuring all operations maintain type
  * consistency throughout the seed growth process.
  *
  * @param startSnapshot
  *   The saved state (offset, line, column) at rule start, used to correctly restore position with
  *   accurate line/column
  */
private def growLRResult[E, Elem, A](
  inner: ParserK[E, Elem, A],
  key: MemoKey[E, A],
  startSnapshot: StateSnapshot,
  lr: LR,
  seedEndPos: Int,
  state: ParserState
): Result[E, A] = {
  val pos = startSnapshot.offset
  state.heads.put(pos, lr.head.get)

  var lastResult: Result[E, A] = castSeed[E, A](lr.seed)
  var lastPos = seedEndPos
  var lastLine = state.line
  var lastColumn = state.column

  var continue = true
  while continue do {
    state.restore(startSnapshot)
    state.memo.put(key, pos, lastResult, lastPos)
    lr.head.get.evalSet = lr.head.get.involvedSet.clone()

    val result = toResult(interpretI(inner, state))
    val resultPos = state.offset

    result match {
      case _: Result.Failure[?, ?] =>
        continue = false
      case _ if resultPos <= lastPos =>
        continue = false
      case _ =>
        lastResult = result
        lastPos = resultPos
        lastLine = state.line
        lastColumn = state.column
        lr.seed = eraseSeed(result)
    }
  }

  state.heads.remove(pos)
  state.restore(StateSnapshot.of(lastPos, lastLine, lastColumn))
  state.memo.put(key, pos, lastResult, lastPos)
  lastResult
}

/** Fast path for simple memoization without left-recursion support.
  *
  * Performance optimizations vs LR path:
  *   - No heads.get(pos) lookup
  *   - No lrStack manipulation
  *   - No Either[LR, Entry] unpacking
  *   - No Option[Result] wrapping
  *   - Direct result storage and retrieval
  *
  * Approximately 50% faster than LR path for cache hits.
  *
  * Returns IResult (not Result) to match interpretI signature.
  */
private def interpretSimpleMemoI[E, Elem, A](
  inner: ParserK[E, Elem, A],
  key: MemoKey[E, A],
  state: ParserState
): IResult[E, A] = {
  val pos = state.offset

  state.simpleCache.get(key, pos) match {
    case Some(entry) =>
      state.restore(StateSnapshot.of(entry.pos, state.line, state.column))
      resultToIResult(castSimpleCacheResult[E, A](entry.result))

    case None =>
      val result = interpretI(inner, state)
      val endPos = state.offset
      val forcedResult = toResult(result)
      state.simpleCache.put(key, pos, forcedResult, endPos)
      result
  }
}

/** Cast cached result back to typed result.
  *
  * SAFETY: This cast is safe because:
  *   1. The result was stored with a specific MemoKey[E, A]
  *   2. The same MemoKey[E, A] is used to retrieve it
  *   3. Therefore the erased type matches [E, A]
  */
private def castSimpleCacheResult[E, A](result: Result[Any, Any]): Result[E, A] =
  result.asInstanceOf[Result[E, A]] // scalafix:ok DisableSyntax.asInstanceOf

/** Returns true for parsers that never modify state on failure (no save/restore needed). */
private def isSimple(p: ParserK[?, ?, ?]): Boolean = p match {
  case _: Parser.Satisfy[?] => true
  case _: Parser.StringMatch => true
  case _ => false
}

/** Peek-tests whether `Or`'s left branch will fail on its very first char without running it.
  *
  * Returns `true` iff [[p]]'s leading token is decidable from a single-char peek AND that peek
  * proves a mismatch. Handles terminals whose acceptance is a single-char predicate
  * ([[Parser.Satisfy]], [[Parser.StringMatch]], [[Parser.Eof]]) and peels wrappers that don't
  * change the leading token:
  *   - [[Parser.Map]] — source's failure is source's failure, unmodified
  *   - [[Parser.Zip]]-left — if left fails at offset 0, Zip fails at offset 0
  *   - [[Parser.LookAhead]] — same failure shape (non-consuming on success)
  *   - [[Parser.FlatMap]] — if source fails, the continuation never runs
  *   - [[Parser.InternedGreen]] — interning only rewrites a successful green; a failing inner
  *     propagates unchanged, so the leading-token test peels the wrapper
  *
  * Opaque cases ([[Parser.Defer]], [[Parser.Memo]], [[Parser.Named]], arbitrary [[Parser.Or]] /
  * [[Parser.Choice]]) return `false` so the caller falls through to the full interpret. Peeking
  * only; state is never modified.
  *
  * No [[LazyFailure]] is synthesised on the fast path: the `Or` case skips straight to the right
  * branch and returns its result. If both branches fail, only the right side's errors are surfaced.
  * Same tradeoff as the prior `FlatMap(simple, _)` fast path — error contribution from the left is
  * exchanged for skipping its [[LazyFailure]] + closure + [[Location]] allocation.
  */
private def orLookaheadFails(p: ParserK[?, ?, ?], state: ParserState): Boolean = {
  var node: ParserK[?, ?, ?] = p
  var decided = false
  var fails = false
  while !decided do {
    node match {
      case Parser.Satisfy(pred, _) =>
        // Char-path peek helper (reads `currentChar`). The Satisfy element is Char here; widen the
        // existential predicate to `Char => Boolean`. Sound under route A (all input char-backed);
        // when a token backing lands, token grammars don't take this string-only Or fast path.
        val test = pred.asInstanceOf[Char => Boolean] // scalafix:ok DisableSyntax.asInstanceOf
        fails = !state.hasChar || !test(state.currentChar)
        decided = true

      case s: Parser.StringMatch =>
        val target = s.target
        val len = target.length
        fails = state.offset + len > state.input.length ||
          !state.input.regionMatches(state.offset, target, 0, len)
        decided = true

      case _: Parser.Eof[?] =>
        fails = !state.atEnd
        decided = true

      case Parser.Map(source, _) => node = source
      case Parser.Zip(left, _) => node = left
      case Parser.SkipLeft(left, _) => node = left
      case Parser.SkipRight(left, _) => node = left
      case Parser.LookAhead(inner) => node = inner
      case Parser.FlatMap(source, _) => node = source
      case Parser.InternedGreen(inner) => node = inner

      case _ => decided = true
    }
  }
  fails
}

/** Try a simple parser (Satisfy or StringMatch) without allocating a LazyFailure on failure.
  *
  * Returns the consumed character count on success, or -1 on failure. This is the hot-path entry
  * used by `interpretManyI` / `interpretSkipManyI` to loop until failure: they discard the failure
  * payload anyway, so constructing a LazyFailure + error thunk + Location Tuple3 per
  * loop-terminating iteration is pure waste (~20% of sampled bytes on rumil_jsonMedium per JFR).
  *
  * The caller must have already verified `isSimple(p)`. For other parser kinds this returns -1
  * (no-op failure) without attempting a match.
  *
  * On success for Satisfy, advances state by one character; caller reads the char from the state
  * before the call if needed (use `state.currentChar` pre-call, since after the call offset has
  * moved). For StringMatch, advances by `target.length` and the matched string is the target
  * itself.
  */
private def tryMatchSimpleI(p: ParserK[?, ?, ?], state: ParserState): Int = p match {
  case Parser.Satisfy(pred, _) =>
    // Char-path fast loop (reads `currentChar`); callers gate Satisfy on `state.isCharBacked`, so
    // the element is Char. Widen the existential predicate to `Char => Boolean`.
    val test = pred.asInstanceOf[Char => Boolean] // scalafix:ok DisableSyntax.asInstanceOf
    if state.hasChar && test(state.currentChar) then {
      state.advance()
      1
    } else {
      -1
    }

  case s: Parser.StringMatch =>
    val target = s.target
    val len = target.length
    if state.offset + len <= state.input.length && state.input.regionMatches(state.offset, target, 0, len)
    then {
      state.advanceByString(target)
      len
    } else {
      -1
    }

  case _ => -1
}

/** Interprets the Many combinator - zero or more repetitions (returns IResult).
  *
  * Uses ArrayBuffer for O(1) append, converts to List at end. This is significantly faster than
  * prepend-then-reverse for long sequences.
  *
  * For simple inner parsers (Satisfy, StringMatch) that never modify state on failure, the
  * save/restore overhead is skipped entirely.
  *
  * @param p
  *   The parser to repeat
  * @param state
  *   Mutable parse state
  * @return
  *   Success with list of all parsed values
  */
private def interpretManyI[E, Elem, A](p: ParserK[E, Elem, A], state: ParserState): IResult[E, List[A]] = {
  p match {
    case sat: Parser.Satisfy[?] if state.isCharBacked =>
      // Char-scan fast path: valid only for a char-backed state (the matched Satisfy's element is
      // then Char). Under route A all input is char-backed, so the guard is always true today; it
      // exists so a future token-stream `Satisfy[Tok]` cannot be routed through the Char loop — it
      // falls through to the general element-generic branch below. Cast: Satisfy[?] -> Satisfy[Char]
      // is sound under the char-backed guard.
      val satChar = sat.asInstanceOf[Parser.Satisfy[Char]] // scalafix:ok DisableSyntax.asInstanceOf
      interpretManySatisfy(satChar, state).asInstanceOf[IResult[E, List[A]]] // scalafix:ok DisableSyntax.asInstanceOf

    case sm: Parser.StringMatch =>
      interpretManyStringMatch(sm, state).asInstanceOf[IResult[E, List[A]]] // scalafix:ok DisableSyntax.asInstanceOf

    case _ =>
      val acc = scala.collection.mutable.ArrayBuffer.empty[A]
      val errThunks = scala.collection.mutable.ArrayBuffer.empty[() => List[E]]
      var totalConsumed = 0
      var continue = true
      val prevDiscarded = state.errorsDiscarded
      state.setErrorsDiscarded(true)

      while continue do {
        val snapshot = state.save
        interpretI(p, state) match {
          case Result.Success(value, consumed) =>
            acc += value
            totalConsumed += consumed
          case LazyPartial(value, mkErrs, consumed) =>
            acc += value
            errThunks += mkErrs
            totalConsumed += consumed
          case LazyFailure(_, _) =>
            state.restore(snapshot)
            continue = false
        }
      }

      state.setErrorsDiscarded(prevDiscarded)

      if errThunks.isEmpty then {
        Result.Success(acc.toList, totalConsumed)
      } else {
        LazyPartial(acc.toList, () => errThunks.flatMap(_.apply()).toList, totalConsumed)
      }
  }
}

/** Specialized `many(satisfy(pred, _))` loop. Zero allocation per terminating-failure iteration —
  * no LazyFailure, no error thunk, no Location. The char is read directly from the state.
  */
private def interpretManySatisfy(p: Parser.Satisfy[Char], state: ParserState): IResult[Nothing, List[Char]] = {
  val acc = scala.collection.mutable.ArrayBuffer.empty[Char]
  val pred = p.pred
  while state.hasChar && pred(state.currentChar) do {
    acc += state.currentChar
    state.advance()
  }
  Result.Success(acc.toList, acc.length)
}

/** Specialized `many(string(target))` loop. Zero allocation per terminating-failure iteration. The
  * matched string is always `target` itself; the accumulator stores the target repeated by the
  * match count.
  */
private def interpretManyStringMatch(p: Parser.StringMatch, state: ParserState): IResult[Nothing, List[String]] = {
  val acc = scala.collection.mutable.ArrayBuffer.empty[String]
  val target = p.target
  val len = target.length
  var totalConsumed = 0
  var continue = true
  while continue do {
    if state.offset + len <= state.input.length && state.input.regionMatches(state.offset, target, 0, len)
    then {
      state.advanceByString(target)
      acc += target
      totalConsumed += len
    } else {
      continue = false
    }
  }
  Result.Success(acc.toList, totalConsumed)
}

/** Interprets the Many1 combinator - one or more repetitions (returns IResult).
  *
  * Requires at least one match. Implemented => one match followed by Many (zero or more).
  *
  * @param p
  *   The parser to repeat
  * @param state
  *   Mutable parse state
  * @return
  *   Success with non-empty list, or Failure
  */
private def interpretMany1I[E, Elem, A](p: ParserK[E, Elem, A], state: ParserState): IResult[E, List[A]] =
  interpretI(p, state) match {
    case Result.Success(head, consumed1) =>
      interpretManyI(p, state) match {
        case Result.Success(tail, consumed2) =>
          Result.Success(head :: tail, consumed1 + consumed2)
        case LazyPartial(tail, mkErrs, consumed2) =>
          LazyPartial(head :: tail, mkErrs, consumed1 + consumed2)
        case LazyFailure(mkErrs, loc) =>
          LazyFailure(mkErrs, loc)
      }
    case LazyPartial(head, mkErrors1, consumed1) =>
      interpretManyI(p, state) match {
        case Result.Success(tail, consumed2) =>
          LazyPartial(head :: tail, mkErrors1, consumed1 + consumed2)
        case LazyPartial(tail, mkErrors2, consumed2) =>
          LazyPartial(head :: tail, () => mkErrors1() ++ mkErrors2(), consumed1 + consumed2)
        case LazyFailure(mkErrors2, furthest) =>
          LazyFailure(() => mkErrors1() ++ mkErrors2(), furthest)
      }
    case LazyFailure(mkErrs, loc) =>
      LazyFailure(mkErrs, loc)
  }

/** Shared zero-alloc loop for the simple-`p` SkipMany fast path: repeatedly `tryMatchSimpleI` until
  * failure, discarding values. Caller has confirmed `p` is a `Satisfy` (on a char-backed state) or
  * a `StringMatch`.
  */
private def skipMatchSimpleLoop(p: ParserK[?, ?, ?], state: ParserState): IResult[Nothing, Unit] = {
  var totalConsumed = 0
  var n = tryMatchSimpleI(p, state)
  while n >= 0 do {
    totalConsumed += n
    n = tryMatchSimpleI(p, state)
  }
  Result.Success((), totalConsumed)
}

/** Interprets the SkipMany combinator - zero or more repetitions, discarding values (returns
  * IResult).
  *
  * Like interpretManyI but without value accumulation — no ArrayBuffer, no List construction. Uses
  * the same isSimple(p) fast-path split.
  */
private def interpretSkipManyI[E, Elem, A](p: ParserK[E, Elem, A], state: ParserState): IResult[E, Unit] = {
  p match {
    // Char-scan fast path. `tryMatchSimpleI` reads `currentChar`, so Satisfy is only eligible on a
    // char-backed state (always true under route A; the guard keeps a future token-Satisfy off this
    // loop). StringMatch is intrinsically char-backed, so it needs no guard.
    case _: Parser.Satisfy[?] if state.isCharBacked =>
      skipMatchSimpleLoop(p, state)
    case _: Parser.StringMatch =>
      skipMatchSimpleLoop(p, state)

    case _ =>
      val errThunks = scala.collection.mutable.ArrayBuffer.empty[() => List[E]]
      var totalConsumed = 0
      var continue = true
      val prevDiscarded = state.errorsDiscarded
      state.setErrorsDiscarded(true)

      while continue do {
        val snapshot = state.save
        interpretI(p, state) match {
          case Result.Success(_, consumed) =>
            totalConsumed += consumed
          case LazyPartial(_, mkErrs, consumed) =>
            errThunks += mkErrs
            totalConsumed += consumed
          case LazyFailure(_, _) =>
            state.restore(snapshot)
            continue = false
        }
      }

      state.setErrorsDiscarded(prevDiscarded)

      if errThunks.isEmpty then {
        Result.Success((), totalConsumed)
      } else {
        LazyPartial((), () => errThunks.flatMap(_.apply()).toList, totalConsumed)
      }
  }
}

/** Interprets the Choice combinator - try alternatives in sequence (returns IResult).
  *
  * Tail-recursive implementation that tries each alternative until one succeeds. Tracks the
  * furthest error location for good error messages. Uses lazy error construction - errors from
  * failed alternatives are only materialized if ALL alternatives fail.
  *
  * @param remaining
  *   Alternatives left to try
  * @param state
  *   Mutable parse state
  * @param snapshot
  *   Saved state for backtracking
  * @param accMkErrors
  *   Accumulated error thunks from failed alternatives
  * @param furthest
  *   Furthest location reached by any alternative
  * @return
  *   First successful result, or failure with best error info
  */
@scala.annotation.tailrec
private def interpretChoiceI[E, Elem, A](
  remaining: List[ParserK[E, Elem, A]],
  state: ParserState,
  snapshot: StateSnapshot,
  accMkErrors: () => List[E],
  furthest: Location
): IResult[E, A] = remaining match {
  case Nil =>
    LazyFailure(accMkErrors, furthest)
  case head :: tail =>
    interpretI(head, state) match {
      case success @ Result.Success(_, _) => success
      case partial @ LazyPartial(_, _, _) => partial
      case LazyFailure(mkErrs, loc) =>
        state.restore(snapshot)
        val (newMkErrors, newFurthest) =
          if loc.offset > furthest.offset then (mkErrs, loc)
          else if loc.offset == furthest.offset then {
            val prevMkErrors = accMkErrors
            (() => prevMkErrors() ++ mkErrs(), furthest)
          } else (accMkErrors, furthest)
        interpretChoiceI(tail, state, snapshot, newMkErrors, newFurthest)
    }
}

/** Pratt (Top-Down Operator Precedence) loop interpreter.
  *
  * Parses `nud` to establish the LHS accumulator, then repeatedly consults `getOp`. An infix
  * operator with `lbp > minBp` recursively parses a RHS at `rbp` and folds via `combine`. A postfix
  * operator with `bp > minBp` applies in-place. Any other outcome (operator fails, or binds too
  * weakly) terminates the loop with the accumulated LHS restored to the snapshot taken before
  * `getOp`.
  *
  * Encapsulated mutation (`var` accumulators) matches the `interpretManyI` precedent and is
  * invisible outside this function. Not stack-safe for unbounded operator chains — this is the
  * direct-recursion spike; Phase 5 lifts the loop into the trampoline.
  *
  * @param nud
  *   Null-denotation parser: atoms, prefix operators, parenthesized sub-expressions
  * @param getOp
  *   Operator-dispatch parser returning a `PrattOp` describing how to combine
  * @param minBp
  *   Binding power threshold; the loop terminates when the next operator's `lbp` (or postfix `bp`)
  *   is not strictly greater
  * @param state
  *   Mutable parse state
  */
private def interpretPrattI[E, Elem, A](
  nud: ParserK[E, Elem, A],
  getOp: ParserK[E, Elem, PrattOp[A]],
  minBp: Int,
  state: ParserState
): IResult[E, A] = {
  interpretI(nud, state) match {
    case Result.Success(initial, nudConsumed) =>
      prattLoop(nud, getOp, minBp, state, initial, nudConsumed, () => Nil, hasErrors = false)

    case LazyPartial(initial, mkErrs, nudConsumed) =>
      prattLoop(nud, getOp, minBp, state, initial, nudConsumed, mkErrs, hasErrors = true)

    case failure @ LazyFailure(_, _) => failure
  }
}

private def prattLoop[E, Elem, A](
  nud: ParserK[E, Elem, A],
  getOp: ParserK[E, Elem, PrattOp[A]],
  minBp: Int,
  state: ParserState,
  initialLhs: A,
  initialConsumed: Int,
  initialMkErrs: () => List[E],
  hasErrors: Boolean
): IResult[E, A] = {
  var lhs: A = initialLhs
  var totalConsumed: Int = initialConsumed
  var mkErrs: () => List[E] = initialMkErrs
  var accumulatedErrors: Boolean = hasErrors
  var continue: Boolean = true
  var earlyFailure: Option[IResult[E, A]] = None

  while continue do {
    val snapshot = state.save
    interpretI(getOp, state) match {
      case Result.Success(PrattOp.Infix(lbp, rbp, combine), opConsumed) =>
        if lbp > minBp then {
          interpretPrattI(nud, getOp, rbp, state) match {
            case Result.Success(rhs, rhsConsumed) =>
              lhs = combine(lhs, rhs)
              totalConsumed += opConsumed + rhsConsumed

            case LazyPartial(rhs, rhsMkErrs, rhsConsumed) =>
              lhs = combine(lhs, rhs)
              totalConsumed += opConsumed + rhsConsumed
              val prev = mkErrs
              mkErrs = () => prev() ++ rhsMkErrs()
              accumulatedErrors = true

            case LazyFailure(rhsMkErrs, loc) =>
              val prev = mkErrs
              earlyFailure = Some(LazyFailure(() => prev() ++ rhsMkErrs(), loc))
              continue = false
          }
        } else {
          state.restore(snapshot)
          continue = false
        }

      case Result.Success(PrattOp.Postfix(bp, apply), opConsumed) =>
        if bp > minBp then {
          lhs = apply(lhs)
          totalConsumed += opConsumed
        } else {
          state.restore(snapshot)
          continue = false
        }

      case LazyPartial(PrattOp.Infix(lbp, rbp, combine), opMkErrs, opConsumed) =>
        if lbp > minBp then {
          val prev = mkErrs
          mkErrs = () => prev() ++ opMkErrs()
          accumulatedErrors = true
          interpretPrattI(nud, getOp, rbp, state) match {
            case Result.Success(rhs, rhsConsumed) =>
              lhs = combine(lhs, rhs)
              totalConsumed += opConsumed + rhsConsumed

            case LazyPartial(rhs, rhsMkErrs, rhsConsumed) =>
              lhs = combine(lhs, rhs)
              totalConsumed += opConsumed + rhsConsumed
              val prev2 = mkErrs
              mkErrs = () => prev2() ++ rhsMkErrs()

            case LazyFailure(rhsMkErrs, loc) =>
              val prev2 = mkErrs
              earlyFailure = Some(LazyFailure(() => prev2() ++ rhsMkErrs(), loc))
              continue = false
          }
        } else {
          state.restore(snapshot)
          continue = false
        }

      case LazyPartial(PrattOp.Postfix(bp, apply), opMkErrs, opConsumed) =>
        if bp > minBp then {
          lhs = apply(lhs)
          totalConsumed += opConsumed
          val prev = mkErrs
          mkErrs = () => prev() ++ opMkErrs()
          accumulatedErrors = true
        } else {
          state.restore(snapshot)
          continue = false
        }

      case LazyFailure(_, _) =>
        state.restore(snapshot)
        continue = false
    }
  }

  given CanEqual[Option[IResult[E, A]], Option[IResult[E, A]]] = CanEqual.derived
  earlyFailure match {
    case Some(r) => r
    case _ =>
      if accumulatedErrors then LazyPartial(lhs, mkErrs, totalConsumed)
      else Result.Success(lhs, totalConsumed)
  }
}

/** Optimized interpreter for StringChoice - choice of string literals.
  *
  * This avoids allocating intermediate IResult objects during backtracking. Instead, we loop
  * through the alternatives with simple string comparisons, only allocating a result at the very
  * end.
  *
  * @param targets
  *   Array of string alternatives to try
  * @param state
  *   Parser state
  * @return
  *   Success with matched string, or Failure
  */
private def interpretStringChoice(
  radix: RadixNode,
  targets: Array[String],
  state: ParserState
): IResult[ParseError, String] = {
  val input = state.input
  val offset = state.offset
  val matched = radix.matchAtOrNull(input, offset)

  if matched ne null then { // scalafix:ok DisableSyntax.null
    state.advanceByString(matched)
    Result.Success(matched, matched.length)
  } else if state.errorsDiscarded then {
    SentinelLazyFailure
  } else {
    val loc = state.location
    val inputLen = input.length
    val maxLen = targets.map(_.length).max
    val found = input.substring(offset, math.min(offset + maxLen, inputLen))
    val expected = targets.map(s => s"\"$s\"").toSet
    LazyFailure(
      () => List(ParseError.Unexpected(found, expected, loc)),
      loc
    )
  }
}
