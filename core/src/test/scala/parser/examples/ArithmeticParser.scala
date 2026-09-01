package parser.examples

import munit.FunSuite

import parser.core.*
import parser.syntax.*

class ArithmeticParserTests extends FunSuite {

  test("parse single number") {
    val number = digit.many1.map(_.mkString.toInt)
    val result = number.run("42")
    assert(result.isSuccess)
    assertEquals(result.toOption, Some(42))
  }

  test("parse addition") {
    val number = digit.many1.map(_.mkString.toInt)
    val result = {
      for {
        n1 <- number
        _ <- char('+')
        n2 <- number
      } yield n1 + n2
    }.run("1+2")
    assertEquals(result.toOption, Some(3))
  }

  test("parse multiplication") {
    val number = digit.many1.map(_.mkString.toInt)
    val result = {
      for {
        n1 <- number
        _ <- char('*')
        n2 <- number
      } yield n1 * n2
    }.run("2*3")
    assertEquals(result.toOption, Some(6))
  }

  test("parse complex expression with precedence") {
    // Grammar: expr = term (('+' | '-') term)*
    //          term = factor (('*' | '/') factor)*
    //          factor = number | '(' expr ')'

    // Using defer to break recursion cycles
    lazy val expr: Parser[ParseError, Int] =
      defer(term).chainl1(
        (char('+').as((a: Int, b: Int) => a + b)) |
          (char('-').as((a: Int, b: Int) => a - b))
      )

    lazy val term: Parser[ParseError, Int] =
      defer(factor).chainl1(
        (char('*').as((a: Int, b: Int) => a * b)) |
          (char('/').as((a: Int, b: Int) => a / b))
      )

    lazy val factor: Parser[ParseError, Int] = {
      val number = digit.many1.map(_.mkString.toInt)
      number | (char('(') *> defer(expr) <* char(')'))
    }

    val result = expr.run("2+3*4")
    assertEquals(result.toOption, Some(14)) // 2 + (3*4) = 2 + 12 = 14
  }

  test("parse parentheses") {
    lazy val expr: Parser[ParseError, Int] =
      defer(term).chainl1(
        (char('+').as((a: Int, b: Int) => a + b)) |
          (char('-').as((a: Int, b: Int) => a - b))
      )

    lazy val term: Parser[ParseError, Int] =
      defer(factor).chainl1(
        (char('*').as((a: Int, b: Int) => a * b)) |
          (char('/').as((a: Int, b: Int) => a / b))
      )

    lazy val factor: Parser[ParseError, Int] = {
      val number = digit.many1.map(_.mkString.toInt)
      number | (char('(') *> defer(expr) <* char(')'))
    }

    val result = expr.run("(2+3)*4")
    assertEquals(result.toOption, Some(20)) // (2+3)*4 = 5*4 = 20
  }

  test("parse nested expression") {
    lazy val expr: Parser[ParseError, Int] =
      defer(term).chainl1(
        (char('+').as((a: Int, b: Int) => a + b)) |
          (char('-').as((a: Int, b: Int) => a - b))
      )

    lazy val term: Parser[ParseError, Int] =
      defer(factor).chainl1(
        (char('*').as((a: Int, b: Int) => a * b)) |
          (char('/').as((a: Int, b: Int) => a / b))
      )

    lazy val factor: Parser[ParseError, Int] = {
      val number = digit.many1.map(_.mkString.toInt)
      number | (char('(') *> defer(expr) <* char(')'))
    }

    val result = expr.run("((2+3)*4)+5")
    assertEquals(result.toOption, Some(25)) // ((2+3)*4)+5 = (5*4)+5 = 20+5 = 25
  }

  test("error on invalid input") {
    val number = digit.many1.map(_.mkString.toInt)
    val result = number.run("abc")
    assert(result.isFailure)
  }
}
