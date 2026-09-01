package parser.core

import scala.collection.immutable.Vector

/** Tree splicing operations for [[GreenNode]].
  *
  * Provides efficient operations to replace subtrees in an immutable green tree structure. These
  * operations are fundamental to incremental parsing — we parse a subtree and splice it back into
  * the original.
  *
  * All operations preserve the immutability of [[GreenNode]] — they create new nodes rather than
  * modifying existing ones. Structural sharing ensures that unchanged subtrees are reused.
  */
object TreeSplicing {

  /** Path to a node in a green tree, represented as child indices.
    *
    * An empty path refers to the root node. `Path(0, 2)` means: first child's third child.
    */
  type TreePath = Vector[Int]

  /** Replace the node at the given path with a new node.
    *
    * Creates a new tree with structural sharing — only nodes on the path from root to replacement
    * are newly allocated.
    */
  def replaceAt[Tok, Syn](
    root: GreenNodeOf[Tok, Syn],
    path: TreePath,
    replacement: GreenNodeOf[Tok, Syn]
  ): Option[GreenNodeOf[Tok, Syn]] =
    if path.isEmpty then Some(replacement)
    else replaceAtPath(root, path, 0, replacement)

  private def replaceAtPath[Tok, Syn](
    node: GreenNodeOf[Tok, Syn],
    path: TreePath,
    pathIdx: Int,
    replacement: GreenNodeOf[Tok, Syn]
  ): Option[GreenNodeOf[Tok, Syn]] = {
    // Two-pass iterative splice (was recursive in path length — overflows on deep paths). Pass 1:
    // walk down `path` collecting each (Tree kind, children, chosen childIdx, parent width) on a
    // stack, bailing to None on any non-Tree or out-of-range index — exactly the old guards. Pass
    // 2: rebuild bottom-up, `updated`-ing each level's chosen child, which is the rewritten subtree
    // from below (or `replacement` at the deepest level). Structural sharing is preserved: only
    // nodes on the path are reallocated, same as the recursive version.
    var frames: List[(Syn, Vector[GreenNodeOf[Tok, Syn]], Int, Int)] = Nil
    var current = node
    var idx = pathIdx
    var failed = false
    var done = false
    while !done do {
      current match {
        case GreenNode.Tree(kind, children, width) =>
          val childIdx = path(idx)
          if childIdx < 0 || childIdx >= children.length then {
            failed = true
            done = true
          } else {
            frames = (kind, children, childIdx, width) :: frames
            if idx == path.length - 1 then done = true
            else {
              current = children(childIdx)
              idx += 1
            }
          }
        case _ =>
          failed = true
          done = true
      }
    }
    if failed then None
    else {
      // Rebuild bottom-up. `frames` is innermost-first (it was prepended on the way down). The new
      // parent width is NOT a re-sum of all N siblings (that was O(width) per level → O(depth·width)
      // total): only the spliced child changed, so the new width is the O(1) delta
      // `oldParentWidth - oldChildWidth + newChildWidth`. `treeWithWidth` takes that precomputed
      // width, so each level is O(1) (plus the O(log₃₂ N) `Vector.updated`) and the whole splice is
      // O(depth) — no deep width sum, behaviorally identical width to the old `treeOfVec` rebuild.
      var rebuilt = replacement
      var rebuiltWidth = GreenNode.textLength(replacement)
      frames.foreach { case (kind, children, childIdx, oldParentWidth) =>
        val oldChildWidth = GreenNode.textLength(children(childIdx))
        val newParentWidth = oldParentWidth - oldChildWidth + rebuiltWidth
        rebuilt = GreenNode.treeWithWidth(kind, children.updated(childIdx, rebuilt), newParentWidth)
        rebuiltWidth = newParentWidth
      }
      Some(rebuilt)
    }
  }

  /** Replace multiple children at once in a tree node.
    *
    * More efficient than multiple single replacements when updating several children of the same
    * parent.
    */
  def replaceChildRange[Tok, Syn](
    root: GreenNodeOf[Tok, Syn],
    path: TreePath,
    startChildIdx: Int,
    endChildIdx: Int,
    replacements: Vector[GreenNodeOf[Tok, Syn]]
  ): Option[GreenNodeOf[Tok, Syn]] =
    findNode(root, path).flatMap {
      case GreenNode.Tree(kind, children, _) =>
        if startChildIdx < 0 || endChildIdx > children.length || startChildIdx > endChildIdx then {
          None
        } else {
          val before = children.take(startChildIdx)
          val after = children.drop(endChildIdx)
          val newChildren = before ++ replacements ++ after
          val newNode = GreenNode.treeOfVec(kind, newChildren)
          if path.isEmpty then Some(newNode)
          else replaceAt(root, path, newNode)
        }
      case GreenNode.Token(_, _) => None
      case GreenNode.Missing(_) => None
      case GreenNode.Unexpected(_, _) => None
    }

  /** Find a node at the given path. */
  def findNode[Tok, Syn](
    root: GreenNodeOf[Tok, Syn],
    path: TreePath
  ): Option[GreenNodeOf[Tok, Syn]] =
    if path.isEmpty then Some(root)
    else findNodeAtPath(root, path, 0)

  private def findNodeAtPath[Tok, Syn](
    node: GreenNodeOf[Tok, Syn],
    path: TreePath,
    pathIdx: Int
  ): Option[GreenNodeOf[Tok, Syn]] = {
    // Iterative walk down `path` (was recursive in path length — overflows on deep paths). Same
    // guards: bail to None on any non-Tree or out-of-range index; return the child at the final
    // path step.
    var current = node
    var idx = pathIdx
    var result: Option[GreenNodeOf[Tok, Syn]] = None
    var done = false
    while !done do {
      current match {
        case GreenNode.Tree(_, children, _) =>
          val childIdx = path(idx)
          if childIdx < 0 || childIdx >= children.length then done = true
          else if idx == path.length - 1 then {
            result = Some(children(childIdx))
            done = true
          } else {
            current = children(childIdx)
            idx += 1
          }
        case _ => done = true
      }
    }
    result
  }

  /** Build a path from a [[RedTree]] node back to the root.
    *
    * The resulting path can be used with [[replaceAt]] to replace this node in the green tree.
    * Sibling disambiguation uses [[RedTree.childIndex]] — see [[RedTree.pathFromRoot]] for the
    * rationale.
    */
  def pathFromRedTree[Tok, Syn](node: RedTree[Tok, Syn]): TreePath = {
    // Iterative walk up the parent chain (was recursive — depth-bounded, overflows on deep trees).
    // Same result as `RedTree.pathFromRoot`: root-to-node child indices.
    var acc = Vector.empty[Int]
    var current = node
    while current.parent.isDefined do {
      acc = current.childIndex +: acc
      current = current.parent.get
    }
    acc
  }

  /** Calculate the text length of a node. Delegates to [[GreenNode.textLength]]. */
  def nodeLength[Tok, Syn](node: GreenNodeOf[Tok, Syn]): Int = GreenNode.textLength(node)

  /** Find the path to the deepest node containing a given offset. */
  def pathToOffset[Tok, Syn](root: GreenNodeOf[Tok, Syn], offset: Int): TreePath = {
    // `findChild` scans siblings left-to-right (iterative — was recursive over width, which
    // overflows on a flat tree's wide child vector), summing lengths to locate the child whose
    // half-open span contains `offset`.
    def findChild(
      children: Vector[GreenNodeOf[Tok, Syn]],
      startOffset: Int
    ): Option[(GreenNodeOf[Tok, Syn], Int, Int)] = {
      var i = 0
      var childOffset = startOffset
      var found: Option[(GreenNodeOf[Tok, Syn], Int, Int)] = None
      while found.isEmpty && i < children.length do {
        val child = children(i)
        val childLen = nodeLength(child)
        if offset >= childOffset && offset < childOffset + childLen then found = Some((child, childOffset, i))
        else {
          childOffset += childLen
          i += 1
        }
      }
      found
    }

    // Iterative descent (was recursive over tree DEPTH — overflows on deep trees). Walks down,
    // appending the chosen child index at each Tree level; stops at the deepest containing node.
    // The empty-path / not-found cases below reproduce the recursive version's returns exactly.
    var node = root
    var nodeOffset = 0
    var acc = Vector.empty[Int]
    var result = Vector.empty[Int]
    var done = false
    while !done do {
      done = true // every arm terminates except the Tree-with-matching-child descent, which re-arms
      node match {
        case GreenNode.Token(_, text) =>
          result = if offset >= nodeOffset && offset < nodeOffset + text.length then acc else Vector.empty

        case GreenNode.Missing(_) =>
          // Zero-width leaf — only "contains" its own offset, and only vacuously (no range).
          result = Vector.empty

        case GreenNode.Unexpected(_, _) =>
          // Recovery marker is an atomic unit for splicing; don't descend past it even though
          // it carries children. Callers that need the tokens can still walk `RedTree.children`.
          result = acc

        case GreenNode.Tree(_, children, _) =>
          findChild(children, nodeOffset) match {
            case Some((child, childOffset, idx)) =>
              acc = acc :+ idx
              node = child
              nodeOffset = childOffset
              done = false
            case None =>
              result = if offset == nodeOffset && children.isEmpty then acc else Vector.empty
          }
      }
    }
    result
  }
}
