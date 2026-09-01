package parser.core

import scala.collection.immutable.Vector

/** RedTree - Position-aware view over [[GreenNode]].
  *
  * Inspired by rust-analyzer's Rowan library, RedTree provides an ephemeral, position-aware
  * interface over the immutable [[GreenNode]] structure.
  *
  * Key design principles:
  *   - Green trees are position-independent (immutable, cacheable)
  *   - Red trees compute positions on-demand (cheap views)
  *   - Parent/sibling navigation without storing back-pointers
  *   - Lazy computation of spans and locations
  *
  * RedTree instances are lightweight - they only store a reference to the green node, an offset, an
  * optional parent reference, and the node's index among its parent's children.
  *
  * Sibling identity is carried in [[childIndex]]. Green-node reference identity (`_.green eq _`) is
  * no longer used as a sibling-disambiguation key — see the constructor's `childIndex` doc below
  * for the rationale.
  *
  * @tparam Tok
  *   The language's token kind alphabet
  * @tparam Syn
  *   The language's syntax kind alphabet
  * @param green
  *   The underlying green node
  * @param offset
  *   The absolute offset of this node in the source
  * @param parent
  *   Optional parent red tree node
  * @param childIndex
  *   Position of this node in `parent.children` when constructed. `0` for a root node (by
  *   convention — the root has no parent and therefore no meaningful sibling index). Replaces the
  *   prior `_.green eq target.green` sibling-lookup key: offset-based disambiguation collapses
  *   zero-width siblings onto the same index, and green reference identity collapses
  *   structurally-equal siblings (e.g. after green-node interning). `childIndex` is assigned once
  *   at construction and survives both of those equivalences.
  */
final class RedTree[Tok, Syn] private (
  val green: GreenNodeOf[Tok, Syn],
  val offset: Int,
  val parent: Option[RedTree[Tok, Syn]],
  val childIndex: Int
) {

  /** Length of the underlying green in source characters. Cached once on first access, since greens
    * are immutable and the length never changes for a given node.
    */
  lazy val length: Int = GreenNode.textLength(green)

  /** Absolute span of this node in source.
    *
    * Derived from this red view's [[offset]] plus [[length]]. Line/column are left at `(1, 1)`
    * placeholders — line tracking is decoupled from offset tracking and added on demand by the
    * caller that has the source string (see the `Location` helpers below when we need them). For
    * the incremental parser path, only offsets matter, and offsets are always correct.
    */
  lazy val span: Span = {
    val start = (line = 1, column = 1, offset = offset)
    val end = (line = 1, column = 1, offset = offset + length)
    (start = start, end = end)
  }

  /** Absolute location (line, column) in source. See [[span]] re: line/column placeholders. */
  def location: Location = span.start

  /** The text content of this node — reconstructed from the green subtree. */
  def text: String = GreenNode.toSource(green)

  /** Children as red trees, each carrying its correct absolute offset.
    *
    * [[GreenNode.Tree]] and [[GreenNode.Unexpected]] both carry children that are visited here;
    * [[GreenNode.Token]] and [[GreenNode.Missing]] are leaves, so they return an empty vector.
    *
    * Walks the children vector summing each kid's `textLength` to compute the next child's offset.
    * Missing children contribute 0, which is exactly right — the RedTree view of a `Missing`
    * placeholder sits at the same offset as the token that would have followed it.
    */
  lazy val children: Vector[RedTree[Tok, Syn]] = green match {
    case GreenNode.Token(_, _) => Vector.empty
    case GreenNode.Missing(_) => Vector.empty
    case GreenNode.Tree(_, kids, _) => redChildren(kids)
    case GreenNode.Unexpected(kids, _) => redChildren(kids)
  }

  private def redChildren(kids: Vector[GreenNodeOf[Tok, Syn]]): Vector[RedTree[Tok, Syn]] = {
    var childOffset = offset
    var j = 0
    kids.map { kid =>
      val red = new RedTree(kid, childOffset, Some(this), j)
      childOffset += GreenNode.textLength(kid)
      j += 1
      red
    }
  }

  /** Navigate to parent node.
    *
    * Returns Some(parent) if this node has a parent, None otherwise.
    */
  def parentNode: Option[RedTree[Tok, Syn]] = parent

  /** Navigate to next sibling, or [[None]] if this is the last child.
    *
    * Sibling disambiguation uses [[childIndex]] — see [[pathFromRoot]].
    */
  def nextSibling: Option[RedTree[Tok, Syn]] = parent.flatMap { p =>
    val siblings = p.children
    val index = this.childIndex
    if index < siblings.length - 1 then {
      Some(siblings(index + 1))
    } else {
      None
    }
  }

  /** Navigate to previous sibling, or [[None]] if this is the first child.
    *
    * Sibling disambiguation uses [[childIndex]] — see [[pathFromRoot]].
    */
  def prevSibling: Option[RedTree[Tok, Syn]] = parent.flatMap { p =>
    val siblings = p.children
    val index = this.childIndex
    if index > 0 then {
      Some(siblings(index - 1))
    } else {
      None
    }
  }

  /** All descendants in pre-order.
    *
    * Returns a list of all descendant red trees, visiting parents before children.
    */
  def descendants: Vector[RedTree[Tok, Syn]] = {
    // Explicit work-stack pre-order (was recursive — overflows on deep trees). Push children so the
    // leftmost is popped first, matching the old `node +: children.flatMap(loop)` order.
    val out = Vector.newBuilder[RedTree[Tok, Syn]]
    var stack: List[RedTree[Tok, Syn]] = this.children.toList
    while stack.nonEmpty do {
      val cur = stack.head
      stack = stack.tail
      out += cur
      stack = cur.children.foldRight(stack)(_ :: _)
    }
    out.result()
  }

  /** Find the deepest node whose span strictly contains [[targetOffset]] (half-open: start
    * inclusive, end exclusive).
    *
    * "Which token does the cursor sit inside?" — returns [[None]] at end-of-file since no token's
    * half-open span includes `source.length`. For an edit-range query that must be satisfied by
    * insertions at end-of-file, use [[nodeEnclosingRange]] which treats the right edge as inclusive
    * on the root's span.
    */
  def nodeAt(targetOffset: Int): Option[RedTree[Tok, Syn]] =
    if targetOffset < span.start.offset || targetOffset >= span.end.offset then {
      None
    } else {
      // Descend selecting the child whose half-open span contains `targetOffset`, building exactly
      // one RedTree per level (see [[descend]]). Equivalent to the old
      // `children.find(...).flatMap(_.nodeAt).orElse(Some(this))` but without materializing the
      // N−1 unchosen siblings as red nodes on the way down.
      Some(descend((childStart, childLen) => targetOffset >= childStart && targetOffset < childStart + childLen))
    }

  /** Descend from this node, at each level building exactly ONE child RedTree — the first one for
    * which `selectChild(childStart, childLen)` holds — instead of materializing all N siblings via
    * [[children]] / [[redChildren]]. Walks the green children directly with a running cumulative
    * offset; each child's width is an O(1) field read ([[GreenNode.textLength]]), so a level with N
    * siblings costs O(N) comparisons but O(1) allocations (one red node), versus [[children]]'s
    * O(N) allocations. Stops at the deepest node for which no child is selected and returns it — a
    * leaf (Token/Missing) has no children, so it always stops there.
    *
    * Iterative (explicit `current`, no recursion over depth) so a deep spine cannot overflow the
    * stack — same discipline as the Phase B trampoline. The returned node carries the correct
    * offset / childIndex / parent chain (each parent is the one-per-level red node built above it),
    * so [[pathFromRoot]], [[span]], and a later [[children]] force all agree with the old
    * `children.find(...)` descent: behaviorally identical, just without the sibling allocation.
    */
  private def descend(selectChild: (Int, Int) => Boolean): RedTree[Tok, Syn] = {
    var current = this
    var descending = true
    while descending do {
      val kids = current.green match {
        case GreenNode.Tree(_, ks, _) => ks
        case GreenNode.Unexpected(ks, _) => ks
        case _ => Vector.empty // Token / Missing leaves have no children
      }
      var childOffset = current.offset
      var i = 0
      var found = false
      // Sequential iterator, NOT indexed `kids(i)`: a `Vector.apply(i)` is O(log₃₂ N), so an
      // indexed scan of a wide level is O(N·log₃₂ N) with poor locality; the iterator walks the
      // trie's leaf arrays in order, O(N) with sequential access.
      val it = kids.iterator
      while !found && it.hasNext do {
        val kid = it.next()
        val kidLen = GreenNode.textLength(kid)
        if selectChild(childOffset, kidLen) then {
          // `Some(current)` captures the OLD `current` as parent — the RHS is fully evaluated
          // before the reassignment, so the parent chain is built correctly.
          current = new RedTree(kid, childOffset, Some(current), i)
          found = true
        } else {
          childOffset += kidLen
          i += 1
        }
      }
      if !found then descending = false
    }
    current
  }

  /** Find the deepest node whose span covers `[editStart, editEnd]` with the right edge inclusive.
    *
    * Unlike [[nodeAt]], which treats its input as a cursor position strictly inside a node's
    * half-open range, this is the right primitive for an edit range. A pure insertion at the very
    * end of the source (`editStart == editEnd == span.end.offset`) returns this node; an insertion
    * between two tokens returns the deepest common ancestor containing both sides of the boundary.
    */
  def nodeEnclosingRange(editStart: Int, editEnd: Int): Option[RedTree[Tok, Syn]] =
    if editStart < span.start.offset || editEnd > span.end.offset then {
      None
    } else {
      // Descend selecting the child whose span fully covers `[editStart, editEnd]`, building exactly
      // one RedTree per level (see [[descend]]). Equivalent to the old
      // `children.find(...).flatMap(_.nodeEnclosingRange).orElse(Some(this))` but without
      // materializing the N−1 unchosen siblings as red nodes.
      Some(descend((childStart, childLen) => editStart >= childStart && editEnd <= childStart + childLen))
    }

  /** Traverses the tree collecting [[ParseError]]s from structural error markers:
    *
    *   - [[GreenNode.Missing]] → `ParseError.EndOfInput(kind.toString, loc)`
    *   - [[GreenNode.Unexpected]] → `ParseError.Custom("Unexpected: <text>", loc)`
    *   - any [[GreenNode.Token]] matching [[isErrorToken]] → `ParseError.Custom("Error token: …")`
    *
    * Missing uses `EndOfInput` rather than `Unexpected` because the semantic is "parser expected K
    * but found nothing" — EndOfInput best matches the "expected K, not available" message shape.
    *
    * `isErrorToken` is supplied by the caller so the notion of an "error token kind" stays
    * language-specific; the default language reports its `Tokens.Error` kind via an extension that
    * fills in the predicate.
    */
  def validateWith(isErrorToken: Tok => Boolean): List[ParseError] = {
    // Explicit work-stack pre-order (was recursive — overflows on deep trees). Each popped node
    // prepends its error (if any) to `acc`; children are pushed leftmost-first so visit order
    // matches the old `error :: acc` then `children.foldLeft` walk. Reverse once at the end for
    // document order — identical output to the recursive version.
    var acc: List[ParseError] = Nil
    var stack: List[RedTree[Tok, Syn]] = this :: Nil
    while stack.nonEmpty do {
      val node = stack.head
      stack = stack.tail
      node.green match {
        case GreenNode.Token(k, text) if isErrorToken(k) =>
          acc = ParseError.Custom(s"Error token: $text", node.location) :: acc
        case GreenNode.Missing(k) =>
          acc = ParseError.EndOfInput(k.toString, node.location) :: acc
        case GreenNode.Unexpected(_, _) =>
          acc = ParseError.Custom(s"Unexpected: ${node.text}", node.location) :: acc
        case _ => ()
      }
      stack = node.children.foldRight(stack)(_ :: _)
    }
    acc.reverse
  }

  /** Classify this node into one of four kinds — [[NodeKind.TokenK]], [[NodeKind.TreeK]],
    * [[NodeKind.MissingK]], [[NodeKind.UnexpectedK]]. Prefer pattern-matching [[green]] directly
    * when the caller needs the payload; this accessor is for quick "what is this thing" checks.
    */
  def kind: NodeKind[Tok, Syn] = green match {
    case GreenNode.Token(k, _) => NodeKind.TokenK(k)
    case GreenNode.Tree(k, _, _) => NodeKind.TreeK(k)
    case GreenNode.Missing(k) => NodeKind.MissingK(k)
    case GreenNode.Unexpected(_, _) => NodeKind.UnexpectedK()
  }

  /** True for a [[GreenNode.Token]]. Missing and Unexpected are NOT considered tokens — they are
    * their own categories. Legacy users that want "any leaf" can use `!isTree`.
    */
  def isToken: Boolean = green match {
    case GreenNode.Token(_, _) => true
    case _ => false
  }

  /** True for a [[GreenNode.Tree]]. Missing and Unexpected are NOT trees even though Unexpected
    * carries children — they are error markers that happen to wrap source text.
    */
  def isTree: Boolean = green match {
    case GreenNode.Tree(_, _, _) => true
    case _ => false
  }

  /** True iff the green is a [[GreenNode.Missing]] placeholder. */
  def isMissing: Boolean = green match {
    case GreenNode.Missing(_) => true
    case _ => false
  }

  /** True iff the green is a [[GreenNode.Unexpected]] wrapper over skipped tokens. */
  def isUnexpected: Boolean = green match {
    case GreenNode.Unexpected(_, _) => true
    case _ => false
  }

  /** Syntax kind if this is a [[GreenNode.Tree]], otherwise [[None]]. Missing and Unexpected are
    * never reparseable — returning [[None]] keeps `findReparseRegion` from accidentally landing on
    * an error marker as a reparse boundary.
    */
  def syntaxKind: Option[Syn] = green match {
    case GreenNode.Tree(k, _, _) => Some(k)
    case _ => None
  }

  /** Token kind if this is a [[GreenNode.Token]], otherwise [[None]]. */
  def tokenKind: Option[Tok] = green match {
    case GreenNode.Token(k, _) => Some(k)
    case _ => None
  }

  /** Kind of token the parser was expecting, if this is a [[GreenNode.Missing]] placeholder. */
  def missingKind: Option[Tok] = green match {
    case GreenNode.Missing(k) => Some(k)
    case _ => None
  }

  /** Find the nearest ancestor (including self) that is a reparsable boundary.
    *
    * Reparsable boundaries are syntax nodes that form natural units for incremental reparsing.
    * Typically these are blocks, functions, statements, or other self-contained constructs.
    *
    * The search starts at this node and walks up to ancestors until finding one whose kind is in
    * the reparsable set.
    *
    * @param reparsableKinds
    *   The set of syntax kinds that are reparsable boundaries
    * @return
    *   The nearest reparsable ancestor, or None if none found
    */
  def findReparseAncestor(reparsableKinds: Set[Syn]): Option[RedTree[Tok, Syn]] = {
    // Iterative walk up the parent chain (was recursive — the ancestor chain is depth-bounded and
    // overflows on deep trees, just like the downward ops). Returns the nearest self-or-ancestor
    // whose kind is reparsable, or None at the root.
    var current: Option[RedTree[Tok, Syn]] = Some(this)
    var result: Option[RedTree[Tok, Syn]] = None
    var done = false
    while !done do {
      current match {
        case Some(node) =>
          node.syntaxKind match {
            case Some(k) if reparsableKinds.contains(k) =>
              result = Some(node)
              done = true
            case _ => current = node.parent
          }
        case None => done = true
      }
    }
    result
  }

  /** Find the smallest ancestor containing a given offset range that is reparsable.
    *
    * This is the key operation for incremental parsing - it finds the minimal subtree that needs to
    * be reparsed after an edit at the given range.
    *
    * @param editStart
    *   Start offset of the edit (inclusive)
    * @param editEnd
    *   End offset of the edit (exclusive)
    * @param reparsableKinds
    *   The set of syntax kinds that are reparsable boundaries
    * @return
    *   The smallest reparsable ancestor containing the edit range
    */
  def findReparseRegion(
    editStart: Int,
    editEnd: Int,
    reparsableKinds: Set[Syn]
  ): Option[RedTree[Tok, Syn]] =
    nodeEnclosingRange(editStart, editEnd).flatMap(_.findReparseAncestor(reparsableKinds))

  /** Get all ancestor nodes from this node up to the root.
    *
    * Returns ancestors in order from immediate parent to root.
    */
  def ancestors: List[RedTree[Tok, Syn]] = {
    // Iterative walk up the parent chain (was recursive — depth-bounded, overflows on deep trees).
    // Prepend each parent then reverse once, yielding immediate-parent-to-root order as before.
    var acc: List[RedTree[Tok, Syn]] = Nil
    var current = this.parent
    while current.isDefined do {
      val p = current.get
      acc = p :: acc
      current = p.parent
    }
    acc.reverse
  }

  /** Build the path from root to this node as a vector of child indices, suitable for
    * [[TreeSplicing.replaceAt]].
    *
    * Sibling disambiguation is by [[childIndex]] — the index assigned at construction. Replaces two
    * prior candidate keys: offset (collapses zero-width siblings onto the same index) and
    * green-node reference identity (collapses structurally-equal siblings, which happens when
    * greens are interned). `childIndex` survives both equivalences.
    */
  def pathFromRoot: Vector[Int] = {
    // Iterative walk up the parent chain (was recursive — depth-bounded, overflows on deep trees).
    // Prepend each node's childIndex while a parent exists (the root contributes none), producing
    // root-to-this child indices identically to the recursive version.
    var acc = Vector.empty[Int]
    var current = this
    while current.parent.isDefined do {
      acc = current.childIndex +: acc
      current = current.parent.get
    }
    acc
  }

  override def toString: String = {
    val kindStr = kind match {
      case NodeKind.TokenK(k) => s"Token($k)"
      case NodeKind.TreeK(k) => s"Tree($k)"
      case NodeKind.MissingK(k) => s"Missing($k)"
      case NodeKind.UnexpectedK() => "Unexpected"
    }
    s"RedTree($kindStr, offset=$offset, length=$length)"
  }
}

object RedTree {

  /** Create root red tree at offset 0.
    *
    * This is the main entry point for creating red trees from green nodes. Root `childIndex` is `0`
    * by convention — the root has no parent and therefore no meaningful sibling index.
    */
  def apply[Tok, Syn](green: GreenNodeOf[Tok, Syn]): RedTree[Tok, Syn] =
    new RedTree(green, 0, None, 0)

  /** Create red tree at specific offset with parent. Typically used internally when constructing
    * child red trees; callers must supply the `childIndex` that positions this red in its parent's
    * `children` vector.
    */
  def apply[Tok, Syn](
    green: GreenNodeOf[Tok, Syn],
    offset: Int,
    parent: Option[RedTree[Tok, Syn]],
    childIndex: Int
  ): RedTree[Tok, Syn] =
    new RedTree(green, offset, parent, childIndex)
}
