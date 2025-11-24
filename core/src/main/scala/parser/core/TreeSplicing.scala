package parser.core

import scala.collection.immutable.Vector

/**
 * Tree splicing operations for GreenNode.
 *
 * Provides efficient operations to replace subtrees in an immutable
 * green tree structure. These operations are fundamental to incremental
 * parsing - we parse a subtree and splice it back into the original.
 *
 * All operations preserve the immutability of GreenNode - they create
 * new nodes rather than modifying existing ones. Structural sharing
 * ensures that unchanged subtrees are reused.
 */
object TreeSplicing {

  /**
   * Path to a node in a green tree, represented as child indices.
   *
   * An empty path refers to the root node.
   * Path(0, 2) means: first child's third child.
   */
  type TreePath = Vector[Int]

  /**
   * Replace the node at the given path with a new node.
   *
   * Creates a new tree with structural sharing - only nodes on the
   * path from root to replacement are newly allocated.
   *
   * @param root The root of the tree
   * @param path Path to the node to replace
   * @param replacement The new node to splice in
   * @return A new tree with the replacement, or None if path is invalid
   */
  def replaceAt(root: GreenNode, path: TreePath, replacement: GreenNode): Option[GreenNode] =
    if (path.isEmpty) Some(replacement)
    else replaceAtPath(root, path, 0, replacement)

  private def replaceAtPath(
    node: GreenNode,
    path: TreePath,
    pathIdx: Int,
    replacement: GreenNode
  ): Option[GreenNode] = node match {
    case GreenNode.Token(_, _, _) =>
      // Can't descend into a token
      None

    case GreenNode.Tree(kind, children) =>
      val childIdx = path(pathIdx)
      if (childIdx < 0 || childIdx >= children.length) {
        None
      } else if (pathIdx == path.length - 1) {
        // This is the parent of the node to replace
        val newChildren = children.updated(childIdx, replacement)
        Some(GreenNode.Tree(kind, newChildren))
      } else {
        // Recurse into the child
        replaceAtPath(children(childIdx), path, pathIdx + 1, replacement).map { newChild =>
          val newChildren = children.updated(childIdx, newChild)
          GreenNode.Tree(kind, newChildren)
        }
      }
  }

  /**
   * Replace multiple children at once in a tree node.
   *
   * More efficient than multiple single replacements when updating
   * several children of the same parent.
   *
   * @param root The root tree node (must be a Tree, not Token)
   * @param path Path to the parent node
   * @param startChildIdx Index of first child to replace
   * @param endChildIdx Index after last child to replace (exclusive)
   * @param replacements The new children to splice in
   * @return A new tree with the replacements, or None if invalid
   */
  def replaceChildRange(
    root: GreenNode,
    path: TreePath,
    startChildIdx: Int,
    endChildIdx: Int,
    replacements: Vector[GreenNode]
  ): Option[GreenNode] =
    findNode(root, path).flatMap {
      case GreenNode.Tree(kind, children) =>
        if (startChildIdx < 0 || endChildIdx > children.length || startChildIdx > endChildIdx) {
          None
        } else {
          val before      = children.take(startChildIdx)
          val after       = children.drop(endChildIdx)
          val newChildren = before ++ replacements ++ after
          val newNode     = GreenNode.Tree(kind, newChildren)
          if (path.isEmpty) Some(newNode)
          else replaceAt(root, path, newNode)
        }
      case GreenNode.Token(_, _, _) =>
        None
    }

  /**
   * Find a node at the given path.
   *
   * @param root The root of the tree
   * @param path Path to the node
   * @return The node at the path, or None if invalid
   */
  def findNode(root: GreenNode, path: TreePath): Option[GreenNode] =
    if (path.isEmpty) Some(root)
    else findNodeAtPath(root, path, 0)

  private def findNodeAtPath(node: GreenNode, path: TreePath, pathIdx: Int): Option[GreenNode] =
    node match {
      case GreenNode.Token(_, _, _) => None
      case GreenNode.Tree(_, children) =>
        val childIdx = path(pathIdx)
        if (childIdx < 0 || childIdx >= children.length) {
          None
        } else if (pathIdx == path.length - 1) {
          Some(children(childIdx))
        } else {
          findNodeAtPath(children(childIdx), path, pathIdx + 1)
        }
    }

  /**
   * Build a path from a RedTree node back to the root.
   *
   * The resulting path can be used with replaceAt to replace
   * this node in the green tree.
   *
   * @param node The red tree node
   * @return Path from root to this node
   */
  def pathFromRedTree(node: RedTree): TreePath = {
    def loop(current: RedTree, acc: Vector[Int]): Vector[Int] =
      current.parent match {
        case None         => acc
        case Some(parent) =>
          // Find our index in parent's children
          val siblings = parent.children
          val idx      = siblings.indexWhere(_.offset == current.offset)
          loop(parent, idx +: acc)
      }
    loop(node, Vector.empty)
  }

  /**
   * Adjust spans in a green tree after an edit.
   *
   * Updates all spans in the tree to reflect position changes
   * caused by a text edit. Nodes before the edit are unchanged,
   * nodes after are shifted by the length delta.
   *
   * @param node The node to adjust
   * @param edit The edit that was applied
   * @param nodeStartOffset The absolute offset where this node starts
   * @return A new node with adjusted spans
   */
  def adjustSpans(node: GreenNode, edit: TextEdit, nodeStartOffset: Int): GreenNode = {
    val nodeEndOffset = nodeStartOffset + nodeLength(node)

    // If node is entirely before the edit, no changes needed
    if (nodeEndOffset <= edit.startOffset) {
      node
    }
    // If node is entirely after the edit, shift its span
    else if (nodeStartOffset >= edit.endOffset) {
      shiftSpan(node, edit.lengthDelta)
    }
    // Node overlaps with edit - need to recurse into children
    else {
      node match {
        case t @ GreenNode.Token(kind, text, span) =>
          // Token overlaps with edit - just shift if after edit start
          if (span.start.offset >= edit.endOffset) {
            shiftSpan(t, edit.lengthDelta)
          } else {
            t // Token is at or before edit, keep as is (will be reparsed anyway)
          }

        case GreenNode.Tree(kind, children) =>
          var childOffset = nodeStartOffset
          val newChildren = children.map { child =>
            val adjusted = adjustSpans(child, edit, childOffset)
            childOffset += nodeLength(child)
            adjusted
          }
          GreenNode.Tree(kind, newChildren)
      }
    }
  }

  /**
   * Shift all spans in a node by a fixed delta.
   */
  private def shiftSpan(node: GreenNode, delta: Int): GreenNode = node match {
    case GreenNode.Token(kind, text, span) =>
      val newStart = (
        line = span.start.line,
        column = span.start.column,
        offset = span.start.offset + delta
      )
      val newEnd = (
        line = span.end.line,
        column = span.end.column,
        offset = span.end.offset + delta
      )
      GreenNode.Token(kind, text, (start = newStart, end = newEnd))

    case GreenNode.Tree(kind, children) =>
      GreenNode.Tree(kind, children.map(shiftSpan(_, delta)))
  }

  /**
   * Calculate the text length of a node.
   */
  def nodeLength(node: GreenNode): Int = node match {
    case GreenNode.Token(_, text, _) => text.length
    case GreenNode.Tree(_, children) => children.map(nodeLength).sum
  }

  /**
   * Find the path to the deepest node containing a given offset.
   *
   * @param root The root of the tree
   * @param offset The offset to search for
   * @return Path to the deepest containing node, or empty if offset is outside tree
   */
  def pathToOffset(root: GreenNode, offset: Int): TreePath = {
    def loop(node: GreenNode, nodeOffset: Int, acc: Vector[Int]): Vector[Int] = node match {
      case GreenNode.Token(_, text, _) =>
        if (offset >= nodeOffset && offset < nodeOffset + text.length) acc
        else Vector.empty

      case GreenNode.Tree(_, children) =>
        var childOffset = nodeOffset
        var i           = 0
        while (i < children.length) {
          val child    = children(i)
          val childLen = nodeLength(child)
          if (offset >= childOffset && offset < childOffset + childLen) {
            return loop(child, childOffset, acc :+ i)
          }
          childOffset += childLen
          i += 1
        }
        // Offset not in any child but might be at tree boundary
        if (offset == nodeOffset && children.isEmpty) acc
        else Vector.empty
    }
    loop(root, 0, Vector.empty)
  }
}
