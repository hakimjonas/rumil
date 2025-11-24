package parser

import munit.FunSuite
import parser.core._
import parser.core.GreenNode._
import parser.core.GreenNodeOps._

class IncrementalParserTests extends FunSuite {

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
  // TextEdit Tests
  // ============================================================================

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
    assertEquals(edit.lengthDelta, -2) // deleted 5, inserted 3
  }

  test("TextEdit.apply inserts text") {
    val source = "hello world"
    val edit = TextEdit.insert(5, " beautiful")
    val result = edit(source)

    assertEquals(result, "hello beautiful world")
  }

  test("TextEdit.apply deletes text") {
    val source = "hello beautiful world"
    val edit = TextEdit.delete(5, 15)
    val result = edit(source)

    assertEquals(result, "hello world")
  }

  test("TextEdit.apply replaces text") {
    val source = "hello world"
    val edit = TextEdit.replace(6, 11, "universe")
    val result = edit(source)

    assertEquals(result, "hello universe")
  }

  test("TextEdit.affects detects overlapping ranges") {
    val edit = TextEdit(10, 20, "x")

    assert(edit.affects(15, 25))  // Overlaps end
    assert(edit.affects(5, 15))   // Overlaps start
    assert(edit.affects(12, 18))  // Inside
    assert(edit.affects(5, 25))   // Contains
    assert(!edit.affects(0, 5))   // Before
    assert(!edit.affects(25, 30)) // After
  }

  test("TextEdit.adjustOffset shifts offsets correctly") {
    val edit = TextEdit(10, 15, "xxx") // Delete 5, insert 3 -> delta = -2

    assertEquals(edit.adjustOffset(5), 5)   // Before edit - unchanged
    assertEquals(edit.adjustOffset(12), 10) // Inside deleted range - maps to start
    assertEquals(edit.adjustOffset(20), 18) // After edit - shifted by delta
  }

  test("TextEdit.compose adjusts sequential edits") {
    val edits = List(
      TextEdit(0, 0, "A"),   // Insert A at 0 -> delta +1
      TextEdit(5, 5, "B")    // Insert B at 5 (original) -> adjusted to 6
    )

    val composed = TextEdit.compose(edits)

    assertEquals(composed(0).startOffset, 0)
    assertEquals(composed(1).startOffset, 6) // Adjusted by +1
  }

  // ============================================================================
  // TreeSplicing Tests
  // ============================================================================

  test("TreeSplicing.replaceAt replaces root node") {
    val span1 = mkSpan(1, 1, 0, 1, 4, 3)
    val oldNode = identifier("foo", span1)

    val span2 = mkSpan(1, 1, 0, 1, 4, 3)
    val newNode = identifier("bar", span2)

    val result = TreeSplicing.replaceAt(oldNode, Vector.empty, newNode)

    assert(result.isDefined)
    assertEquals(GreenNode.toSource(result.get), "bar")
  }

  test("TreeSplicing.replaceAt replaces child node") {
    val span1 = mkSpan(1, 1, 0, 1, 4, 3)
    val span2 = mkSpan(1, 4, 3, 1, 7, 6)
    val child1 = identifier("foo", span1)
    val child2 = number("123", span2)
    val parent = expression(child1, child2)

    val newSpan = mkSpan(1, 4, 3, 1, 7, 6)
    val newChild = number("456", newSpan)

    val result = TreeSplicing.replaceAt(parent, Vector(1), newChild)

    assert(result.isDefined)
    assertEquals(GreenNode.toSource(result.get), "foo456")
  }

  test("TreeSplicing.replaceAt preserves structural sharing") {
    val span1 = mkSpan(1, 1, 0, 1, 4, 3)
    val span2 = mkSpan(1, 4, 3, 1, 7, 6)
    val child1 = identifier("foo", span1)
    val child2 = number("123", span2)
    val parent = expression(child1, child2)

    val newSpan = mkSpan(1, 4, 3, 1, 7, 6)
    val newChild = number("456", newSpan)

    val result = TreeSplicing.replaceAt(parent, Vector(1), newChild)

    // child1 should be the exact same instance (structural sharing)
    result match {
      case Some(GreenNode.Tree(_, children)) =>
        assert(children(0) eq child1)
      case _ =>
        fail("Expected tree result")
    }
  }

  test("TreeSplicing.findNode finds node at path") {
    val span1 = mkSpan(1, 1, 0, 1, 4, 3)
    val span2 = mkSpan(1, 4, 3, 1, 7, 6)
    val child1 = identifier("foo", span1)
    val child2 = number("123", span2)
    val parent = expression(child1, child2)

    val found = TreeSplicing.findNode(parent, Vector(0))

    assert(found.isDefined)
    assertEquals(GreenNode.toSource(found.get), "foo")
  }

  test("TreeSplicing.nodeLength calculates correct length") {
    val span1 = mkSpan(1, 1, 0, 1, 6, 5)
    val token = identifier("hello", span1)

    assertEquals(TreeSplicing.nodeLength(token), 5)
  }

  test("TreeSplicing.nodeLength sums children for tree") {
    val span1 = mkSpan(1, 1, 0, 1, 4, 3)
    val span2 = mkSpan(1, 4, 3, 1, 6, 5)
    val child1 = identifier("foo", span1)
    val child2 = number("12", span2)
    val parent = expression(child1, child2)

    assertEquals(TreeSplicing.nodeLength(parent), 5) // 3 + 2
  }

  // ============================================================================
  // RedTree findReparseRegion Tests
  // ============================================================================

  test("RedTree.findReparseAncestor finds matching ancestor") {
    val span1 = mkSpan(1, 1, 0, 1, 4, 3)
    val token = identifier("foo", span1)
    val block = tree(SyntaxKind.Block, token)
    val func = tree(SyntaxKind.Function, block)

    val red = RedTree(func)
    val tokenRed = red.children(0).children(0)

    val reparsable = Set(SyntaxKind.Block, SyntaxKind.Function)
    val found = tokenRed.findReparseAncestor(reparsable)

    assert(found.isDefined)
    assertEquals(found.get.syntaxKind, Some(SyntaxKind.Block))
  }

  test("RedTree.findReparseRegion finds smallest containing ancestor") {
    val span1 = mkSpan(1, 1, 0, 1, 4, 3)
    val span2 = mkSpan(1, 4, 3, 1, 7, 6)
    val token1 = identifier("foo", span1)
    val token2 = number("123", span2)
    val stmt = tree(SyntaxKind.Statement, token1, token2)
    val block = tree(SyntaxKind.Block, stmt)

    val red = RedTree(block)

    val reparsable = Set(SyntaxKind.Statement, SyntaxKind.Block)
    val found = red.findReparseRegion(1, 2, reparsable) // Edit in first token

    assert(found.isDefined)
    assertEquals(found.get.syntaxKind, Some(SyntaxKind.Statement))
  }

  test("RedTree.pathFromRoot builds correct path") {
    val span1 = mkSpan(1, 1, 0, 1, 4, 3)
    val token = identifier("foo", span1)
    val inner = expression(token)
    val outer = statement(inner)

    val red = RedTree(outer)
    val tokenRed = red.children(0).children(0)

    val path = tokenRed.pathFromRoot

    assertEquals(path, Vector(0, 0))
  }

  // ============================================================================
  // IncrementalParser Configuration Tests
  // ============================================================================

  test("IncrementalParser.defaultConfig includes common syntax kinds") {
    val config = IncrementalParser.defaultConfig

    assert(config.reparsableKinds.contains(SyntaxKind.Block))
    assert(config.reparsableKinds.contains(SyntaxKind.Function))
    assert(config.reparsableKinds.contains(SyntaxKind.Statement))
    assert(config.reparsableKinds.contains(SyntaxKind.SourceFile))
  }

  // ============================================================================
  // IncrementalResult Tests
  // ============================================================================

  test("IncrementalResult tracks reparse region") {
    val span = mkSpan(1, 1, 0, 1, 4, 3)
    val tree = identifier("foo", span)
    val result = IncrementalParser.IncrementalResult(tree, Some(span), fullReparse = false)

    assert(result.reparseRegion.isDefined)
    assert(!result.fullReparse)
  }

  test("IncrementalResult indicates full reparse") {
    val span = mkSpan(1, 1, 0, 1, 4, 3)
    val tree = identifier("foo", span)
    val result = IncrementalParser.IncrementalResult(tree, None, fullReparse = true)

    assert(result.reparseRegion.isEmpty)
    assert(result.fullReparse)
  }
}
