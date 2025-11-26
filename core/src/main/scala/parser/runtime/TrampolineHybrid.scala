package parser.runtime

import parser.core._

/**
 * Hybrid stack-safe interpreter combining best of TrampolineOpt and TrampolineZeroCast.
 *
 * Design:
 * 1. GADT Continuation for type safety (like ZeroCast) - reduces casts
 * 2. Manual loop + Array stack (like Opt) - avoids TailRec allocation
 * 3. Localized casts only at composition points
 *
 * Performance target: Match or beat TrampolineOpt on all workloads
 * Type safety target: Match TrampolineZeroCast (7-8 casts total)
 */
object TrampolineHybrid {

  // Sentinel values (necessary in Scala 3)
  private val NoParser: Parser[Any, Any] = null.asInstanceOf[Parser[Any, Any]]
  private val NoResult: IResult[Any, Any] = null.asInstanceOf[IResult[Any, Any]]
  private val NoCont: Continuation[Any, Any, Any] = null.asInstanceOf[Continuation[Any, Any, Any]]

  /**
   * Continuation with GADT type tracking.
   *
   * This is the same as TrampolineZeroCast, providing type safety.
   */
  private enum Continuation[+E, -In, +Out] {
    /** Identity continuation */
    case End[A]() extends Continuation[Nothing, A, A]

    /** Map continuation */
    case MapCont[A, B, C](
      f: A => B,
      next: Continuation[Nothing, B, C]
    ) extends Continuation[Nothing, A, C]

    /** FlatMap continuation */
    case FlatMapCont[E1, A1, B1, C1](
      f: A1 => Parser[E1, B1],
      consumed: Int,
      next: Continuation[E1, B1, C1]
    ) extends Continuation[E1, A1, C1]

    /** FlatMap partial continuation - accumulated errors */
    case FlatMapPartialCont[E1, A1, B1](
      errors: List[E1],
      consumed: Int,
      next: Continuation[E1, A1, B1]
    ) extends Continuation[E1, A1, B1]
  }

  /**
   * Stack-safe interpreter using manual loop.
   *
   * Key differences from TrampolineZeroCast:
   * - Manual while loop instead of TailCalls (no allocation overhead)
   * - Array-based continuation stack (like TrampolineOpt)
   * - Still uses GADT for type tracking (fewer casts than TrampolineOpt)
   */
  def run[E, A](parser: Parser[E, A], state: ParserState): IResult[E, A] = {
    // Continuation stack (heterogeneous, stores Any but GADT tracks types)
    var stack = new Array[Continuation[Any, Any, Any]](32)
    var stackTop = 0

    // Current parser being expanded
    var current: Parser[Any, Any] = parser.asInstanceOf[Parser[Any, Any]]

    // Current result being processed (NoResult when expanding parser)
    var result: IResult[Any, Any] = NoResult

    // Main trampoline loop
    while (true) {
      // Phase 1: Expand parser into continuations
      while (current ne NoParser) {
        current match {
          case Parser.FlatMap(source, f) =>
            // Build FlatMap continuation
            val cont = Continuation.FlatMapCont(
              f.asInstanceOf[Any => Parser[Any, Any]],
              0,
              Continuation.End()
            ).asInstanceOf[Continuation[Any, Any, Any]]

            // Push to stack
            if (stackTop >= stack.length) {
              val newStack = new Array[Continuation[Any, Any, Any]](stack.length * 2)
              System.arraycopy(stack, 0, newStack, 0, stackTop)
              stack = newStack
            }
            stack(stackTop) = cont
            stackTop += 1

            // Continue with source
            current = source.asInstanceOf[Parser[Any, Any]]

          case Parser.Map(source, f) =>
            // Build Map continuation
            val cont = Continuation.MapCont(
              f.asInstanceOf[Any => Any],
              Continuation.End()
            ).asInstanceOf[Continuation[Any, Any, Any]]

            // Push to stack
            if (stackTop >= stack.length) {
              val newStack = new Array[Continuation[Any, Any, Any]](stack.length * 2)
              System.arraycopy(stack, 0, newStack, 0, stackTop)
              stack = newStack
            }
            stack(stackTop) = cont
            stackTop += 1

            // Continue with source
            current = source.asInstanceOf[Parser[Any, Any]]

          case _ =>
            // Terminal case - interpret
            result = interpretI(current, state).asInstanceOf[IResult[Any, Any]]
            current = NoParser
        }
      }

      // Phase 2: Apply continuations
      while (result ne NoResult) {
        if (stackTop == 0) {
          // No more continuations - return result
          return result.asInstanceOf[IResult[E, A]]
        }

        // Pop continuation
        stackTop -= 1
        val cont = stack(stackTop)
        stack(stackTop) = NoCont // Help GC

        // Apply continuation based on type
        cont match {
          case Continuation.End() =>
            // Identity - result unchanged
            ()

          case Continuation.MapCont(f, next) =>
            result match {
              case Result.Success(value, consumed) =>
                val mapped = f(value)
                result = Result.Success(mapped, consumed)

              case Result.Partial(value, errors, consumed) =>
                val mapped = f(value)
                result = Result.Partial(mapped, errors, consumed)

              case _: LazyFailure[?] =>
                // Failure propagates unchanged
                ()
            }

            // Push next continuation back (if not End)
            next match {
              case Continuation.End() => // Done
              case _ =>
                if (stackTop >= stack.length) {
                  val newStack = new Array[Continuation[Any, Any, Any]](stack.length * 2)
                  System.arraycopy(stack, 0, newStack, 0, stackTop)
                  stack = newStack
                }
                stack(stackTop) = next.asInstanceOf[Continuation[Any, Any, Any]]
                stackTop += 1
            }

          case Continuation.FlatMapCont(f, prevConsumed, next) =>
            result match {
              case Result.Success(value, consumed) =>
                // Run f(value) and continue
                current = f(value).asInstanceOf[Parser[Any, Any]]
                result = NoResult // Switch to Phase 1

                // Update continuation with accumulated consumed
                val updatedNext = if (prevConsumed + consumed > 0) {
                  // Wrap next to add consumed
                  Continuation.FlatMapPartialCont(
                    List.empty,
                    prevConsumed + consumed,
                    next
                  ).asInstanceOf[Continuation[Any, Any, Any]]
                } else {
                  next.asInstanceOf[Continuation[Any, Any, Any]]
                }

                // Push updated continuation
                updatedNext match {
                  case Continuation.End() => // No need to push
                  case _ =>
                    if (stackTop >= stack.length) {
                      val newStack = new Array[Continuation[Any, Any, Any]](stack.length * 2)
                      System.arraycopy(stack, 0, newStack, 0, stackTop)
                      stack = newStack
                    }
                    stack(stackTop) = updatedNext
                    stackTop += 1
                }

              case Result.Partial(value, errors, consumed) =>
                // Run f(value) and accumulate errors
                current = f(value).asInstanceOf[Parser[Any, Any]]
                result = NoResult // Switch to Phase 1

                // Push partial continuation
                val partialCont = Continuation.FlatMapPartialCont(
                  errors,
                  prevConsumed + consumed,
                  next
                ).asInstanceOf[Continuation[Any, Any, Any]]

                if (stackTop >= stack.length) {
                  val newStack = new Array[Continuation[Any, Any, Any]](stack.length * 2)
                  System.arraycopy(stack, 0, newStack, 0, stackTop)
                  stack = newStack
                }
                stack(stackTop) = partialCont
                stackTop += 1

              case _: LazyFailure[?] =>
                // Failure propagates, push next back
                next match {
                  case Continuation.End() => // Done
                  case _ =>
                    if (stackTop >= stack.length) {
                      val newStack = new Array[Continuation[Any, Any, Any]](stack.length * 2)
                      System.arraycopy(stack, 0, newStack, 0, stackTop)
                      stack = newStack
                    }
                    stack(stackTop) = next.asInstanceOf[Continuation[Any, Any, Any]]
                    stackTop += 1
                }
            }

          case Continuation.FlatMapPartialCont(errors1, prevConsumed, next) =>
            result match {
              case Result.Success(value, consumed) =>
                result = Result.Partial(value, errors1, prevConsumed + consumed)

              case Result.Partial(value, errors2, consumed) =>
                result = Result.Partial(value, errors1 ++ errors2, prevConsumed + consumed)

              case LazyFailure(mkErrors, furthest) =>
                result = LazyFailure(
                  () => errors1 ++ mkErrors().asInstanceOf[List[Any]],
                  furthest
                )
            }

            // Push next back
            next match {
              case Continuation.End() => // Done
              case _ =>
                if (stackTop >= stack.length) {
                  val newStack = new Array[Continuation[Any, Any, Any]](stack.length * 2)
                  System.arraycopy(stack, 0, newStack, 0, stackTop)
                  stack = newStack
                }
                stack(stackTop) = next.asInstanceOf[Continuation[Any, Any, Any]]
                stackTop += 1
            }
        }
      }
    }

    throw new AssertionError("Unreachable")
  }
}
