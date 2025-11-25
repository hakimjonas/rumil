package parser.runtime

import scala.collection.mutable.ArrayBuffer

import parser.core._

/**
 * Stack-safe interpreter using explicit continuation stack.
 *
 * Instead of using the JVM call stack for recursive interpretI calls,
 * we maintain an explicit stack of continuations on the heap.
 * This trades stack space for heap space, making deep recursion possible.
 *
 * The trampoline handles FlatMap and Map specially - these are the only
 * constructs that cause unbounded stack growth in the standard interpreter.
 * Other parser types are delegated to interpretI (which is fine since they
 * don't recurse unboundedly).
 */
object Trampoline {

  /**
   * A continuation represents "what to do next" after parsing completes.
   * Type-erased to avoid GADT complexity - safety ensured by construction.
   */
  private sealed trait Cont

  /** FlatMap continuation: apply f to success value, track consumed chars */
  private final case class FlatMapCont(f: Any => Parser[Any, Any], consumed: Int) extends Cont

  /** FlatMap continuation for partial results: carries accumulated errors */
  private final case class FlatMapPartialCont(consumed: Int, errors: List[Any]) extends Cont

  /** Map continuation: apply f to success/partial value */
  private final case class MapCont(f: Any => Any) extends Cont

  /** Consumed continuation: add consumed chars to result */
  private final case class ConsumedCont(consumed: Int) extends Cont

  /**
   * Run a parser using trampolined interpretation.
   *
   * This is stack-safe for arbitrarily deep FlatMap chains.
   */
  def runStackSafe[E, A](parser: Parser[E, A], state: ParserState): IResult[E, A] = {
    // Continuation stack - what to do after current parser completes
    val stack = new ArrayBuffer[Cont](16)

    // Current parser to interpret - use Option to avoid null
    var currentOpt: Option[Parser[Any, Any]] = Some(parser.asInstanceOf[Parser[Any, Any]])

    // Current result being propagated through continuations
    var resultOpt: Option[IResult[Any, Any]] = None

    // Main interpretation loop
    while (true) {
      // Phase 1: Expand nested FlatMap/Map into continuations
      while (currentOpt.isDefined) {
        currentOpt.get match {
          case Parser.FlatMap(source, f) =>
            // Push continuation and continue with source
            stack.append(FlatMapCont(f.asInstanceOf[Any => Parser[Any, Any]], 0))
            currentOpt = Some(source.asInstanceOf[Parser[Any, Any]])

          case Parser.Map(source, f) =>
            // Push continuation and continue with source
            stack.append(MapCont(f.asInstanceOf[Any => Any]))
            currentOpt = Some(source.asInstanceOf[Parser[Any, Any]])

          case current =>
            // Non-recursive case - interpret directly and switch to Phase 2
            resultOpt = Some(interpretI(current, state).asInstanceOf[IResult[Any, Any]])
            currentOpt = None
        }
      }

      // Phase 2: Apply continuations from stack
      while (resultOpt.isDefined) {
        val result = resultOpt.get

        if (stack.isEmpty) {
          // No more continuations - we're done
          return result.asInstanceOf[IResult[E, A]]
        }

        stack.remove(stack.length - 1) match {
          case FlatMapCont(f, consumed1) =>
            result match {
              case Result.Success(value, consumed2) =>
                // Success: push consumed tracking and continue with f(value)
                stack.append(ConsumedCont(consumed1 + consumed2))
                currentOpt = Some(f(value))
                resultOpt = None // Switch back to Phase 1

              case Result.Partial(value, errors, consumed2) =>
                // Partial: push partial tracking and continue with f(value)
                stack.append(FlatMapPartialCont(consumed1 + consumed2, errors.asInstanceOf[List[Any]]))
                currentOpt = Some(f(value))
                resultOpt = None // Switch back to Phase 1

              case lf: LazyFailure[?] =>
                // Failure: propagate through stack (stay in Phase 2)
                resultOpt = Some(lf.asInstanceOf[IResult[Any, Any]])
            }

          case FlatMapPartialCont(consumed1, errors1) =>
            result match {
              case Result.Success(value, consumed2) =>
                // Success after partial: result is partial
                resultOpt = Some(Result.Partial(value, errors1, consumed1 + consumed2))

              case Result.Partial(value, errors2, consumed2) =>
                // Partial after partial: combine errors
                resultOpt = Some(Result.Partial(value, errors1 ++ errors2, consumed1 + consumed2))

              case LazyFailure(mkErrors2, furthest) =>
                // Failure after partial: combine errors (still lazy for mkErrors2)
                resultOpt = Some(LazyFailure(() => errors1 ++ mkErrors2().asInstanceOf[List[Any]], furthest))
            }

          case MapCont(f) =>
            result match {
              case Result.Success(value, consumed) =>
                resultOpt = Some(Result.Success(f(value), consumed))
              case Result.Partial(value, errors, consumed) =>
                resultOpt = Some(Result.Partial(f(value), errors, consumed))
              case _: LazyFailure[?] =>
                // Failure passes through unchanged (resultOpt stays the same)
                ()
            }

          case ConsumedCont(extraConsumed) =>
            result match {
              case Result.Success(value, consumed) =>
                resultOpt = Some(Result.Success(value, extraConsumed + consumed))
              case Result.Partial(value, errors, consumed) =>
                resultOpt = Some(Result.Partial(value, errors, extraConsumed + consumed))
              case _: LazyFailure[?] =>
                // Failure passes through unchanged (resultOpt stays the same)
                ()
            }
        }
      }
    }

    // Unreachable - the while(true) always returns
    throw new AssertionError("Unreachable")
  }
}
