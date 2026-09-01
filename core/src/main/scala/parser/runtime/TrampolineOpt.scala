package parser.runtime

import parser.core.*

/** Stack-safe interpreter using a type-preserving GADT continuation stack
  * and @tailrec state machine.
  *
  * Replaces the mutable array + type-erased trampoline with:
  * - ParserCont: immutable GADT continuation linked list (no asInstanceOf)
  * - EvalState: two-phase state machine (Eval parser, Apply result)
  * - @tailrec loop: no stack growth, no mutable vars
  */
object TrampolineOpt {

  /** GADT continuation stack. Each frame preserves the In -> Mid -> Out type chain.
    *
    *   - End: bottom of stack, identity continuation
    *   - Step: flatMap continuation, produces a parser from the input value
    *   - MapStep: pure function application, no parser allocation
    *   - PartialStep: carries error thunks from LazyPartial through flatMap chains
    *   - ComposeK: right-associating composition to prevent O(n^2) left-leaning chains
    */
  private[runtime] enum ParserCont[E, Elem, -In, +Out] {
    case End[E0, Elem0, A0]() extends ParserCont[E0, Elem0, A0, A0]

    case Step[E0, Elem0, In0, Mid0, Out0](
      f: In0 => ParserK[E0, Elem0, Mid0],
      next: ParserCont[E0, Elem0, Mid0, Out0]
    ) extends ParserCont[E0, Elem0, In0, Out0]

    case MapStep[E0, Elem0, In0, Mid0, Out0](
      f: In0 => Mid0,
      next: ParserCont[E0, Elem0, Mid0, Out0]
    ) extends ParserCont[E0, Elem0, In0, Out0]

    case PartialStep[E0, Elem0, A0, Out0](
      mkErrors: () => List[E0],
      next: ParserCont[E0, Elem0, A0, Out0]
    ) extends ParserCont[E0, Elem0, A0, Out0]

    case ComposeK[E0, Elem0, In0, Mid0, Out0](
      first: ParserCont[E0, Elem0, In0, Mid0],
      second: ParserCont[E0, Elem0, Mid0, Out0]
    ) extends ParserCont[E0, Elem0, In0, Out0]

    /** Recursive boundary of a Pratt (Top-Down Operator Precedence) loop.
      *
      * When a Pratt parser enters the Eval phase, the `nud` is evaluated with this frame on top.
      * When nud succeeds (or partially succeeds), the Apply phase dispatches on this frame: it
      * parses `getOp` and, depending on the operator's binding power, either recursively evaluates
      * a RHS (pushing a `PrattCombine` chain), applies a postfix in place, or terminates the loop
      * and hands the accumulated value to `next`.
      *
      * The `A0` type parameter is invariant, which is what makes GADT equality refinement work in
      * the Apply-phase match site without an `asInstanceOf`.
      */
    case PrattLoop[E0, Elem0, A0, Out0](
      minBp: Int,
      nud: ParserK[E0, Elem0, A0],
      getOp: ParserK[E0, Elem0, PrattOp[A0]],
      opTable: PrattOpTable[A0] | Null,
      next: ParserCont[E0, Elem0, A0, Out0]
    ) extends ParserCont[E0, Elem0, A0, Out0]

    /** Fused combine-then-resume continuation for an infix Pratt operator.
      *
      * Receives the RHS value parsed at `rbp`, applies `combine(lhs, _)`, and re-enters the outer
      * loop at `minBp`. Replaces the three-frame chain
      * `PrattLoop(rbp, ...) ▸ MapStep(combine(lhs, _)) ▸ PrattLoop(minBp, ...)` with a single frame
      * that captures all the state. Drops from 3 GADT-node allocations per infix operator to 2
      * (this frame plus the inner `PrattLoop` that parses the RHS), and eliminates the per-operator
      * lambda closure that `MapStep` would have carried.
      */
    case PrattCombine[E0, Elem0, A0, Out0](
      lhs: A0,
      combine: (A0, A0) => A0,
      minBp: Int,
      nud: ParserK[E0, Elem0, A0],
      getOp: ParserK[E0, Elem0, PrattOp[A0]],
      opTable: PrattOpTable[A0] | Null,
      next: ParserCont[E0, Elem0, A0, Out0]
    ) extends ParserCont[E0, Elem0, A0, Out0]

    /** First half of a zero-closure Zip handler.
      *
      * When `Parser.Zip(left, right)` is evaluated, the trampoline pushes this frame and begins
      * evaluating `left`. When `left` succeeds with a value, the Apply phase evaluates `right`
      * under a `PairStep` carrying the captured left value.
      *
      * Replaces the closure-based decomposition `Eval(left, Step(a => Map(right, b => (a, b))))` —
      * which allocates two lambda closures per Zip — with two GADT nodes and zero closures.
      *
      * Invariant `A0` and `B0` type parameters let Scala 3 GADT refinement unify the captured types
      * at the match site without `asInstanceOf`.
      */
    case ZipStep[E0, Elem0, A0, B0, Out0](
      right: ParserK[E0, Elem0, B0],
      next: ParserCont[E0, Elem0, (A0, B0), Out0]
    ) extends ParserCont[E0, Elem0, A0, Out0]

    /** Second half of a zero-closure Zip handler.
      *
      * Carries the already-parsed left value. When `right` produces a value, this frame pairs the
      * two and hands the tuple to `next`. No closure is required because `leftValue` is a field.
      */
    case PairStep[E0, Elem0, A0, B0, Out0](
      leftValue: A0,
      next: ParserCont[E0, Elem0, (A0, B0), Out0]
    ) extends ParserCont[E0, Elem0, B0, Out0]

    /** Single-frame trampolined `Parser.SkipLeft` (keep right, discard left).
      *
      * Pushed when `Parser.SkipLeft(left, right)` is evaluated; the trampoline begins evaluating
      * `left`. When `left` succeeds/partials, its value is *discarded* and `right` is evaluated
      * with `next` directly on top — right's value flows through unchanged, so no second frame is
      * needed. One GADT node per SkipLeft, zero closures.
      */
    case SkipLeftStep[E0, Elem0, A0, B0, Out0](
      right: ParserK[E0, Elem0, B0],
      next: ParserCont[E0, Elem0, B0, Out0]
    ) extends ParserCont[E0, Elem0, A0, Out0]

    /** First half of a trampolined `Parser.SkipRight` (keep left, discard right).
      *
      * Pushed when `Parser.SkipRight(left, right)` is evaluated; the trampoline begins evaluating
      * `left`. When `left` succeeds/partials, its value is captured by a `SkipRightDiscard` frame
      * and `right` is evaluated underneath.
      */
    case SkipRightStep[E0, Elem0, A0, B0, Out0](
      right: ParserK[E0, Elem0, B0],
      next: ParserCont[E0, Elem0, A0, Out0]
    ) extends ParserCont[E0, Elem0, A0, Out0]

    /** Second half of a trampolined `Parser.SkipRight`.
      *
      * Carries the already-parsed left value. When `right` produces a value it is discarded and the
      * captured left value is handed to `next`. No closure — `leftValue` is a field.
      */
    case SkipRightDiscard[E0, Elem0, A0, B0, Out0](
      leftValue: A0,
      next: ParserCont[E0, Elem0, A0, Out0]
    ) extends ParserCont[E0, Elem0, B0, Out0]

    /** First half of a trampolined `Parser.Or`.
      *
      * Pushed when `Or(left, right)` is evaluated and the FIRST-set lookahead did NOT already
      * decide the left branch fails (that fast path skips straight to `right` without a frame). The
      * trampoline then evaluates `left` with this frame on top.
      *
      *   - `left` succeeds / partials → the value flows straight through to `next` (Or is
      *     value-transparent: it returns the winning branch's result unchanged).
      *   - `left` fails → restore `snapshot` and evaluate `right` under an `OrRight` frame carrying
      *     the captured left failure so the two can be merged if `right` also fails.
      *
      * Mirrors the `interpretI(Parser.Or)` backtracking: `state.restore(snapshot)` before `right`,
      * left's value/partial passed through unchanged. The FIRST-set fast path is resolved eagerly
      * in the Eval phase (identical to `interpretI`), so no `simple` flag is carried on the frame.
      *
      * `entryConsumed` is the trampoline's `consumed` accumulator at Or entry. The accumulator is
      * threaded top-down and a partially-consuming `left` (e.g. a `Zip` whose first leg committed)
      * advances it before failing, so the failure→`right` transition must RESET it to the entry
      * value — `interpretI` gets this for free because it recomputes `consumed` bottom-up from
      * return values, but the trampoline must carry it explicitly. Success/partial pass through
      * with the current accumulator unchanged (those paths never over-count).
      */
    case OrLeft[E0, Elem0, A0, Out0](
      right: ParserK[E0, Elem0, A0],
      snapshot: StateSnapshot,
      entryConsumed: Int,
      next: ParserCont[E0, Elem0, A0, Out0]
    ) extends ParserCont[E0, Elem0, A0, Out0]

    /** Second half of a trampolined `Parser.Or`.
      *
      * Carries the `left` branch's `LazyFailure` so that, when `right` also fails, the
      * error-furthest selection / tie-merge can run exactly as `interpretI(Parser.Or)` does:
      *   - `errorsDiscarded` → return the (cheap) left failure, outer context drops it.
      *   - left furthest > right furthest → left failure; right > left → right failure.
      *   - tie → merge both error thunks at the (shared) furthest location.
      * On `right` success / partial the value flows straight through to `next`.
      */
    case OrRight[E0, Elem0, A0, Out0](
      leftFailure: LazyFailure[E0],
      next: ParserCont[E0, Elem0, A0, Out0]
    ) extends ParserCont[E0, Elem0, A0, Out0]

    /** Trampolined `Parser.Choice`: tries each alternative in `remaining` until one succeeds.
      *
      * Threads the same three accumulators `interpretChoiceI` carries:
      *   - `snapshot`: the state at Choice entry, restored before each next alternative.
      *   - `accMkErrors`: merged error thunks from alternatives that failed at the furthest offset.
      *   - `furthest`: the furthest location any failed alternative reached.
      *
      * On the current alternative's success / partial the value flows through to `next`. On
      * failure, update `(accMkErrors, furthest)` by the same furthest-wins / tie-merge rule and
      * either evaluate the next alternative under a fresh `ChoiceFrame` or, if `remaining` is
      * empty, hand a `LazyFailure(accMkErrors, furthest)` to `next`.
      */
    case ChoiceFrame[E0, Elem0, A0, Out0](
      remaining: List[ParserK[E0, Elem0, A0]],
      snapshot: StateSnapshot,
      entryConsumed: Int,
      accMkErrors: () => List[E0],
      furthest: Location,
      next: ParserCont[E0, Elem0, A0, Out0]
    ) extends ParserCont[E0, Elem0, A0, Out0]

    /** Trampolined `Parser.Capture`: replaces the inner parser's value with the slice of input it
      * consumed, preserving the consumed count. Value-transparent on counts (like `MapStep`); the
      * slice is `state.slice(startOffset, state.offset)` — exact because `consumed` tracks offset
      * advancement 1:1, so the end position equals `startOffset + innerConsumed`. Failure passes
      * straight through.
      */
    case CaptureFrame[E0, Elem0, A0, Out0](
      startOffset: Int,
      next: ParserCont[E0, Elem0, String, Out0]
    ) extends ParserCont[E0, Elem0, A0, Out0]

    /** Trampolined `Parser.LookAhead`: runs the inner parser but always restores the entry
      * snapshot, so the lookahead is non-consuming. On success/partial the value flows to `next`
      * with consumed reset to `entryConsumed` and the value's own consumed zeroed (mirrors
      * `Result.Success(value, 0)`); on failure the failure propagates after the restore.
      */
    case LookAheadFrame[E0, Elem0, A0, Out0](
      snapshot: StateSnapshot,
      entryConsumed: Int,
      next: ParserCont[E0, Elem0, A0, Out0]
    ) extends ParserCont[E0, Elem0, A0, Out0]

    /** Trampolined `Parser.NotFollowedBy`: negative lookahead. Always restores the entry snapshot
      * (non-consuming). Inner success/partial → a synthesized `Custom` failure at the entry
      * location; inner failure → `Success((), 0)`. Fixed to `ParseError` because it constructs
      * `ParseError.Custom` (the `NotFollowedBy` ADT case is `Parser[ParseError, Unit]`).
      */
    case NotFollowedByFrame[Elem0, A0, Out0](
      snapshot: StateSnapshot,
      entryConsumed: Int,
      next: ParserCont[ParseError, Elem0, Unit, Out0]
    ) extends ParserCont[ParseError, Elem0, A0, Out0]

    /** Trampolined `Parser.Named`: value-transparent; on failure (unless `errorsDiscarded`)
      * rewrites each `Unexpected` error's expected-set to add `name`. Fixed to `ParseError` because
      * it inspects/builds `ParseError.Unexpected` (the `Named` ADT case is
      * `Parser[ParseError, A]`).
      */
    case NamedFrame[Elem0, A0, Out0](
      name: String,
      next: ParserCont[ParseError, Elem0, A0, Out0]
    ) extends ParserCont[ParseError, Elem0, A0, Out0]

    /** Trampolined `Parser.Expect`: value-transparent; on failure (unless `errorsDiscarded`)
      * replaces the errors with a single `Custom(message)` at the furthest location. Fixed to
      * `ParseError` (the `Expect` ADT case is `Parser[ParseError, A]`).
      */
    case ExpectFrame[Elem0, A0, Out0](
      message: String,
      next: ParserCont[ParseError, Elem0, A0, Out0]
    ) extends ParserCont[ParseError, Elem0, A0, Out0]

    /** Trampolined `Parser.Optional`. Mirrors `interpretI(Parser.Optional)`:
      *
      * At Eval time the caller snapshots, captures `prevDiscarded`, and sets `errorsDiscarded =
      * true` around the inner parse. When this frame fires it FIRST restores
      * `setErrorsDiscarded(prevDiscarded)` (the save/restore discipline the `SentinelLazyFailure`
      * zero-alloc path depends on), then:
      *   - Success(v, c) → `Some(v)`, consumed unchanged.
      *   - LazyPartial(v, mkErrs, c) → `Some(v)`, errors preserved.
      *   - LazyFailure → restore `snapshot`, reset consumed to `entryConsumed`, `Success(None, 0)`.
      */
    case OptionalFrame[E0, Elem0, A0, Out0](
      snapshot: StateSnapshot,
      entryConsumed: Int,
      prevDiscarded: Boolean,
      next: ParserCont[E0, Elem0, Option[A0], Out0]
    ) extends ParserCont[E0, Elem0, A0, Out0]

    /** First half of a trampolined `Parser.RecoverWith` — the PRIMARY parse just completed.
      *
      * At Eval time the caller snapshots, captures `prevDiscarded`, and sets `errorsDiscarded =
      * false` around the primary parse (RecoverWith observes inner errors). When this frame fires
      * it FIRST restores `setErrorsDiscarded(prevDiscarded)`, then:
      *   - Success / LazyPartial → pass straight through to `next` (no recovery needed).
      *   - LazyFailure(mkErrors, _) → restore `snapshot`, reset consumed to `entryConsumed`, and
      *     evaluate `recovery` under a `RecoverCombine` frame carrying the primary error thunk.
      *     NOTE recovery runs under the RESTORED `prevDiscarded` context, exactly as `interpretI`
      *     does (it restores the flag before the recovery call).
      */
    case RecoverTry[E0, Elem0, A0, Out0](
      recovery: ParserK[E0, Elem0, A0],
      snapshot: StateSnapshot,
      entryConsumed: Int,
      prevDiscarded: Boolean,
      next: ParserCont[E0, Elem0, A0, Out0]
    ) extends ParserCont[E0, Elem0, A0, Out0]

    /** Second half of a trampolined `Parser.RecoverWith` — the RECOVERY parse just completed.
      *
      * Carries `primaryMkErrors`, the primary failure's error thunk. Mirrors `interpretI`:
      *   - recovery Success(v, c) → `LazyPartial(v, primaryMkErrors, c)` (success value, but the
      *     primary errors are surfaced as a partial).
      *   - recovery LazyPartial(v, mkRec, c) → `LazyPartial(v, primary ++ mkRec, c)`.
      *   - recovery LazyFailure(mkRec, recFurthest) →
      *     `LazyFailure(primary ++ mkRec, max-furthest)`.
      */
    case RecoverCombine[E0, Elem0, A0, Out0](
      primaryMkErrors: () => List[E0],
      primaryFurthest: Location,
      next: ParserCont[E0, Elem0, A0, Out0]
    ) extends ParserCont[E0, Elem0, A0, Out0]

    /** Trampolined `Parser.Attempt`. Mirrors `interpretI(Parser.Attempt)`.
      *
      * Attempt reifies the inner outcome into a `Result[E0, A0]` value and ALWAYS succeeds at the
      * outer level (the ADT case is `Parser[Nothing, Result[E0, A0]]`). At Eval time the caller
      * captures `prevDiscarded` and sets `errorsDiscarded = false` (Attempt observes inner errors).
      * When this frame fires it restores `setErrorsDiscarded(prevDiscarded)` FIRST, then:
      *   - inner Success(v, c) → `Success(Result.Success(v, c), 0)`.
      *   - inner LazyPartial(v, mkErrs, c) → `Success(Result.Partial(v, mkErrs(), c), 0)`.
      *   - inner LazyFailure(mkErrs, loc) → restore `snapshot`, `Success(Result.Failure(mkErrs(),
      *     loc), 0)`.
      *
      * Consumed semantics (load-bearing): the OUTER consumed is 0 in every arm — the inner `c` is
      * captured inside the reified `Result` value, and the state offset is left advanced on
      * success/partial (restored on failure). So Attempt contributes 0 to the trampoline's prefix
      * accumulator, exactly as `interpretI` returns `Result.Success(..., 0)`. The forced
      * `mkErrs()`/`Result.Partial`/`Result.Failure` materialise the errors precisely where
      * `interpretI` does (errorsDiscarded is false here, so no sentinel reaches this frame).
      *
      * `E0` is the inner error type; the produced value is `Result[E0, A0]`, and the frame's output
      * error type is `Nothing` (Attempt never fails). `next` therefore consumes `Result[E0, A0]`
      * with error type `Nothing`.
      */
    case AttemptFrame[E0, Elem0, A0, Out0](
      snapshot: StateSnapshot,
      prevDiscarded: Boolean,
      next: ParserCont[Nothing, Elem0, Result[E0, A0], Out0]
    ) extends ParserCont[E0, Elem0, A0, Out0]

    /** Trampolined `Parser.Many` looper. Mirrors `interpretI(Parser.Many)`'s general (non-simple)
      * branch — the Satisfy/StringMatch char-scan fast paths stay on their zero-alloc specialized
      * loops (delegated in the Eval phase) and never build this frame.
      *
      * Each iteration re-evaluates `p` with a (re-pushed) `ManyFrame` on top, so `p`'s OWN depth
      * flows through the trampoline rather than recursing in `interpretI`. The repetition itself
      * was already stack-safe (a bounded `while`); this lifts the per-item inner parse.
      *
      *   - `acc` / `errThunks` are the same mutable buffers shared across every re-push (the frame
      *     is immutable, but it carries references to the buffers `interpretI` mutated in place).
      *   - `entryConsumed`: the accumulator at Many entry. The final list is handed to `next` with
      *     consumed = `iterConsumed - entryConsumed` (total repetition consumption).
      *   - `iterConsumed`: the accumulator at the START of the current iteration. On a
      *     partially-consuming-then-failing item the arriving accumulator over-counts, so the
      *     terminating arm resets to `iterConsumed` (same role as `OrLeft.entryConsumed`).
      *   - `snapshot`: state at the current iteration's start, restored when the item fails.
      *   - `prevDiscarded`: restored once at termination (the loop runs under
      *     errorsDiscarded=true).
      */
    case ManyFrame[E0, Elem0, A0, Out0](
      p: ParserK[E0, Elem0, A0],
      acc: scala.collection.mutable.ArrayBuffer[A0],
      errThunks: scala.collection.mutable.ArrayBuffer[() => List[E0]],
      entryConsumed: Int,
      iterConsumed: Int,
      snapshot: StateSnapshot,
      prevDiscarded: Boolean,
      next: ParserCont[E0, Elem0, List[A0], Out0]
    ) extends ParserCont[E0, Elem0, A0, Out0]

    /** Trampolined `Parser.Many1` seed. Mirrors `interpretI(Parser.Many1)`: the FIRST item is
      * parsed under the ambient `errorsDiscarded` flag (NOT toggled), and on success/partial the
      * rest is a `Many` seeded with that head. This frame receives the head, seeds a `ManyFrame`
      * (which sets `errorsDiscarded=true` for the tail) with `acc = [head]`, and continues. On head
      * failure the failure propagates unchanged. Simple-`p` Many1 stays on the existing stack-safe
      * path (delegated in the Eval phase).
      */
    case Many1Frame[E0, Elem0, A0, Out0](
      p: ParserK[E0, Elem0, A0],
      entryConsumed: Int,
      next: ParserCont[E0, Elem0, List[A0], Out0]
    ) extends ParserCont[E0, Elem0, A0, Out0]

    /** Trampolined `Parser.SkipMany` looper. Like `ManyFrame` but discards values (no `acc`), so it
      * produces `Unit`. Mirrors `interpretI(Parser.SkipMany)`'s general branch; the simple-`p`
      * char-scan fast path stays on its specialized loop (delegated in the Eval phase).
      */
    case SkipManyFrame[E0, Elem0, A0, Out0](
      p: ParserK[E0, Elem0, A0],
      errThunks: scala.collection.mutable.ArrayBuffer[() => List[E0]],
      entryConsumed: Int,
      iterConsumed: Int,
      snapshot: StateSnapshot,
      prevDiscarded: Boolean,
      next: ParserCont[E0, Elem0, Unit, Out0]
    ) extends ParserCont[E0, Elem0, A0, Out0]
  }

  /** State machine for the trampoline.
    *
    *   - Eval: have a parser to evaluate, push continuations for FlatMap/Map/Zip/Pratt
    *   - ApplySuccess: carry `(value, consumed)` directly without wrapping in `Result.Success`
    *   - Apply: general carrier for `IResult` (used for LazyPartial / LazyFailure paths, and the
    *     fallthrough from `interpretI` which returns `IResult`)
    *
    * Splitting success out of `Apply` avoids allocating a transient `Result.Success` per trampoline
    * step — JFR profiling showed these were the single largest allocator (48.6% of bytes on
    * pratt_medium100). The shape mirrors Eru's `EvalState.ApplySuccess` / `ApplyFailure` split.
    */
  private[runtime] enum EvalState[E, Elem, Out] {
    case Eval[E0, Elem0, Mid, Out0](
      parser: ParserK[E0, Elem0, Mid],
      cont: ParserCont[E0, Elem0, Mid, Out0],
      consumed: Int
    ) extends EvalState[E0, Elem0, Out0]

    case ApplySuccess[E0, Elem0, Mid, Out0](
      value: Mid,
      valueConsumed: Int,
      cont: ParserCont[E0, Elem0, Mid, Out0],
      consumed: Int
    ) extends EvalState[E0, Elem0, Out0]

    case Apply[E0, Elem0, Mid, Out0](
      result: IResult[E0, Mid],
      cont: ParserCont[E0, Elem0, Mid, Out0],
      consumed: Int
    ) extends EvalState[E0, Elem0, Out0]

    /** Terminal state: the trampoline is done; `result` is the final answer. Produced when a step
      * hits the `End` continuation. The lean `loop` driver returns it directly, ending recursion.
      */
    case Done[E0, Elem0, Out0](
      result: IResult[E0, Out0]
    ) extends EvalState[E0, Elem0, Out0]
  }

  /** Stack-safe interpreter entry point. Signature unchanged from old TrampolineOpt.
    */
  def run[E, A](parser: Parser[E, A], state: ParserState): IResult[E, A] = {
    loop(EvalState.Eval(parser, ParserCont.End(), 0), state)
  }

  /** Add consumed chars to result. The one cast: LazyFailure is covariant so this is safe.
    */
  private def addConsumed[E, A](result: IResult[E, A], extra: Int): IResult[E, A] =
    if extra == 0 then result
    else
      result match {
        case Result.Success(v, c) => Result.Success(v, c + extra)
        case LazyPartial(v, mkE, c) => LazyPartial(v, mkE, c + extra)
        case _: LazyFailure[?] => result // LazyFailure[E] has no A; already a valid IResult[E, A]
      }

  /** Lean `@tailrec` driver. Dispatches on the `EvalState` variant to the matching non-recursive
    * `step*` method, each of which returns the NEXT `EvalState`; the driver re-loops on that.
    *
    * The split exists for a hard JVM constraint, not style: a single `loop` containing every Eval /
    * ApplySuccess / Apply / ComposeK arm compiled to ~15.5 KB of bytecode — about 2× HotSpot's
    * 8000-byte `DontCompileHugeMethods` ceiling — so C2 silently refused to JIT it and the whole
    * interpreter ran interpreted (~30× slower across every path; JMH-confirmed, cats-parse flat as
    * control). Keeping this driver tiny and the `step*` bodies as separate methods lets C2 compile
    * each. The `step*` methods MUST NOT call `loop` (that would defeat the cross-method
    * trampolining and regrow the native stack); they only build and return the next state. Same
    * shape as Eru's cold-step extraction.
    */
  @annotation.tailrec
  private def loop[E, Elem, A](st: EvalState[E, Elem, A], state: ParserState): IResult[E, A] = st match {
    case EvalState.Eval(parser, cont, consumed) =>
      loop(stepEval(parser, cont, consumed, state), state)
    case EvalState.ApplySuccess(value, valueConsumed, cont, consumed) =>
      loop(stepApplySuccess(value, valueConsumed, cont, consumed, state), state)
    case EvalState.Apply(result, cont, consumed) =>
      loop(stepApply(result, cont, consumed, state), state)
    case EvalState.Done(result) =>
      result
  }

  /** Eval phase: decompose `parser`, push the matching continuation frame, and return the next
    * state. Non-recursive — returns an `EvalState` for the driver to step into. Mirrors the
    * original inline Eval arm verbatim; `loop(X, state)` became `X`, and the fallthrough to
    * End/terminal states is produced by the subsequent ApplySuccess/Apply steps, never here.
    */
  private def stepEval[E, Elem, Mid, A](
    parser: ParserK[E, Elem, Mid],
    cont: ParserCont[E, Elem, Mid, A],
    consumed: Int,
    state: ParserState
  ): EvalState[E, Elem, A] =
    parser match {
      case Parser.FlatMap(source, f) =>
        EvalState.Eval(source, ParserCont.Step(f, cont), consumed)

      case Parser.Map(source, f) =>
        EvalState.Eval(source, ParserCont.MapStep(f, cont), consumed)

      case Parser.Zip(left, right) =>
        // Three asInstanceOf are load-bearing here: Scala 3's GADT solver gives subtype bounds
        // for Parser[+E, +A], not equalities (scala3#11956 / strongbow note). We need the
        // exact types to construct ZipStep, so we must cast at the Parser-→-Continuation
        // boundary. The casts are safe because: (a) Parser.Zip's extractor gives back `left`
        // and `right` with the precise types it was constructed with; (b) `cont`'s input type
        // is `Mid` which the match has refined `>: (A, B)` — the cast widens it to exactly
        // `(Any, Any)` so ZipStep can flow through. Behavior-equivalent to the function-based
        // decomposition it replaces, but zero lambda closures per Zip (2 GADT nodes instead of 2
        // closures) and fewer trampoline roundtrips on the hot path.
        val rightErased: ParserK[E, Elem, Any] =
          right.asInstanceOf[ParserK[E, Elem, Any]] // scalafix:ok DisableSyntax.asInstanceOf
        val contErased: ParserCont[E, Elem, (Any, Any), A] =
          cont.asInstanceOf[ParserCont[E, Elem, (Any, Any), A]] // scalafix:ok DisableSyntax.asInstanceOf
        val leftErased: ParserK[E, Elem, Any] =
          left.asInstanceOf[ParserK[E, Elem, Any]] // scalafix:ok DisableSyntax.asInstanceOf
        EvalState.Eval(leftErased, ParserCont.ZipStep(rightErased, contErased), consumed)

      case Parser.SkipLeft(left, right) =>
        // Keep right, discard left — one frame. Same GADT-boundary erasure as Zip: Scala 3 gives
        // subtype bounds, not equalities, so we cast left/right to Any and widen cont to Any. Safe:
        // SkipLeftStep drops left's value and lets right's value flow through unchanged.
        val leftErased: ParserK[E, Elem, Any] =
          left.asInstanceOf[ParserK[E, Elem, Any]] // scalafix:ok DisableSyntax.asInstanceOf
        val rightErased: ParserK[E, Elem, Any] =
          right.asInstanceOf[ParserK[E, Elem, Any]] // scalafix:ok DisableSyntax.asInstanceOf
        val contErased: ParserCont[E, Elem, Any, A] =
          cont.asInstanceOf[ParserCont[E, Elem, Any, A]] // scalafix:ok DisableSyntax.asInstanceOf
        EvalState.Eval(leftErased, ParserCont.SkipLeftStep(rightErased, contErased), consumed)

      case Parser.SkipRight(left, right) =>
        // Keep left, discard right — two frames (SkipRightStep captures left's value, SkipRightDiscard
        // discards right's). Same erasure discipline as Zip.
        val leftErased: ParserK[E, Elem, Any] =
          left.asInstanceOf[ParserK[E, Elem, Any]] // scalafix:ok DisableSyntax.asInstanceOf
        val rightErased: ParserK[E, Elem, Any] =
          right.asInstanceOf[ParserK[E, Elem, Any]] // scalafix:ok DisableSyntax.asInstanceOf
        val contErased: ParserCont[E, Elem, Any, A] =
          cont.asInstanceOf[ParserCont[E, Elem, Any, A]] // scalafix:ok DisableSyntax.asInstanceOf
        EvalState.Eval(leftErased, ParserCont.SkipRightStep(rightErased, contErased), consumed)

      case Parser.Pratt(nud, getOp, minBp, opTable) =>
        EvalState.Eval(nud, ParserCont.PrattLoop(minBp, nud, getOp, opTable, cont), consumed)

      case Parser.Or(left, right) =>
        // Mirror interpretI(Parser.Or): FIRST-set lookahead fast path, else trampoline `left`
        // under an OrLeft frame. The snapshot is taken only when `left` is actually attempted -
        // a lookahead-decided skip never needs a restore. `consumed` is captured as the frame's
        // `entryConsumed` so a partially-consuming `left` (e.g. a committed Zip leg) does not
        // over-count when we backtrack into `right`.
        if orLookaheadFails(left, state) then {
          EvalState.Eval(right, cont, consumed)
        } else {
          val snapshot = state.save
          EvalState.Eval(left, ParserCont.OrLeft(right, snapshot, consumed, cont), consumed)
        }

      case Parser.Choice(alternatives) =>
        // Mirror interpretChoiceI's entry: snapshot + entry location, empty accumulated errors,
        // then evaluate the first alternative under a ChoiceFrame carrying the rest.
        val snapshot = state.save
        val loc = state.location
        alternatives match {
          case Nil =>
            EvalState.Apply(LazyFailure(() => Nil, loc), cont, consumed)
          case head :: tail =>
            EvalState.Eval(head, ParserCont.ChoiceFrame(tail, snapshot, consumed, () => Nil, loc, cont), consumed)
        }

      case Parser.Defer(thunk) =>
        // Lifted so recursive grammars (`defer { ... }`) flow through the trampoline rather than
        // re-entering interpretI. Without this, structural recursion via Defer reaches interpretI's
        // own recursive Or/Zip cases and overflows — Defer is the recursion point in the
        // acceptance grammar. Zero-allocation: just forces the thunk and continues.
        EvalState.Eval(thunk(), cont, consumed)

      case Parser.Capture(p) =>
        // Capture the start offset and run the inner parser; CaptureFrame slices the consumed
        // region on success/partial. No snapshot/restore and no errorsDiscarded toggle — matches
        // interpretI(Parser.Capture).
        EvalState.Eval(p, ParserCont.CaptureFrame(state.offset, cont), consumed)

      case Parser.LookAhead(p) =>
        // Snapshot, run inner, always restore (non-consuming). consumed reset to entry on the way
        // out so the lookahead contributes zero.
        EvalState.Eval(p, ParserCont.LookAheadFrame(state.save, consumed, cont), consumed)

      case Parser.NotFollowedBy(p) =>
        // NotFollowedBy's ADT case fixes E = ParseError; the match refines cont to
        // ParserCont[ParseError, Unit, Out]. The cast is the GADT-boundary cast (same rationale as
        // Zip): Scala 3 gives subtype bounds, not equalities, for Parser[+E, +A], so we widen
        // `cont` to the exact type NotFollowedByFrame requires. Safe: the Parser.NotFollowedBy
        // extractor returns `p: Parser[ParseError, A]` and the frame produces Unit.
        val pErasedNfb: ParserK[ParseError, Elem, Any] =
          p.asInstanceOf[ParserK[ParseError, Elem, Any]] // scalafix:ok DisableSyntax.asInstanceOf
        val contNfb: ParserCont[ParseError, Elem, Unit, A] =
          cont.asInstanceOf[ParserCont[ParseError, Elem, Unit, A]] // scalafix:ok DisableSyntax.asInstanceOf
        // The produced state is EvalState[ParseError, Elem, A]; widen to EvalState[E, Elem, A]
        // (E >: ParseError here). EvalState is invariant in its error param, so the cast is required
        // — runtime-identical, only the phantom error type differs. Same discipline as the
        // frame-boundary casts.
        EvalState
          .Eval(pErasedNfb, ParserCont.NotFollowedByFrame(state.save, consumed, contNfb), consumed)
          .asInstanceOf[EvalState[E, Elem, A]] // scalafix:ok DisableSyntax.asInstanceOf

      case Parser.Named(p, name) =>
        // Named's ADT case fixes E = ParseError; the inner value type is the existential `Mid`,
        // which can't be named here, so we erase it to `Any` exactly as the Zip case does. The two
        // casts are the GADT-boundary casts: Scala 3 gives subtype bounds, not equalities, for
        // Parser[+E, +A]. Safe — Named is value-transparent, so the erased `Any` value flowing
        // through the frame is the same runtime object the inner parser produced.
        val pErasedN: ParserK[ParseError, Elem, Any] =
          p.asInstanceOf[ParserK[ParseError, Elem, Any]] // scalafix:ok DisableSyntax.asInstanceOf
        val contErasedN: ParserCont[ParseError, Elem, Any, A] =
          cont.asInstanceOf[ParserCont[ParseError, Elem, Any, A]] // scalafix:ok DisableSyntax.asInstanceOf
        EvalState
          .Eval(pErasedN, ParserCont.NamedFrame(name, contErasedN), consumed)
          .asInstanceOf[EvalState[E, Elem, A]] // scalafix:ok DisableSyntax.asInstanceOf

      case Parser.Expect(p, message) =>
        // Same erasure rationale as Named. Expect is value-transparent.
        val pErasedE: ParserK[ParseError, Elem, Any] =
          p.asInstanceOf[ParserK[ParseError, Elem, Any]] // scalafix:ok DisableSyntax.asInstanceOf
        val contErasedE: ParserCont[ParseError, Elem, Any, A] =
          cont.asInstanceOf[ParserCont[ParseError, Elem, Any, A]] // scalafix:ok DisableSyntax.asInstanceOf
        EvalState
          .Eval(pErasedE, ParserCont.ExpectFrame(message, contErasedE), consumed)
          .asInstanceOf[EvalState[E, Elem, A]] // scalafix:ok DisableSyntax.asInstanceOf

      case Parser.Optional(p) =>
        // Snapshot + set errorsDiscarded=true around the inner parse, capturing prevDiscarded for
        // the frame to restore. Mirrors interpretI(Parser.Optional). The inner value type is the
        // existential `Mid`; the case refines A = Option[Mid] but Mid can't be named, so erase to
        // Any (Zip precedent) — OptionalFrame wraps the Any value back into Some on success.
        val snapshotOpt = state.save
        val prevDiscardedOpt = state.errorsDiscarded
        state.setErrorsDiscarded(true)
        val pErasedOpt: ParserK[E, Elem, Any] =
          p.asInstanceOf[ParserK[E, Elem, Any]] // scalafix:ok DisableSyntax.asInstanceOf
        val contErasedOpt: ParserCont[E, Elem, Option[Any], A] =
          cont.asInstanceOf[ParserCont[E, Elem, Option[Any], A]] // scalafix:ok DisableSyntax.asInstanceOf
        EvalState.Eval(
          pErasedOpt,
          ParserCont.OptionalFrame(snapshotOpt, consumed, prevDiscardedOpt, contErasedOpt),
          consumed
        )

      case Parser.RecoverWith(p, recovery) =>
        // Snapshot + set errorsDiscarded=false around the PRIMARY parse (RecoverWith observes
        // inner errors), capturing prevDiscarded. RecoverTry restores the flag when it fires, so
        // the recovery parse runs under prevDiscarded — exactly as interpretI does.
        val snapshotRec = state.save
        val prevDiscardedRec = state.errorsDiscarded
        state.setErrorsDiscarded(false)
        EvalState.Eval(
          p,
          ParserCont.RecoverTry(recovery, snapshotRec, consumed, prevDiscardedRec, cont),
          consumed
        )

      case Parser.Attempt(p) =>
        // Attempt's ADT case fixes the OUTER E = Nothing and Mid = Result[Einner, Ainner]; the
        // inner error/value types are existential, so erase to Any (Zip precedent). Snapshot + set
        // errorsDiscarded=false around the inner parse (Attempt observes inner errors), capturing
        // prevDiscarded for AttemptFrame to restore. The cast on `cont` widens the outer Mid =
        // Result[Einner,Ainner] to Result[Any,Any]; safe because Attempt is value-reifying only —
        // the runtime Result object flowing to `next` is exactly what the frame builds.
        // The inner parse must stay in the loop's error type E so the recursive `loop` call keeps
        // type EvalState[E, A]; only the value type is erased to Any (Zip precedent). The frame's
        // `next` consumes Result[E, Any] at error type Nothing (Attempt never fails outward).
        val snapshotAtt = state.save
        val prevDiscardedAtt = state.errorsDiscarded
        state.setErrorsDiscarded(false)
        val pErasedAtt: ParserK[E, Elem, Any] =
          p.asInstanceOf[ParserK[E, Elem, Any]] // scalafix:ok DisableSyntax.asInstanceOf
        val contErasedAtt: ParserCont[Nothing, Elem, Result[E, Any], A] =
          cont.asInstanceOf[ParserCont[Nothing, Elem, Result[E, Any], A]] // scalafix:ok DisableSyntax.asInstanceOf
        EvalState.Eval(pErasedAtt, ParserCont.AttemptFrame(snapshotAtt, prevDiscardedAtt, contErasedAtt), consumed)

      case Parser.Many(p) =>
        // Simple inner parsers (Satisfy/StringMatch) keep their zero-alloc char-scan loops — they
        // have no inner recursion, so they're already stack-safe. Only the general case is
        // trampolined so a deeply-nested `p` flows through `loop` instead of recursing in
        // interpretI. Value type erased to Any (Optional precedent); the ArrayBuffers are typed at
        // Any and the produced List[Any] is the same runtime list `next` expects.
        p match {
          case _: Parser.Satisfy[?] | _: Parser.StringMatch =>
            EvalState.Apply(interpretManyI(p, state), cont, consumed)
          case _ =>
            val prevDiscardedMany = state.errorsDiscarded
            state.setErrorsDiscarded(true)
            val pErasedMany: ParserK[E, Elem, Any] =
              p.asInstanceOf[ParserK[E, Elem, Any]] // scalafix:ok DisableSyntax.asInstanceOf
            val contErasedMany: ParserCont[E, Elem, List[Any], A] =
              cont.asInstanceOf[ParserCont[E, Elem, List[Any], A]] // scalafix:ok DisableSyntax.asInstanceOf
            val acc = scala.collection.mutable.ArrayBuffer.empty[Any]
            val errThunks = scala.collection.mutable.ArrayBuffer.empty[() => List[E]]
            EvalState.Eval(
              pErasedMany,
              ParserCont
                .ManyFrame(
                  pErasedMany,
                  acc,
                  errThunks,
                  consumed,
                  consumed,
                  state.save,
                  prevDiscardedMany,
                  contErasedMany
                ),
              consumed
            )
        }

      case Parser.Many1(p) =>
        // Simple-`p` Many1 stays on the existing stack-safe path. General case: parse the FIRST
        // item under the ambient flag (Many1Frame does not toggle), then seed a Many for the tail.
        p match {
          case _: Parser.Satisfy[?] | _: Parser.StringMatch =>
            EvalState.Apply(interpretMany1I(p, state), cont, consumed)
          case _ =>
            val pErasedMany1: ParserK[E, Elem, Any] =
              p.asInstanceOf[ParserK[E, Elem, Any]] // scalafix:ok DisableSyntax.asInstanceOf
            val contErasedMany1: ParserCont[E, Elem, List[Any], A] =
              cont.asInstanceOf[ParserCont[E, Elem, List[Any], A]] // scalafix:ok DisableSyntax.asInstanceOf
            EvalState.Eval(pErasedMany1, ParserCont.Many1Frame(pErasedMany1, consumed, contErasedMany1), consumed)
        }

      case Parser.SkipMany(p) =>
        // Mirror of Many but value-discarding. Simple-`p` keeps the zero-alloc char-scan loop.
        p match {
          case _: Parser.Satisfy[?] | _: Parser.StringMatch =>
            EvalState.Apply(interpretSkipManyI(p, state), cont, consumed)
          case _ =>
            val prevDiscardedSkip = state.errorsDiscarded
            state.setErrorsDiscarded(true)
            val pErasedSkip: ParserK[E, Elem, Any] =
              p.asInstanceOf[ParserK[E, Elem, Any]] // scalafix:ok DisableSyntax.asInstanceOf
            val contErasedSkip: ParserCont[E, Elem, Unit, A] =
              cont.asInstanceOf[ParserCont[E, Elem, Unit, A]] // scalafix:ok DisableSyntax.asInstanceOf
            val errThunks = scala.collection.mutable.ArrayBuffer.empty[() => List[E]]
            EvalState.Eval(
              pErasedSkip,
              ParserCont
                .SkipManyFrame(
                  pErasedSkip,
                  errThunks,
                  consumed,
                  consumed,
                  state.save,
                  prevDiscardedSkip,
                  contErasedSkip
                ),
              consumed
            )
        }

      case Parser.FirstCharChoice(table, expected, fallback) =>
        // Pure dispatch: peek the next char and select the mapped parser (or fallback). No frame —
        // the decision picks the next parser to evaluate, mirroring interpretI(FirstCharChoice).
        // Char-pinned + ParseError-fixed, so no GADT-boundary cast.
        def miss(): EvalState[E, Elem, A] =
          fallback match {
            case Some(fb) => EvalState.Eval(fb, cont, consumed)
            case None =>
              if state.errorsDiscarded then EvalState.Apply(SentinelLazyFailure, cont, consumed)
              else {
                val loc = state.location
                if state.hasChar then
                  EvalState.Apply(
                    LazyFailure(
                      () => List(ParseError.Unexpected(state.currentChar.toString, Set(s"one of \"$expected\""), loc)),
                      loc
                    ),
                    cont,
                    consumed
                  )
                else
                  EvalState.Apply(
                    LazyFailure(() => List(ParseError.EndOfInput(s"one of \"$expected\"", loc)), loc),
                    cont,
                    consumed
                  )
              }
          }
        if state.hasChar then {
          table.get(state.currentChar) match {
            case Some(p) => EvalState.Eval(p, cont, consumed)
            case None => miss()
          }
        } else miss()

      case other =>
        EvalState.Apply(interpretI(other, state), cont, consumed)
    }

  /** ApplySuccess phase: value + valueConsumed fast-path, no `Result.Success` wrapping. Returns the
    * next `EvalState`; terminal (`End`) yields `Done`. Non-recursive — never calls `loop`.
    */
  private def stepApplySuccess[E, Elem, Mid, A](
    value: Mid,
    valueConsumed: Int,
    cont: ParserCont[E, Elem, Mid, A],
    consumed: Int,
    state: ParserState
  ): EvalState[E, Elem, A] =
    cont match {
      case _: ParserCont.End[?, ?, ?] =>
        EvalState.Done(Result.Success(value, consumed + valueConsumed))

      case ParserCont.Step(f, next) =>
        EvalState.Eval(f(value), next, consumed + valueConsumed)

      case ParserCont.MapStep(f, next) =>
        EvalState.ApplySuccess(f(value), valueConsumed, next, consumed)

      case ParserCont.PartialStep(mkErrors, next) =>
        EvalState.Apply(LazyPartial(value, mkErrors, valueConsumed), next, consumed)

      case ParserCont.ZipStep(right, next) =>
        EvalState.Eval(right, ParserCont.PairStep(value, next), consumed + valueConsumed)

      case ParserCont.PairStep(leftValue, next) =>
        EvalState.ApplySuccess((leftValue, value), valueConsumed, next, consumed)

      case ParserCont.SkipLeftStep(right, next) =>
        // Left (discarded) value produced: drop it and run right under `next` directly.
        EvalState.Eval(right, next, consumed + valueConsumed)

      case ParserCont.SkipRightStep(right, next) =>
        // Left (kept) value produced: capture it and run right under a discard frame.
        EvalState.Eval(right, ParserCont.SkipRightDiscard(value, next), consumed + valueConsumed)

      case ParserCont.SkipRightDiscard(leftValue, next) =>
        // Right (discarded) value produced: return the captured left value.
        EvalState.ApplySuccess(leftValue, valueConsumed, next, consumed)

      case ParserCont.PrattLoop(minBp, nud, getOp, opTable, next) =>
        applyPrattLoop(Result.Success(value, valueConsumed), minBp, nud, getOp, opTable, next, consumed, state)

      case ParserCont.PrattCombine(lhs, combine, minBp, nud, getOp, opTable, next) =>
        EvalState.ApplySuccess(
          combine(lhs, value),
          valueConsumed,
          ParserCont.PrattLoop(minBp, nud, getOp, opTable, next),
          consumed
        )

      case ParserCont.OrLeft(_, _, _, next) =>
        // Left branch succeeded: Or is value-transparent — pass the winning value straight to
        // next. The right branch and snapshot are dropped (no backtrack needed on success).
        EvalState.ApplySuccess(value, valueConsumed, next, consumed)

      case ParserCont.OrRight(_, next) =>
        // Right branch succeeded after left failed: pass the value through; the captured left
        // failure is discarded.
        EvalState.ApplySuccess(value, valueConsumed, next, consumed)

      case ParserCont.ChoiceFrame(_, _, _, _, _, next) =>
        // An alternative succeeded: pass its value through; remaining alternatives and accumulated
        // errors are discarded.
        EvalState.ApplySuccess(value, valueConsumed, next, consumed)

      case ParserCont.CaptureFrame(startOffset, next) =>
        // Inner succeeded: replace the value with the consumed slice. End offset is state.offset
        // (advanced 1:1 with consumption), equal to startOffset + the inner's total consumed.
        EvalState.ApplySuccess(state.slice(startOffset, state.offset), valueConsumed, next, consumed)

      case ParserCont.LookAheadFrame(snapshot, entryConsumed, next) =>
        // Inner succeeded: restore (non-consuming) and forward the value with zero consumed,
        // mirroring interpretI's `Result.Success(value, 0)`. consumed resets to entry.
        state.restore(snapshot)
        EvalState.ApplySuccess(value, 0, next, entryConsumed)

      case ParserCont.NotFollowedByFrame(snapshot, entryConsumed, next) =>
        // Inner succeeded → NotFollowedBy fails. Restore, synthesize a Custom failure at the
        // (restored) location, hand it to next with consumed reset to entry.
        state.restore(snapshot)
        val loc = state.location
        EvalState.Apply(LazyFailure(() => List(ParseError.Custom("Unexpected success", loc)), loc), next, entryConsumed)

      case ParserCont.NamedFrame(_, next) =>
        // Named is value-transparent on success — only failures are rewritten.
        EvalState.ApplySuccess(value, valueConsumed, next, consumed)

      case ParserCont.ExpectFrame(_, next) =>
        // Expect is value-transparent on success — only failures are rewritten.
        EvalState.ApplySuccess(value, valueConsumed, next, consumed)

      case ParserCont.OptionalFrame(_, _, prevDiscarded, next) =>
        // Inner succeeded: restore the discarded flag, then wrap the value in Some. Snapshot and
        // entryConsumed are only needed on the failure path.
        state.setErrorsDiscarded(prevDiscarded)
        EvalState.ApplySuccess(Some(value), valueConsumed, next, consumed)

      case ParserCont.RecoverTry(_, _, _, prevDiscarded, next) =>
        // Primary parse succeeded: restore the discarded flag, pass the value straight through.
        // Recovery and snapshot are dropped (no recovery needed).
        state.setErrorsDiscarded(prevDiscarded)
        EvalState.ApplySuccess(value, valueConsumed, next, consumed)

      case ParserCont.RecoverCombine(primaryMkErrors, _, next) =>
        // Recovery succeeded: surface the primary errors as a LazyPartial around the recovery
        // value (mirrors interpretI's `LazyPartial(value, mkErrors, consumed)`).
        EvalState.Apply(LazyPartial(value, primaryMkErrors, valueConsumed), next, consumed)

      case ParserCont.AttemptFrame(_, prevDiscarded, next) =>
        // Inner succeeded: restore the flag, reify into Result.Success(value, valueConsumed), and
        // hand it to next with OUTER consumed 0 (the inner consumed is captured inside the value;
        // the state stays advanced). Mirrors interpretI's `Result.Success(Result.Success(v,c),0)`.
        state.setErrorsDiscarded(prevDiscarded)
        // AttemptFrame's `next` fixes error type Nothing; widen the produced EvalState[Nothing,
        // Elem, A] to EvalState[E, Elem, A] (invariant error param, runtime-identical). Same as
        // applyAttemptFrame.
        EvalState
          .ApplySuccess(Result.Success(value, valueConsumed), 0, next, consumed)
          .asInstanceOf[EvalState[E, Elem, A]] // scalafix:ok DisableSyntax.asInstanceOf

      case ParserCont.ManyFrame(p, acc, errThunks, entryConsumed, iterConsumed, snapshot, prevDiscarded, next) =>
        // Materialize success and delegate; the general Many path already allocates per item, so a
        // dedicated zero-alloc ApplySuccess branch buys nothing (simple parsers bypass this frame).
        applyManyFrame(
          Result.Success(value, valueConsumed),
          p,
          acc,
          errThunks,
          entryConsumed,
          iterConsumed,
          snapshot,
          prevDiscarded,
          next,
          consumed,
          state
        )

      case ParserCont.SkipManyFrame(p, errThunks, entryConsumed, iterConsumed, snapshot, prevDiscarded, next) =>
        applySkipManyFrame(
          Result.Success(value, valueConsumed),
          p,
          errThunks,
          entryConsumed,
          iterConsumed,
          snapshot,
          prevDiscarded,
          next,
          consumed,
          state
        )

      case ParserCont.Many1Frame(p, entryConsumed, next) =>
        applyMany1Frame(Result.Success(value, valueConsumed), p, entryConsumed, next, consumed, state)

      case ParserCont.ComposeK(first, second) =>
        // Delegate to the general Apply-phase ComposeK logic by materializing Success here.
        // ComposeK is a rare path (only from Many/Choice composition), not worth a dedicated
        // ApplySuccess branch. The ComposeK mid type is existential; erase to Any so it matches
        // stepComposeK's (first: ...,Any], second: [Any,...]) signature — runtime-identical.
        val firstErasedCK: ParserCont[E, Elem, Mid, Any] =
          first.asInstanceOf[ParserCont[E, Elem, Mid, Any]] // scalafix:ok DisableSyntax.asInstanceOf
        val secondErasedCK: ParserCont[E, Elem, Any, A] =
          second.asInstanceOf[ParserCont[E, Elem, Any, A]] // scalafix:ok DisableSyntax.asInstanceOf
        stepComposeK(Result.Success(value, valueConsumed), firstErasedCK, secondErasedCK, consumed, state)
    }

  /** Apply phase: process `result` against the top continuation. Returns the next `EvalState`;
    * terminal (`End`) yields `Done`. The large `ComposeK` decomposition lives in `stepComposeK` to
    * keep this method (and the driver) under C2's huge-method ceiling. Non-recursive.
    */
  private def stepApply[E, Elem, Mid, A](
    result: IResult[E, Mid],
    cont: ParserCont[E, Elem, Mid, A],
    consumed: Int,
    state: ParserState
  ): EvalState[E, Elem, A] =
    cont match {

      // --- End: return final result ---
      case _: ParserCont.End[?, ?, ?] =>
        EvalState.Done(addConsumed(result, consumed))

      // --- Step: flatMap continuation ---
      case ParserCont.Step(f, next) =>
        result match {
          case Result.Success(v, c) =>
            EvalState.Eval(f(v), next, consumed + c)

          case LazyPartial(v, mkErrs, c) =>
            EvalState.Eval(f(v), ParserCont.PartialStep(mkErrs, next), consumed + c)

          case LazyFailure(mkErrs, loc) =>
            EvalState.Apply(LazyFailure(mkErrs, loc), next, consumed)
        }

      // --- MapStep: pure function ---
      case ParserCont.MapStep(f, next) =>
        result match {
          case Result.Success(v, c) =>
            EvalState.ApplySuccess(f(v), c, next, consumed)

          case LazyPartial(v, mkErrs, c) =>
            EvalState.Apply(LazyPartial(f(v), mkErrs, c), next, consumed)

          case LazyFailure(mkErrs, loc) =>
            EvalState.Apply(LazyFailure(mkErrs, loc), next, consumed)
        }

      // --- PartialStep: carry error thunks ---
      case ParserCont.PartialStep(mkErrors, next) =>
        result match {
          case Result.Success(v, c) =>
            EvalState.Apply(LazyPartial(v, mkErrors, c), next, consumed)

          case LazyPartial(v, mkErrs2, c) =>
            val combined = () => mkErrors() ++ mkErrs2()
            EvalState.Apply(LazyPartial(v, combined, c), next, consumed)

          case LazyFailure(mkErrs2, loc) =>
            val combined = () => mkErrors() ++ mkErrs2()
            EvalState.Apply(LazyFailure(combined, loc), next, consumed)
        }

      // --- ZipStep: left side of Zip complete, evaluate right ---
      case ParserCont.ZipStep(right, next) =>
        result match {
          case Result.Success(a, c) =>
            EvalState.Eval(right, ParserCont.PairStep(a, next), consumed + c)

          case LazyPartial(a, mkErrs, c) =>

            EvalState.Eval(right, ParserCont.PairStep(a, ParserCont.PartialStep(mkErrs, next)), consumed + c)

          case LazyFailure(mkErrs, loc) =>
            EvalState.Apply(LazyFailure(mkErrs, loc), next, consumed)
        }

      // --- PairStep: pair left value with right result ---
      case ParserCont.PairStep(leftValue, next) =>
        result match {
          case Result.Success(b, c) =>
            EvalState.ApplySuccess((leftValue, b), c, next, consumed)

          case LazyPartial(b, mkErrs, c) =>
            EvalState.Apply(LazyPartial((leftValue, b), mkErrs, c), next, consumed)

          case LazyFailure(mkErrs, loc) =>
            EvalState.Apply(LazyFailure(mkErrs, loc), next, consumed)
        }

      // --- SkipLeftStep: left (discarded) leg done; run right, discarding left's value ---
      case ParserCont.SkipLeftStep(right, next) =>
        result match {
          case Result.Success(_, c) =>
            EvalState.Eval(right, next, consumed + c)

          case LazyPartial(_, mkErrs, c) =>
            EvalState.Eval(right, ParserCont.PartialStep(mkErrs, next), consumed + c)

          case LazyFailure(mkErrs, loc) =>
            EvalState.Apply(LazyFailure(mkErrs, loc), next, consumed)
        }

      // --- SkipRightStep: left (kept) leg done; capture it and run right under a discard frame ---
      case ParserCont.SkipRightStep(right, next) =>
        result match {
          case Result.Success(a, c) =>
            EvalState.Eval(right, ParserCont.SkipRightDiscard(a, next), consumed + c)

          case LazyPartial(a, mkErrs, c) =>
            EvalState.Eval(right, ParserCont.SkipRightDiscard(a, ParserCont.PartialStep(mkErrs, next)), consumed + c)

          case LazyFailure(mkErrs, loc) =>
            EvalState.Apply(LazyFailure(mkErrs, loc), next, consumed)
        }

      // --- SkipRightDiscard: right (discarded) leg done; return the captured left value ---
      case ParserCont.SkipRightDiscard(leftValue, next) =>
        result match {
          case Result.Success(_, c) =>
            EvalState.ApplySuccess(leftValue, c, next, consumed)

          case LazyPartial(_, mkErrs, c) =>
            EvalState.Apply(LazyPartial(leftValue, mkErrs, c), next, consumed)

          case LazyFailure(mkErrs, loc) =>
            EvalState.Apply(LazyFailure(mkErrs, loc), next, consumed)
        }

      // --- PrattLoop: TDOP recursive boundary ---
      case ParserCont.PrattLoop(minBp, nud, getOp, opTable, next) =>
        applyPrattLoop(result, minBp, nud, getOp, opTable, next, consumed, state)

      // --- PrattCombine: fused combine-and-resume for infix RHS ---
      case ParserCont.PrattCombine(lhs, combine, minBp, nud, getOp, opTable, next) =>
        result match {
          case Result.Success(rhs, c) =>

            EvalState.ApplySuccess(
              combine(lhs, rhs),
              c,
              ParserCont.PrattLoop(minBp, nud, getOp, opTable, next),
              consumed
            )

          case LazyPartial(rhs, mkErrs, c) =>

            EvalState.Apply(
              LazyPartial(combine(lhs, rhs), mkErrs, c),
              ParserCont.PrattLoop(minBp, nud, getOp, opTable, next),
              consumed
            )

          case LazyFailure(mkErrs, loc) =>
            EvalState.Apply(LazyFailure(mkErrs, loc), next, consumed)
        }

      // --- OrLeft: left branch of Or complete; on failure backtrack into right ---
      case ParserCont.OrLeft(right, snapshot, entryConsumed, next) =>
        applyOrLeft(result, right, snapshot, entryConsumed, next, consumed, state)

      // --- OrRight: right branch of Or complete; on failure merge with left failure ---
      case ParserCont.OrRight(leftFailure, next) =>
        applyOrRight(result, leftFailure, next, consumed, state)

      // --- ChoiceFrame: one alternative complete; on failure try the next ---
      case ParserCont.ChoiceFrame(remaining, snapshot, entryConsumed, accMkErrors, furthest, next) =>

        applyChoiceFrame(result, remaining, snapshot, entryConsumed, accMkErrors, furthest, next, consumed, state)

      // --- CaptureFrame: replace value with consumed slice ---
      case ParserCont.CaptureFrame(startOffset, next) =>
        applyCaptureFrame(result, startOffset, next, consumed, state)

      // --- LookAheadFrame: non-consuming positive lookahead ---
      case ParserCont.LookAheadFrame(snapshot, entryConsumed, next) =>
        applyLookAheadFrame(result, snapshot, entryConsumed, next, state)

      // --- NotFollowedByFrame: non-consuming negative lookahead ---
      case ParserCont.NotFollowedByFrame(snapshot, entryConsumed, next) =>
        // Frame fixes error type ParseError; widen the produced EvalState[ParseError, Elem, A] to
        // EvalState[E, Elem, A] (invariant error param, runtime-identical).
        applyNotFollowedByFrame(result, snapshot, entryConsumed, next, state)
          .asInstanceOf[EvalState[E, Elem, A]] // scalafix:ok DisableSyntax.asInstanceOf

      // --- NamedFrame: rewrite failure expected-set ---
      case ParserCont.NamedFrame(name, next) =>
        // Erase the value type to Any at the call boundary (Zip precedent): the E=ParseError fix
        // plus the In=value-type constraint defeats Scala 3's GADT solver, which only gives
        // subtype bounds for the covariant value param. Named is value-transparent, so the runtime
        // object is unchanged.
        val resultErasedN: IResult[ParseError, Any] =
          result.asInstanceOf[IResult[ParseError, Any]] // scalafix:ok DisableSyntax.asInstanceOf
        val nextErasedN: ParserCont[ParseError, Elem, Any, A] =
          next.asInstanceOf[ParserCont[ParseError, Elem, Any, A]] // scalafix:ok DisableSyntax.asInstanceOf
        applyNamedFrame(resultErasedN, name, nextErasedN, consumed, state)

      // --- ExpectFrame: replace failure with Custom message ---
      case ParserCont.ExpectFrame(message, next) =>
        val resultErasedE: IResult[ParseError, Any] =
          result.asInstanceOf[IResult[ParseError, Any]] // scalafix:ok DisableSyntax.asInstanceOf
        val nextErasedE: ParserCont[ParseError, Elem, Any, A] =
          next.asInstanceOf[ParserCont[ParseError, Elem, Any, A]] // scalafix:ok DisableSyntax.asInstanceOf
        applyExpectFrame(resultErasedE, message, nextErasedE, consumed, state)

      // --- OptionalFrame: Some on success/partial, None (backtrack) on failure ---
      case ParserCont.OptionalFrame(snapshot, entryConsumed, prevDiscarded, next) =>
        applyOptionalFrame(result, snapshot, entryConsumed, prevDiscarded, next, consumed, state)

      // --- RecoverTry: primary parse complete; on failure run recovery ---
      case ParserCont.RecoverTry(recovery, snapshot, entryConsumed, prevDiscarded, next) =>
        applyRecoverTry(result, recovery, snapshot, entryConsumed, prevDiscarded, next, consumed, state)

      // --- RecoverCombine: recovery parse complete; fold in primary errors ---
      case ParserCont.RecoverCombine(primaryMkErrors, primaryFurthest, next) =>
        applyRecoverCombine(result, primaryMkErrors, primaryFurthest, next, consumed, state)

      // --- AttemptFrame: reify inner outcome into a Result value (always outer-success) ---
      case ParserCont.AttemptFrame(snapshot, prevDiscarded, next) =>
        // applyAttemptFrame returns EvalState[Nothing, Elem, A]; widen to EvalState[E, Elem, A]
        // (invariant error param, runtime-identical).
        applyAttemptFrame(result, snapshot, prevDiscarded, next, consumed, state)
          .asInstanceOf[EvalState[E, Elem, A]] // scalafix:ok DisableSyntax.asInstanceOf

      // --- ManyFrame: one repetition complete; re-loop or finalize the list ---
      case ParserCont.ManyFrame(p, acc, errThunks, entryConsumed, iterConsumed, snapshot, prevDiscarded, next) =>

        applyManyFrame(
          result,
          p,
          acc,
          errThunks,
          entryConsumed,
          iterConsumed,
          snapshot,
          prevDiscarded,
          next,
          consumed,
          state
        )

      // --- SkipManyFrame: one repetition complete; re-loop or finalize Unit ---
      case ParserCont.SkipManyFrame(p, errThunks, entryConsumed, iterConsumed, snapshot, prevDiscarded, next) =>

        applySkipManyFrame(
          result,
          p,
          errThunks,
          entryConsumed,
          iterConsumed,
          snapshot,
          prevDiscarded,
          next,
          consumed,
          state
        )

      // --- Many1Frame: first item complete; seed the tail Many or propagate head failure ---
      case ParserCont.Many1Frame(p, entryConsumed, next) =>
        applyMany1Frame(result, p, entryConsumed, next, consumed, state)

      // --- ComposeK: right-associate and decompose (extracted to keep stepApply C2-compilable) ---
      case ParserCont.ComposeK(first, second) =>
        // ComposeK mid type is existential; erase to Any to match stepComposeK's signature.
        val firstErasedCK: ParserCont[E, Elem, Mid, Any] =
          first.asInstanceOf[ParserCont[E, Elem, Mid, Any]] // scalafix:ok DisableSyntax.asInstanceOf
        val secondErasedCK: ParserCont[E, Elem, Any, A] =
          second.asInstanceOf[ParserCont[E, Elem, Any, A]] // scalafix:ok DisableSyntax.asInstanceOf
        stepComposeK(result, firstErasedCK, secondErasedCK, consumed, state)
    }

  /** ComposeK decomposition phase: right-associate the composed continuation and dispatch on its
    * `first` frame. Extracted from `stepApply` because this nested match is the single largest
    * bytecode contributor — keeping it in its own method holds both `stepApply` and the driver
    * under C2's huge-method ceiling. Returns the next `EvalState`; non-recursive (never calls
    * `loop`).
    */
  private def stepComposeK[E, Elem, Mid, A](
    result: IResult[E, Mid],
    first: ParserCont[E, Elem, Mid, Any],
    second: ParserCont[E, Elem, Any, A],
    consumed: Int,
    state: ParserState
  ): EvalState[E, Elem, A] =
    first match {
      case _: ParserCont.End[?, ?, ?] =>
        EvalState.Apply(result, second, consumed)

      case ParserCont.Step(f, next) =>
        val next2 = composeK(next, second)
        result match {
          case Result.Success(v, c) =>
            EvalState.Eval(f(v), next2, consumed + c)
          case LazyPartial(v, mkErrs, c) =>
            EvalState.Eval(f(v), ParserCont.PartialStep(mkErrs, next2), consumed + c)
          case LazyFailure(mkErrs, loc) =>
            EvalState.Apply(LazyFailure(mkErrs, loc), next2, consumed)
        }

      case ParserCont.MapStep(f, next) =>
        val next2 = composeK(next, second)
        result match {
          case Result.Success(v, c) =>
            EvalState.Apply(Result.Success(f(v), c), next2, consumed)
          case LazyPartial(v, mkErrs, c) =>
            EvalState.Apply(LazyPartial(f(v), mkErrs, c), next2, consumed)
          case LazyFailure(mkErrs, loc) =>
            EvalState.Apply(LazyFailure(mkErrs, loc), next2, consumed)
        }

      case ParserCont.PartialStep(mkErrors, next) =>
        val next2 = composeK(next, second)
        result match {
          case Result.Success(v, c) =>
            EvalState.Apply(LazyPartial(v, mkErrors, c), next2, consumed)
          case LazyPartial(v, mkErrs2, c) =>
            val combined = () => mkErrors() ++ mkErrs2()
            EvalState.Apply(LazyPartial(v, combined, c), next2, consumed)
          case LazyFailure(mkErrs2, loc) =>
            val combined = () => mkErrors() ++ mkErrs2()
            EvalState.Apply(LazyFailure(combined, loc), next2, consumed)
        }

      case ParserCont.ZipStep(right, next) =>
        val next2 = composeK(next, second)
        result match {
          case Result.Success(a, c) =>
            EvalState.Eval(right, ParserCont.PairStep(a, next2), consumed + c)
          case LazyPartial(a, mkErrs, c) =>

            EvalState.Eval(
              right,
              ParserCont.PairStep(a, ParserCont.PartialStep(mkErrs, next2)),
              consumed + c
            )
          case LazyFailure(mkErrs, loc) =>
            EvalState.Apply(LazyFailure(mkErrs, loc), next2, consumed)
        }

      case ParserCont.PairStep(leftValue, next) =>
        val next2 = composeK(next, second)
        result match {
          case Result.Success(b, c) =>
            EvalState.ApplySuccess((leftValue, b), c, next2, consumed)
          case LazyPartial(b, mkErrs, c) =>
            EvalState.Apply(LazyPartial((leftValue, b), mkErrs, c), next2, consumed)
          case LazyFailure(mkErrs, loc) =>
            EvalState.Apply(LazyFailure(mkErrs, loc), next2, consumed)
        }

      case ParserCont.SkipLeftStep(right, next) =>
        val next2 = composeK(next, second)
        result match {
          case Result.Success(_, c) =>
            EvalState.Eval(right, next2, consumed + c)
          case LazyPartial(_, mkErrs, c) =>
            EvalState.Eval(right, ParserCont.PartialStep(mkErrs, next2), consumed + c)
          case LazyFailure(mkErrs, loc) =>
            EvalState.Apply(LazyFailure(mkErrs, loc), next2, consumed)
        }

      case ParserCont.SkipRightStep(right, next) =>
        val next2 = composeK(next, second)
        result match {
          case Result.Success(a, c) =>
            EvalState.Eval(right, ParserCont.SkipRightDiscard(a, next2), consumed + c)
          case LazyPartial(a, mkErrs, c) =>
            EvalState.Eval(right, ParserCont.SkipRightDiscard(a, ParserCont.PartialStep(mkErrs, next2)), consumed + c)
          case LazyFailure(mkErrs, loc) =>
            EvalState.Apply(LazyFailure(mkErrs, loc), next2, consumed)
        }

      case ParserCont.SkipRightDiscard(leftValue, next) =>
        val next2 = composeK(next, second)
        result match {
          case Result.Success(_, c) =>
            EvalState.ApplySuccess(leftValue, c, next2, consumed)
          case LazyPartial(_, mkErrs, c) =>
            EvalState.Apply(LazyPartial(leftValue, mkErrs, c), next2, consumed)
          case LazyFailure(mkErrs, loc) =>
            EvalState.Apply(LazyFailure(mkErrs, loc), next2, consumed)
        }

      case ParserCont.PrattLoop(minBp, nud, getOp, opTable, next) =>
        val next2 = composeK(next, second)
        applyPrattLoop(result, minBp, nud, getOp, opTable, next2, consumed, state)

      case ParserCont.PrattCombine(lhs, combine, minBp, nud, getOp, opTable, next) =>
        val next2 = composeK(next, second)
        result match {
          case Result.Success(rhs, c) =>

            EvalState.Apply(
              Result.Success(combine(lhs, rhs), c),
              ParserCont.PrattLoop(minBp, nud, getOp, opTable, next2),
              consumed
            )
          case LazyPartial(rhs, mkErrs, c) =>

            EvalState.Apply(
              LazyPartial(combine(lhs, rhs), mkErrs, c),
              ParserCont.PrattLoop(minBp, nud, getOp, opTable, next2),
              consumed
            )
          case LazyFailure(mkErrs, loc) =>
            EvalState.Apply(LazyFailure(mkErrs, loc), next2, consumed)
        }

      case ParserCont.OrLeft(right, snapshot, entryConsumed, next) =>
        val next2 = composeK(next, second)
        applyOrLeft(result, right, snapshot, entryConsumed, next2, consumed, state)

      case ParserCont.OrRight(leftFailure, next) =>
        val next2 = composeK(next, second)
        applyOrRight(result, leftFailure, next2, consumed, state)

      case ParserCont.ChoiceFrame(remaining, snapshot, entryConsumed, accMkErrors, furthest, next) =>
        val next2 = composeK(next, second)

        applyChoiceFrame(
          result,
          remaining,
          snapshot,
          entryConsumed,
          accMkErrors,
          furthest,
          next2,
          consumed,
          state
        )

      case ParserCont.CaptureFrame(startOffset, next) =>
        val next2 = composeK(next, second)
        applyCaptureFrame(result, startOffset, next2, consumed, state)

      case ParserCont.LookAheadFrame(snapshot, entryConsumed, next) =>
        val next2 = composeK(next, second)
        applyLookAheadFrame(result, snapshot, entryConsumed, next2, state)

      case ParserCont.NotFollowedByFrame(snapshot, entryConsumed, next) =>
        val next2 = composeK(next, second)
        applyNotFollowedByFrame(result, snapshot, entryConsumed, next2, state)
          .asInstanceOf[EvalState[E, Elem, A]] // scalafix:ok DisableSyntax.asInstanceOf

      case ParserCont.NamedFrame(name, next) =>
        val next2 = composeK(next, second)
        val resultErasedN: IResult[ParseError, Any] =
          result.asInstanceOf[IResult[ParseError, Any]] // scalafix:ok DisableSyntax.asInstanceOf
        val next2ErasedN: ParserCont[ParseError, Elem, Any, A] =
          next2.asInstanceOf[ParserCont[ParseError, Elem, Any, A]] // scalafix:ok DisableSyntax.asInstanceOf
        applyNamedFrame(resultErasedN, name, next2ErasedN, consumed, state)

      case ParserCont.ExpectFrame(message, next) =>
        val next2 = composeK(next, second)
        val resultErasedE: IResult[ParseError, Any] =
          result.asInstanceOf[IResult[ParseError, Any]] // scalafix:ok DisableSyntax.asInstanceOf
        val next2ErasedE: ParserCont[ParseError, Elem, Any, A] =
          next2.asInstanceOf[ParserCont[ParseError, Elem, Any, A]] // scalafix:ok DisableSyntax.asInstanceOf
        applyExpectFrame(resultErasedE, message, next2ErasedE, consumed, state)

      case ParserCont.OptionalFrame(snapshot, entryConsumed, prevDiscarded, next) =>
        val next2 = composeK(next, second)
        applyOptionalFrame(result, snapshot, entryConsumed, prevDiscarded, next2, consumed, state)

      case ParserCont.RecoverTry(recovery, snapshot, entryConsumed, prevDiscarded, next) =>
        val next2 = composeK(next, second)

        applyRecoverTry(result, recovery, snapshot, entryConsumed, prevDiscarded, next2, consumed, state)

      case ParserCont.RecoverCombine(primaryMkErrors, primaryFurthest, next) =>
        val next2 = composeK(next, second)
        applyRecoverCombine(result, primaryMkErrors, primaryFurthest, next2, consumed, state)

      case ParserCont.AttemptFrame(snapshot, prevDiscarded, next) =>
        // AttemptFrame's `next` has error type Nothing while `second` carries the loop's E, and
        // ParserCont's E is invariant — so they don't unify structurally. composeK only LINKS
        // continuations (the error param is phantom at the continuation level), so compose
        // under an erased error type and cast back. Same GADT-boundary discipline as Zip.
        val nextErasedAtt: ParserCont[Nothing, Elem, Result[Any, Any], Any] =
          next.asInstanceOf[ParserCont[Nothing, Elem, Result[Any, Any], Any]] // scalafix:ok DisableSyntax.asInstanceOf
        val secondErasedAtt: ParserCont[Nothing, Elem, Any, Any] =
          second.asInstanceOf[ParserCont[Nothing, Elem, Any, Any]] // scalafix:ok DisableSyntax.asInstanceOf
        val next2: ParserCont[Nothing, Elem, Result[Any, Any], A] =
          composeK(nextErasedAtt, secondErasedAtt)
            .asInstanceOf[ParserCont[Nothing, Elem, Result[Any, Any], A]] // scalafix:ok DisableSyntax.asInstanceOf
        val resultErasedAtt: IResult[Any, Any] =
          result.asInstanceOf[IResult[Any, Any]] // scalafix:ok DisableSyntax.asInstanceOf
        applyAttemptFrame(resultErasedAtt, snapshot, prevDiscarded, next2, consumed, state)
          .asInstanceOf[EvalState[E, Elem, A]] // scalafix:ok DisableSyntax.asInstanceOf

      case ParserCont.ManyFrame(p, acc, errThunks, entryConsumed, iterConsumed, snapshot, prevDiscarded, next) =>
        val next2 = composeK(next, second)

        applyManyFrame(
          result,
          p,
          acc,
          errThunks,
          entryConsumed,
          iterConsumed,
          snapshot,
          prevDiscarded,
          next2,
          consumed,
          state
        )

      case ParserCont.SkipManyFrame(p, errThunks, entryConsumed, iterConsumed, snapshot, prevDiscarded, next) =>
        val next2 = composeK(next, second)

        applySkipManyFrame(
          result,
          p,
          errThunks,
          entryConsumed,
          iterConsumed,
          snapshot,
          prevDiscarded,
          next2,
          consumed,
          state
        )

      case ParserCont.Many1Frame(p, entryConsumed, next) =>
        val next2 = composeK(next, second)
        applyMany1Frame(result, p, entryConsumed, next2, consumed, state)

      case ParserCont.ComposeK(first2, second2) =>
        // Right-associate: ComposeK(ComposeK(f1, f2), second) => ComposeK(f1, ComposeK(f2, second))
        EvalState
          .Apply(result, ParserCont.ComposeK(first2, ParserCont.ComposeK(second2, second)), consumed)
    }

  /** Apply-phase logic for `PrattLoop`.
    *
    * Returns the next `EvalState` the trampoline should step into. Expressed as a pure function so
    * the enclosing `loop` stays `@tailrec`. Operator parsing is synchronous via `interpretI` —
    * `getOp` is typically a tight dispatch (StringChoice or short Or-of-chars), so paying the
    * trampoline cost per operator check would be pure overhead.
    *
    *   - `Success(lhs, c)` + infix with `lbp > minBp`: push a combine-and-recurse chain built as
    *     `PrattLoop(rbp, ...) ▶ MapStep(rhs => combine(lhs, rhs)) ▶ PrattLoop(minBp, ...) ▶ next`.
    *     The inner frame parses RHS at `rbp`; MapStep folds it with the captured `lhs`; the outer
    *     frame resumes the loop at `minBp`.
    *   - `Success(lhs, c)` + postfix with `bp > minBp`: apply in place and re-enter the same frame.
    *     Constant work per operator.
    *   - `Success(lhs, c)` + operator that binds weakly, or parses to failure: restore snapshot and
    *     hand `Success(lhs, c)` to `next`.
    *   - `LazyPartial(lhs, mkErrs, c)`: same branches as Success, but the continuation chain is
    *     wrapped in `PartialStep(mkErrs, ...)` so errors propagate through the next evaluation.
    *   - `LazyFailure`: propagate straight to `next`.
    */
  private def applyPrattLoop[E, Elem, A, Out](
    result: IResult[E, A],
    minBp: Int,
    nud: ParserK[E, Elem, A],
    getOp: ParserK[E, Elem, PrattOp[A]],
    opTable: PrattOpTable[A] | Null,
    next: ParserCont[E, Elem, A, Out],
    consumed: Int,
    state: ParserState
  ): EvalState[E, Elem, Out] = result match {
    case Result.Success(lhs, c) =>
      // Fast path: if opTable is populated, peek the next char and dispatch through the table.
      // This avoids ever producing a LazyFailure / Location on operator-misses, and avoids the
      // PrattOp.Infix/Postfix boxing since the table holds pre-built instances.
      //
      // Postfix operators are iterated internally without round-tripping through the trampoline:
      // the bounded `while` loop chews through consecutive postfix hits updating `acc` in place.
      // The loop is bounded by input length (each iteration calls state.advance()), so there is no
      // stack-safety cost. An infix hit exits the loop to push a RHS evaluation through the
      // trampoline; a miss / low-bp / end-of-input exits with the accumulated `acc`.
      val tbl: PrattOpTable[A] | Null = opTable
      if tbl ne null then { // scalafix:ok DisableSyntax.null
        val table: PrattOpTable[A] = tbl
        var acc: A = lhs
        var extraConsumed: Int = 0
        var exit: EvalState[E, Elem, Out] | Null = null // scalafix:ok DisableSyntax.null
        while (exit eq null) do { // scalafix:ok DisableSyntax.null
          if !state.hasChar then {
            exit = EvalState.ApplySuccess(acc, c + extraConsumed, next, consumed)
          } else {
            val ch = state.currentChar
            table.opAt(ch) match {
              case PrattOp.Infix(lbp, rbp, combine) if lbp > minBp =>
                state.advance()
                exit = EvalState.Eval(
                  nud,
                  ParserCont.PrattLoop(
                    rbp,
                    nud,
                    getOp,
                    opTable,
                    ParserCont.PrattCombine(acc, combine, minBp, nud, getOp, opTable, next)
                  ),
                  consumed + c + extraConsumed + 1
                )
              case PrattOp.Postfix(bp, apply) if bp > minBp =>
                state.advance()
                acc = apply(acc)
                extraConsumed += 1
              case _ =>
                exit = EvalState.ApplySuccess(acc, c + extraConsumed, next, consumed)
            }
          }
        }
        exit
      } else {
        // Slow path: no opTable compiled; run the general getOp parser.
        val snapshot = state.save
        interpretI(getOp, state) match {
          case Result.Success(PrattOp.Infix(lbp, rbp, combine), oc) if lbp > minBp =>
            EvalState.Eval(
              nud,
              ParserCont.PrattLoop(
                rbp,
                nud,
                getOp,
                opTable,
                ParserCont.PrattCombine(lhs, combine, minBp, nud, getOp, opTable, next)
              ),
              consumed + c + oc
            )

          case Result.Success(PrattOp.Postfix(bp, apply), oc) if bp > minBp =>
            EvalState.Apply(
              Result.Success(apply(lhs), 0),
              ParserCont.PrattLoop(minBp, nud, getOp, opTable, next),
              consumed + c + oc
            )

          case LazyPartial(PrattOp.Infix(lbp, rbp, combine), mkErrs, oc) if lbp > minBp =>
            EvalState.Eval(
              nud,
              ParserCont.PrattLoop(
                rbp,
                nud,
                getOp,
                opTable,
                ParserCont.PartialStep(
                  mkErrs,
                  ParserCont.PrattCombine(lhs, combine, minBp, nud, getOp, opTable, next)
                )
              ),
              consumed + c + oc
            )

          case LazyPartial(PrattOp.Postfix(bp, apply), mkErrs, oc) if bp > minBp =>
            EvalState.Apply(
              LazyPartial(apply(lhs), mkErrs, 0),
              ParserCont.PrattLoop(minBp, nud, getOp, opTable, next),
              consumed + c + oc
            )

          case _ =>
            state.restore(snapshot)
            EvalState.Apply(Result.Success(lhs, c), next, consumed)
        }
      }

    case LazyPartial(lhs, accMkErrs, c) =>
      val tbl: PrattOpTable[A] | Null = opTable
      if tbl ne null then { // scalafix:ok DisableSyntax.null
        val table: PrattOpTable[A] = tbl
        var acc: A = lhs
        var extraConsumed: Int = 0
        var exit: EvalState[E, Elem, Out] | Null = null // scalafix:ok DisableSyntax.null
        while (exit eq null) do { // scalafix:ok DisableSyntax.null
          if !state.hasChar then {
            exit = EvalState.Apply(LazyPartial(acc, accMkErrs, c + extraConsumed), next, consumed)
          } else {
            val ch = state.currentChar
            table.opAt(ch) match {
              case PrattOp.Infix(lbp, rbp, combine) if lbp > minBp =>
                state.advance()
                exit = EvalState.Eval(
                  nud,
                  ParserCont.PrattLoop(
                    rbp,
                    nud,
                    getOp,
                    opTable,
                    ParserCont.PrattCombine(
                      acc,
                      combine,
                      minBp,
                      nud,
                      getOp,
                      opTable,
                      ParserCont.PartialStep(accMkErrs, next)
                    )
                  ),
                  consumed + c + extraConsumed + 1
                )
              case PrattOp.Postfix(bp, apply) if bp > minBp =>
                state.advance()
                acc = apply(acc)
                extraConsumed += 1
              case _ =>
                exit = EvalState.Apply(LazyPartial(acc, accMkErrs, c + extraConsumed), next, consumed)
            }
          }
        }
        exit
      } else {
        val snapshot = state.save
        interpretI(getOp, state) match {
          case Result.Success(PrattOp.Infix(lbp, rbp, combine), oc) if lbp > minBp =>
            EvalState.Eval(
              nud,
              ParserCont.PrattLoop(
                rbp,
                nud,
                getOp,
                opTable,
                ParserCont.PrattCombine(
                  lhs,
                  combine,
                  minBp,
                  nud,
                  getOp,
                  opTable,
                  ParserCont.PartialStep(accMkErrs, next)
                )
              ),
              consumed + c + oc
            )

          case Result.Success(PrattOp.Postfix(bp, apply), oc) if bp > minBp =>
            EvalState.Apply(
              LazyPartial(apply(lhs), accMkErrs, 0),
              ParserCont.PrattLoop(minBp, nud, getOp, opTable, next),
              consumed + c + oc
            )

          case LazyPartial(PrattOp.Infix(lbp, rbp, combine), mkErrs, oc) if lbp > minBp =>
            val combined = () => accMkErrs() ++ mkErrs()
            EvalState.Eval(
              nud,
              ParserCont.PrattLoop(
                rbp,
                nud,
                getOp,
                opTable,
                ParserCont.PrattCombine(
                  lhs,
                  combine,
                  minBp,
                  nud,
                  getOp,
                  opTable,
                  ParserCont.PartialStep(combined, next)
                )
              ),
              consumed + c + oc
            )

          case LazyPartial(PrattOp.Postfix(bp, apply), mkErrs, oc) if bp > minBp =>
            val combined = () => accMkErrs() ++ mkErrs()
            EvalState.Apply(
              LazyPartial(apply(lhs), combined, 0),
              ParserCont.PrattLoop(minBp, nud, getOp, opTable, next),
              consumed + c + oc
            )

          case _ =>
            state.restore(snapshot)
            EvalState.Apply(LazyPartial(lhs, accMkErrs, c), next, consumed)
        }
      }

    case LazyFailure(mkErrs, loc) =>
      EvalState.Apply(LazyFailure(mkErrs, loc), next, consumed)
  }

  /** Apply-phase logic for `OrLeft` — the left branch of a trampolined `Parser.Or` just completed.
    *
    * Mirrors `interpretI(Parser.Or)`'s post-`left` match exactly:
    *   - Success / LazyPartial: `Or` is value-transparent, so pass the result straight to `next`.
    *   - LazyFailure: restore the entry `snapshot`, reset the trampoline's `consumed` accumulator
    *     to `entryConsumed` (a partially-consuming `left` advanced it; backtracking must undo
    *     that), and evaluate `right` under an `OrRight` frame carrying the captured left failure
    *     for merging.
    *
    * Returns the next `EvalState`; expressed as a pure function so the enclosing `loop` stays
    * `@tailrec`.
    */
  private def applyOrLeft[E, Elem, A, Out](
    result: IResult[E, A],
    right: ParserK[E, Elem, A],
    snapshot: StateSnapshot,
    entryConsumed: Int,
    next: ParserCont[E, Elem, A, Out],
    consumed: Int,
    state: ParserState
  ): EvalState[E, Elem, Out] = result match {
    case Result.Success(_, _) =>
      EvalState.Apply(result, next, consumed)
    case LazyPartial(_, _, _) =>
      EvalState.Apply(result, next, consumed)
    case leftFailure @ LazyFailure(_, _) =>
      state.restore(snapshot)
      EvalState.Eval(right, ParserCont.OrRight(leftFailure, next), entryConsumed)
  }

  /** Apply-phase logic for `OrRight` — the right branch of a trampolined `Parser.Or` just completed
    * after the left branch had failed.
    *
    * Mirrors `interpretI(Parser.Or)`'s post-`right` match exactly (error-furthest selection +
    * tie-merge, with the `errorsDiscarded` short-circuit returning the cheap left failure):
    *   - Success / LazyPartial: pass through to `next`; the captured left failure is discarded.
    *   - LazyFailure: select by furthest offset, merge thunks on a tie, or return `leftFailure`
    *     unchanged when the outer context will discard the merged errors.
    */
  private def applyOrRight[E, Elem, A, Out](
    result: IResult[E, A],
    leftFailure: LazyFailure[E],
    next: ParserCont[E, Elem, A, Out],
    consumed: Int,
    state: ParserState
  ): EvalState[E, Elem, Out] = result match {
    case Result.Success(_, _) =>
      EvalState.Apply(result, next, consumed)
    case LazyPartial(_, _, _) =>
      EvalState.Apply(result, next, consumed)
    case rightFailure @ LazyFailure(rightMkErrors, rightFurthest) =>
      val merged: IResult[E, A] =
        if state.errorsDiscarded then leftFailure
        else if leftFailure.furthest.offset > rightFurthest.offset then leftFailure
        else if rightFurthest.offset > leftFailure.furthest.offset then rightFailure
        else {
          val leftMkErrors = leftFailure.mkErrors
          LazyFailure(() => leftMkErrors() ++ rightMkErrors(), leftFailure.furthest)
        }
      EvalState.Apply(merged, next, consumed)
  }

  /** Apply-phase logic for `ChoiceFrame` — the currently-evaluating alternative of a trampolined
    * `Parser.Choice` just completed.
    *
    * Mirrors `interpretChoiceI` exactly:
    *   - Success / LazyPartial: an alternative matched, pass it through to `next`.
    *   - LazyFailure: restore `snapshot`, fold the failure into `(accMkErrors, furthest)` by the
    *     furthest-wins / tie-merge rule, then either evaluate the next alternative under a fresh
    *     `ChoiceFrame` (consumed reset to `entryConsumed`) or, when none remain, hand a
    *     `LazyFailure(accMkErrors, furthest)` to `next`.
    */
  private def applyChoiceFrame[E, Elem, A, Out](
    result: IResult[E, A],
    remaining: List[ParserK[E, Elem, A]],
    snapshot: StateSnapshot,
    entryConsumed: Int,
    accMkErrors: () => List[E],
    furthest: Location,
    next: ParserCont[E, Elem, A, Out],
    consumed: Int,
    state: ParserState
  ): EvalState[E, Elem, Out] = result match {
    case Result.Success(_, _) =>
      EvalState.Apply(result, next, consumed)
    case LazyPartial(_, _, _) =>
      EvalState.Apply(result, next, consumed)
    case LazyFailure(mkErrs, loc) =>
      state.restore(snapshot)
      val (newMkErrors, newFurthest) =
        if loc.offset > furthest.offset then (mkErrs, loc)
        else if loc.offset == furthest.offset then {
          val prevMkErrors = accMkErrors
          (() => prevMkErrors() ++ mkErrs(), furthest)
        } else (accMkErrors, furthest)
      remaining match {
        case Nil =>
          EvalState.Apply(LazyFailure(newMkErrors, newFurthest), next, consumed)
        case head :: tail =>
          EvalState.Eval(
            head,
            ParserCont.ChoiceFrame(tail, snapshot, entryConsumed, newMkErrors, newFurthest, next),
            entryConsumed
          )
      }
  }

  /** Apply-phase logic for `CaptureFrame` — mirrors `interpretI(Parser.Capture)`.
    *
    * Replaces the inner value with the consumed input slice. The slice end is `state.offset` (the
    * live position; Capture never restores), equal to `startOffset + totalInnerConsumed`. The
    * result's local consumed `c` and the `consumed` accumulator are passed through untouched (only
    * the value changes), exactly as `MapStep` does. Failure propagates unchanged.
    */
  private def applyCaptureFrame[E, Elem, A, Out](
    result: IResult[E, A],
    startOffset: Int,
    next: ParserCont[E, Elem, String, Out],
    consumed: Int,
    state: ParserState
  ): EvalState[E, Elem, Out] = result match {
    case Result.Success(_, c) =>
      EvalState.ApplySuccess(state.slice(startOffset, state.offset), c, next, consumed)
    case LazyPartial(_, mkErrs, c) =>
      EvalState.Apply(LazyPartial(state.slice(startOffset, state.offset), mkErrs, c), next, consumed)
    case f: LazyFailure[?] =>
      EvalState.Apply(f, next, consumed)
  }

  /** Apply-phase logic for `LookAheadFrame` — mirrors `interpretI(Parser.LookAhead)`.
    *
    * Always restores the entry `snapshot` (non-consuming) and resets the `consumed` accumulator to
    * `entryConsumed`, since the inner parse advanced both the state and the accumulator. The result
    * value/errors flow through with their own consumed zeroed (`Result.Success(value, 0)` /
    * `LazyPartial(value, mkErrs, 0)`); a failure propagates after the restore.
    */
  private def applyLookAheadFrame[E, Elem, A, Out](
    result: IResult[E, A],
    snapshot: StateSnapshot,
    entryConsumed: Int,
    next: ParserCont[E, Elem, A, Out],
    state: ParserState
  ): EvalState[E, Elem, Out] = {
    state.restore(snapshot)
    result match {
      case Result.Success(value, _) =>
        EvalState.Apply(Result.Success(value, 0), next, entryConsumed)
      case LazyPartial(value, mkErrs, _) =>
        EvalState.Apply(LazyPartial(value, mkErrs, 0), next, entryConsumed)
      case failure @ LazyFailure(_, _) =>
        EvalState.Apply(failure, next, entryConsumed)
    }
  }

  /** Apply-phase logic for `NotFollowedByFrame` — mirrors `interpretI(Parser.NotFollowedBy)`.
    *
    * Negative lookahead: always restores the entry `snapshot` and resets `consumed` to
    * `entryConsumed`. Inner success/partial → a synthesized `Custom` failure at the restored
    * location; inner failure → `Success((), 0)`.
    */
  private def applyNotFollowedByFrame[Elem, A, Out](
    result: IResult[ParseError, A],
    snapshot: StateSnapshot,
    entryConsumed: Int,
    next: ParserCont[ParseError, Elem, Unit, Out],
    state: ParserState
  ): EvalState[ParseError, Elem, Out] = {
    state.restore(snapshot)
    result match {
      case Result.Success(_, _) =>
        val loc = state.location
        EvalState.Apply(LazyFailure(() => List(ParseError.Custom("Unexpected success", loc)), loc), next, entryConsumed)
      case LazyPartial(_, _, _) =>
        val loc = state.location
        EvalState.Apply(
          LazyFailure(() => List(ParseError.Custom("Unexpected partial success", loc)), loc),
          next,
          entryConsumed
        )
      case LazyFailure(_, _) =>
        EvalState.Apply(Result.Success((), 0), next, entryConsumed)
    }
  }

  /** Apply-phase logic for `NamedFrame` — mirrors `interpretI(Parser.Named)`.
    *
    * Value-transparent. On failure (unless `errorsDiscarded`, where the sentinel must pass through
    * untouched), rewrites each `Unexpected` error to add `name` to its expected-set.
    */
  private def applyNamedFrame[Elem, A, Out](
    result: IResult[ParseError, A],
    name: String,
    next: ParserCont[ParseError, Elem, A, Out],
    consumed: Int,
    state: ParserState
  ): EvalState[ParseError, Elem, Out] = result match {
    case Result.Success(_, _) =>
      EvalState.Apply(result, next, consumed)
    case LazyPartial(_, _, _) =>
      EvalState.Apply(result, next, consumed)
    case failure @ LazyFailure(mkErrors, furthest) =>
      if state.errorsDiscarded then EvalState.Apply(failure, next, consumed)
      else
        EvalState.Apply(
          LazyFailure(
            () =>
              mkErrors().map {
                case ParseError.Unexpected(found, expected, loc) =>
                  ParseError.Unexpected(found, expected + name, loc)
                case other => other
              },
            furthest
          ),
          next,
          consumed
        )
  }

  /** Apply-phase logic for `ExpectFrame` — mirrors `interpretI(Parser.Expect)`.
    *
    * Value-transparent. On failure (unless `errorsDiscarded`), replaces the errors with a single
    * `Custom(message)` at the furthest location.
    */
  private def applyExpectFrame[Elem, A, Out](
    result: IResult[ParseError, A],
    message: String,
    next: ParserCont[ParseError, Elem, A, Out],
    consumed: Int,
    state: ParserState
  ): EvalState[ParseError, Elem, Out] = result match {
    case Result.Success(_, _) =>
      EvalState.Apply(result, next, consumed)
    case LazyPartial(_, _, _) =>
      EvalState.Apply(result, next, consumed)
    case failure @ LazyFailure(_, furthest) =>
      if state.errorsDiscarded then EvalState.Apply(failure, next, consumed)
      else EvalState.Apply(LazyFailure(() => List(ParseError.Custom(message, furthest)), furthest), next, consumed)
  }

  /** Apply-phase logic for `OptionalFrame` — mirrors `interpretI(Parser.Optional)`.
    *
    * Restores `prevDiscarded` FIRST (the unconditional restore `interpretI` does after the inner
    * parse, regardless of outcome — the `SentinelLazyFailure` zero-alloc path depends on this),
    * then:
    *   - Success(v, c) → `Some(v)`, consumed unchanged.
    *   - LazyPartial(v, mkErrs, c) → `Some(v)`, errors preserved.
    *   - LazyFailure → restore `snapshot`, reset consumed to `entryConsumed`, `Success(None, 0)`.
    */
  private def applyOptionalFrame[E, Elem, A, Out](
    result: IResult[E, A],
    snapshot: StateSnapshot,
    entryConsumed: Int,
    prevDiscarded: Boolean,
    next: ParserCont[E, Elem, Option[A], Out],
    consumed: Int,
    state: ParserState
  ): EvalState[E, Elem, Out] = {
    state.setErrorsDiscarded(prevDiscarded)
    result match {
      case Result.Success(value, c) =>
        EvalState.ApplySuccess(Some(value), c, next, consumed)
      case LazyPartial(value, mkErrs, c) =>
        EvalState.Apply(LazyPartial(Some(value), mkErrs, c), next, consumed)
      case LazyFailure(_, _) =>
        state.restore(snapshot)
        EvalState.Apply(Result.Success(None, 0), next, entryConsumed)
    }
  }

  /** Apply-phase logic for `RecoverTry` — the PRIMARY parse of `Parser.RecoverWith` just completed.
    *
    * Restores `prevDiscarded` FIRST, then mirrors `interpretI(Parser.RecoverWith)`:
    *   - Success / LazyPartial → pass straight through (no recovery needed).
    *   - LazyFailure(mkErrors, furthest) → restore `snapshot`, reset consumed to `entryConsumed`,
    *     and evaluate `recovery` under a `RecoverCombine` frame carrying the primary error thunk
    *     and furthest. Recovery runs under the now-restored `prevDiscarded` context, exactly as
    *     `interpretI` does (it restores the flag before the recovery call).
    */
  private def applyRecoverTry[E, Elem, A, Out](
    result: IResult[E, A],
    recovery: ParserK[E, Elem, A],
    snapshot: StateSnapshot,
    entryConsumed: Int,
    prevDiscarded: Boolean,
    next: ParserCont[E, Elem, A, Out],
    consumed: Int,
    state: ParserState
  ): EvalState[E, Elem, Out] = {
    state.setErrorsDiscarded(prevDiscarded)
    result match {
      case Result.Success(_, _) =>
        EvalState.Apply(result, next, consumed)
      case LazyPartial(_, _, _) =>
        EvalState.Apply(result, next, consumed)
      case LazyFailure(mkErrors, furthest) =>
        state.restore(snapshot)
        EvalState.Eval(recovery, ParserCont.RecoverCombine(mkErrors, furthest, next), entryConsumed)
    }
  }

  /** Apply-phase logic for `RecoverCombine` — the RECOVERY parse of `Parser.RecoverWith` just
    * completed. Folds the primary error thunk into the recovery outcome, mirroring `interpretI`:
    *   - recovery Success(v, c) → `LazyPartial(v, primaryMkErrors, c)`.
    *   - recovery LazyPartial(v, mkRec, c) → `LazyPartial(v, primary ++ mkRec, c)`.
    *   - recovery LazyFailure(mkRec, recFurthest) → `LazyFailure(primary ++ mkRec, max-furthest)`.
    */
  private def applyRecoverCombine[E, Elem, A, Out](
    result: IResult[E, A],
    primaryMkErrors: () => List[E],
    primaryFurthest: Location,
    next: ParserCont[E, Elem, A, Out],
    consumed: Int,
    state: ParserState
  ): EvalState[E, Elem, Out] = result match {
    case Result.Success(value, c) =>
      EvalState.Apply(LazyPartial(value, primaryMkErrors, c), next, consumed)
    case LazyPartial(value, mkRecoveryErrors, c) =>
      EvalState.Apply(LazyPartial(value, () => primaryMkErrors() ++ mkRecoveryErrors(), c), next, consumed)
    case LazyFailure(mkRecoveryErrors, recoveryFurthest) =>
      val finalFurthest =
        if primaryFurthest.offset > recoveryFurthest.offset then primaryFurthest
        else recoveryFurthest
      EvalState.Apply(LazyFailure(() => primaryMkErrors() ++ mkRecoveryErrors(), finalFurthest), next, consumed)
  }

  /** Apply-phase logic for `AttemptFrame` — mirrors `interpretI(Parser.Attempt)`.
    *
    * Restores `prevDiscarded` FIRST, then reifies the inner outcome into a `Result[E, A]` value and
    * ALWAYS produces an outer success with OUTER consumed 0 (the inner `c` lives inside the reified
    * value; the state offset stays advanced on success/partial, restored on failure):
    *   - Success(v, c) → `Result.Success(Result.Success(v, c), 0)`.
    *   - LazyPartial(v, mkErrs, c) → `Result.Success(Result.Partial(v, mkErrs(), c), 0)`.
    *   - LazyFailure(mkErrs, loc) → restore `snapshot`, `Result.Success(Result.Failure(mkErrs(),
    *     loc), 0)`.
    *
    * Output error type is `Nothing` (Attempt never fails); `next` consumes `Result[E, A]`.
    */
  private def applyAttemptFrame[E, Elem, A, Out](
    result: IResult[E, A],
    snapshot: StateSnapshot,
    prevDiscarded: Boolean,
    next: ParserCont[Nothing, Elem, Result[E, A], Out],
    consumed: Int,
    state: ParserState
  ): EvalState[Nothing, Elem, Out] = {
    state.setErrorsDiscarded(prevDiscarded)
    result match {
      case Result.Success(v, c) =>
        EvalState.ApplySuccess(Result.Success(v, c), 0, next, consumed)
      case LazyPartial(v, mkErrs, c) =>
        EvalState.ApplySuccess(Result.Partial(v, mkErrs(), c), 0, next, consumed)
      case LazyFailure(mkErrs, loc) =>
        state.restore(snapshot)
        EvalState.ApplySuccess(Result.Failure(mkErrs(), loc), 0, next, consumed)
    }
  }

  /** Apply-phase logic for `ManyFrame` — one iteration of a trampolined `Parser.Many` completed.
    * Mirrors the general branch of `interpretI(Parser.Many)`.
    *
    *   - Success(value, c) → append `value`, RE-PUSH a fresh `ManyFrame` (new snapshot, new
    *     `iterConsumed = consumed + c`) and re-evaluate `p` for the next item.
    *   - LazyPartial(value, mkErrs, c) → as Success, but also record the error thunk.
    *   - LazyFailure → loop terminates: restore `snapshot` (undo the failed item's partial
    *     advance), restore `prevDiscarded`, and hand `next` the accumulated list. Relative consumed
    *     is `iterConsumed - entryConsumed` (sum of all successful items); the accumulator resets to
    *     `entryConsumed`, discarding the over-counted arriving `consumed`.
    *
    * Re-pushing allocates one frame per item — acceptable because the simple-`p` fast paths
    * (Satisfy/StringMatch char-scans) bypass this frame entirely; only the already-allocating
    * general path reaches here.
    */
  private def applyManyFrame[E, Elem, A, Out](
    result: IResult[E, A],
    p: ParserK[E, Elem, A],
    acc: scala.collection.mutable.ArrayBuffer[A],
    errThunks: scala.collection.mutable.ArrayBuffer[() => List[E]],
    entryConsumed: Int,
    iterConsumed: Int,
    snapshot: StateSnapshot,
    prevDiscarded: Boolean,
    next: ParserCont[E, Elem, List[A], Out],
    consumed: Int,
    state: ParserState
  ): EvalState[E, Elem, Out] = result match {
    case Result.Success(value, c) =>
      acc += value
      val iterEnd = consumed + c
      EvalState.Eval(
        p,
        ParserCont.ManyFrame(p, acc, errThunks, entryConsumed, iterEnd, state.save, prevDiscarded, next),
        iterEnd
      )
    case LazyPartial(value, mkErrs, c) =>
      acc += value
      errThunks += mkErrs
      val iterEnd = consumed + c
      EvalState.Eval(
        p,
        ParserCont.ManyFrame(p, acc, errThunks, entryConsumed, iterEnd, state.save, prevDiscarded, next),
        iterEnd
      )
    case LazyFailure(_, _) =>
      state.restore(snapshot)
      state.setErrorsDiscarded(prevDiscarded)
      val relConsumed = iterConsumed - entryConsumed
      if errThunks.isEmpty then EvalState.ApplySuccess(acc.toList, relConsumed, next, entryConsumed)
      else
        EvalState.Apply(
          LazyPartial(acc.toList, () => errThunks.flatMap(_.apply()).toList, relConsumed),
          next,
          entryConsumed
        )
  }

  /** Apply-phase logic for `SkipManyFrame` — like `applyManyFrame` but value-discarding (no `acc`),
    * producing `Unit`. Mirrors the general branch of `interpretI(Parser.SkipMany)`.
    */
  private def applySkipManyFrame[E, Elem, A, Out](
    result: IResult[E, A],
    p: ParserK[E, Elem, A],
    errThunks: scala.collection.mutable.ArrayBuffer[() => List[E]],
    entryConsumed: Int,
    iterConsumed: Int,
    snapshot: StateSnapshot,
    prevDiscarded: Boolean,
    next: ParserCont[E, Elem, Unit, Out],
    consumed: Int,
    state: ParserState
  ): EvalState[E, Elem, Out] = result match {
    case Result.Success(_, c) =>
      val iterEnd = consumed + c
      EvalState.Eval(
        p,
        ParserCont.SkipManyFrame(p, errThunks, entryConsumed, iterEnd, state.save, prevDiscarded, next),
        iterEnd
      )
    case LazyPartial(_, mkErrs, c) =>
      errThunks += mkErrs
      val iterEnd = consumed + c
      EvalState.Eval(
        p,
        ParserCont.SkipManyFrame(p, errThunks, entryConsumed, iterEnd, state.save, prevDiscarded, next),
        iterEnd
      )
    case LazyFailure(_, _) =>
      state.restore(snapshot)
      state.setErrorsDiscarded(prevDiscarded)
      val relConsumed = iterConsumed - entryConsumed
      if errThunks.isEmpty then EvalState.ApplySuccess((), relConsumed, next, entryConsumed)
      else
        EvalState.Apply(
          LazyPartial((), () => errThunks.flatMap(_.apply()).toList, relConsumed),
          next,
          entryConsumed
        )
  }

  /** Apply-phase logic for `Many1Frame` — the FIRST item of a trampolined `Parser.Many1` completed.
    * Mirrors `interpretI(Parser.Many1)`: the head is parsed under the AMBIENT `errorsDiscarded`
    * flag (not toggled). On head success/partial, seed a `ManyFrame` for the tail (which sets
    * `errorsDiscarded=true` and restores the ambient flag at termination), with `acc = [head]`. On
    * head failure the failure propagates unchanged (no state restore — same as `interpretMany1I`).
    */
  private def applyMany1Frame[E, Elem, A, Out](
    result: IResult[E, A],
    p: ParserK[E, Elem, A],
    entryConsumed: Int,
    next: ParserCont[E, Elem, List[A], Out],
    consumed: Int,
    state: ParserState
  ): EvalState[E, Elem, Out] = result match {
    case Result.Success(head, c) =>
      val prevDiscarded = state.errorsDiscarded
      state.setErrorsDiscarded(true)
      val acc = scala.collection.mutable.ArrayBuffer.empty[A]
      acc += head
      val errThunks = scala.collection.mutable.ArrayBuffer.empty[() => List[E]]
      val iterEnd = consumed + c
      EvalState.Eval(
        p,
        ParserCont.ManyFrame(p, acc, errThunks, entryConsumed, iterEnd, state.save, prevDiscarded, next),
        iterEnd
      )
    case LazyPartial(head, mkErrs, c) =>
      val prevDiscarded = state.errorsDiscarded
      state.setErrorsDiscarded(true)
      val acc = scala.collection.mutable.ArrayBuffer.empty[A]
      acc += head
      val errThunks = scala.collection.mutable.ArrayBuffer.empty[() => List[E]]
      errThunks += mkErrs
      val iterEnd = consumed + c
      EvalState.Eval(
        p,
        ParserCont.ManyFrame(p, acc, errThunks, entryConsumed, iterEnd, state.save, prevDiscarded, next),
        iterEnd
      )
    case failure @ LazyFailure(_, _) =>
      EvalState.Apply(failure, next, consumed)
  }

  /** Smart constructor for ComposeK that avoids wrapping End.
    */
  private def composeK[E, Elem, A, B, C](
    first: ParserCont[E, Elem, A, B],
    second: ParserCont[E, Elem, B, C]
  ): ParserCont[E, Elem, A, C] =
    first match {
      case _: ParserCont.End[?, ?, ?] =>
        second.asInstanceOf[ParserCont[E, Elem, A, C]] // scalafix:ok DisableSyntax.asInstanceOf
      case _ => ParserCont.ComposeK(first, second)
    }
}
