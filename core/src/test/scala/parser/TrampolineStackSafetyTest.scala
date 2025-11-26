package parser

import munit.FunSuite
import parser.core._
import parser.runtime.run
import parser.syntax._

/**
 * Verify that TrampolineOpt (the current default interpreter) is actually stack-safe.
 *
 * Previous tests used runRecursive which we discovered was NOT stack-safe.
 * These tests use run() which delegates to TrampolineOpt.
 */
class TrampolineStackSafetyTest extends FunSuite {

  test("stack safety: 100,000 sequential parsers using ~") {
    val count = 100000
    var p: Parser[ParseError, Any] = char('1')
    for (_ <- 1 until count) {
      p = p ~ char('1')
    }
    val input = "1" * count
    val result = run(p, input)
    assert(result.isSuccess, s"Failed to parse $count sequential parsers")
  }

  test("stack safety: 1,000,000 sequential parsers using ~") {
    val count = 1000000
    var p: Parser[ParseError, Any] = char('1')
    for (_ <- 1 until count) {
      p = p ~ char('1')
    }
    val input = "1" * count
    val result = run(p, input)
    assert(result.isSuccess, s"Failed to parse $count sequential parsers")
  }

  test("stack safety: 5,000,000 sequential parsers using ~") {
    val count = 5000000
    var p: Parser[ParseError, Any] = char('1')
    for (_ <- 1 until count) {
      p = p ~ char('1')
    }
    val input = "1" * count
    val result = run(p, input)
    assert(result.isSuccess, s"Failed to parse $count sequential parsers")
  }

  test("stack safety: 100,000 flatMap chains") {
    val count = 100000
    var p: Parser[ParseError, Char] = char('1')
    for (_ <- 1 until count) {
      p = p.flatMap(_ => char('1'))
    }
    val input = "1" * count
    val result = run(p, input)
    assert(result.isSuccess, s"Failed to parse $count flatMap chains")
  }

  test("stack safety: deeply nested many combinator") {
    // many itself should not stack overflow regardless of matches
    val p = parser.core.many(char('1'))
    val input = "1" * 1000000
    val result = run(p, input)
    assert(result.isSuccess, "Failed to parse 1M repetitions with many")
  }
}
