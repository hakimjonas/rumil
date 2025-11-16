package parser

import munit.FunSuite
import parser.core.{given, *}
import parser.core.GreenNode.*
import parser.core.GreenNodeOps.*

class GreenNodeTests extends FunSuite {

  // ============================================================================
  // Token Node Creation Tests
  // ============================================================================

  test("create token node using Token case") {
    val span: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 4, offset = 3)
    )
    val node = GreenNode.Token(TokenKind.Identifier, "foo", span)

    node match {
      case GreenNode.Token(kind, text, s) =>
        assertEquals(kind, TokenKind.Identifier)
        assertEquals(text, "foo")
        assertEquals(s, span)
      case _ => fail("Expected Token node")
    }
  }

  test("create token node using token helper") {
    val span: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 3, offset = 2)
    )
    val node = token(TokenKind.Number, "42", span)

    node match {
      case GreenNode.Token(kind, text, s) =>
        assertEquals(kind, TokenKind.Number)
        assertEquals(text, "42")
        assertEquals(s, span)
      case _ => fail("Expected Token node")
    }
  }

  test("create identifier token using helper") {
    val span: Span = (
      start = (line = 1, column = 5, offset = 4),
      end = (line = 1, column = 8, offset = 7)
    )
    val node = identifier("bar", span)

    node match {
      case GreenNode.Token(kind, text, _) =>
        assertEquals(kind, TokenKind.Identifier)
        assertEquals(text, "bar")
      case _ => fail("Expected identifier token")
    }
  }

  test("create number token using helper") {
    val span: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 4, offset = 3)
    )
    val node = number("123", span)

    node match {
      case GreenNode.Token(kind, text, _) =>
        assertEquals(kind, TokenKind.Number)
        assertEquals(text, "123")
      case _ => fail("Expected number token")
    }
  }

  test("create keyword token using helper") {
    val span: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 4, offset = 3)
    )
    val node = keyword("val", span)

    node match {
      case GreenNode.Token(kind, text, _) =>
        assertEquals(kind, TokenKind.Keyword)
        assertEquals(text, "val")
      case _ => fail("Expected keyword token")
    }
  }

  // ============================================================================
  // Tree Node Creation Tests
  // ============================================================================

  test("create tree node using Tree case") {
    val span1: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 4, offset = 3)
    )
    val span2: Span = (
      start = (line = 1, column = 5, offset = 4),
      end = (line = 1, column = 7, offset = 6)
    )

    val child1 = identifier("foo", span1)
    val child2 = number("42", span2)
    val node = GreenNode.Tree(SyntaxKind.Expression, Vector(child1, child2))

    node match {
      case GreenNode.Tree(kind, children) =>
        assertEquals(kind, SyntaxKind.Expression)
        assertEquals(children.length, 2)
      case _ => fail("Expected Tree node")
    }
  }

  test("create tree node using tree helper") {
    val span1: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 2, offset = 1)
    )
    val child1 = identifier("x", span1)
    val node = tree(SyntaxKind.Statement, child1)

    node match {
      case GreenNode.Tree(kind, children) =>
        assertEquals(kind, SyntaxKind.Statement)
        assertEquals(children.length, 1)
      case _ => fail("Expected Tree node")
    }
  }

  test("create expression tree using helper") {
    val span1: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 2, offset = 1)
    )
    val span2: Span = (
      start = (line = 1, column = 3, offset = 2),
      end = (line = 1, column = 4, offset = 3)
    )

    val id = identifier("a", span1)
    val num = number("1", span2)
    val node = expression(id, num)

    node match {
      case GreenNode.Tree(kind, children) =>
        assertEquals(kind, SyntaxKind.Expression)
        assertEquals(children.length, 2)
      case _ => fail("Expected expression tree")
    }
  }

  test("create statement tree using helper") {
    val span: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 6, offset = 5)
    )
    val kw = keyword("return", span)
    val node = statement(kw)

    node match {
      case GreenNode.Tree(kind, children) =>
        assertEquals(kind, SyntaxKind.Statement)
        assertEquals(children.length, 1)
      case _ => fail("Expected statement tree")
    }
  }

  test("create empty tree") {
    val node = tree(SyntaxKind.Block)

    node match {
      case GreenNode.Tree(kind, children) =>
        assertEquals(kind, SyntaxKind.Block)
        assertEquals(children.length, 0)
      case _ => fail("Expected tree node")
    }
  }

  // ============================================================================
  // Span Computation Tests
  // ============================================================================

  test("compute span for token node") {
    val expectedSpan: Span = (
      start = (line = 1, column = 5, offset = 4),
      end = (line = 1, column = 8, offset = 7)
    )
    val node = identifier("foo", expectedSpan)
    val actualSpan = span(node)

    assertEquals(actualSpan, expectedSpan)
  }

  test("compute span for tree with single child") {
    val childSpan: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 4, offset = 3)
    )
    val child = number("123", childSpan)
    val node = expression(child)
    val computedSpan = span(node)

    assertEquals(computedSpan.start, childSpan.start)
    assertEquals(computedSpan.end, childSpan.end)
  }

  test("compute span for tree with multiple children") {
    val span1: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 4, offset = 3)
    )
    val span2: Span = (
      start = (line = 1, column = 5, offset = 4),
      end = (line = 1, column = 7, offset = 6)
    )
    val span3: Span = (
      start = (line = 1, column = 8, offset = 7),
      end = (line = 1, column = 10, offset = 9)
    )

    val child1 = identifier("foo", span1)
    val child2 = identifier("bar", span2)
    val child3 = number("42", span3)
    val node = expression(child1, child2, child3)
    val computedSpan = span(node)

    assertEquals(computedSpan.start, span1.start)
    assertEquals(computedSpan.end, span3.end)
  }

  test("compute span for empty tree") {
    val node = tree(SyntaxKind.Block)
    val computedSpan = span(node)

    val expectedSpan: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 1, offset = 0)
    )
    assertEquals(computedSpan, expectedSpan)
  }

  test("compute span for nested tree") {
    val span1: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 2, offset = 1)
    )
    val span2: Span = (
      start = (line = 1, column = 3, offset = 2),
      end = (line = 1, column = 4, offset = 3)
    )

    val innerChild1 = identifier("a", span1)
    val innerChild2 = number("1", span2)
    val innerTree = expression(innerChild1, innerChild2)
    val outerTree = statement(innerTree)
    val computedSpan = span(outerTree)

    assertEquals(computedSpan.start, span1.start)
    assertEquals(computedSpan.end, span2.end)
  }

  // ============================================================================
  // Source Reconstruction Tests (Lossless Property)
  // ============================================================================

  test("reconstruct source from token node") {
    val s: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 6, offset = 5)
    )
    val node = identifier("hello", s)
    val source = toSource(node)

    assertEquals(source, "hello")
  }

  test("reconstruct source from tree with single child") {
    val s: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 3, offset = 2)
    )
    val child = number("42", s)
    val node = expression(child)
    val source = toSource(node)

    assertEquals(source, "42")
  }

  test("reconstruct source from tree with multiple children") {
    val span1: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 4, offset = 3)
    )
    val span2: Span = (
      start = (line = 1, column = 4, offset = 3),
      end = (line = 1, column = 5, offset = 4)
    )
    val span3: Span = (
      start = (line = 1, column = 5, offset = 4),
      end = (line = 1, column = 7, offset = 6)
    )

    val id = identifier("foo", span1)
    val ws = token(TokenKind.Whitespace, " ", span2)
    val num = number("42", span3)
    val node = expression(id, ws, num)
    val source = toSource(node)

    assertEquals(source, "foo 42")
  }

  test("reconstruct source from empty tree") {
    val node = tree(SyntaxKind.Block)
    val source = toSource(node)

    assertEquals(source, "")
  }

  test("reconstruct source from nested tree (lossless round-trip)") {
    val span1: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 4, offset = 3)
    )
    val span2: Span = (
      start = (line = 1, column = 4, offset = 3),
      end = (line = 1, column = 5, offset = 4)
    )
    val span3: Span = (
      start = (line = 1, column = 5, offset = 4),
      end = (line = 1, column = 8, offset = 7)
    )
    val span4: Span = (
      start = (line = 1, column = 8, offset = 7),
      end = (line = 1, column = 9, offset = 8)
    )
    val span5: Span = (
      start = (line = 1, column = 9, offset = 8),
      end = (line = 1, column = 11, offset = 10)
    )

    // Build tree for "foo bar 42"
    val kw = keyword("val", span1)
    val ws1 = token(TokenKind.Whitespace, " ", span2)
    val id = identifier("bar", span3)
    val ws2 = token(TokenKind.Whitespace, " ", span4)
    val num = number("42", span5)

    val innerExpr = expression(id, ws2, num)
    val outerStmt = statement(kw, ws1, innerExpr)
    val source = toSource(outerStmt)

    assertEquals(source, "val bar 42")
  }

  test("lossless property preserves whitespace and comments") {
    val span1: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 6, offset = 5)
    )
    val span2: Span = (
      start = (line = 1, column = 6, offset = 5),
      end = (line = 1, column = 18, offset = 17)
    )
    val span3: Span = (
      start = (line = 1, column = 18, offset = 17),
      end = (line = 1, column = 19, offset = 18)
    )
    val span4: Span = (
      start = (line = 1, column = 19, offset = 18),
      end = (line = 1, column = 22, offset = 21)
    )

    val kw = keyword("hello", span1)
    val comment = token(TokenKind.Comment, "/* world */", span2)
    val ws = token(TokenKind.Whitespace, " ", span3)
    val num = number("123", span4)

    val node = expression(kw, comment, ws, num)
    val source = toSource(node)

    assertEquals(source, "hello/* world */ 123")
  }

  // ============================================================================
  // Tree Traversal Tests
  // ============================================================================

  test("traverse token node") {
    val s: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 4, offset = 3)
    )
    val node = identifier("foo", s)
    var count = 0

    traverse(node) { _ => count += 1 }

    assertEquals(count, 1)
  }

  test("traverse tree with single child") {
    val s: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 3, offset = 2)
    )
    val child = number("42", s)
    val node = expression(child)
    var count = 0

    traverse(node) { _ => count += 1 }

    assertEquals(count, 2) // Tree node + child token
  }

  test("traverse tree with multiple children") {
    val span1: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 2, offset = 1)
    )
    val span2: Span = (
      start = (line = 1, column = 2, offset = 1),
      end = (line = 1, column = 3, offset = 2)
    )
    val span3: Span = (
      start = (line = 1, column = 3, offset = 2),
      end = (line = 1, column = 4, offset = 3)
    )

    val child1 = identifier("a", span1)
    val child2 = identifier("b", span2)
    val child3 = identifier("c", span3)
    val node = expression(child1, child2, child3)
    var count = 0

    traverse(node) { _ => count += 1 }

    assertEquals(count, 4) // Tree + 3 children
  }

  test("traverse nested tree structure") {
    val span1: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 2, offset = 1)
    )
    val span2: Span = (
      start = (line = 1, column = 2, offset = 1),
      end = (line = 1, column = 3, offset = 2)
    )

    val child1 = identifier("x", span1)
    val child2 = number("1", span2)
    val innerTree = expression(child1, child2)
    val outerTree = statement(innerTree)
    var count = 0

    traverse(outerTree) { _ => count += 1 }

    assertEquals(count, 4) // 2 trees + 2 tokens
  }

  test("traverse visits nodes in pre-order") {
    val span1: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 2, offset = 1)
    )
    val span2: Span = (
      start = (line = 1, column = 2, offset = 1),
      end = (line = 1, column = 3, offset = 2)
    )

    val child1 = identifier("a", span1)
    val child2 = number("1", span2)
    val innerTree = expression(child1, child2)
    val outerTree = statement(innerTree)

    var order = List.empty[String]

    traverse(outerTree) {
      case GreenNode.Tree(SyntaxKind.Statement, _) => order = order :+ "stmt"
      case GreenNode.Tree(SyntaxKind.Expression, _) => order = order :+ "expr"
      case GreenNode.Token(TokenKind.Identifier, _, _) => order = order :+ "id"
      case GreenNode.Token(TokenKind.Number, _, _) => order = order :+ "num"
      case _ => ()
    }

    assertEquals(order, List("stmt", "expr", "id", "num"))
  }

  test("traverse can collect node information") {
    val span1: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 4, offset = 3)
    )
    val span2: Span = (
      start = (line = 1, column = 4, offset = 3),
      end = (line = 1, column = 5, offset = 4)
    )
    val span3: Span = (
      start = (line = 1, column = 5, offset = 4),
      end = (line = 1, column = 7, offset = 6)
    )

    val id = identifier("foo", span1)
    val ws = token(TokenKind.Whitespace, " ", span2)
    val num = number("42", span3)
    val node = expression(id, ws, num)

    var texts = List.empty[String]

    traverse(node) {
      case GreenNode.Token(_, text, _) => texts = texts :+ text
      case _ => ()
    }

    assertEquals(texts, List("foo", " ", "42"))
  }

  // ============================================================================
  // Complex Integration Tests
  // ============================================================================

  test("build and traverse complex nested structure") {
    // Simulate: val x = 1 + 2
    val span1: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 4, offset = 3)
    )
    val span2: Span = (
      start = (line = 1, column = 4, offset = 3),
      end = (line = 1, column = 5, offset = 4)
    )
    val span3: Span = (
      start = (line = 1, column = 5, offset = 4),
      end = (line = 1, column = 6, offset = 5)
    )
    val span4: Span = (
      start = (line = 1, column = 6, offset = 5),
      end = (line = 1, column = 7, offset = 6)
    )
    val span5: Span = (
      start = (line = 1, column = 7, offset = 6),
      end = (line = 1, column = 8, offset = 7)
    )
    val span6: Span = (
      start = (line = 1, column = 8, offset = 7),
      end = (line = 1, column = 9, offset = 8)
    )
    val span7: Span = (
      start = (line = 1, column = 9, offset = 8),
      end = (line = 1, column = 10, offset = 9)
    )
    val span8: Span = (
      start = (line = 1, column = 10, offset = 9),
      end = (line = 1, column = 11, offset = 10)
    )
    val span9: Span = (
      start = (line = 1, column = 11, offset = 10),
      end = (line = 1, column = 12, offset = 11)
    )

    val kw = keyword("val", span1)
    val ws1 = token(TokenKind.Whitespace, " ", span2)
    val id = identifier("x", span3)
    val ws2 = token(TokenKind.Whitespace, " ", span4)
    val eq = token(TokenKind.Operator, "=", span5)
    val ws3 = token(TokenKind.Whitespace, " ", span6)
    val num1 = number("1", span7)
    val plus = token(TokenKind.Operator, "+", span8)
    val num2 = number("2", span9)

    val addExpr = expression(num1, plus, num2)
    val valStmt = statement(kw, ws1, id, ws2, eq, ws3, addExpr)

    // Test source reconstruction
    val source = toSource(valStmt)
    assertEquals(source, "val x = 1+2")

    // Test span computation
    val valSpan = span(valStmt)
    assertEquals(valSpan.start, span1.start)
    assertEquals(valSpan.end, span9.end)

    // Test traversal
    var tokenCount = 0
    var treeCount = 0

    traverse(valStmt) {
      case GreenNode.Token(_, _, _) => tokenCount += 1
      case GreenNode.Tree(_, _) => treeCount += 1
    }

    assertEquals(tokenCount, 9)
    assertEquals(treeCount, 2)
  }

  test("verify lossless property with realistic input") {
    // Build tree representing: "hello /* comment */ world"
    val span1: Span = (
      start = (line = 1, column = 1, offset = 0),
      end = (line = 1, column = 6, offset = 5)
    )
    val span2: Span = (
      start = (line = 1, column = 6, offset = 5),
      end = (line = 1, column = 7, offset = 6)
    )
    val span3: Span = (
      start = (line = 1, column = 7, offset = 6),
      end = (line = 1, column = 20, offset = 19)
    )
    val span4: Span = (
      start = (line = 1, column = 20, offset = 19),
      end = (line = 1, column = 21, offset = 20)
    )
    val span5: Span = (
      start = (line = 1, column = 21, offset = 20),
      end = (line = 1, column = 26, offset = 25)
    )

    val id1 = identifier("hello", span1)
    val ws1 = token(TokenKind.Whitespace, " ", span2)
    val comment = token(TokenKind.Comment, "/* comment */", span3)
    val ws2 = token(TokenKind.Whitespace, " ", span4)
    val id2 = identifier("world", span5)

    val node = expression(id1, ws1, comment, ws2, id2)

    val originalSource = "hello /* comment */ world"
    val reconstructed = toSource(node)

    assertEquals(reconstructed, originalSource)
  }
}
