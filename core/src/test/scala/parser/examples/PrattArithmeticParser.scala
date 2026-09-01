package parser.examples

import munit.FunSuite

import parser.core.{*, given}
import parser.syntax.*

/** Correctness parity between the Pratt combinator and `chainl1` for an arithmetic grammar that
  * emits a lossless `GreenNode` tree.
  *
  * Both parsers must produce byte-identical `GreenNode.toSource` output on the same input. Pratt
  * additionally handles right-associative `^` and prefix unary `-`, which `chainl1` cannot express
  * without rebuilding the grammar.
  */
class PrattArithmeticParser extends FunSuite {

  private def numberToken(c: Char): GreenNode =
    GreenNode.Token(TokenKind.Number, c.toString)

  private def opToken(c: Char): GreenNode =
    GreenNode.Token(TokenKind.Operator, c.toString)

  private def binOp(left: GreenNode, op: Char, right: GreenNode): GreenNode =
    GreenNode.treeOfVec(SyntaxKind.Expression, Vector(left, opToken(op), right))

  private def unaryOp(op: Char, operand: GreenNode): GreenNode =
    GreenNode.treeOfVec(SyntaxKind.Expression, Vector(opToken(op), operand))

  private val atomDigit: Parser[ParseError, GreenNode] =
    digit.map(numberToken)

  /** chainl1-based baseline: supports `+ - * /` with left precedence, plus parentheses. */
  private def chainlArith: Parser[ParseError, GreenNode] = {
    lazy val expr: Parser[ParseError, GreenNode] =
      defer(term).chainl1(
        char('+').as((a: GreenNode, b: GreenNode) => binOp(a, '+', b)) |
          char('-').as((a: GreenNode, b: GreenNode) => binOp(a, '-', b))
      )
    lazy val term: Parser[ParseError, GreenNode] =
      defer(factor).chainl1(
        char('*').as((a: GreenNode, b: GreenNode) => binOp(a, '*', b)) |
          char('/').as((a: GreenNode, b: GreenNode) => binOp(a, '/', b))
      )
    lazy val factor: Parser[ParseError, GreenNode] =
      atomDigit | (char('(') *> defer(expr) <* char(')'))
    expr
  }

  /** Pratt-based equivalent of the chainl1 grammar: same four operators, same precedence, same
    * left-associative semantics, same GreenNode shape.
    */
  private def prattArith: Parser[ParseError, GreenNode] = {
    lazy val atom: Parser[ParseError, GreenNode] =
      atomDigit | (char('(') *> defer(exprP) <* char(')'))
    lazy val exprP: Parser[ParseError, GreenNode] =
      pratt(
        defer(atom),
        List(
          Operator.InfixLeft(char('+'), 10, (a: GreenNode, b: GreenNode) => binOp(a, '+', b)),
          Operator.InfixLeft(char('-'), 10, (a: GreenNode, b: GreenNode) => binOp(a, '-', b)),
          Operator.InfixLeft(char('*'), 20, (a: GreenNode, b: GreenNode) => binOp(a, '*', b)),
          Operator.InfixLeft(char('/'), 20, (a: GreenNode, b: GreenNode) => binOp(a, '/', b))
        )
      )
    exprP
  }

  /** Pratt parser extended with right-associative `^` (power) and prefix unary `-`. */
  private def prattArithFull: Parser[ParseError, GreenNode] = {
    lazy val atom: Parser[ParseError, GreenNode] =
      atomDigit | (char('(') *> defer(exprP) <* char(')'))
    lazy val exprP: Parser[ParseError, GreenNode] =
      pratt(
        defer(atom),
        List(
          Operator.InfixLeft(char('+'), 10, (a: GreenNode, b: GreenNode) => binOp(a, '+', b)),
          Operator.InfixLeft(char('-'), 10, (a: GreenNode, b: GreenNode) => binOp(a, '-', b)),
          Operator.InfixLeft(char('*'), 20, (a: GreenNode, b: GreenNode) => binOp(a, '*', b)),
          Operator.InfixLeft(char('/'), 20, (a: GreenNode, b: GreenNode) => binOp(a, '/', b)),
          Operator.InfixRight(char('^'), 30, (a: GreenNode, b: GreenNode) => binOp(a, '^', b)),
          Operator.Prefix(char('-'), 40, (a: GreenNode) => unaryOp('-', a))
        )
      )
    exprP
  }

  private def assertSameSource(chainlResult: Result[?, GreenNode], prattResult: Result[?, GreenNode]): Unit = {
    assert(chainlResult.isSuccess, s"chainl1 failed: $chainlResult")
    assert(prattResult.isSuccess, s"pratt failed: $prattResult")
    val chainlTree = chainlResult.toOption.get
    val prattTree = prattResult.toOption.get
    assertEquals(GreenNode.toSource(prattTree), GreenNode.toSource(chainlTree))
    assertEquals(prattTree, chainlTree)
  }

  test("Pratt ≡ chainl1: single digit") {
    assertSameSource(chainlArith.run("5"), prattArith.run("5"))
  }

  test("Pratt ≡ chainl1: left-associative subtraction") {
    assertSameSource(chainlArith.run("5-3-1"), prattArith.run("5-3-1"))
  }

  test("Pratt ≡ chainl1: precedence mix") {
    assertSameSource(chainlArith.run("1+2*3"), prattArith.run("1+2*3"))
    assertSameSource(chainlArith.run("2*3+4"), prattArith.run("2*3+4"))
  }

  test("Pratt ≡ chainl1: deep chain") {
    val input = (1 to 50).map(_ % 10).mkString("+")
    assertSameSource(chainlArith.run(input), prattArith.run(input))
  }

  test("Pratt ≡ chainl1: parenthesized sub-expressions") {
    assertSameSource(chainlArith.run("(1+2)*3"), prattArith.run("(1+2)*3"))
    assertSameSource(chainlArith.run("((2+3)*4)+5"), prattArith.run("((2+3)*4)+5"))
  }

  test("Pratt extended: right-associative power") {
    val tree = prattArithFull.run("2^3^2").toOption.get
    assertEquals(GreenNode.toSource(tree), "2^3^2")
    tree match {
      case GreenNode.Tree(SyntaxKind.Expression, Vector(l, op, r), _) =>
        assertEquals(GreenNode.toSource(l), "2")
        assertEquals(GreenNode.toSource(op), "^")
        assertEquals(GreenNode.toSource(r), "3^2")
      case _ => fail("expected right-nested Expression tree")
    }
  }

  test("Pratt extended: prefix unary minus") {
    val tree = prattArithFull.run("-5+3").toOption.get
    assertEquals(GreenNode.toSource(tree), "-5+3")
    tree match {
      case GreenNode.Tree(SyntaxKind.Expression, Vector(l, op, r), _) =>
        assertEquals(GreenNode.toSource(l), "-5")
        assertEquals(GreenNode.toSource(op), "+")
        assertEquals(GreenNode.toSource(r), "3")
      case _ => fail("expected Expression tree with prefix minus on left")
    }
  }

  test("Pratt extended: prefix binds tighter than any infix") {
    val tree = prattArithFull.run("-2^3").toOption.get
    assertEquals(GreenNode.toSource(tree), "-2^3")
    tree match {
      case GreenNode.Tree(SyntaxKind.Expression, Vector(l, op, r), _) =>
        assertEquals(GreenNode.toSource(l), "-2")
        assertEquals(GreenNode.toSource(op), "^")
        assertEquals(GreenNode.toSource(r), "3")
      case _ => fail("expected (-2)^3 shape")
    }
  }

  test("Pratt: operator absent → single atom") {
    val tree = prattArith.run("7").toOption.get
    assertEquals(GreenNode.toSource(tree), "7")
  }

  test("Pratt: truncated after operator fails") {
    val result = prattArith.run("1+")
    assert(result.isFailure, s"expected failure, got $result")
  }
}
