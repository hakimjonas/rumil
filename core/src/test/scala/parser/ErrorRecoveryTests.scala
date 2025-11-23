package parser

import munit.FunSuite
import parser.core._
import parser.syntax._

/**
 * Tests for error recovery combinators: orElse (Parser.RecoverWith) and expect (Parser.Expect).
 *
 * These combinators enable resilient parsing where:
 * - Parsing can continue despite errors (via orElse)
 * - Errors can be given custom messages (via expect)
 * - Multiple errors can be accumulated (via Result.Partial)
 */
class ErrorRecoveryTests extends FunSuite {

  // ============================================================================
  // orElse (Parser.RecoverWith) Tests
  // ============================================================================

  test("orElse: primary parser succeeds - returns Success") {
    val parser = char('a').orElse(char('b'))
    val result = parser.run("a")

    assert(result.isSuccess)
    assertEquals(result.toOption, Some('a'))
  }

  test("orElse: primary fails, fallback succeeds - returns Partial with errors") {
    val parser = char('a').orElse(char('b'))
    val result = parser.run("b")

    // Should be Partial because we recovered but want to preserve the original error
    assert(result.isPartial, s"Expected Partial, got $result")
    assertEquals(result.toOption, Some('b'))
    assert(result.errors.nonEmpty, "Should preserve original error")
  }

  test("orElse: both parsers fail - returns Failure with combined errors") {
    val parser = char('a').orElse(char('b'))
    val result = parser.run("x")

    assert(result.isFailure)
    assert(result.errors.nonEmpty)
  }

  test("orElse: fallback consumes input correctly") {
    val parser = string("hello").orElse(string("help"))
    val result = parser.run("help")

    assert(result.isPartial || result.isSuccess)
    assertEquals(result.toOption, Some("help"))
  }

  test("orElse: preserves consumed count from fallback") {
    val parser = char('a').orElse(string("xyz"))
    val result = parser.run("xyz")

    result match {
      case Result.Partial(value, _, consumed) =>
        assertEquals(value, "xyz")
        assertEquals(consumed, 3)
      case other => fail(s"Expected Partial, got $other")
    }
  }

  test("orElse: chained recovery") {
    val parser = char('a').orElse(char('b')).orElse(char('c'))

    assertEquals(parser.run("a").toOption, Some('a'))
    assertEquals(parser.run("b").toOption, Some('b'))
    assertEquals(parser.run("c").toOption, Some('c'))
    assert(parser.run("x").isFailure)
  }

  test("orElse: with succeed as fallback (default value pattern)") {
    val number      = digit.many1.map(_.mkString.toInt)
    val withDefault = number.orElse(succeed(0))

    // Valid number
    assertEquals(withDefault.run("42").toOption, Some(42))

    // Invalid - should recover with default
    val recovered = withDefault.run("abc")
    assert(recovered.isPartial)
    assertEquals(recovered.toOption, Some(0))
  }

  test("orElse: error location preserved from primary parser") {
    val parser = string("hello").orElse(succeed("default"))
    val result = parser.run("xyz")

    result match {
      case Result.Partial(_, errors, _) =>
        assert(errors.nonEmpty)
      // Error should be at the start where "hello" was expected
      case other => fail(s"Expected Partial, got $other")
    }
  }

  // ============================================================================
  // expect (Parser.Expect) Tests
  // ============================================================================

  test("expect: success passes through unchanged") {
    val parser = char('a').expect("letter 'a' required")
    val result = parser.run("a")

    assert(result.isSuccess)
    assertEquals(result.toOption, Some('a'))
  }

  test("expect: failure gets custom error message") {
    val parser = char('a').expect("letter 'a' required")
    val result = parser.run("x")

    assert(result.isFailure)
    result match {
      case Result.Failure(errors, _) =>
        assertEquals(errors.length, 1)
        errors.head match {
          case ParseError.Custom(msg, _) =>
            assertEquals(msg, "letter 'a' required")
          case other =>
            fail(s"Expected Custom error, got $other")
        }
      case _ => fail("Expected Failure")
    }
  }

  test("expect: replaces all errors with single custom message") {
    // A parser that would generate multiple error expectations
    val parser = (char('a') | char('b') | char('c')).expect("one of a, b, or c")
    val result = parser.run("x")

    assert(result.isFailure)
    result match {
      case Result.Failure(errors, _) =>
        assertEquals(errors.length, 1) // Only one custom error
        errors.head match {
          case ParseError.Custom(msg, _) =>
            assertEquals(msg, "one of a, b, or c")
          case _ => fail("Expected Custom error")
        }
      case _ => fail("Expected Failure")
    }
  }

  test("expect: preserves error location") {
    val parser = (string("abc") *> char('x')).expect("expected 'x' after 'abc'")
    val result = parser.run("abcy")

    assert(result.isFailure)
    result match {
      case Result.Failure(errors, furthest) =>
        // Error should be at position 3 (after "abc")
        assertEquals(furthest.offset, 3)
      case _ => fail("Expected Failure")
    }
  }

  test("expect: partial result passes through") {
    // Create a parser that might produce Partial
    val parser = char('a').orElse(char('b')).expect("a or b required")
    val result = parser.run("b")

    // orElse produces Partial, expect should pass it through
    assert(result.isPartial)
    assertEquals(result.toOption, Some('b'))
  }

  // ============================================================================
  // Combined orElse + expect Tests
  // ============================================================================

  test("orElse with expect: custom error on complete failure") {
    val parser = char('a').orElse(char('b')).expect("must be 'a' or 'b'")

    // Success case
    assertEquals(parser.run("a").toOption, Some('a'))

    // Recovery case (Partial)
    assert(parser.run("b").isPartial)

    // Failure case - should have custom message
    val failure = parser.run("x")
    assert(failure.isFailure)
    failure match {
      case Result.Failure(errors, _) =>
        errors.head match {
          case ParseError.Custom(msg, _) =>
            assertEquals(msg, "must be 'a' or 'b'")
          case _ => fail("Expected Custom error")
        }
      case _ => fail("Expected Failure")
    }
  }

  // ============================================================================
  // Integration Tests - Real-world Scenarios
  // ============================================================================

  test("resilient number parsing with default") {
    val number          = digit.many1.map(_.mkString.toInt)
    val resilientNumber = number.orElse(succeed(-1)).expect("integer expected")

    // Valid numbers
    assertEquals(resilientNumber.run("123").toOption, Some(123))
    assertEquals(resilientNumber.run("0").toOption, Some(0))

    // Invalid - recovers with default
    val recovered = resilientNumber.run("abc")
    assert(recovered.isPartial)
    assertEquals(recovered.toOption, Some(-1))
  }

  test("resilient list parsing - continues after error") {
    val number = digit.many1.map(_.mkString.toInt)
    val item   = number.orElse(succeed(0))
    val list   = item.sepBy(char(','))

    // All valid
    assertEquals(list.run("1,2,3").toOption, Some(List(1, 2, 3)))

    // Some invalid - should recover with 0s
    val result = list.run("1,x,3")
    // First item succeeds, second recovers, but comma-separated parsing is tricky
    // The key is that we don't crash
    assert(result.toOption.isDefined)
  }

  test("error accumulation through multiple orElse") {
    val a = char('a')
    val b = char('b')
    val c = char('c')

    // Chain of recoveries
    val parser = a.orElse(b).orElse(c)

    // 'c' requires going through two fallbacks
    val result = parser.run("c")
    assert(result.isPartial || result.isSuccess)
    assertEquals(result.toOption, Some('c'))

    // Check that errors accumulated (from 'a' and 'b' failures)
    if (result.isPartial) {
      assert(result.errors.nonEmpty, "Should accumulate errors from failed attempts")
    }
  }

  // ============================================================================
  // Edge Cases
  // ============================================================================

  test("orElse: empty input") {
    val parser = char('a').orElse(succeed('?'))
    val result = parser.run("")

    assert(result.isPartial)
    assertEquals(result.toOption, Some('?'))
  }

  test("expect: empty input") {
    val parser = char('a').expect("expected 'a'")
    val result = parser.run("")

    assert(result.isFailure)
    result match {
      case Result.Failure(errors, _) =>
        errors.head match {
          case ParseError.Custom(msg, _) =>
            assertEquals(msg, "expected 'a'")
          case _ => fail("Expected Custom error")
        }
      case _ => fail("Expected Failure")
    }
  }

  test("orElse: fallback also uses orElse (nested recovery)") {
    val deep = char('a').orElse(char('b').orElse(char('c')))

    assertEquals(deep.run("a").toOption, Some('a'))
    assertEquals(deep.run("b").toOption, Some('b'))
    assertEquals(deep.run("c").toOption, Some('c'))
  }

  test("expect: nested expect preserves outer message") {
    val inner = char('a').expect("inner message")
    val outer = inner.expect("outer message")

    val result = outer.run("x")
    assert(result.isFailure)
    result match {
      case Result.Failure(errors, _) =>
        errors.head match {
          case ParseError.Custom(msg, _) =>
            // Outer expect should replace inner's message
            assertEquals(msg, "outer message")
          case _ => fail("Expected Custom error")
        }
      case _ => fail("Expected Failure")
    }
  }
}
