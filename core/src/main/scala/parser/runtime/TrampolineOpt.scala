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
    var stack    = new Array[Frame](32)
    var stackTop = 0

    var current: Parser[Any, Any] = parser.asInstanceOf[Parser[Any, Any]]
    var result: IResult[Any, Any] = NoResult
    var consumedAcc               = 0
    var mapFn: Any => Any         = NoMapFn

    while (true) {
      while (current ne NoParser)
        current match {
          case Parser.FlatMap(source, f) =>
            if (stackTop >= stack.length) {
              val newStack = new Array[Frame](stack.length * 2)
              System.arraycopy(stack, 0, newStack, 0, stackTop)
              stack = newStack
            }
            val fn: Any => Parser[Any, Any] = if (mapFn ne NoMapFn) {
              val mf = mapFn
              mapFn = NoMapFn
              (a: Any) => Parser.Map(f.asInstanceOf[Any => Parser[Any, Any]](a), mf)
            } else {
              f.asInstanceOf[Any => Parser[Any, Any]]
            }
            stack(stackTop) = Frame.FlatMap(fn, consumedAcc)
            stackTop += 1
            consumedAcc = 0
            current = source.asInstanceOf[Parser[Any, Any]]

          case Parser.Map(source, f) =>
            val innerF = f.asInstanceOf[Any => Any]
            mapFn = if (mapFn ne NoMapFn) {
              val outerF = mapFn
              (a: Any) => outerF(innerF(a))
            } else {
              innerF
            }
            current = source.asInstanceOf[Parser[Any, Any]]

          case _ =>
            result = interpretI(current, state).asInstanceOf[IResult[Any, Any]]
            current = NoParser
        }

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

      while (result ne NoResult) {
        if (stackTop == 0) {
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

        stackTop -= 1
        val frame = stack(stackTop)
        stack(stackTop) = null.asInstanceOf[Frame]

        frame match {
          case fm: Frame.FlatMap =>
            val fn           = fm.fn
            val prevConsumed = fm.consumed

            result match {
              case Result.Success(value, consumed) =>
                consumedAcc = prevConsumed + consumed + consumedAcc
                current = fn(value)
                result = NoResult

              case LazyPartial(value, mkErrs, consumed) =>
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
                result = NoResult

              case _: LazyFailure[?] =>
                consumedAcc += prevConsumed
            }

          case fmp: Frame.FlatMapPartial =>
            val mkErrors1    = fmp.mkErrors
            val prevConsumed = fmp.consumed

            result match {
              case Result.Success(value, consumed) =>
                result = LazyPartial(value, mkErrors1, prevConsumed + consumed + consumedAcc)
                consumedAcc = 0

              case LazyPartial(value, mkErrors2, consumed) =>
                result = LazyPartial(
                  value,
                  () => mkErrors1() ++ mkErrors2(),
                  prevConsumed + consumed + consumedAcc)
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
