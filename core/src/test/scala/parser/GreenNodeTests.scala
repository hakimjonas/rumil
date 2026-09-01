package parser

import munit.FunSuite

import parser.core.*
import parser.core.GreenNode.*
import parser.core.GreenNodeOps.*

class GreenNodeTests extends FunSuite {

  test("Token case carries kind and text") {
    val node = Token(TokenKind.Identifier, "foo")
    node match {
      case Token(kind, text) =>
        assertEquals(kind, TokenKind.Identifier)
        assertEquals(text, "foo")
      case _ => fail("expected Token")
    }
  }

  test("token helper and kind-specific helpers produce matching tokens") {
    assertEquals(token(TokenKind.Number, "42"), Token(TokenKind.Number, "42"))
    assertEquals(identifier("bar"), Token(TokenKind.Identifier, "bar"))
    assertEquals(number("123"), Token(TokenKind.Number, "123"))
    assertEquals(keyword("val"), Token(TokenKind.Keyword, "val"))
  }

  test("Tree case and tree helper agree on structure") {
    val id = identifier("x")
    val num = number("1")
    val viaCase = treeOfVec(SyntaxKind.Expression, Vector(id, num))
    val viaHelper = tree(SyntaxKind.Expression, id, num)
    assertEquals(viaCase, viaHelper)
  }

  test("expression and statement helpers set the right SyntaxKind") {
    val id = identifier("a")
    val num = number("1")
    expression(id, num) match {
      case Tree(kind, children, _) =>
        assertEquals(kind, SyntaxKind.Expression)
        assertEquals(children.length, 2)
      case _ => fail("expected Tree")
    }
    statement(keyword("return")) match {
      case Tree(kind, children, _) =>
        assertEquals(kind, SyntaxKind.Statement)
        assertEquals(children.length, 1)
      case _ => fail("expected Tree")
    }
  }

  test("empty tree is allowed and has zero children") {
    tree(SyntaxKind.Block) match {
      case Tree(kind, children, _) =>
        assertEquals(kind, SyntaxKind.Block)
        assertEquals(children.length, 0)
      case _ => fail("expected Tree")
    }
  }

  test("textLength of a token is text.length") {
    assertEquals(textLength(identifier("foo")), 3)
    assertEquals(textLength(token(TokenKind.Whitespace, " ")), 1)
  }

  test("textLength of a tree sums children") {
    val node = expression(identifier("foo"), token(TokenKind.Whitespace, " "), number("42"))
    assertEquals(textLength(node), 6)
  }

  test("textLength of empty tree is 0") {
    assertEquals(textLength(tree(SyntaxKind.Block)), 0)
  }

  test("textLength of nested tree is the full source length") {
    val inner = expression(identifier("a"), number("1"))
    val outer = statement(inner)
    assertEquals(textLength(outer), 2)
  }

  test("toSource of a token is its text") {
    assertEquals(toSource(identifier("hello")), "hello")
  }

  test("toSource of a tree concatenates children") {
    val node = expression(identifier("foo"), token(TokenKind.Whitespace, " "), number("42"))
    assertEquals(toSource(node), "foo 42")
  }

  test("toSource of empty tree is empty string") {
    assertEquals(toSource(tree(SyntaxKind.Block)), "")
  }

  test("toSource round-trips a nested structure") {
    // val bar 42
    val tree0 = statement(
      keyword("val"),
      token(TokenKind.Whitespace, " "),
      expression(identifier("bar"), token(TokenKind.Whitespace, " "), number("42"))
    )
    assertEquals(toSource(tree0), "val bar 42")
  }

  test("toSource preserves whitespace and comments losslessly") {
    val node = expression(
      keyword("hello"),
      token(TokenKind.Comment, "/* world */"),
      token(TokenKind.Whitespace, " "),
      number("123")
    )
    assertEquals(toSource(node), "hello/* world */ 123")
  }

  test("traverse visits a single token once") {
    var count = 0
    traverse(identifier("foo"))(_ => count += 1)
    assertEquals(count, 1)
  }

  test("traverse visits tree + child in pre-order") {
    var count = 0
    traverse(expression(number("42")))(_ => count += 1)
    assertEquals(count, 2)
  }

  test("traverse visits a nested tree in pre-order") {
    val node = statement(expression(identifier("a"), number("1")))
    var order = List.empty[String]
    traverse(node) {
      case Tree(SyntaxKind.Statement, _, _) => order = order :+ "stmt"
      case Tree(SyntaxKind.Expression, _, _) => order = order :+ "expr"
      case Token(TokenKind.Identifier, _) => order = order :+ "id"
      case Token(TokenKind.Number, _) => order = order :+ "num"
      case _ => ()
    }
    assertEquals(order, List("stmt", "expr", "id", "num"))
  }

  test("traverse can collect token text in source order") {
    val node = expression(identifier("foo"), token(TokenKind.Whitespace, " "), number("42"))
    var texts = List.empty[String]
    traverse(node) {
      case Token(_, text) => texts = texts :+ text
      case _ => ()
    }
    assertEquals(texts, List("foo", " ", "42"))
  }

  test("round-trip: build, reconstruct, count tokens and trees") {
    // val x = 1+2
    val node = statement(
      keyword("val"),
      token(TokenKind.Whitespace, " "),
      identifier("x"),
      token(TokenKind.Whitespace, " "),
      token(TokenKind.Operator, "="),
      token(TokenKind.Whitespace, " "),
      expression(number("1"), token(TokenKind.Operator, "+"), number("2"))
    )
    assertEquals(toSource(node), "val x = 1+2")

    var tokenCount = 0
    var treeCount = 0
    traverse(node) {
      case Token(_, _) => tokenCount += 1
      case Tree(_, _, _) => treeCount += 1
      case _ => ()
    }
    assertEquals(tokenCount, 9)
    assertEquals(treeCount, 2)
  }

  // ------------------------------------------------------------------------
  // Missing / Unexpected — structural error nodes
  // ------------------------------------------------------------------------

  test("Missing has zero textLength") {
    assertEquals(textLength(Missing(TokenKind.RightParen)), 0)
  }

  test("Missing toSource is the empty string") {
    assertEquals(toSource(Missing(TokenKind.Semicolon)), "")
  }

  test("Unexpected textLength is the sum of its children") {
    val node = unexpectedOfVec(Vector(identifier("junk"), token(TokenKind.Whitespace, " ")))
    assertEquals(textLength(node), 5)
  }

  test("Unexpected toSource reconstructs the skipped tokens verbatim") {
    val node = unexpectedOfVec(Vector(identifier("garbage"), token(TokenKind.Operator, "#")))
    assertEquals(toSource(node), "garbage#")
  }

  test("tree containing a Missing placeholder is still lossless") {
    // Parsed "(1" — closing paren missing. The tree has a Missing at the end.
    val inside = expression(
      token(TokenKind.LeftParen, "("),
      number("1"),
      Missing(TokenKind.RightParen)
    )
    assertEquals(toSource(inside), "(1")
  }

  test("traverse visits Missing as a leaf") {
    var visited = 0
    traverse(Missing(TokenKind.Semicolon))(_ => visited += 1)
    assertEquals(visited, 1)
  }

  test("traverse descends into Unexpected children") {
    val node = unexpectedOfVec(Vector(identifier("a"), identifier("b")))
    var count = 0
    traverse(node)(_ => count += 1)
    // 1 for the Unexpected itself + 2 children
    assertEquals(count, 3)
  }

  test("lossless round-trip with comments and multiple whitespace regions") {
    val node = expression(
      identifier("hello"),
      token(TokenKind.Whitespace, " "),
      token(TokenKind.Comment, "/* comment */"),
      token(TokenKind.Whitespace, " "),
      identifier("world")
    )
    assertEquals(toSource(node), "hello /* comment */ world")
  }
}
