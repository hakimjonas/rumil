package parser

import munit.FunSuite

import parser.core.*
import parser.runtime.run
import parser.syntax.*

/** Stack-safety coverage for the Pratt combinator at scale.
  *
  * The trampoline-lifted Pratt (`PrattLoop` in `ParserCont`) should survive deeply left-
  * associative operator chains. This complements `TrampolineStackSafetyTest` which covers
  * statically-built deep `~`/`flatMap`/`many` parsers.
  *
  * Note: a parallel regression test for `chainl1` was considered but dropped — the exact overflow
  * boundary is JVM/JIT-dependent (observed at ~100 ops cold, >200 after warmup) and asserting on an
  * overflow is therefore flaky across test orders. The `chainl1` limitation is documented in
  * `benchmark-results/pratt-vs-chainl1-phase4.md`.
  */
class ChainlVsPrattStackSafety extends FunSuite {

  private def prattExpr: Parser[ParseError, Int] =
    pratt(
      digit.map(_.toString.toInt),
      List(Operator.InfixLeft(char('+'), 10, (a: Int, b: Int) => a + b))
    )

  private def tryParse(parser: Parser[ParseError, Int], size: Int): Either[String, Int] = {
    val input = (1 to size).map(i => (i % 10).toString).mkString("+")
    try {
      run(parser, input) match {
        case Result.Success(_, _) => Right(size)
        case other => Left(other.toString.take(80))
      }
    } catch {
      case _: StackOverflowError => Left("StackOverflowError")
    }
  }

  private def prattRightAssoc: Parser[ParseError, Int] =
    pratt(
      digit.map(_.toString.toInt),
      List(Operator.InfixRight(char('+'), 10, (a: Int, b: Int) => a + b))
    )

  test("pratt handles 100_000 left-associative operators without overflow") {
    val result = tryParse(prattExpr, 100_000)
    assert(result.isRight, s"expected success, got $result")
  }

  test("pratt handles 1_000_000 left-associative operators without overflow") {
    val result = tryParse(prattExpr, 1_000_000)
    assert(result.isRight, s"expected success, got $result")
  }

  test("pratt handles 100_000 right-associative operators without overflow") {
    val result = tryParse(prattRightAssoc, 100_000)
    assert(result.isRight, s"expected success, got $result")
  }
}
