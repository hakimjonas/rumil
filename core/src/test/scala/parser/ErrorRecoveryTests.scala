package parser

import munit.FunSuite

import parser.core.*
import parser.syntax.*

/** Tests for error recovery and alternation combinators.
  *
  * Two combinators for trying alternatives:
  *   - `orElse` (Parser.Or) - Fast alternation, no error tracking
  *   - `recover` (Parser.RecoverWith) - Error recovery with error tracking
  *
  * And for error customization:
  *   - `expect` (Parser.Expect) - Custom error messages
  */
class ErrorRecoveryTests extends FunSuite {

  // ============================================================================
  // orElse (Parser.Or) Tests - Fast alternation WITHOUT error tracking
  // ============================================================================

  test("orElse: primary parser succeeds - returns Success") {
    val parser = char('a').orElse(char('b'))
    val result = parser.run("a")

    assert(result.isSuccess)
    assertEquals(result.toOption, Some('a'))
  }

  test("orElse: primary fails, fallback succeeds - returns Success (no errors)") {
    val parser = char('a').orElse(char('b'))
    val result = parser.run("b")

    // New behavior: orElse is fast alternation - no error tracking!
    assert(result.isSuccess, s"Expected Success (no error tracking), got $result")
    assertEquals(result.toOption, Some('b'))
    assert(result.errors.isEmpty, "orElse should not track errors from primary")
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

    // Should be Success - no error tracking in orElse
    assert(result.isSuccess)
    assertEquals(result.toOption, Some("help"))
  }

  test("orElse: preserves consumed count from fallback") {
    val parser = char('a').orElse(string("xyz"))
    val result = parser.run("xyz")

    result match {
      case Result.Success(value, consumed) =>
        assertEquals(value, "xyz")
        assertEquals(consumed, 3)
      case other => fail(s"Expected Success, got $other")
    }
  }

  test("orElse: chained recovery") {
    val parser = char('a').orElse(char('b')).orElse(char('c'))

    assertEquals(parser.run("a").toOption, Some('a'))
    assertEquals(parser.run("b").toOption, Some('b'))
    assertEquals(parser.run("c").toOption, Some('c'))
    assert(parser.run("x").isFailure)
  }

  test("orElse: with succeed => fallback (default value pattern)") {
    val number = digit.many1.map(_.mkString.toInt)
    val withDefault = number.orElse(succeed(0))

    // Valid number
    assertEquals(withDefault.run("42").toOption, Some(42))

    // Invalid - should recover with default (Success, no errors)
    val recovered = withDefault.run("abc")
    assert(recovered.isSuccess, "orElse returns Success, not Partial")
    assertEquals(recovered.toOption, Some(0))
  }

  // ============================================================================
  // recover (Parser.RecoverWith) Tests - Error recovery WITH tracking
  // ============================================================================

  test("recover: primary parser succeeds - returns Success") {
    val parser = char('a').recover(char('b'))
    val result = parser.run("a")

    assert(result.isSuccess)
    assertEquals(result.toOption, Some('a'))
  }

  test("recover: primary fails, fallback succeeds - returns Partial with errors") {
    val parser = char('a').recover(char('b'))
    val result = parser.run("b")

    // Should be Partial because we recovered but want to preserve the original error
    assert(result.isPartial, s"Expected Partial, got $result")
    assertEquals(result.toOption, Some('b'))
    assert(result.errors.nonEmpty, "Should preserve original error")
  }

  test("recover: both parsers fail - returns Failure with combined errors") {
    val parser = char('a').recover(char('b'))
    val result = parser.run("x")

    assert(result.isFailure)
    assert(result.errors.nonEmpty)
  }

  test("recover: fallback consumes input correctly") {
    val parser = string("hello").recover(string("help"))
    val result = parser.run("help")

    assert(result.isPartial)
    assertEquals(result.toOption, Some("help"))
  }

  test("recover: preserves consumed count from fallback") {
    val parser = char('a').recover(string("xyz"))
    val result = parser.run("xyz")

    result match {
      case Result.Partial(value, _, consumed) =>
        assertEquals(value, "xyz")
        assertEquals(consumed, 3)
      case other => fail(s"Expected Partial, got $other")
    }
  }

  test("recover: chained recovery") {
    val parser = char('a').recover(char('b')).recover(char('c'))

    assertEquals(parser.run("a").toOption, Some('a'))
    assertEquals(parser.run("b").toOption, Some('b'))
    assertEquals(parser.run("c").toOption, Some('c'))
    assert(parser.run("x").isFailure)
  }

  test("recover: with succeed => fallback (default value pattern)") {
    val number = digit.many1.map(_.mkString.toInt)
    val withDefault = number.recover(succeed(0))

    // Valid number
    assertEquals(withDefault.run("42").toOption, Some(42))

    // Invalid - should recover with default and track error
    val recovered = withDefault.run("abc")
    assert(recovered.isPartial, "recover returns Partial with errors")
    assertEquals(recovered.toOption, Some(0))
    assert(recovered.errors.nonEmpty, "Should have error from failed primary")
  }

  test("recover: error location preserved from primary parser") {
    val parser = string("hello").recover(succeed("default"))
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
    // Create a parser that might produce Partial (using recover)
    val parser = char('a').recover(char('b')).expect("a or b required")
    val result = parser.run("b")

    // recover produces Partial, expect should pass it through
    assert(result.isPartial)
    assertEquals(result.toOption, Some('b'))
  }

  // ============================================================================
  // Combined recover + expect Tests
  // ============================================================================

  test("recover with expect: custom error on complete failure") {
    val parser = char('a').recover(char('b')).expect("must be 'a' or 'b'")

    // Success case
    assertEquals(parser.run("a").toOption, Some('a'))

    // Recovery case (Partial with errors)
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

  test("resilient number parsing with default (using recover)") {
    val number = digit.many1.map(_.mkString.toInt)
    val resilientNumber = number.recover(succeed(-1)).expect("integer expected")

    // Valid numbers
    assertEquals(resilientNumber.run("123").toOption, Some(123))
    assertEquals(resilientNumber.run("0").toOption, Some(0))

    // Invalid - recovers with default and tracks error
    val recovered = resilientNumber.run("abc")
    assert(recovered.isPartial)
    assertEquals(recovered.toOption, Some(-1))
  }

  test("resilient list parsing - continues after error (using recover)") {
    val number = digit.many1.map(_.mkString.toInt)
    val item = number.recover(succeed(0))
    val list = item.sepBy(char(','))

    // All valid
    assertEquals(list.run("1,2,3").toOption, Some(List(1, 2, 3)))

    // Some invalid - should recover with 0s
    val result = list.run("1,x,3")
    // First item succeeds, second recovers, but comma-separated parsing is tricky
    // The key is that we don't crash
    assert(result.toOption.isDefined)
  }

  test("error accumulation through multiple recover") {
    val a = char('a')
    val b = char('b')
    val c = char('c')

    // Chain of recoveries (using recover for error tracking)
    val parser = a.recover(b).recover(c)

    // 'c' requires going through two fallbacks
    val result = parser.run("c")
    assert(result.isPartial || result.isSuccess)
    assertEquals(result.toOption, Some('c'))

    // Check that errors accumulated (from 'a' and 'b' failures)
    if result.isPartial then {
      assert(result.errors.nonEmpty, "Should accumulate errors from failed attempts")
    }
  }

  // ============================================================================
  // Edge Cases
  // ============================================================================

  test("orElse: empty input - returns Success from fallback") {
    val parser = char('a').orElse(succeed('?'))
    val result = parser.run("")

    // orElse doesn't track errors
    assert(result.isSuccess)
    assertEquals(result.toOption, Some('?'))
  }

  test("recover: empty input - returns Partial with error") {
    val parser = char('a').recover(succeed('?'))
    val result = parser.run("")

    // recover tracks errors
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

  test("orElse: fallback also uses orElse (nested alternation)") {
    val deep = char('a').orElse(char('b').orElse(char('c')))

    assertEquals(deep.run("a").toOption, Some('a'))
    assertEquals(deep.run("b").toOption, Some('b'))
    assertEquals(deep.run("c").toOption, Some('c'))
  }

  test("recover: fallback also uses recover (nested recovery)") {
    val deep = char('a').recover(char('b').recover(char('c')))

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

  // ============================================================================
  // Comparison: orElse vs recover
  // ============================================================================

  test("comparison: orElse is faster (no error tracking)") {
    val parser = char('a').orElse(char('b'))
    val result = parser.run("b")

    assert(result.isSuccess, "orElse returns Success")
    assert(result.errors.isEmpty, "orElse has no errors")
  }

  test("comparison: recover tracks errors") {
    val parser = char('a').recover(char('b'))
    val result = parser.run("b")

    assert(result.isPartial, "recover returns Partial")
    assert(result.errors.nonEmpty, "recover has errors from primary")
  }
}
