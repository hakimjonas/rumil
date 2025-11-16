package parser.core

import scala.collection.immutable.Vector

/**
 * RedTree - Position-aware view over GreenNode.
 *
 * Inspired by rust-analyzer's Rowan library, RedTree provides an ephemeral,
 * position-aware interface over the immutable GreenNode structure.
 *
 * Key design principles:
 * - Green trees are position-independent (immutable, cacheable)
 * - Red trees compute positions on-demand (cheap views)
 * - Parent/sibling navigation without storing back-pointers
 * - Lazy computation of spans and locations
 *
 * RedTree instances are lightweight - they only store a reference to the
 * green node, an offset, and an optional parent reference.
 *
 * @param green The underlying green node
 * @param offset The absolute offset of this node in the source
 * @param parent Optional parent red tree node
 */
final class RedTree private (
  val green: GreenNode,
  val offset: Int,
  val parent: Option[RedTree]
) {

  /**
   * Absolute span of this node in source.
   *
   * Computed from the green node's span and this red node's offset.
   * For tokens, returns the span directly adjusted by offset.
   * For trees, computes from first to last child.
   */
  lazy val span: Span = green match {
    case GreenNode.Token(_, _, greenSpan) =>
      // Adjust the green span by the red tree's offset
      val start = (
        line = greenSpan.start.line,
        column = greenSpan.start.column,
        offset = offset
      )
      val length = greenSpan.end.offset - greenSpan.start.offset
      val end = (
        line = greenSpan.end.line,
        column = greenSpan.end.column,
        offset = offset + length
      )
      (start = start, end = end)

    case GreenNode.Tree(_, children) =>
      if (children.isEmpty) {
        val loc = (line = 1, column = 1, offset = offset)
        (start = loc, end = loc)
      } else {
        // First child starts at our offset
        var childOffset = offset
        val firstChildSpan = GreenNode.span(children.head)
        val start = (
          line = firstChildSpan.start.line,
          column = firstChildSpan.start.column,
          offset = childOffset
        )

        // Compute offset of last child
        children.dropRight(1).foreach { child =>
          val childSpan = GreenNode.span(child)
          childOffset += (childSpan.end.offset - childSpan.start.offset)
        }

        val lastChildSpan = GreenNode.span(children.last)
        val lastLength = lastChildSpan.end.offset - lastChildSpan.start.offset
        val end = (
          line = lastChildSpan.end.line,
          column = lastChildSpan.end.column,
          offset = childOffset + lastLength
        )

        (start = start, end = end)
      }
  }

  /**
   * Absolute location (line, column) in source.
   *
   * Returns the starting location of this node.
   */
  def location: Location = span.start

  /**
   * The text content of this node.
   *
   * Reconstructs source text from the green node.
   */
  def text: String = GreenNode.toSource(green)

  /**
   * Length of this node in characters.
   */
  def length: Int = span.end.offset - span.start.offset

  /**
   * Children as red trees (position-aware).
   *
   * Each child is wrapped in a RedTree with the correct absolute offset.
   * Computed lazily and cached.
   */
  lazy val children: Vector[RedTree] = green match {
    case GreenNode.Token(_, _, _) => Vector.empty
    case GreenNode.Tree(_, kids) =>
      var childOffset = offset
      kids.map { kid =>
        val red = new RedTree(kid, childOffset, Some(this))
        val kidSpan = GreenNode.span(kid)
        childOffset += (kidSpan.end.offset - kidSpan.start.offset)
        red
      }
  }

  /**
   * Navigate to parent node.
   *
   * Returns Some(parent) if this node has a parent, None otherwise.
   */
  def parentNode: Option[RedTree] = parent

  /**
   * Navigate to next sibling.
   *
   * Returns Some(sibling) if this node has a next sibling, None otherwise.
   */
  def nextSibling: Option[RedTree] = parent.flatMap { p =>
    val siblings = p.children
    val index = siblings.indexWhere(_.offset == this.offset)
    if (index >= 0 && index < siblings.length - 1) {
      Some(siblings(index + 1))
    } else {
      None
    }
  }

  /**
   * Navigate to previous sibling.
   *
   * Returns Some(sibling) if this node has a previous sibling, None otherwise.
   */
  def prevSibling: Option[RedTree] = parent.flatMap { p =>
    val siblings = p.children
    val index = siblings.indexWhere(_.offset == this.offset)
    if (index > 0) {
      Some(siblings(index - 1))
    } else {
      None
    }
  }

  /**
   * All descendants in pre-order.
   *
   * Returns a list of all descendant red trees, visiting parents before children.
   */
  def descendants: Vector[RedTree] = {
    def loop(node: RedTree): Vector[RedTree] = {
      node +: node.children.flatMap(loop)
    }
    loop(this).tail // Exclude self
  }

  /**
   * Find node at given offset.
   *
   * Returns the deepest (most specific) node that contains the given offset.
   * Returns None if the offset is outside this tree's span.
   */
  def nodeAt(targetOffset: Int): Option[RedTree] = {
    if (targetOffset < span.start.offset || targetOffset >= span.end.offset) {
      None
    } else {
      // Search children for a more specific match
      children.find { child =>
        targetOffset >= child.span.start.offset && targetOffset < child.span.end.offset
      }.flatMap(_.nodeAt(targetOffset))
        .orElse(Some(this))
    }
  }

  /**
   * Validate structure and collect errors.
   *
   * Traverses the tree looking for error tokens and structural issues.
   * Returns a list of parse errors found in the tree.
   */
  def validate: List[ParseError] = {
    def loop(node: RedTree, acc: List[ParseError]): List[ParseError] = {
      val errors = node.green match {
        case GreenNode.Token(TokenKind.Error, text, _) =>
          ParseError.Custom(s"Error token: $text", node.location) :: acc
        case _ => acc
      }
      node.children.foldLeft(errors)((errs, child) => loop(child, errs))
    }
    loop(this, Nil).reverse
  }

  /**
   * Get the kind of this node.
   */
  def kind: Either[TokenKind, SyntaxKind] = green match {
    case GreenNode.Token(k, _, _) => Left(k)
    case GreenNode.Tree(k, _)     => Right(k)
  }

  /**
   * Check if this is a token node.
   */
  def isToken: Boolean = green match {
    case GreenNode.Token(_, _, _) => true
    case _                        => false
  }

  /**
   * Check if this is a tree node.
   */
  def isTree: Boolean = !isToken

  override def toString: String = {
    val kindStr = kind.fold(k => s"Token($k)", k => s"Tree($k)")
    s"RedTree($kindStr, offset=$offset, length=$length)"
  }
}

object RedTree {

  /**
   * Create root red tree at offset 0.
   *
   * This is the main entry point for creating red trees from green nodes.
   *
   * @param green The green node to wrap
   * @return A red tree view at offset 0 with no parent
   */
  def apply(green: GreenNode): RedTree =
    new RedTree(green, 0, None)

  /**
   * Create red tree at specific offset with parent.
   *
   * This is typically used internally when constructing child red trees.
   *
   * @param green The green node to wrap
   * @param offset The absolute offset in the source
   * @param parent The parent red tree (if any)
   * @return A red tree view at the specified offset
   */
  def apply(green: GreenNode, offset: Int, parent: Option[RedTree]): RedTree =
    new RedTree(green, offset, parent)
}
