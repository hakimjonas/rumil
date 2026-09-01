package parser

import munit.FunSuite

import parser.core.*
import parser.core.DefaultLanguage.validate
import parser.core.GreenNode.*
import parser.core.GreenNodeOps.*

class RedTreeTests extends FunSuite {

  test("RedTree wraps a green at offset 0, no parent") {
    val green = identifier("foo")
    val red = RedTree(green)

    assertEquals(red.offset, 0)
    assertEquals(red.green, green)
    assertEquals(red.parent, None)
  }

  test("span for a token is [offset, offset + text.length)") {
    val red = RedTree(identifier("foo"))
    assertEquals(red.span.start.offset, 0)
    assertEquals(red.span.end.offset, 3)
  }

  test("span for a tree covers all children's text") {
    val node = expression(identifier("foo"), token(TokenKind.Whitespace, " "), number("42"))
    val red = RedTree(node)
    assertEquals(red.span.start.offset, 0)
    assertEquals(red.span.end.offset, 6)
  }

  test("text reconstructs the source from the green") {
    assertEquals(RedTree(identifier("hello")).text, "hello")
  }

  test("length agrees with textLength") {
    val red = RedTree(expression(identifier("ab"), number("3")))
    assertEquals(red.length, 3)
  }

  test("token children is empty") {
    assertEquals(RedTree(identifier("foo")).children.length, 0)
  }

  test("tree children count matches green children") {
    val red = RedTree(expression(identifier("a"), number("1")))
    assertEquals(red.children.length, 2)
  }

  test("child offsets reflect cumulative text lengths") {
    val node = expression(identifier("foo"), token(TokenKind.Whitespace, " "), number("42"))
    val red = RedTree(node)
    val kids = red.children
    assertEquals(kids(0).offset, 0)
    assertEquals(kids(1).offset, 3)
    assertEquals(kids(2).offset, 4)
  }

  test("each child carries its parent reference") {
    val red = RedTree(expression(identifier("a"), number("1")))
    red.children.foreach { child =>
      assertEquals(child.parent, Some(red))
    }
  }

  test("parentNode walks back up") {
    val red = RedTree(expression(identifier("x")))
    assertEquals(red.children(0).parentNode, Some(red))
  }

  test("root has no parent") {
    assertEquals(RedTree(identifier("foo")).parentNode, None)
  }

  test("nextSibling returns adjacent sibling") {
    val red = RedTree(expression(identifier("a"), identifier("b"), identifier("c")))
    val kids = red.children
    assertEquals(kids(0).nextSibling, Some(kids(1)))
    assertEquals(kids(1).nextSibling, Some(kids(2)))
  }

  test("last child has no nextSibling") {
    val red = RedTree(expression(identifier("a"), identifier("b")))
    assertEquals(red.children.last.nextSibling, None)
  }

  test("prevSibling returns previous sibling") {
    val red = RedTree(expression(identifier("a"), identifier("b"), identifier("c")))
    val kids = red.children
    assertEquals(kids(1).prevSibling, Some(kids(0)))
    assertEquals(kids(2).prevSibling, Some(kids(1)))
  }

  test("first child has no prevSibling") {
    val red = RedTree(expression(identifier("a"), identifier("b")))
    assertEquals(red.children.head.prevSibling, None)
  }

  test("pathFromRoot and siblings disambiguate structurally-equal siblings by childIndex") {
    // Regression test: pins the invariant that killed session 2's interning attempt. If the green
    // nodes at indices 0 and 2 are structurally equal (e.g. both `number("1")` tokens) — which is
    // exactly what a green-cache would produce after interning — the old `_.green eq target.green`
    // key returned the same index for both. `childIndex` distinguishes them.
    val root = expression(number("1"), token(TokenKind.Operator, "+"), number("1"))
    val red = RedTree(root)
    val kids = red.children

    // Sanity: the two number siblings hold structurally-equal greens. If a caller swapped in an
    // interning cache, these would also be `eq`-equal; this test holds either way.
    assertEquals(kids(0).green, kids(2).green)

    // childIndex reflects position in parent.children, not green structure.
    assertEquals(kids(0).childIndex, 0)
    assertEquals(kids(1).childIndex, 1)
    assertEquals(kids(2).childIndex, 2)

    // pathFromRoot on the second structurally-equal sibling returns its own index, not the first's.
    assertEquals(kids(2).pathFromRoot, Vector(2))

    // nextSibling / prevSibling walk by childIndex, so they step past the duplicate instead of
    // looping back to the first match.
    assertEquals(kids(0).nextSibling.map(_.childIndex), Some(1))
    assertEquals(kids(1).nextSibling.map(_.childIndex), Some(2))
    assertEquals(kids(2).nextSibling, None)
    assertEquals(kids(2).prevSibling.map(_.childIndex), Some(1))
    assertEquals(kids(1).prevSibling.map(_.childIndex), Some(0))
  }

  test("descendants are all nodes under the root, in pre-order") {
    val inner = expression(identifier("a"), number("1"))
    val red = RedTree(statement(inner))
    val d = red.descendants
    assertEquals(d.length, 3)
    assert(d(0).isTree)
    assert(d(1).isToken)
    assert(d(2).isToken)
  }

  test("token has zero descendants") {
    assertEquals(RedTree(identifier("foo")).descendants.length, 0)
  }

  test("nodeAt finds a token at its exact offset") {
    val red = RedTree(expression(identifier("foo"), token(TokenKind.Whitespace, " "), number("42")))
    val atStart = red.nodeAt(0)
    assert(atStart.isDefined)
    assertEquals(atStart.get.offset, 0)
    val atFour = red.nodeAt(4)
    assert(atFour.isDefined)
    assertEquals(atFour.get.offset, 4)
  }

  test("nodeAt returns None for out-of-range offsets") {
    val red = RedTree(identifier("foo"))
    assertEquals(red.nodeAt(-1), None)
    assertEquals(red.nodeAt(10), None)
  }

  test("nodeAt reaches the deepest token") {
    val red = RedTree(statement(expression(identifier("a"), number("1"))))
    val deepest = red.nodeAt(0)
    assert(deepest.isDefined)
    assert(deepest.get.isToken)
  }

  test("nodeAt returns None at end-of-file (half-open semantics)") {
    val red = RedTree(expression(identifier("foo")))
    assertEquals(red.nodeAt(3), None)
  }

  test("nodeEnclosingRange returns root for end-of-file insertion") {
    val red = RedTree(expression(identifier("foo")))
    val found = red.nodeEnclosingRange(3, 3)
    assert(found.isDefined)
    assertEquals(found.get.offset, 0)
    assertEquals(found.get.length, 3)
  }

  test("nodeEnclosingRange descends into the smallest covering subtree") {
    val red = RedTree(expression(identifier("foo"), token(TokenKind.Whitespace, " "), number("42")))
    val found = red.nodeEnclosingRange(4, 6)
    assert(found.isDefined)
    assertEquals(found.get.text, "42")
  }

  test("nodeEnclosingRange returns None for out-of-bounds ranges") {
    val red = RedTree(identifier("foo"))
    assertEquals(red.nodeEnclosingRange(-1, 2), None)
    assertEquals(red.nodeEnclosingRange(1, 10), None)
  }

  test("validate flags an Error token") {
    val red = RedTree(expression(identifier("foo"), token(TokenKind.Error, "err")))
    assertEquals(red.validate.length, 1)
  }

  test("validate returns empty for a clean tree") {
    val red = RedTree(expression(identifier("foo"), number("42")))
    assertEquals(red.validate.length, 0)
  }

  test("validate collects multiple Error tokens") {
    val red = RedTree(
      expression(token(TokenKind.Error, "e1"), identifier("x"), token(TokenKind.Error, "e2"))
    )
    assertEquals(red.validate.length, 2)
  }

  test("kind returns TokenK for a token") {
    RedTree(identifier("foo")).kind match {
      case NodeKind.TokenK(TokenKind.Identifier) => ()
      case other => fail(s"expected TokenK(Identifier), got $other")
    }
  }

  test("kind returns TreeK for a tree") {
    RedTree(expression(identifier("x"))).kind match {
      case NodeKind.TreeK(SyntaxKind.Expression) => ()
      case other => fail(s"expected TreeK(Expression), got $other")
    }
  }

  test("kind returns MissingK for a Missing node") {
    RedTree(GreenNode.Missing(TokenKind.RightParen)).kind match {
      case NodeKind.MissingK(TokenKind.RightParen) => ()
      case other => fail(s"expected MissingK(RightParen), got $other")
    }
  }

  test("kind returns UnexpectedK for an Unexpected node") {
    RedTree(GreenNode.unexpectedOfVec(Vector(identifier("junk")))).kind match {
      case NodeKind.UnexpectedK() => ()
      case other => fail(s"expected UnexpectedK, got $other")
    }
  }

  // -------------------------------------------------------------------------
  // Missing / Unexpected at the red layer
  // -------------------------------------------------------------------------

  test("Missing leaf has zero length and a zero-width span at its offset") {
    val tree = expression(number("1"), Missing(TokenKind.RightParen))
    val red = RedTree(tree)
    val missing = red.children(1)
    assertEquals(missing.length, 0)
    assertEquals(missing.span.start.offset, 1)
    assertEquals(missing.span.end.offset, 1)
  }

  test("Missing is classified correctly by the predicates") {
    val red = RedTree(Missing(TokenKind.RightParen))
    assert(red.isMissing)
    assert(!red.isUnexpected)
    assert(!red.isToken)
    assert(!red.isTree)
    assertEquals(red.missingKind, Some(TokenKind.RightParen))
    assertEquals(red.tokenKind, None)
    assertEquals(red.syntaxKind, None)
  }

  test("Unexpected is classified correctly and exposes its children") {
    val red = RedTree(unexpectedOfVec(Vector(identifier("junk"), number("42"))))
    assert(red.isUnexpected)
    assert(!red.isMissing)
    assert(!red.isToken)
    assert(!red.isTree)
    assertEquals(red.children.length, 2)
    assertEquals(red.children(0).text, "junk")
    assertEquals(red.children(1).text, "42")
  }

  test("siblings skip over a zero-width Missing when walking by offset") {
    // A tree with two tokens of offset 0 would defeat the old offset-based identity;
    // verify the same invariant now also holds when one of them is Missing.
    val tree0 = expression(Missing(TokenKind.LeftParen), number("1"))
    val red = RedTree(tree0)
    assertEquals(red.children.length, 2)
    assertEquals(red.children(0).offset, 0)
    assertEquals(red.children(1).offset, 0)
    assertEquals(red.children(0).nextSibling.map(_.text), Some("1"))
    assertEquals(red.children(1).prevSibling.map(_.missingKind), Some(Some(TokenKind.LeftParen)))
  }

  test("findReparseRegion walks past a zero-width Missing at the edit boundary") {
    // A subtle scenario: a Missing sibling at the same offset as a real token. An editor
    // insertion at that shared offset hits nodeEnclosingRange, which does iterate-in-order and
    // may first visit the Missing. The ancestor walk in findReparseRegion is expected to step
    // past the Missing (which has no syntaxKind) and land on the real reparsable ancestor.
    val inner = expression(Missing(TokenKind.LeftParen), number("1"))
    val root = expression(inner, token(TokenKind.Whitespace, " "), number("2"))
    val red = RedTree(root)
    val reparseableKinds = Set(SyntaxKind.Expression)
    // Insertion at offset 0 — shared between root.Expression, inner.Expression, and the Missing.
    // The reparse region should land on the outermost (or inner) Expression, not the Missing.
    val region = red.findReparseRegion(0, 0, reparseableKinds)
    assert(region.isDefined, "expected a reparse region at offset 0")
    assertEquals(
      region.get.syntaxKind,
      Some(SyntaxKind.Expression),
      clue = "expected findReparseRegion to walk past the zero-width Missing and land on an Expression"
    )
  }

  test("validate reports Missing and Unexpected as parse errors") {
    val tree0 = expression(
      token(TokenKind.LeftParen, "("),
      number("1"),
      Missing(TokenKind.RightParen),
      unexpectedOfVec(Vector(identifier("junk")))
    )
    val errors = RedTree(tree0).validate
    assertEquals(errors.length, 2)
  }

  test("isToken / isTree are opposites") {
    assert(RedTree(identifier("foo")).isToken)
    assert(!RedTree(identifier("foo")).isTree)
    assert(RedTree(expression(identifier("x"))).isTree)
    assert(!RedTree(expression(identifier("x"))).isToken)
  }
}
