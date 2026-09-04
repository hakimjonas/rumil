package parser

import scala.collection.immutable.Vector

import parser.core.*

/** Stack safety for the POST-PARSE green-tree utility layer (the consumer side).
  *
  * The stack-safety arc made the PARSER stack-safe ([[StructuralNestingStackSafety]]); it never
  * touched these post-parse traversals. Before this fix they recursed over tree DEPTH (and over the
  * ancestor chain / over sibling width) with no trampoline, and `StackOverflowError`-ed on ordinary
  * deep trees: a left-associative chain `((((5)+5)+5)…)` is exactly what `a+b+c+d…`, `a.b.c.d…`, or
  * deep expression nesting produces. The diagnosis measured the keystroke path overflowing at depth
  * ≈18k and `traverse` at ≈8k (docs/INCREMENT-3-DIAGNOSIS-RESULTS.md, Finding B).
  *
  * This test is the regression guard. It builds a left-deep green tree well past every measured
  * overflow threshold and asserts every depth-, ancestor-, and width-bounded op completes. It runs
  * on the DEFAULT stack — no `-Xss` padding — because a fix that only survives a padded stack is no
  * fix. A flat (wide) tree additionally guards the sibling-width-recursive ops.
  */
class GreenTreeStackSafety extends munit.FunSuite {

  // Two depth tiers, set by each op's measured overflow ceiling + margin (NOT a convenience
  // number) — a stack-safety guard is decorative if its depth doesn't exceed the op's own ceiling,
  // since a reverted recursive implementation would still pass.
  //
  //   - `pureDepth` (≥100k): for the O(n) green ops `textLength` / `toSource` / `traverse` /
  //     `validateWith`. Measured ceilings ~8k–64k; these run in milliseconds even at 100k. Permanent.
  //   - `redDepth` (100k): for the RedTree-based ops `nodeAt` / `descendants` / `pathFromRoot` /
  //     keystroke `incrementalParse` / deep `replaceAt`. After Phase A's width-cache made
  //     `GreenNode.textLength` an O(1) field read, `RedTree.redChildren`'s per-child `textLength`
  //     calls are O(1) and these ops collapsed from O(depth²) to O(depth) — so 100k now runs in
  //     milliseconds (was tens of seconds at 50k in Phase B). The `nodeAt`/`descendants` scaling
  //     test below proves the quadratic is gone (50k→100k stays ~2×, not ~4×).
  private val pureDepth = 100_000
  private val redDepth = 100_000
  // Flat-tree width ops (`pathToOffset` sibling scan, single-level `replaceAt`) are O(width), so a
  // wide tree is cheap; size it past any plausible width-recursion ceiling.
  private val wideWidth = 200_000

  private def num: GreenNode = GreenNode.Token(TokenKind.Number, "5")
  private def op: GreenNode = GreenNode.Token(TokenKind.Operator, "+")

  /** Left-associative chain over `n` number leaves: `Tree(Expr, [acc, +, 5])` nested `n-1` deep.
    * Spine depth ≈ n, width 3 per level.
    */
  private def leftDeep(n: Int): GreenNode = {
    var acc: GreenNode = num
    var i = 1
    while i < n do {
      acc = GreenNode.treeOfVec(SyntaxKind.Expression, Vector(acc, op, num))
      i += 1
    }
    acc
  }

  /** Flat tree: `Tree(Block, [5, +, 5, +, …, 5])` — `n` number leaves as direct children (width ≈
    * 2n, depth 2). Stresses the sibling-width-recursive ops (`pathToOffset`'s child scan).
    */
  private def flat(n: Int): GreenNode = {
    val kids = Vector.newBuilder[GreenNode]
    var i = 0
    while i < n do {
      if i > 0 then kids += op
      kids += num
      i += 1
    }
    GreenNode.treeOfVec(SyntaxKind.Block, kids.result())
  }

  private val parsers: IncrementalParser.ReparseableParsers[TokenKind, SyntaxKind, ParseError] =
    IncrementalParser.ReparseableParsers
      .onlyFull[TokenKind, SyntaxKind, ParseError](
        // `full` is never reached on the token-level fast path; a never-matching parser is fine.
        full = Parser.Fail(ParseError.Custom("unused", (line = 1, column = 1, offset = 0))),
        onParseFailure = (src: String) => GreenNode.Token(TokenKind.Error, src)
      )
      .copy(isSimpleToken = (k: TokenKind) => k == TokenKind.Number)

  test("GreenNode.textLength on a deep tree does not overflow") {
    val tree = leftDeep(pureDepth)
    assertEquals(GreenNode.textLength(tree), pureDepth + (pureDepth - 1)) // numbers + operators
  }

  test("GreenNode.toSource on a deep tree does not overflow and round-trips") {
    val tree = leftDeep(pureDepth)
    val src = GreenNode.toSource(tree)
    assertEquals(src.length, pureDepth + (pureDepth - 1))
    // Lossless: a left-deep chain of single-char tokens reconstructs to "5+5+5+…+5".
    assert(src.startsWith("5+5"))
    assert(src.endsWith("5"))
  }

  test("GreenNode.traverse on a deep tree does not overflow and visits every node") {
    val tree = leftDeep(pureDepth)
    var count = 0
    GreenNode.traverse(tree)(_ => count += 1)
    // pureDepth numbers + (pureDepth-1) operators + (pureDepth-1) interior Expression trees.
    assertEquals(count, pureDepth + (pureDepth - 1) + (pureDepth - 1))
  }

  test("RedTree.descendants on a deep tree does not overflow") {
    val red = RedTree(leftDeep(redDepth))
    // Just force the whole traversal; the exact count equals traverse's count minus the root.
    assertEquals(red.descendants.length, (redDepth + (redDepth - 1) + (redDepth - 1)) - 1)
  }

  test("RedTree.nodeAt on a deep tree does not overflow") {
    val red = RedTree(leftDeep(redDepth))
    // Offset 0 is the deepest-left leaf; locating it descends the full spine.
    val found = red.nodeAt(0)
    assert(found.isDefined)
    assertEquals(found.get.green, num)
  }

  test("RedTree.pathFromRoot on a deep tree does not overflow") {
    val red = RedTree(leftDeep(redDepth))
    val deepestLeft = red.nodeAt(0).get
    // The deepest-left leaf is reached by descending child 0 at every level: path is all zeros,
    // one entry per interior level.
    val path = deepestLeft.pathFromRoot
    assertEquals(path.length, redDepth - 1)
    assert(path.forall(_ == 0))
  }

  test("RedTree.validateWith on a deep tree does not overflow") {
    val red = RedTree(leftDeep(redDepth))
    assertEquals(red.validateWith(_ == TokenKind.Error), Nil)
  }

  test("incrementalParse keystroke on a deep tree does not overflow") {
    val tree = leftDeep(redDepth)
    val src = GreenNode.toSource(tree)
    val edit = TextEdit.replace(0, 1, "9") // edit the deepest-left number token
    val result = IncrementalParser.incrementalParse(tree, src, edit, parsers)
    assert(!result.fullReparse, "expected the token-level fast path, not a full reparse")
    assertEquals(GreenNode.toSource(result.tree), edit(src))
  }

  test("TreeSplicing.pathToOffset / replaceAt on a wide flat tree do not overflow") {
    val tree = flat(wideWidth)
    val src = GreenNode.toSource(tree)
    // Offset of the last number leaf: every prior (num, op) pair is one char each.
    val lastLeafOffset = src.length - 1
    val path = TreeSplicing.pathToOffset(tree, lastLeafOffset)
    assertEquals(path, Vector(2 * (wideWidth - 1))) // index of the final child under the Block
    val spliced = TreeSplicing.replaceAt(tree, path, GreenNode.Token(TokenKind.Number, "9"))
    assert(spliced.isDefined)
    assertEquals(GreenNode.toSource(spliced.get), src.dropRight(1) + "9")
  }

  test("TreeSplicing.replaceAt on a deep tree does not overflow") {
    val tree = leftDeep(redDepth)
    val deepestLeft = RedTree(tree).nodeAt(0).get
    val path = deepestLeft.pathFromRoot
    val spliced = TreeSplicing.replaceAt(tree, path, GreenNode.Token(TokenKind.Number, "9"))
    assert(spliced.isDefined)
    assertEquals(GreenNode.toSource(spliced.get), "9" + GreenNode.toSource(tree).drop(1))
  }

  // ---------------------------------------------------------------------------
  // Sibling-width fix: keystroke is O(depth·width), made a SMALLER linear constant
  // (no sibling red-node allocation, O(1) splice width). True O(log width) was
  // deliberately NOT pursued — it needs a prefix-offset array on every green node
  // (extra ADT field + per-node memory), and the no-alloc descent already gives a
  // ~16–26× constant-factor win. So the enforced invariant guards LINEARITY: a
  // wide-level edit must not become SUPER-linear (O(width²)) — which is exactly
  // what reverting to `redChildren` materialization + a re-summing splice would do.
  // ---------------------------------------------------------------------------

  /** Offset of the middle number leaf in `flat(n)`: child `2k` (a number) sits at offset `2k`, so
    * the middle number (`k = n/2`) is at offset `n`. Edited in place by the token-level fast path.
    */
  private def midNumberOffset(n: Int): Int = n // 2 * (n / 2)

  test("sibling-width: flat keystroke is correct and hits the token-level fast path at width") {
    val n = 50_000
    val tree = flat(n)
    val src = GreenNode.toSource(tree)
    val target = midNumberOffset(n)
    val edit = TextEdit.replace(target, target + 1, "9")
    val result = IncrementalParser.incrementalParse(tree, src, edit, parsers)
    assert(!result.fullReparse, "expected the token-level fast path (Number is a simple token)")
    // Behavioral identity: the spliced tree reconstructs to the edited source exactly.
    assertEquals(GreenNode.toSource(result.tree), edit(src))
  }

  test("sibling-width: descend nodeAt is behaviorally identical to the children.find reference") {
    // Reference: the pre-fix descent — `children.find(span contains offset)` repeated, which
    // materializes ALL siblings as red nodes at each level. The new `nodeAt` uses `descend` (one
    // red node per level). They must return the SAME node: same green, offset, childIndex, and
    // root path. A divergence here is a wrong-child bug → corrupted splice.
    def reference(root: RedTree[TokenKind, SyntaxKind], offset: Int): Option[RedTree[TokenKind, SyntaxKind]] =
      if offset < root.span.start.offset || offset >= root.span.end.offset then None
      else {
        var current = root
        var result: Option[RedTree[TokenKind, SyntaxKind]] = None
        while result.isEmpty do {
          current.children.find(c => offset >= c.span.start.offset && offset < c.span.end.offset) match {
            case Some(child) => current = child
            case None => result = Some(current)
          }
        }
        result
      }
    // Varied corpus: wide-flat, deep-left, and a mixed nested file (flat + unexpected + missing +
    // deep) — the same shapes as the textLength value-equivalence corpus.
    val corpus: List[GreenNode] = List(
      flat(2000),
      leftDeep(2000),
      GreenNode.treeOfVec(
        SyntaxKind.SourceFile,
        Vector(
          flat(500),
          GreenNode.unexpectedOfVec(Vector(GreenNode.Token(TokenKind.Error, "xx"), num)),
          GreenNode.Missing(TokenKind.Semicolon),
          leftDeep(500)
        )
      )
    )
    corpus.foreach { g =>
      val red = RedTree(g)
      val len = GreenNode.textLength(g)
      // Edits at start / middle / end (and a few interior points) of each tree's span.
      val offsets = List(0, 1, len / 3, len / 2, (2 * len) / 3, len - 1)
      offsets.filter(o => o >= 0 && o < len).foreach { o =>
        val got = red.nodeAt(o)
        val ref = reference(red, o)
        assertEquals(got.isDefined, ref.isDefined, s"definedness differs at offset $o")
        (got, ref) match {
          case (Some(a), Some(b)) =>
            assert(a.green eq b.green, s"different green at offset $o")
            assertEquals(a.offset, b.offset, s"different offset at $o")
            assertEquals(a.childIndex, b.childIndex, s"different childIndex at $o")
            // pathFromRoot is what the keystroke splice consumes — identical path → identical splice.
            assertEquals(a.pathFromRoot, b.pathFromRoot, s"different path at $o")
          case _ => ()
        }
      }
    }
  }

  test("sibling-width: keystroke scales LINEARLY (NOT super-linearly) in sibling width") {
    // The no-alloc descent + O(1) splice keep the wide-level keystroke O(width): a 10× width
    // increase should cost ~10× (the descent scans ~width/2 green children to reach the middle
    // token). Assert a generous ceiling (< 20×) that a super-linear regression (O(width²) → ~100×,
    // e.g. reintroducing `redChildren`'s O(width) sibling materialization inside the find, or a
    // splice that re-sums all siblings per level) would blow through, but linear (~10×) clears with
    // margin. Wall-clock is noisy → warm up + best-of-3, exactly like the O(depth) test above.
    val smallN = 20_000
    val largeN = 200_000 // 10× width
    def keystroke(tree: GreenNode, src: String, n: Int): Int = {
      val target = midNumberOffset(n)
      val edit = TextEdit.replace(target, target + 1, "9")
      val r = IncrementalParser.incrementalParse(tree, src, edit, parsers)
      GreenNode.textLength(r.tree) // force the spliced tree, return something live
    }
    val smallTree = flat(smallN)
    val smallSrc = GreenNode.toSource(smallTree)
    val largeTree = flat(largeN)
    val largeSrc = GreenNode.toSource(largeTree)
    var sink = 0
    def exerciseSmall(): Unit = sink += keystroke(smallTree, smallSrc, smallN)
    def exerciseLarge(): Unit = sink += keystroke(largeTree, largeSrc, largeN)
    def timeNs(work: => Unit): Long = {
      val start = System.nanoTime()
      work
      System.nanoTime() - start
    }
    var w = 0
    while w < 3 do { exerciseSmall(); exerciseLarge(); w += 1 }
    def best(work: => Unit): Long = {
      var bestNs = Long.MaxValue
      var i = 0
      while i < 3 do { bestNs = math.min(bestNs, timeNs(work)); i += 1 }
      bestNs
    }
    val smallNs = best(exerciseSmall())
    val largeNs = best(exerciseLarge())
    assert(sink != Int.MinValue) // keep `sink` live so the work isn't elided
    val ratio = largeNs.toDouble / smallNs.toDouble
    assert(
      ratio < 20.0,
      s"flat keystroke scaled ${ratio}x for a 10x width increase — expected ~10x (O(width)); " +
        s">=20x suggests a super-linear (O(width²)) regression (smallNs=$smallNs, largeNs=$largeNs)"
    )
  }

  // ---------------------------------------------------------------------------
  // Phase A: width-cache made textLength O(1). The following prove that the win
  // actually landed (not just that nothing crashes).
  // ---------------------------------------------------------------------------

  test("Phase A: deep tree CONSTRUCTION populates width without overflow (guards the population path)") {
    // The width must be populated O(children)-per-level at construction. If any construction site
    // summed descendants recursively, building this 200k-deep tree (or reading its width) would
    // overflow. Build via the smart constructor (the parser/test path) and read width via the
    // O(1) textLength.
    val deep = leftDeep(200_000)
    assertEquals(GreenNode.textLength(deep), 200_000 + (200_000 - 1)) // numbers + operators
  }

  test("Phase A: scaling — nodeAt/descendants are O(depth), NOT O(depth²)") {
    // Pre-Phase-A these were O(depth²) (redChildren × O(subtree) textLength down the spine): a
    // 2× depth increase cost ~4× time. With textLength O(1) they are O(depth): ~2× for 2× depth.
    // Wall-clock is noisy, so assert a generous ceiling (< 3×) that a quadratic (~4×) would blow
    // through but a linear (~2×) clears with margin. Warm up first so the JIT has compiled.
    // `sink` consumes results so nothing is a discarded non-Unit value (-Werror) and the JIT can't
    // dead-code-eliminate the work being timed.
    var sink = 0
    def exercise(tree: GreenNode): Unit = {
      sink += RedTree(tree).nodeAt(0).fold(0)(_.length)
      sink += RedTree(tree).descendants.length
    }
    def timeNs(work: => Unit): Long = {
      val start = System.nanoTime()
      work
      System.nanoTime() - start
    }
    val small = leftDeep(50_000)
    val large = leftDeep(100_000)
    // Warm-up rounds (JIT).
    var w = 0
    while w < 3 do { exercise(small); exercise(large); w += 1 }
    // Best-of-3 to damp scheduler noise.
    def best(tree: GreenNode): Long = {
      var bestNs = Long.MaxValue
      var i = 0
      while i < 3 do { bestNs = math.min(bestNs, timeNs(exercise(tree))); i += 1 }
      bestNs
    }
    val smallNs = best(small)
    val largeNs = best(large)
    assert(sink != Int.MinValue) // keep `sink` live so the work isn't elided
    val ratio = largeNs.toDouble / smallNs.toDouble
    assert(
      ratio < 3.0,
      s"nodeAt/descendants scaled ${ratio}x for a 2x depth increase — expected ~2x (O(depth)); " +
        s">=3x suggests the O(depth²) regression returned (smallNs=$smallNs, largeNs=$largeNs)"
    )
  }

  test("Phase A: textLength value-equivalence with an independent char-sum over a varied corpus") {
    // The cached width must equal what a straightforward char-sum would compute, for every node
    // shape. Corpus mixes Token / Tree / Unexpected / Missing, nesting, and empties.
    //
    // The reference traversal is tail-recursive with an accumulator — compiled to a loop, so the
    // checker itself is immune to JVM-stack depth (the original recursive version overflowed on CI
    // at leftDeep(1000), ~5 JVM frames per level). It stays an independent implementation: a plain
    // sum over token text lengths, no width cache involved. Child order is irrelevant to a sum, so
    // kids are simply prepended.
    def naive(root: GreenNode): Int = {
      @annotation.tailrec
      def loop(pending: List[GreenNode], total: Int): Int = pending match {
        case Nil => total
        case GreenNode.Token(_, text) :: rest => loop(rest, total + text.length)
        case GreenNode.Missing(_) :: rest => loop(rest, total)
        case GreenNode.Tree(_, kids, _) :: rest =>
          loop(kids.toList ::: rest, total)
        case GreenNode.Unexpected(kids, _) :: rest =>
          loop(kids.toList ::: rest, total)
      }
      loop(List(root), 0)
    }
    val corpus: List[GreenNode] = List(
      num,
      GreenNode.Missing(TokenKind.RightParen),
      GreenNode.treeOfVec(SyntaxKind.Block, Vector.empty), // empty tree, width 0
      GreenNode.unexpectedOfVec(Vector(GreenNode.Token(TokenKind.Error, "junk"))),
      flat(1000),
      leftDeep(1000),
      GreenNode.treeOfVec(
        SyntaxKind.SourceFile,
        Vector(
          flat(50),
          GreenNode.unexpectedOfVec(Vector(GreenNode.Token(TokenKind.Error, "xx"), num)),
          GreenNode.Missing(TokenKind.Semicolon),
          leftDeep(50)
        )
      )
    )
    corpus.foreach(g => assertEquals(GreenNode.textLength(g), naive(g)))
  }
}
