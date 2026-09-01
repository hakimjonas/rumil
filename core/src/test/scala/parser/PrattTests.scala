package parser

import munit.FunSuite

import parser.core.*
import parser.runtime.run
import parser.syntax.*

/** Edge-case coverage for the Pratt combinator beyond the chainl1 parity suite.
  *
  * Covers operator kinds not expressible in chainl1 (postfix, mixed associativity), error reporting
  * after a consumed operator, nested Pratt grammars, and LazyPartial propagation via RecoverWith.
  */
class PrattTests extends FunSuite {

  private val digitInt: Parser[ParseError, Int] =
    digit.map(_.toString.toInt)

  test("postfix operator applies to accumulated lhs") {
    val p = pratt(
      digitInt,
      List(Operator.Postfix(char('!'), 50, (n: Int) => -n))
    )
    assertEquals(p.run("5!").toOption, Some(-5))
  }

  test("postfix chains left-to-right") {
    val p = pratt(
      digitInt,
      List(Operator.Postfix(char('!'), 50, (n: Int) => n + 1))
    )
    assertEquals(p.run("5!!!").toOption, Some(8))
  }

  test("postfix and infix mix: `2+3!` parses as `2+(3!)`") {
    val p = pratt(
      digitInt,
      List(
        Operator.InfixLeft(char('+'), 10, (a: Int, b: Int) => a + b),
        Operator.Postfix(char('!'), 50, (n: Int) => n * 10)
      )
    )
    assertEquals(p.run("2+3!").toOption, Some(2 + 30))
  }

  test("prefix and postfix mix: `-5!` parses as `-(5!)` when postfix binds tighter") {
    val p = pratt(
      digitInt,
      List(
        Operator.Prefix(char('-'), 30, (n: Int) => -n),
        Operator.Postfix(char('!'), 50, (n: Int) => n * 10)
      )
    )
    assertEquals(p.run("-5!").toOption, Some(-50))
  }

  test("mixed left- and right-associative: `1+2^3+4` parses as `(1+(2^3))+4`") {
    val p = pratt(
      digitInt,
      List(
        Operator.InfixLeft(char('+'), 10, (a: Int, b: Int) => a + b),
        Operator.InfixRight(char('^'), 30, (a: Int, b: Int) => math.pow(a.toDouble, b.toDouble).toInt)
      )
    )
    assertEquals(p.run("1+2^3+4").toOption, Some(1 + 8 + 4))
  }

  test("right-associative associates right: `a^b^c = a^(b^c)`") {
    val p = pratt(
      digitInt,
      List(Operator.InfixRight(char('^'), 30, (a: Int, b: Int) => math.pow(a.toDouble, b.toDouble).toInt))
    )
    assertEquals(p.run("2^3^2").toOption, Some(512))
  }

  test("pratt nested inside pratt: outer over comma, inner over +") {
    val inner = pratt(
      digitInt,
      List(Operator.InfixLeft(char('+'), 10, (a: Int, b: Int) => a + b))
    )
    val outer = pratt(
      inner,
      List(Operator.InfixLeft(char(','), 1, (a: Int, b: Int) => a * 100 + b))
    )
    assertEquals(outer.run("1+2,3+4").toOption, Some(307))
  }

  test("pratt over parenthesized pratt via atom") {
    lazy val atom: Parser[ParseError, Int] =
      digitInt | (char('(') *> defer(expr) <* char(')'))
    lazy val expr: Parser[ParseError, Int] =
      pratt(
        defer(atom),
        List(
          Operator.InfixLeft(char('+'), 10, (a: Int, b: Int) => a + b),
          Operator.InfixLeft(char('*'), 20, (a: Int, b: Int) => a * b)
        )
      )
    assertEquals(expr.run("(1+2)*(3+4)").toOption, Some(21))
  }

  test("unknown operator => parser succeeds with partial chain and stops") {
    val p = pratt(
      digitInt,
      List(Operator.InfixLeft(char('+'), 10, (a: Int, b: Int) => a + b))
    )
    val result = p.run("1+2-3").toOption
    assertEquals(result, Some(3))
  }

  test("empty input after atom (e.g. `5`) succeeds with just the atom") {
    val p = pratt(
      digitInt,
      List(Operator.InfixLeft(char('+'), 10, (a: Int, b: Int) => a + b))
    )
    assertEquals(p.run("5").toOption, Some(5))
  }

  test("no operators => pratt reduces to nud") {
    val p = pratt(digitInt, List.empty)
    assertEquals(p.run("7").toOption, Some(7))
  }

  test("RecoverWith around pratt recovers after operator error") {
    val p = pratt(
      digitInt,
      List(Operator.InfixLeft(char('+'), 10, (a: Int, b: Int) => a + b))
    )
    val recovered = p.recoverWith((_: ParseError) => succeed(-1))
    assertEquals(recovered.run("5").toOption, Some(5))
    assertEquals(recovered.run("x").toOption, Some(-1))
  }

  test("pratt with atom failure propagates as failure, not partial") {
    val p = pratt(
      digitInt,
      List(Operator.InfixLeft(char('+'), 10, (a: Int, b: Int) => a + b))
    )
    assert(p.run("x").isFailure)
  }

  test("pratt after operator + RHS failure propagates furthest error") {
    val p = pratt(
      digitInt,
      List(Operator.InfixLeft(char('+'), 10, (a: Int, b: Int) => a + b))
    )
    val result = p.run("1+x")
    assert(result.isFailure, s"expected failure, got $result")
  }

  test("two-infix same precedence: left-assoc stays left") {
    val p = pratt(
      digitInt,
      List(
        Operator.InfixLeft(char('-'), 10, (a: Int, b: Int) => a - b),
        Operator.InfixLeft(char('+'), 10, (a: Int, b: Int) => a + b)
      )
    )
    assertEquals(p.run("5-3+2").toOption, Some(4))
  }

  test("three-level precedence: `1+2*3^2 = 1+(2*(3^2))`") {
    val p = pratt(
      digitInt,
      List(
        Operator.InfixLeft(char('+'), 10, (a: Int, b: Int) => a + b),
        Operator.InfixLeft(char('*'), 20, (a: Int, b: Int) => a * b),
        Operator.InfixRight(char('^'), 30, (a: Int, b: Int) => math.pow(a.toDouble, b.toDouble).toInt)
      )
    )
    assertEquals(p.run("1+2*3^2").toOption, Some(19))
  }
}
