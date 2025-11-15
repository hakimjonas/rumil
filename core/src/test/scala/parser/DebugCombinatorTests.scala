package parser

import munit.FunSuite
import parser.core._
import parser.syntax._

class DebugCombinatorTests extends FunSuite {

  // ============================================================================
  // Trace Combinator Tests
  // ============================================================================

  test("trace returns identical result on success") {
    val parser = digit.trace("number")
    val result = parser.run("5")
    assertEquals(result.toOption, Some('5'))
  }

  test("trace returns identical result on failure") {
    val parser = digit.trace("number")
    val result = parser.run("x")
    assert(result.isFailure)
  }

  test("trace shows correct offset") {
    val parser = string("ab").trace("prefix")
    val result = parser.run("ab")
    assertEquals(result.toOption, Some("ab"))
  }

  test("trace works with combinators") {
    val parser = (char('a').trace("first") ~ char('b').trace("second"))
    val result = parser.run("ab")
    assertEquals(result.toOption, Some(('a', 'b')))
  }

  test("trace on failing parser") {
    val parser = char('a').trace("letter-a")
    val result = parser.run("b")
    assert(result.isFailure)
  }

  // ============================================================================
  // Debug Combinator Tests
  // ============================================================================

  test("debug returns identical result on success") {
    val parser = digit.debug("number")
    val result = parser.run("7")
    assertEquals(result.toOption, Some('7'))
  }

  test("debug returns identical result on failure") {
    val parser = digit.debug("number")
    val result = parser.run("x")
    assert(result.isFailure)
  }

  test("debug shows parsed values") {
    val parser = string("hello").debug("greeting")
    val result = parser.run("hello")
    assertEquals(result.toOption, Some("hello"))
  }

  test("debug works with combinators") {
    val parser = (digit.debug("first-digit") ~ digit.debug("second-digit"))
    val result = parser.run("42")
    assertEquals(result.toOption, Some(('4', '2')))
  }

  test("debug on failing parser") {
    val parser = char('x').debug("letter-x")
    val result = parser.run("y")
    assert(result.isFailure)
  }

  // ============================================================================
  // Combined Trace and Debug Tests
  // ============================================================================

  test("trace and debug can be combined") {
    val parser = digit.trace("tracing").debug("debugging")
    val result = parser.run("9")
    assertEquals(result.toOption, Some('9'))
  }

  test("trace and debug preserve consumed count") {
    val parser = string("abc").trace("test").debug("test")
    val result = parser.run("abc")
    result match {
      case Result.Success(value, consumed) =>
        assertEquals(value, "abc")
        assertEquals(consumed, 3)
      case _ => fail("Expected success")
    }
  }

  test("trace and debug preserve errors") {
    val parser = char('a').trace("test").debug("test")
    val result = parser.run("b")
    result match {
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
      case _ => fail("Expected failure")
    }
  }

  // ============================================================================
  // Complex Parser Tests
  // ============================================================================

  test("debug with many combinator") {
    val parser = digit.many.debug("digits")
    val result = parser.run("123")
    assertEquals(result.toOption, Some(List('1', '2', '3')))
  }

  test("trace with many1 combinator") {
    val parser = digit.many1.trace("digits")
    val result = parser.run("456")
    assertEquals(result.toOption, Some(List('4', '5', '6')))
  }

  test("debug with optional combinator") {
    val parser = digit.optional.debug("maybe-digit")
    val result1 = parser.run("5")
    val result2 = parser.run("x")
    assertEquals(result1.toOption, Some(Some('5')))
    assertEquals(result2.toOption, Some(None))
  }
}
