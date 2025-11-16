package parser

import munit.FunSuite
import parser.core.{
  between => _,
  chainLeft => _,
  chainRight => _,
  manyAtLeast => _,
  skipMany => _,
  skipManyNonEmpty => _,
  surroundedBy => _,
  _
}
import parser.syntax._

/**
 * Tests for convenience combinators added in 0.2.2.
 *
 * These combinators can all be composed from existing primitives,
 * but are provided for ergonomics and common use cases.
 */
class ConvenienceCombinatorTests extends FunSuite {

  // ============================================================================
  // between
  // ============================================================================

  test("between - parse value between delimiters") {
    val parser = digit.map(_.toString.toInt).between(char('('), char(')'))

    assertEquals(parser.run("(5)").toOption, Some(5))
    assertEquals(parser.run("(9)").toOption, Some(9))
  }

  test("between - parse multiple chars between delimiters") {
    val content = satisfy(c => c != '"', "content char").many.map(_.mkString)
    val parser  = content.between(char('"'), char('"'))

    assertEquals(parser.run("\"hello\"").toOption, Some("hello"))
    assertEquals(parser.run("\"world\"").toOption, Some("world"))
  }

  test("between - fails if opening delimiter missing") {
    val parser = digit.between(char('('), char(')'))

    assert(parser.run("5)").isFailure)
  }

  test("between - fails if closing delimiter missing") {
    val parser = digit.between(char('('), char(')'))

    assert(parser.run("(5").isFailure)
  }

  test("between - works with different delimiters") {
    val parser = digit.many.map(_.mkString).between(char('['), char(']'))

    assertEquals(parser.run("[123]").toOption, Some("123"))
  }

  test("between - extension method syntax") {
    val parser = digit.map(_.toString.toInt).between(char('('), char(')'))

    assertEquals(parser.run("(7)").toOption, Some(7))
  }

  // ============================================================================
  // surroundedBy
  // ============================================================================

  test("surroundedBy - parse value surrounded by same delimiter") {
    val parser = satisfy(c => c != '"', "content").many.map(_.mkString).surroundedBy(char('"'))

    assertEquals(parser.run("\"hello\"").toOption, Some("hello"))
  }

  test("surroundedBy - works with asterisks") {
    val parser = letter.many.map(_.mkString).surroundedBy(char('*'))

    assertEquals(parser.run("*bold*").toOption, Some("bold"))
  }

  test("surroundedBy - fails if delimiters don't match") {
    val parser = satisfy(c => c != '"', "content").many.map(_.mkString).surroundedBy(char('"'))

    assert(parser.run("\"hello'").isFailure)
  }

  test("surroundedBy - extension method syntax") {
    val parser = digit.many.map(_.mkString).surroundedBy(char('|'))

    assertEquals(parser.run("|123|").toOption, Some("123"))
  }

  // ============================================================================
  // skipMany
  // ============================================================================

  test("skipMany - skips zero occurrences successfully") {
    val parser = char('a').skipMany

    val result = parser.run("bbb")
    assertEquals(result.toOption, Some(()))
    // Should consume 0 characters
  }

  test("skipMany - skips multiple occurrences") {
    val parser = char('a').skipMany *> char('b')

    assertEquals(parser.run("aaab").toOption, Some('b'))
  }

  test("skipMany - use case: skip whitespace") {
    val skipSpaces = char(' ').skipMany
    val parser     = skipSpaces *> string("hello")

    assertEquals(parser.run("   hello").toOption, Some("hello"))
    assertEquals(parser.run("hello").toOption, Some("hello"))
  }

  test("skipMany - extension method syntax") {
    val parser = char(' ').skipMany *> string("test")

    assertEquals(parser.run("  test").toOption, Some("test"))
  }

  // ============================================================================
  // skipManyNonEmpty
  // ============================================================================

  test("skipManyNonEmpty - requires at least one occurrence") {
    val parser = char('a').skipManyNonEmpty

    assert(parser.run("bbb").isFailure)
  }

  test("skipManyNonEmpty - succeeds with one occurrence") {
    val parser = char('a').skipManyNonEmpty *> char('b')

    assertEquals(parser.run("ab").toOption, Some('b'))
  }

  test("skipManyNonEmpty - succeeds with many occurrences") {
    val parser = char('a').skipManyNonEmpty *> char('b')

    assertEquals(parser.run("aaaab").toOption, Some('b'))
  }

  test("skipManyNonEmpty - use case: require at least one space") {
    val requireSpaces = char(' ').skipManyNonEmpty
    val parser        = string("hello") *> requireSpaces *> string("world")

    assertEquals(parser.run("hello world").toOption, Some("world"))
    assertEquals(parser.run("hello  world").toOption, Some("world"))
    assert(parser.run("helloworld").isFailure)
  }

  test("skipManyNonEmpty - extension method syntax") {
    val parser = char('x').skipManyNonEmpty *> char('y')

    assertEquals(parser.run("xxxy").toOption, Some('y'))
  }

  // ============================================================================
  // manyAtLeast
  // ============================================================================

  test("manyAtLeast - fails if fewer than required") {
    val parser = digit.manyAtLeast(3)

    assert(parser.run("12").isFailure)
  }

  test("manyAtLeast - succeeds with exactly required") {
    val parser = digit.manyAtLeast(3)

    val result = parser.run("123")
    assertEquals(result.toOption, Some(List('1', '2', '3')))
  }

  test("manyAtLeast - succeeds with more than required") {
    val parser = digit.manyAtLeast(3)

    val result = parser.run("12345")
    assertEquals(result.toOption, Some(List('1', '2', '3', '4', '5')))
  }

  test("manyAtLeast - manyAtLeast(0) behaves like many") {
    val parser = digit.manyAtLeast(0)

    assertEquals(parser.run("").toOption, Some(List()))
    assertEquals(parser.run("123").toOption, Some(List('1', '2', '3')))
  }

  test("manyAtLeast - use case: require minimum length") {
    val atLeast5Chars = letter.manyAtLeast(5).map(_.mkString)

    assert(atLeast5Chars.run("hi").isFailure)
    assertEquals(atLeast5Chars.run("hello").toOption, Some("hello"))
    assertEquals(atLeast5Chars.run("wonderful").toOption, Some("wonderful"))
  }

  test("manyAtLeast - extension method syntax") {
    val parser = digit.manyAtLeast(2)

    assert(parser.run("1").isFailure)
    assertEquals(parser.run("12").toOption, Some(List('1', '2')))
  }

  // ============================================================================
  // chainLeft
  // ============================================================================

  test("chainLeft - returns default on empty input") {
    val num    = digit.map(_.toString.toInt)
    val plus   = char('+').as((a: Int, b: Int) => a + b)
    val parser = num.chainLeft(plus, 0)

    assertEquals(parser.run("").toOption, Some(0))
  }

  test("chainLeft - returns single value") {
    val num    = digit.map(_.toString.toInt)
    val plus   = char('+').as((a: Int, b: Int) => a + b)
    val parser = num.chainLeft(plus, 0)

    assertEquals(parser.run("5").toOption, Some(5))
  }

  test("chainLeft - left-associates multiple values") {
    val num    = digit.map(_.toString.toInt)
    val minus  = char('-').as((a: Int, b: Int) => a - b)
    val parser = num.chainLeft(minus, 0)

    // (5 - 3) - 1 = 1
    assertEquals(parser.run("5-3-1").toOption, Some(1))
  }

  test("chainLeft - extension method syntax") {
    val num    = digit.map(_.toString.toInt)
    val plus   = char('+').as((a: Int, b: Int) => a + b)
    val parser = num.chainLeft(plus, 0)

    assertEquals(parser.run("1+2+3").toOption, Some(6))
  }

  // ============================================================================
  // chainRight
  // ============================================================================

  test("chainRight - returns default on empty input") {
    val num    = digit.map(_.toString.toInt)
    val power  = char('^').as((a: Int, b: Int) => Math.pow(a.toDouble, b.toDouble).toInt)
    val parser = num.chainRight(power, 1)

    assertEquals(parser.run("").toOption, Some(1))
  }

  test("chainRight - returns single value") {
    val num    = digit.map(_.toString.toInt)
    val power  = char('^').as((a: Int, b: Int) => Math.pow(a.toDouble, b.toDouble).toInt)
    val parser = num.chainRight(power, 1)

    assertEquals(parser.run("5").toOption, Some(5))
  }

  test("chainRight - right-associates multiple values") {
    val num    = digit.map(_.toString.toInt)
    val power  = char('^').as((a: Int, b: Int) => Math.pow(a.toDouble, b.toDouble).toInt)
    val parser = num.chainRight(power, 1)

    // 2 ^ (3 ^ 2) = 2 ^ 9 = 512
    assertEquals(parser.run("2^3^2").toOption, Some(512))
  }

  test("chainRight - extension method syntax") {
    val num    = digit.map(_.toString.toInt)
    val power  = char('^').as((a: Int, b: Int) => Math.pow(a.toDouble, b.toDouble).toInt)
    val parser = num.chainRight(power, 1)

    assertEquals(parser.run("2^3").toOption, Some(8))
  }

  // ============================================================================
  // Real-World Use Cases
  // ============================================================================

  test("real-world - parse quoted strings") {
    val content = satisfy(c => c != '"' && c != '\\', "content").many.map(_.mkString)
    val quoted  = content.between(char('"'), char('"'))

    assertEquals(quoted.run("\"hello world\"").toOption, Some("hello world"))
  }

  test("real-world - parse bracketed lists") {
    val num  = digit.map(_.toString.toInt)
    val list = num.separatedBy(char(',')).between(char('['), char(']'))

    assertEquals(list.run("[1,2,3]").toOption, Some(List(1, 2, 3)))
  }

  test("real-world - skip leading whitespace") {
    val ws     = (char(' ') | char('\t') | char('\n')).skipMany
    val value  = digit.manyNonEmpty.map(_.mkString.toInt)
    val parser = ws *> value

    assertEquals(parser.run("  \t\n  42").toOption, Some(42))
  }

  test("real-world - require minimum password length") {
    val passwordChar = satisfy(c => c.isLetterOrDigit || c == '_', "password char")
    val password     = passwordChar.manyAtLeast(8).map(_.mkString)

    assert(password.run("short").isFailure)
    assertEquals(password.run("long_enough_123").toOption, Some("long_enough_123"))
  }

  test("real-world - parse arithmetic expressions") {
    val num   = digit.map(_.toString.toInt)
    val plus  = char('+').as((a: Int, b: Int) => a + b)
    val times = char('*').as((a: Int, b: Int) => a * b)

    // Multiplication has higher precedence (parse first)
    val term = num.chainLeft1(times)
    val expr = term.chainLeft1(plus)

    // 2 + 3 * 4 = 2 + 12 = 14
    assertEquals(expr.run("2+3*4").toOption, Some(14))
  }
}
