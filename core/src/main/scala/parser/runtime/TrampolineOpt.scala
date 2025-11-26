package parser.runtime

import parser.core._

/**
 * Highly optimized stack-safe interpreter.
 *
 * Optimizations over basic trampoline:
 * 1. Uses sentinel objects instead of Option - avoids boxing overhead
 * 2. Avoids allocating ConsumedCont - tracks consumed inline in FlatMapCont
 * 3. Fuses consecutive Maps - only allocates continuation for outermost
 * 4. Uses Array with manual index instead of ArrayBuffer
 * 5. Uses tagged union encoding for continuations
 *
 * The goal is to minimize allocation per FlatMap while maintaining stack safety.
 */
object TrampolineOpt {

  // Sentinel values to avoid Option boxing
  private val NoParser: Parser[Any, Any]  = null.asInstanceOf[Parser[Any, Any]]
  private val NoResult: IResult[Any, Any] = null.asInstanceOf[IResult[Any, Any]]
  private val NoMapFn: Any => Any         = null.asInstanceOf[Any => Any]

  /**
   * Continuation frame for trampolined interpretation.
   *
   * Uses Scala 3 enum GADT pattern - each case has only the fields it needs.
   * No null placeholders, no manual tagging, cleaner pattern matching.
   */
  private enum Frame {

    /** FlatMap continuation: apply function to success value, track consumed chars */
    case FlatMap(fn: Any => Parser[Any, Any], consumed: Int)

    /** FlatMap continuation for partial results: carries accumulated error thunk */
    case FlatMapPartial(mkErrors: () => List[Any], consumed: Int)
  }

  /**
   * Optimized stack-safe interpreter.
   */
  def run[E, A](parser: Parser[E, A], state: ParserState): IResult[E, A] = {
    // Pre-allocate stack with reasonable capacity
    var stack    = new Array[Frame](32)
    var stackTop = 0

    // Current parser - NoParser means we're in result-processing mode
    var current: Parser[Any, Any] = parser.asInstanceOf[Parser[Any, Any]]

    // Current result - NoResult means we're in parser-expansion mode
    var result: IResult[Any, Any] = NoResult

    // Accumulated consumed count (avoids allocating separate continuation)
    var consumedAcc = 0

    // Accumulated map functions (fuses consecutive maps)
    var mapFn: Any => Any = NoMapFn

    // Main loop
    while (true) {
      // Phase 1: Expand FlatMap/Map chains into continuations
      while (current ne NoParser)
        current match {
          case Parser.FlatMap(source, f) =>
            // Ensure stack capacity
            if (stackTop >= stack.length) {
              val newStack = new Array[Frame](stack.length * 2)
              System.arraycopy(stack, 0, newStack, 0, stackTop)
              stack = newStack
            }
            // If we have accumulated maps, they apply to the OUTPUT of the flatMap,
            // so we need to wrap f to apply maps after. e.g. Map(FlatMap(src, f), mf)
            // means: src.flatMap(f).map(mf), so the result of f(a) needs mf applied after.
            val fn: Any => Parser[Any, Any] = if (mapFn ne NoMapFn) {
              val mf = mapFn
              mapFn = NoMapFn
              // Wrap the parser returned by f with a Map
              (a: Any) => Parser.Map(f.asInstanceOf[Any => Parser[Any, Any]](a), mf)
            } else {
              f.asInstanceOf[Any => Parser[Any, Any]]
            }
            // Push FlatMap continuation with current consumed accumulator
            stack(stackTop) = Frame.FlatMap(fn, consumedAcc)
            stackTop += 1
            consumedAcc = 0
            current = source.asInstanceOf[Parser[Any, Any]]

          case Parser.Map(source, f) =>
            // Fuse maps - don't allocate continuation, just compose
            // Map(Map(p, f1), f2) desugars to p.map(f1).map(f2) = p.map(a => f2(f1(a)))
            // When we see Map(source, f), f is the OUTER map function.
            // If mapFn is already set, it's an even MORE outer function.
            // So composition should be: outer(inner(a)) = mapFn(f(a))
            val innerF = f.asInstanceOf[Any => Any]
            mapFn = if (mapFn ne NoMapFn) {
              val outerF = mapFn
              (a: Any) => outerF(innerF(a))
            } else {
              innerF
            }
            current = source.asInstanceOf[Parser[Any, Any]]

          case _ =>
            // Terminal case - interpret and get result
            result = interpretI(current, state).asInstanceOf[IResult[Any, Any]]
            current = NoParser
        }

      // Apply any pending map function to the result
      if (mapFn ne NoMapFn) {
        val mf = mapFn
        mapFn = NoMapFn
        result = result match {
          case Result.Success(value, consumed) =>
            Result.Success(mf(value), consumed)
          case LazyPartial(value, mkErrs, consumed) =>
            LazyPartial(mf(value), mkErrs, consumed)
          case lf: LazyFailure[?] =>
            lf.asInstanceOf[IResult[Any, Any]]
        }
      }

      // Phase 2: Apply continuations
      while (result ne NoResult) {
        if (stackTop == 0) {
          // No more continuations - apply accumulated consumed and return
          if (consumedAcc > 0) {
            result = result match {
              case Result.Success(value, consumed) =>
                Result.Success(value, consumedAcc + consumed)
              case LazyPartial(value, mkErrs, consumed) =>
                LazyPartial(value, mkErrs, consumedAcc + consumed)
              case lf: LazyFailure[?] =>
                lf.asInstanceOf[IResult[Any, Any]]
            }
          }
          return result.asInstanceOf[IResult[E, A]]
        }

        // Pop continuation
        stackTop -= 1
        val frame = stack(stackTop)
        stack(stackTop) = null.asInstanceOf[Frame] // Help GC

        frame match {
          case fm: Frame.FlatMap =>
            val fn           = fm.fn
            val prevConsumed = fm.consumed

            result match {
              case Result.Success(value, consumed) =>
                // Success: continue with f(value)
                consumedAcc = prevConsumed + consumed + consumedAcc
                current = fn(value)
                result = NoResult // Switch to Phase 1

              case LazyPartial(value, mkErrs, consumed) =>
                // Partial: push partial continuation and continue
                if (stackTop >= stack.length) {
                  val newStack = new Array[Frame](stack.length * 2)
                  System.arraycopy(stack, 0, newStack, 0, stackTop)
                  stack = newStack
                }
                stack(stackTop) = Frame.FlatMapPartial(
                  mkErrs.asInstanceOf[() => List[Any]],
                  prevConsumed + consumed + consumedAcc
                )
                stackTop += 1
                consumedAcc = 0
                current = fn(value)
                result = NoResult // Switch to Phase 1

              case _: LazyFailure[?] =>
                // Failure: add accumulated consumed back and propagate
                consumedAcc += prevConsumed
              // result stays the same, continue popping
            }

          case fmp: Frame.FlatMapPartial =>
            val mkErrors1    = fmp.mkErrors
            val prevConsumed = fmp.consumed

            result match {
              case Result.Success(value, consumed) =>
                result = LazyPartial(value, mkErrors1, prevConsumed + consumed + consumedAcc)
                consumedAcc = 0

              case LazyPartial(value, mkErrors2, consumed) =>
                result =
                  LazyPartial(value, () => mkErrors1() ++ mkErrors2(), prevConsumed + consumed + consumedAcc)
                consumedAcc = 0

              case LazyFailure(mkErrors2, furthest) =>
                result = LazyFailure(
                  () => mkErrors1() ++ mkErrors2().asInstanceOf[List[Any]],
                  furthest
                )
                consumedAcc += prevConsumed
            }
        }
      }
    }

    throw new AssertionError("Unreachable")
  }
}
