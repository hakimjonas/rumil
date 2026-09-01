package parser

import munit.FunSuite

import parser.core.*
import parser.core.DefaultLanguage.validate
import parser.core.GreenNode.*
import parser.core.GreenNodeOps.*
import parser.runtime.run
import parser.syntax.*

/** Tests for the `expectToken` combinator and the `Missing` / `Unexpected` green cases.
  *
  * The combinator's job is to keep the parse going when a required token isn't there, leaving a
  * zero-width `Missing(kind)` placeholder in the tree and surfacing the original errors through a
  * `Result.Partial`. These tests verify:
  *   - success path: `expectToken` returns the inner parser's token unchanged
  *   - failure path: `expectToken` produces a `Partial` whose value is `Missing(kind)`
  *   - lossless invariant: `toSource` of the containing tree equals the original input
  *   - error propagation: the original errors are reachable via `Partial.errors`
  */
class ErrorNodeTests extends FunSuite {

  private val closeParen: Parser[ParseError, GreenNode] =
    char(')').map(c => Token(TokenKind.RightParen, c.toString))

  private val openParen: Parser[ParseError, GreenNode] =
    char('(').map(c => Token(TokenKind.LeftParen, c.toString))

  private val digitToken: Parser[ParseError, GreenNode] =
    digit.map(c => Token(TokenKind.Number, c.toString))

  /** `( digit expected-) ` wrapped in an Expression tree. */
  private val parenExpr: Parser[ParseError, GreenNode] =
    (openParen ~ digitToken ~ expectToken(TokenKind.RightParen, closeParen)).map { case ((o, d), c) =>
      treeOfVec(SyntaxKind.Expression, Vector(o, d, c))
    }

  test("expectToken(success path) returns the inner token") {
    run(parenExpr, "(5)") match {
      case Result.Success(tree, _) =>
        assertEquals(toSource(tree), "(5)")
        tree match {
          case Tree(SyntaxKind.Expression, Vector(_, _, closeT), _) =>
            closeT match {
              case Token(TokenKind.RightParen, ")") => ()
              case other => fail(s"expected RightParen token, got $other")
            }
          case other => fail(s"expected Expression tree, got $other")
        }
      case other => fail(s"expected Success, got $other")
    }
  }

  test("expectToken(failure path) produces Partial with Missing placeholder") {
    run(parenExpr, "(5") match {
      case Result.Partial(tree, errors, _) =>
        assertEquals(toSource(tree), "(5")
        assert(errors.nonEmpty, "expected the inner parser's errors to be surfaced")
        tree match {
          case Tree(SyntaxKind.Expression, Vector(_, _, missing), _) =>
            missing match {
              case Missing(TokenKind.RightParen) => ()
              case other => fail(s"expected Missing(RightParen), got $other")
            }
          case other => fail(s"expected Expression tree, got $other")
        }
      case other => fail(s"expected Partial, got $other")
    }
  }

  test("expectToken preserves the lossless invariant with a Missing placeholder") {
    run(parenExpr, "(5") match {
      case Result.Partial(tree, _, _) =>
        // Missing contributes 0 chars; reconstructed source matches original input exactly.
        assertEquals(toSource(tree), "(5")
      case other => fail(s"expected Partial, got $other")
    }
  }

  test("Missing node is present in the RedTree descendants at the right offset") {
    val tree = run(parenExpr, "(5") match {
      case Result.Partial(t, _, _) => t
      case other => fail(s"expected Partial, got $other")
    }
    val red = RedTree(tree)
    val missingNode = red.descendants.find(_.isMissing)
    assert(missingNode.isDefined, "expected a Missing descendant")
    // Missing is placed where the close-paren should have been: right after "(5", offset 2.
    assertEquals(missingNode.get.offset, 2)
    assertEquals(missingNode.get.length, 0)
    assertEquals(missingNode.get.missingKind, Some(TokenKind.RightParen))
  }

  test("expectToken on a successful inner does not allocate a Missing") {
    run(parenExpr, "(7)") match {
      case Result.Success(tree, _) =>
        var sawMissing = false
        traverse(tree) {
          case Missing(_) => sawMissing = true
          case _ => ()
        }
        assert(!sawMissing, "Success path must not contain a Missing placeholder")
      case other => fail(s"expected Success, got $other")
    }
  }

  test("validate on a tree with a Missing node reports one error") {
    run(parenExpr, "(5") match {
      case Result.Partial(tree, _, _) =>
        val red = RedTree(tree)
        assertEquals(red.validate.length, 1)
      case other => fail(s"expected Partial, got $other")
    }
  }
}
