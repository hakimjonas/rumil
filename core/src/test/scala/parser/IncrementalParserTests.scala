package parser

import munit.FunSuite

import parser.core.*
import parser.core.GreenNode.*
import parser.core.GreenNodeOps.*

class IncrementalParserTests extends FunSuite {

  private def mkSpan(startOff: Int, endOff: Int): Span =
    (start = (line = 1, column = 1, offset = startOff), end = (line = 1, column = 1, offset = endOff))

  // --------------------------------------------------------------------------
  // TextEdit
  // --------------------------------------------------------------------------

  test("TextEdit.insert creates insertion edit") {
    val edit = TextEdit.insert(5, "hello")

    assertEquals(edit.startOffset, 5)
    assertEquals(edit.endOffset, 5)
    assertEquals(edit.newText, "hello")
    assert(edit.isInsertion)
    assert(!edit.isDeletion)
    assertEquals(edit.lengthDelta, 5)
  }

  test("TextEdit.delete creates deletion edit") {
    val edit = TextEdit.delete(5, 10)

    assertEquals(edit.startOffset, 5)
    assertEquals(edit.endOffset, 10)
    assertEquals(edit.newText, "")
    assert(edit.isDeletion)
    assert(!edit.isInsertion)
    assertEquals(edit.lengthDelta, -5)
  }

  test("TextEdit.replace creates replacement edit") {
    val edit = TextEdit.replace(5, 10, "new")

    assertEquals(edit.startOffset, 5)
    assertEquals(edit.endOffset, 10)
    assertEquals(edit.newText, "new")
    assert(edit.isReplacement)
    assertEquals(edit.lengthDelta, -2)
  }

  test("TextEdit.apply inserts text") {
    assertEquals(TextEdit.insert(5, " beautiful")("hello world"), "hello beautiful world")
  }

  test("TextEdit.apply deletes text") {
    assertEquals(TextEdit.delete(5, 15)("hello beautiful world"), "hello world")
  }

  test("TextEdit.apply replaces text") {
    assertEquals(TextEdit.replace(6, 11, "universe")("hello world"), "hello universe")
  }

  test("TextEdit.affects detects overlapping ranges") {
    val edit = TextEdit(10, 20, "x")

    assert(edit.affects(15, 25))
    assert(edit.affects(5, 15))
    assert(edit.affects(12, 18))
    assert(edit.affects(5, 25))
    assert(!edit.affects(0, 5))
    assert(!edit.affects(25, 30))
  }

  test("TextEdit.adjustOffset shifts offsets correctly") {
    val edit = TextEdit(10, 15, "xxx")

    assertEquals(edit.adjustOffset(5), 5)
    assertEquals(edit.adjustOffset(12), 10)
    assertEquals(edit.adjustOffset(20), 18)
  }

  test("TextEdit.compose adjusts sequential edits") {
    val edits = List(TextEdit(0, 0, "A"), TextEdit(5, 5, "B"))
    val composed = TextEdit.compose(edits)
    assertEquals(composed(0).startOffset, 0)
    assertEquals(composed(1).startOffset, 6)
  }

  // --------------------------------------------------------------------------
  // TreeSplicing
  // --------------------------------------------------------------------------

  test("TreeSplicing.replaceAt replaces root when path is empty") {
    val result = TreeSplicing.replaceAt(identifier("foo"), Vector.empty, identifier("bar"))
    assert(result.isDefined)
    assertEquals(toSource(result.get), "bar")
  }

  test("TreeSplicing.replaceAt replaces a child at path") {
    val parent = expression(identifier("foo"), number("123"))
    val result = TreeSplicing.replaceAt(parent, Vector(1), number("456"))
    assert(result.isDefined)
    assertEquals(toSource(result.get), "foo456")
  }

  test("TreeSplicing.replaceAt keeps unaffected children structurally shared") {
    val child1 = identifier("foo")
    val parent = expression(child1, number("123"))
    val result = TreeSplicing.replaceAt(parent, Vector(1), number("456"))
    result match {
      case Some(Tree(_, children, _)) => assert(children(0) eq child1)
      case _ => fail("expected Tree")
    }
  }

  test("TreeSplicing.findNode returns node at path") {
    val parent = expression(identifier("foo"), number("123"))
    val found = TreeSplicing.findNode(parent, Vector(0))
    assert(found.isDefined)
    assertEquals(toSource(found.get), "foo")
  }

  test("TreeSplicing.nodeLength for a token is its text length") {
    assertEquals(TreeSplicing.nodeLength(identifier("hello")), 5)
  }

  test("TreeSplicing.nodeLength for a tree sums its children") {
    val node = expression(identifier("foo"), number("12"))
    assertEquals(TreeSplicing.nodeLength(node), 5)
  }

  // --------------------------------------------------------------------------
  // RedTree reparse navigation
  // --------------------------------------------------------------------------

  test("RedTree.findReparseAncestor climbs to nearest matching ancestor") {
    val green = tree(SyntaxKind.Function, tree(SyntaxKind.Block, identifier("foo")))
    val tokenRed = RedTree(green).children(0).children(0)
    val found = tokenRed.findReparseAncestor(Set(SyntaxKind.Block, SyntaxKind.Function))
    assert(found.isDefined)
    assertEquals(found.get.syntaxKind, Some(SyntaxKind.Block))
  }

  test("RedTree.findReparseRegion picks the smallest reparseable ancestor covering the edit") {
    val green = tree(
      SyntaxKind.Block,
      tree(SyntaxKind.Statement, identifier("foo"), number("123"))
    )
    val red = RedTree(green)
    val found = red.findReparseRegion(1, 2, Set(SyntaxKind.Statement, SyntaxKind.Block))
    assert(found.isDefined)
    assertEquals(found.get.syntaxKind, Some(SyntaxKind.Statement))
  }

  test("RedTree.pathFromRoot returns the correct sequence of child indices") {
    val outer = statement(expression(identifier("foo")))
    val tokenRed = RedTree(outer).children(0).children(0)
    assertEquals(tokenRed.pathFromRoot, Vector(0, 0))
  }

  // --------------------------------------------------------------------------
  // Configuration + Result
  // --------------------------------------------------------------------------

  test("ReparseableParsers.onlyFull has no reparseable kinds") {
    val dummy: Parser[ParseError, GreenNode] = Parser.Succeed(identifier("x"))
    val p = IncrementalParser.ReparseableParsers.onlyFull[TokenKind, SyntaxKind, ParseError](
      dummy,
      (src: String) => GreenNode.Token(TokenKind.Error, src)
    )
    assertEquals(p.reparsableKinds, Set.empty[SyntaxKind])
  }

  test("ReparseableParsers.reparsableKinds exposes byKind.keySet") {
    val dummy: Parser[ParseError, GreenNode] = Parser.Succeed(identifier("x"))
    val p = IncrementalParser.ReparseableParsers[TokenKind, SyntaxKind, ParseError](
      full = dummy,
      byKind = Map(
        SyntaxKind.Statement -> dummy,
        SyntaxKind.Block -> dummy
      ),
      isSimpleToken = _ => false,
      onParseFailure = (src: String) => GreenNode.Token(TokenKind.Error, src)
    )
    assertEquals(p.reparsableKinds, Set(SyntaxKind.Statement, SyntaxKind.Block))
  }

  test("IncrementalResult carries reparseRegion and fullReparse flags") {
    val region = mkSpan(0, 3)
    val t = identifier("foo")
    val incremental = IncrementalParser.IncrementalResult(t, Some(region), fullReparse = false)
    assert(incremental.reparseRegion.isDefined)
    assert(!incremental.fullReparse)

    val full = IncrementalParser.IncrementalResult(t, None, fullReparse = true)
    assert(full.reparseRegion.isEmpty)
    assert(full.fullReparse)
  }
}
