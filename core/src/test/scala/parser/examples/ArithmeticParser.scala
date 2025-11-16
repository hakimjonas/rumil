package parser.examples

import munit.FunSuite
import parser.core._
import parser.syntax._

class ArithmeticParserTests extends FunSuite {

  test("parse single number") {
    val number = digit.manyNonEmpty.map(_.mkString.toInt)
    val result = number.run("42")
    assert(result.isSuccess)
    assertEquals(result.toOption, Some(42))
  }

  test("parse addition") {
    val number = digit.manyNonEmpty.map(_.mkString.toInt)
    val result = {
      for {
        n1 <- number
        _  <- char('+')
        n2 <- number
      } yield n1 + n2
    }.run("1+2")
    assertEquals(result.toOption, Some(3))
  }

  test("parse multiplication") {
    val number = digit.manyNonEmpty.map(_.mkString.toInt)
    val result = {
      for {
        n1 <- number
        _  <- char('*')
        n2 <- number
      } yield n1 * n2
    }.run("2*3")
    assertEquals(result.toOption, Some(6))
  }

  test("parse complex expression with precedence") {
    // Grammar: expr = term (('+' | '-') term)*
    //          term = factor (('*' | '/') factor)*
    //          factor = number | '(' expr ')'

    // Using left recursion support (much cleaner than before!)
    val number = digit.manyNonEmpty.map(_.mkString.toInt)

    lazy val expr: Parser[ParseError, Int] = recursive {
      term.chainLeft1(
        (char('+').as((a: Int, b: Int) => a + b)) |
          (char('-').as((a: Int, b: Int) => a - b))
      )
    }

    lazy val term: Parser[ParseError, Int] = recursive {
      factor.chainLeft1(
        (char('*').as((a: Int, b: Int) => a * b)) |
          (char('/').as((a: Int, b: Int) => a / b))
      )
    }

    lazy val factor: Parser[ParseError, Int] =
      number | (char('(') *> expr <* char(')'))

    val result = expr.run("2+3*4")
    assertEquals(result.toOption, Some(14)) // 2 + (3*4) = 2 + 12 = 14
  }

  test("parse parentheses") {
    val number = digit.manyNonEmpty.map(_.mkString.toInt)

    lazy val expr: Parser[ParseError, Int] = recursive {
      term.chainLeft1(
        (char('+').as((a: Int, b: Int) => a + b)) |
          (char('-').as((a: Int, b: Int) => a - b))
      )
    }

    lazy val term: Parser[ParseError, Int] = recursive {
      factor.chainLeft1(
        (char('*').as((a: Int, b: Int) => a * b)) |
          (char('/').as((a: Int, b: Int) => a / b))
      )
    }

    lazy val factor: Parser[ParseError, Int] =
      number | (char('(') *> expr <* char(')'))

    val result = expr.run("(2+3)*4")
    assertEquals(result.toOption, Some(20)) // (2+3)*4 = 5*4 = 20
  }

  test("parse nested expression") {
    val number = digit.manyNonEmpty.map(_.mkString.toInt)

    lazy val expr: Parser[ParseError, Int] = recursive {
      term.chainLeft1(
        (char('+').as((a: Int, b: Int) => a + b)) |
          (char('-').as((a: Int, b: Int) => a - b))
      )
    }

    lazy val term: Parser[ParseError, Int] = recursive {
      factor.chainLeft1(
        (char('*').as((a: Int, b: Int) => a * b)) |
          (char('/').as((a: Int, b: Int) => a / b))
      )
    }

    lazy val factor: Parser[ParseError, Int] =
      number | (char('(') *> expr <* char(')'))

    val result = expr.run("((2+3)*4)+5")
    assertEquals(result.toOption, Some(25)) // ((2+3)*4)+5 = (5*4)+5 = 20+5 = 25
  }

  test("error on invalid input") {
    val number = digit.manyNonEmpty.map(_.mkString.toInt)
    val result = number.run("abc")
    assert(result.isFailure)
  }
}
