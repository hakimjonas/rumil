package parser

import munit.FunSuite

import parser.core.*
import parser.core.GreenNode.*
import parser.syntax.*

class GreenCacheTests extends FunSuite {

  private def opTok(c: Char): GreenNode =
    Token(TokenKind.Operator, c.toString)

  test("internToken returns eq-equal greens within one parse") {
    // Two `internToken(char('+').map(opTok))` invocations parse "++". The parser runs in a single
    // `run` call, so both invocations see the same parse-scoped GreenCache. The two produced
    // greens are structurally `Token(Operator, "+")` — after interning, they must collapse to one
    // canonical heap instance that `eq` distinguishes from a fresh `opTok('+')`.
    val plus: Parser[ParseError, GreenNode] = internToken(char('+').map(opTok))
    val pair: Parser[ParseError, (GreenNode, GreenNode)] = plus ~ plus

    pair.run("++") match {
      case Result.Success((a, b), _) =>
        assert(a eq b, s"expected interned greens to be eq-equal within one parse; got a=$a b=$b")
      case other => fail(s"expected Success, got $other")
    }
  }

  test("internToken uses a fresh cache per parse — greens from different runs are not eq") {
    val plus: Parser[ParseError, GreenNode] = internToken(char('+').map(opTok))

    val r1 = plus.run("+")
    val r2 = plus.run("+")

    (r1, r2) match {
      case (Result.Success(a, _), Result.Success(b, _)) =>
        // Structural equality: identical values.
        assertEquals(a, b)
        // Reference equality: different heap instances, because each `run` starts with an empty
        // GreenCache. If a future change makes the cache survive across parses, this assertion
        // fires immediately and the parse-scoping contract is restored.
        assert(!(a eq b), s"expected separate runs to produce distinct green instances; got a eq b with a=$a")
      case other => fail(s"expected two Success results, got $other")
    }
  }

  test("internToken does not rewrite structurally-distinct greens") {
    // Interning must not conflate two tokens that differ in kind or text. Parse "+-" with two
    // distinct internToken parsers; the two greens should NOT be eq (different text).
    val plus: Parser[ParseError, GreenNode] = internToken(char('+').map(opTok))
    val minus: Parser[ParseError, GreenNode] = internToken(char('-').map(opTok))

    (plus ~ minus).run("+-") match {
      case Result.Success((a, b), _) =>
        assert(!(a eq b), "'+' and '-' tokens share a cache but are distinct greens")
        assertNotEquals(a, b)
      case other => fail(s"expected Success, got $other")
    }
  }

  // --------------------------------------------------------------------------
  // Tree-level interning (session 3)
  // --------------------------------------------------------------------------

  /** Build `Tree(Expression, [Token(Operator, c)])` — a one-child tree so structural equality
    * recurses into a real child, not an empty vector.
    */
  private def opTree(c: Char): GreenNode =
    GreenNode.treeOfVec(SyntaxKind.Expression, Vector(opTok(c)))

  test("internTree returns eq-equal subtrees within one parse") {
    // Two internTree calls over char('+').map(opTree) consume "++". Both produce the same
    // structural tree; the cache must return the same canonical heap instance for both.
    val plus: Parser[ParseError, GreenNode] = internTree(char('+').map(opTree))
    val pair: Parser[ParseError, (GreenNode, GreenNode)] = plus ~ plus

    pair.run("++") match {
      case Result.Success((a, b), _) =>
        assert(a eq b, s"expected interned subtrees to be eq-equal within one parse; got a=$a b=$b")
      case other => fail(s"expected Success, got $other")
    }
  }

  test("internTree uses a fresh cache per parse — subtrees from different runs are not eq") {
    val plus: Parser[ParseError, GreenNode] = internTree(char('+').map(opTree))

    val r1 = plus.run("+")
    val r2 = plus.run("+")

    (r1, r2) match {
      case (Result.Success(a, _), Result.Success(b, _)) =>
        assertEquals(a, b)
        assert(!(a eq b), s"expected separate runs to produce distinct subtree instances; got a eq b with a=$a")
      case other => fail(s"expected two Success results, got $other")
    }
  }

  test("internTree does not rewrite structurally-distinct subtrees") {
    val plus: Parser[ParseError, GreenNode] = internTree(char('+').map(opTree))
    val minus: Parser[ParseError, GreenNode] = internTree(char('-').map(opTree))

    (plus ~ minus).run("+-") match {
      case Result.Success((a, b), _) =>
        assert(!(a eq b), "Tree(Expression, [+]) and Tree(Expression, [-]) are distinct subtrees")
        assertNotEquals(a, b)
      case other => fail(s"expected Success, got $other")
    }
  }
}
