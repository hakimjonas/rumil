package parser

import munit.FunSuite

import parser.core.*
import parser.core.GreenNode.*
import parser.syntax.*

class TreeOfTests extends FunSuite {

  private def id(s: String): GreenNode =
    Token(TokenKind.Identifier, s)

  test("treeOf produces a green structurally equal to ~/.map composition") {
    // Build two parsers that each consume "ab" and produce
    // Tree(Expression, [Token(Identifier, "a"), Token(Identifier, "b")]) — one via treeOf, the
    // other via the ~/.map composition shape that treeOf is meant to replace. Their outputs must
    // be structurally equal; the primitive is a shape-preserving optimization, not a semantic
    // change.
    val a: Parser[ParseError, GreenNode] = char('a').map(c => id(c.toString))
    val b: Parser[ParseError, GreenNode] = char('b').map(c => id(c.toString))

    val viaTreeOf: Parser[ParseError, GreenNode] =
      treeOf(SyntaxKind.Expression, a, b)

    val viaZipMap: Parser[ParseError, GreenNode] =
      (a ~ b).map { case (x, y) =>
        treeOfVec(SyntaxKind.Expression, Vector(x, y))
      }

    (viaTreeOf.run("ab"), viaZipMap.run("ab")) match {
      case (Result.Success(t1, c1), Result.Success(t2, c2)) =>
        assertEquals(t1, t2, clue = "treeOf output differs from ~/.map output structurally")
        assertEquals(c1, c2, clue = "consumed counts differ")
      case other => fail(s"expected two Success results, got $other")
    }
  }

  test("treeOf with three children preserves order") {
    // Multi-child case: treeOf(kind, a, b, c) must produce children in the argument order, not
    // reversed or shuffled by the foldLeft desugar.
    val a: Parser[ParseError, GreenNode] = char('a').map(c => id(c.toString))
    val b: Parser[ParseError, GreenNode] = char('b').map(c => id(c.toString))
    val c: Parser[ParseError, GreenNode] = char('c').map(s => id(s.toString))

    treeOf(SyntaxKind.Expression, a, b, c).run("abc") match {
      case Result.Success(Tree(SyntaxKind.Expression, kids, _), consumed) =>
        assertEquals(consumed, 3)
        assertEquals(kids.length, 3)
        assertEquals(kids(0), id("a"))
        assertEquals(kids(1), id("b"))
        assertEquals(kids(2), id("c"))
      case other => fail(s"expected Success with Tree(Expression, 3 children), got $other")
    }
  }

  test("treeOf fails if any child parser fails; no partial Tree") {
    // Short-circuit semantics: treeOf must fail the whole composition on any child failure. No
    // Tree should escape with a partial children vector.
    val a: Parser[ParseError, GreenNode] = char('a').map(c => id(c.toString))
    val b: Parser[ParseError, GreenNode] = char('b').map(c => id(c.toString))

    treeOf(SyntaxKind.Expression, a, b).run("ax") match {
      case Result.Failure(_, _) => ()
      case other => fail(s"expected Failure when second child fails mid-parse, got $other")
    }
  }

  test("treeOf with zero children yields an empty Tree") {
    treeOf[TokenKind, SyntaxKind](SyntaxKind.Expression).run("") match {
      case Result.Success(Tree(SyntaxKind.Expression, kids, _), consumed) =>
        assertEquals(consumed, 0)
        assertEquals(kids, Vector.empty[GreenNode])
      case other => fail(s"expected empty Tree(Expression), got $other")
    }
  }

  test("treeOf uses a fresh child vector per parse — no cross-parse contamination") {
    // The Defer-wrapped builder allocation must fire per parse; two independent runs of the same
    // treeOf parser must produce distinct, complete Trees (no shared builder leaking children
    // between parses).
    val a: Parser[ParseError, GreenNode] = char('a').map(c => id(c.toString))
    val b: Parser[ParseError, GreenNode] = char('b').map(c => id(c.toString))
    val p: Parser[ParseError, GreenNode] = treeOf(SyntaxKind.Expression, a, b)

    val r1 = p.run("ab")
    val r2 = p.run("ab")

    (r1, r2) match {
      case (Result.Success(t1, _), Result.Success(t2, _)) =>
        assertEquals(t1, t2)
        // Assert on the children specifically: a leaked builder would produce a 4-element vector
        // on the second parse.
        t2 match {
          case Tree(_, kids, _) => assertEquals(kids.length, 2)
          case other => fail(s"expected Tree, got $other")
        }
      case other => fail(s"expected two Success results, got $other")
    }
  }
}
