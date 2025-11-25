package parser

import parser.core._
import parser.syntax._

class StackSafetyTests extends munit.FunSuite {

  /**
   * Critical test for 1.0 readiness: Verify the interpreter is stack-safe
   * for deeply left-associated sequences.
   *
   * The combinator ~ creates left-associative trees: (((a ~ b) ~ c) ~ d)
   * This translates to: FlatMap(FlatMap(FlatMap(a, ...), ...), ...)
   *
   * Without trampolining, the interpreter will stack overflow on deep chains.
   */
  test("stack safety: 10,000 sequential parsers using ~") {
    // Create a deeply left-associated sequence: char('a') ~ char('a') ~ ... (10,000 times)
    val n = 10000
    var parser: Parser[ParseError, Any] = char('a')

    for (_ <- 1 until n) {
      parser = parser ~ char('a')
    }

    val input = "a" * n
    val result = parser.run(input)

    // If we get here without StackOverflowError, we're stack-safe
    assert(result.isSuccess, "Should successfully parse 10,000 'a' characters")
  }

  test("stack safety: 5,000 sequential parsers using flatMap") {
    // Even more direct test: explicit flatMap chain
    val n = 5000
    var parser: Parser[ParseError, List[Char]] = char('a').map(List(_))

    for (_ <- 1 until n) {
      parser = parser.flatMap(acc => char('a').map(c => c :: acc))
    }

    val input = "a" * n
    val result = parser.run(input)

    assert(result.isSuccess, "Should successfully parse 5,000 'a' characters via flatMap")
  }

  test("stack safety: deeply nested many combinator") {
    // Test the many combinator which uses internal recursion
    val manyA = char('a').many
    val input = "a" * 10000

    val result = manyA.run(input)

    assert(result.isSuccess, "Should handle 10,000 repetitions without stack overflow")
    result.toOption.foreach { chars =>
      assertEquals(chars.size, 10000, "Should parse all 10,000 characters")
    }
  }

  test("stack safety: left-recursive rule with deep recursion") {
    // Test left recursion doesn't cause stack overflow
    lazy val expr: Parser[ParseError, Int] = rule {
      (expr ~ char('+') ~ digit.map(_.asDigit)).map { case ((e, _), d) => e + d } |
      digit.map(_.asDigit)
    }

    // Deep left-recursive parse: 1+1+1+1+... (1000 times)
    val input = "1" + ("+1" * 1000)
    val result = expr.run(input)

    assert(result.isSuccess, "Left-recursive parser should handle deep recursion")
    result.toOption.foreach { value =>
      assertEquals(value, 1001, "Should correctly evaluate 1 + 1000")
    }
  }
}
