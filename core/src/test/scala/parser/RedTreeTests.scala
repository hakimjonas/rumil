package parser

import munit.FunSuite
import parser.core.GreenNode._
import parser.core.GreenNodeOps._
import parser.core.{_, given}

class RedTreeTests extends FunSuite {

  // Helper to create spans
  def mkSpan(
    startLine: Int,
    startCol: Int,
    startOff: Int,
    endLine: Int,
    endCol: Int,
    endOff: Int): Span =
    (
      start = (line = startLine, column = startCol, offset = startOff),
      end = (line = endLine, column = endCol, offset = endOff)
    )

  // ============================================================================
  // RedTree Basic Construction Tests
  // ============================================================================

  test("RedTree wraps GreenNode at offset 0") {
    val span  = mkSpan(1, 1, 0, 1, 4, 3)
    val green = identifier("foo", span)
    val red   = RedTree(green)

    assertEquals(red.offset, 0)
    assertEquals(red.green, green)
    assertEquals(red.parent, None)
  }

  test("RedTree computes correct span for token") {
    val span  = mkSpan(1, 1, 0, 1, 4, 3)
    val green = identifier("foo", span)
    val red   = RedTree(green)

    assertEquals(red.span.start.offset, 0)
    assertEquals(red.span.end.offset, 3)
  }

  test("RedTree computes correct span for tree with single child") {
    val childSpan = mkSpan(1, 1, 0, 1, 3, 2)
    val child     = number("42", childSpan)
    val green     = expression(child)
    val red       = RedTree(green)

    assertEquals(red.span.start.offset, 0)
    assertEquals(red.span.end.offset, 2)
  }

  test("RedTree computes correct span for tree with multiple children") {
    val span1 = mkSpan(1, 1, 0, 1, 4, 3)
    val span2 = mkSpan(1, 4, 3, 1, 5, 4)
    val span3 = mkSpan(1, 5, 4, 1, 7, 6)

    val child1 = identifier("foo", span1)
    val child2 = token(TokenKind.Whitespace, " ", span2)
    val child3 = number("42", span3)
    val green  = expression(child1, child2, child3)
    val red    = RedTree(green)

    assertEquals(red.span.start.offset, 0)
    assertEquals(red.span.end.offset, 6)
  }

  test("RedTree returns correct text") {
    val span  = mkSpan(1, 1, 0, 1, 6, 5)
    val green = identifier("hello", span)
    val red   = RedTree(green)

    assertEquals(red.text, "hello")
  }

  test("RedTree computes correct length") {
    val span  = mkSpan(1, 1, 0, 1, 4, 3)
    val green = identifier("foo", span)
    val red   = RedTree(green)

    assertEquals(red.length, 3)
  }

  // ============================================================================
  // RedTree Children Tests
  // ============================================================================

  test("RedTree token has no children") {
    val span  = mkSpan(1, 1, 0, 1, 4, 3)
    val green = identifier("foo", span)
    val red   = RedTree(green)

    assertEquals(red.children.length, 0)
  }

  test("RedTree tree has correct number of children") {
    val span1 = mkSpan(1, 1, 0, 1, 2, 1)
    val span2 = mkSpan(1, 2, 1, 1, 3, 2)

    val child1 = identifier("a", span1)
    val child2 = number("1", span2)
    val green  = expression(child1, child2)
    val red    = RedTree(green)

    assertEquals(red.children.length, 2)
  }

  test("RedTree children have correct offsets") {
    val span1 = mkSpan(1, 1, 0, 1, 4, 3)
    val span2 = mkSpan(1, 4, 3, 1, 5, 4)
    val span3 = mkSpan(1, 5, 4, 1, 7, 6)

    val child1 = identifier("foo", span1)
    val child2 = token(TokenKind.Whitespace, " ", span2)
    val child3 = number("42", span3)
    val green  = expression(child1, child2, child3)
    val red    = RedTree(green)

    val children = red.children
    assertEquals(children(0).offset, 0)
    assertEquals(children(1).offset, 3)
    assertEquals(children(2).offset, 4)
  }

  test("RedTree children have correct parent reference") {
    val span1 = mkSpan(1, 1, 0, 1, 2, 1)
    val span2 = mkSpan(1, 2, 1, 1, 3, 2)

    val child1 = identifier("a", span1)
    val child2 = number("1", span2)
    val green  = expression(child1, child2)
    val red    = RedTree(green)

    red.children.foreach { child =>
      assert(child.parent.isDefined)
      assertEquals(child.parent.get, red)
    }
  }

  // ============================================================================
  // RedTree Navigation Tests
  // ============================================================================

  test("RedTree parentNode returns parent") {
    val span1 = mkSpan(1, 1, 0, 1, 2, 1)
    val child = identifier("x", span1)
    val green = expression(child)
    val red   = RedTree(green)

    val childRed = red.children(0)
    assertEquals(childRed.parentNode, Some(red))
  }

  test("RedTree root has no parent") {
    val span  = mkSpan(1, 1, 0, 1, 4, 3)
    val green = identifier("foo", span)
    val red   = RedTree(green)

    assertEquals(red.parentNode, None)
  }

  test("RedTree nextSibling returns next sibling") {
    val span1 = mkSpan(1, 1, 0, 1, 2, 1)
    val span2 = mkSpan(1, 2, 1, 1, 3, 2)
    val span3 = mkSpan(1, 3, 2, 1, 4, 3)

    val child1 = identifier("a", span1)
    val child2 = identifier("b", span2)
    val child3 = identifier("c", span3)
    val green  = expression(child1, child2, child3)
    val red    = RedTree(green)

    val children = red.children
    assertEquals(children(0).nextSibling, Some(children(1)))
    assertEquals(children(1).nextSibling, Some(children(2)))
  }

  test("RedTree last child has no next sibling") {
    val span1 = mkSpan(1, 1, 0, 1, 2, 1)
    val span2 = mkSpan(1, 2, 1, 1, 3, 2)

    val child1 = identifier("a", span1)
    val child2 = identifier("b", span2)
    val green  = expression(child1, child2)
    val red    = RedTree(green)

    val lastChild = red.children.last
    assertEquals(lastChild.nextSibling, None)
  }

  test("RedTree prevSibling returns previous sibling") {
    val span1 = mkSpan(1, 1, 0, 1, 2, 1)
    val span2 = mkSpan(1, 2, 1, 1, 3, 2)
    val span3 = mkSpan(1, 3, 2, 1, 4, 3)

    val child1 = identifier("a", span1)
    val child2 = identifier("b", span2)
    val child3 = identifier("c", span3)
    val green  = expression(child1, child2, child3)
    val red    = RedTree(green)

    val children = red.children
    assertEquals(children(1).prevSibling, Some(children(0)))
    assertEquals(children(2).prevSibling, Some(children(1)))
  }

  test("RedTree first child has no previous sibling") {
    val span1 = mkSpan(1, 1, 0, 1, 2, 1)
    val span2 = mkSpan(1, 2, 1, 1, 3, 2)

    val child1 = identifier("a", span1)
    val child2 = identifier("b", span2)
    val green  = expression(child1, child2)
    val red    = RedTree(green)

    val firstChild = red.children.head
    assertEquals(firstChild.prevSibling, None)
  }

  // ============================================================================
  // RedTree Descendants Tests
  // ============================================================================

  test("RedTree descendants includes all descendants") {
    val span1 = mkSpan(1, 1, 0, 1, 2, 1)
    val span2 = mkSpan(1, 2, 1, 1, 3, 2)

    val innerChild1 = identifier("a", span1)
    val innerChild2 = number("1", span2)
    val innerTree   = expression(innerChild1, innerChild2)
    val outerTree   = statement(innerTree)
    val red         = RedTree(outerTree)

    val descendants = red.descendants
    // Should include: innerTree, innerChild1, innerChild2
    assertEquals(descendants.length, 3)
  }

  test("RedTree descendants are in pre-order") {
    val span1 = mkSpan(1, 1, 0, 1, 2, 1)
    val span2 = mkSpan(1, 2, 1, 1, 3, 2)

    val innerChild1 = identifier("a", span1)
    val innerChild2 = number("1", span2)
    val innerTree   = expression(innerChild1, innerChild2)
    val outerTree   = statement(innerTree)
    val red         = RedTree(outerTree)

    val descendants = red.descendants
    // First should be the inner tree, then its children
    assert(descendants(0).isTree)
    assert(descendants(1).isToken)
    assert(descendants(2).isToken)
  }

  test("RedTree token has no descendants") {
    val span  = mkSpan(1, 1, 0, 1, 4, 3)
    val green = identifier("foo", span)
    val red   = RedTree(green)

    assertEquals(red.descendants.length, 0)
  }

  // ============================================================================
  // RedTree nodeAt Tests
  // ============================================================================

  test("RedTree nodeAt finds node at offset") {
    val span1 = mkSpan(1, 1, 0, 1, 4, 3)
    val span2 = mkSpan(1, 4, 3, 1, 5, 4)
    val span3 = mkSpan(1, 5, 4, 1, 7, 6)

    val child1 = identifier("foo", span1)
    val child2 = token(TokenKind.Whitespace, " ", span2)
    val child3 = number("42", span3)
    val green  = expression(child1, child2, child3)
    val red    = RedTree(green)

    val nodeAt0 = red.nodeAt(0)
    assert(nodeAt0.isDefined)
    assertEquals(nodeAt0.get.offset, 0)

    val nodeAt4 = red.nodeAt(4)
    assert(nodeAt4.isDefined)
    assertEquals(nodeAt4.get.offset, 4)
  }

  test("RedTree nodeAt returns None for out-of-bounds offset") {
    val span  = mkSpan(1, 1, 0, 1, 4, 3)
    val green = identifier("foo", span)
    val red   = RedTree(green)

    val nodeAtNegative = red.nodeAt(-1)
    assertEquals(nodeAtNegative, None)

    val nodeAtBeyond = red.nodeAt(10)
    assertEquals(nodeAtBeyond, None)
  }

  test("RedTree nodeAt finds deepest node") {
    val span1 = mkSpan(1, 1, 0, 1, 2, 1)
    val span2 = mkSpan(1, 2, 1, 1, 3, 2)

    val innerChild1 = identifier("a", span1)
    val innerChild2 = number("1", span2)
    val innerTree   = expression(innerChild1, innerChild2)
    val outerTree   = statement(innerTree)
    val red         = RedTree(outerTree)

    val nodeAt0 = red.nodeAt(0)
    assert(nodeAt0.isDefined)
    // Should find the innermost token, not the trees
    assert(nodeAt0.get.isToken)
  }

  // ============================================================================
  // RedTree Validation Tests
  // ============================================================================

  test("RedTree validate finds error tokens") {
    val span1 = mkSpan(1, 1, 0, 1, 4, 3)
    val span2 = mkSpan(1, 4, 3, 1, 7, 6)

    val child1 = identifier("foo", span1)
    val child2 = token(TokenKind.Error, "err", span2)
    val green  = expression(child1, child2)
    val red    = RedTree(green)

    val errors = red.validate
    assertEquals(errors.length, 1)
  }

  test("RedTree validate returns empty list for valid tree") {
    val span1 = mkSpan(1, 1, 0, 1, 4, 3)
    val span2 = mkSpan(1, 4, 3, 1, 7, 6)

    val child1 = identifier("foo", span1)
    val child2 = number("42", span2)
    val green  = expression(child1, child2)
    val red    = RedTree(green)

    val errors = red.validate
    assertEquals(errors.length, 0)
  }

  test("RedTree validate finds multiple error tokens") {
    val span1 = mkSpan(1, 1, 0, 1, 2, 1)
    val span2 = mkSpan(1, 2, 1, 1, 3, 2)
    val span3 = mkSpan(1, 3, 2, 1, 4, 3)

    val child1 = token(TokenKind.Error, "e1", span1)
    val child2 = identifier("x", span2)
    val child3 = token(TokenKind.Error, "e2", span3)
    val green  = expression(child1, child2, child3)
    val red    = RedTree(green)

    val errors = red.validate
    assertEquals(errors.length, 2)
  }

  // ============================================================================
  // RedTree Kind Tests
  // ============================================================================

  test("RedTree kind returns Left for token") {
    val span  = mkSpan(1, 1, 0, 1, 4, 3)
    val green = identifier("foo", span)
    val red   = RedTree(green)

    red.kind match {
      case Left(TokenKind.Identifier) => // OK
      case _                          => fail("Expected Left(TokenKind.Identifier)")
    }
  }

  test("RedTree kind returns Right for tree") {
    val span  = mkSpan(1, 1, 0, 1, 2, 1)
    val child = identifier("x", span)
    val green = expression(child)
    val red   = RedTree(green)

    red.kind match {
      case Right(SyntaxKind.Expression) => // OK
      case _                            => fail("Expected Right(SyntaxKind.Expression)")
    }
  }

  test("RedTree isToken correctly identifies token") {
    val span  = mkSpan(1, 1, 0, 1, 4, 3)
    val green = identifier("foo", span)
    val red   = RedTree(green)

    assert(red.isToken)
    assert(!red.isTree)
  }

  test("RedTree isTree correctly identifies tree") {
    val span  = mkSpan(1, 1, 0, 1, 2, 1)
    val child = identifier("x", span)
    val green = expression(child)
    val red   = RedTree(green)

    assert(red.isTree)
    assert(!red.isToken)
  }
}
