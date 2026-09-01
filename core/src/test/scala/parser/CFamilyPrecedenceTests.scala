package parser

import parser.core.*
import parser.syntax.*

enum TestExpr {
  case Num(n: Int)
  case Bin(op: String, l: TestExpr, r: TestExpr)
  case Un(op: String, e: TestExpr)
}

object TestExpr {
  def eval(e: TestExpr): Int = e match {
    case Num(n) => n
    case Bin("+", l, r) => eval(l) + eval(r)
    case Bin("-", l, r) => eval(l) - eval(r)
    case Bin("*", l, r) => eval(l) * eval(r)
    case Bin("/", l, r) => eval(l) / eval(r)
    case Bin("%", l, r) => eval(l) % eval(r)
    case Bin("==", l, r) => if eval(l) == eval(r) then 1 else 0
    case Bin("!=", l, r) => if eval(l) != eval(r) then 1 else 0
    case Bin("<", l, r) => if eval(l) < eval(r) then 1 else 0
    case Bin("<=", l, r) => if eval(l) <= eval(r) then 1 else 0
    case Bin(">", l, r) => if eval(l) > eval(r) then 1 else 0
    case Bin(">=", l, r) => if eval(l) >= eval(r) then 1 else 0
    case Bin("&&", l, r) => if eval(l) != 0 && eval(r) != 0 then 1 else 0
    case Bin("||", l, r) => if eval(l) != 0 || eval(r) != 0 then 1 else 0
    case Un("-", e) => -eval(e)
    case Un("!", e) => if eval(e) == 0 then 1 else 0
    case other => throw new MatchError(other)
  }
}

class CFamilyPrecedenceTests extends munit.FunSuite {

  private val atom: Parser[ParseError, TestExpr] =
    digit.many1.map(_.mkString.toInt).map(TestExpr.Num.apply)

  private val expr: Parser[ParseError, TestExpr] =
    pratt(
      atom,
      cFamilyPrecedence(
        sym = (s: String) => string(s),
        binary = (op, l, r) => TestExpr.Bin(op, l, r),
        unary = (op, e) => TestExpr.Un(op, e)
      )
    )

  private def eval(input: String): Int =
    expr.run(input).toOption.map(TestExpr.eval).getOrElse(fail(s"parse failed for '$input'"))

  test("multiplicative binds tighter than additive") {
    assertEquals(eval("1+2*3"), 7)
    assertEquals(eval("2*3+4"), 10)
  }

  test("additive is left-associative") {
    assertEquals(eval("10-3-2"), 5)
  }

  test("comparison binds looser than additive") {
    assertEquals(eval("1+2<4"), 1)
  }

  test("equality binds looser than comparison") {
    assertEquals(eval("2<3==1"), 1)
  }

  test("logical operators bind loosest") {
    assertEquals(eval("1==1&&2==3"), 0)
    assertEquals(eval("0||1==1"), 1)
  }

  test("prefix unary binds tighter than infix") {
    assertEquals(eval("-2+3"), 1)
    assertEquals(eval("-2*3"), -6)
    assertEquals(eval("!0==1"), 1)
  }

  test("<= and >= are not split by < and >") {
    assertEquals(eval("2<=2"), 1)
    assertEquals(eval("2>=3"), 0)
    assertEquals(eval("1<2<=2"), 1)
  }
}
