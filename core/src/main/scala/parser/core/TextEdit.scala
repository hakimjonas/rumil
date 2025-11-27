package parser.core

/**
 * Represents a text edit operation on source code.
 *
 * A TextEdit describes a range of text to delete and new text to insert
 * in its place. This is the fundamental unit for incremental parsing.
 *
 * Examples:
 * - Insert "x" at offset 5: TextEdit(5, 5, "x")
 * - Delete characters 10-15: TextEdit(10, 15, "")
 * - Replace "foo" with "bar": TextEdit(start, start + 3, "bar")
 *
 * @param startOffset The start of the range to replace (inclusive, 0-indexed)
 * @param endOffset The end of the range to replace (exclusive, 0-indexed)
 * @param newText The text to insert in place of the deleted range
 */
final case class TextEdit(
  startOffset: Int,
  endOffset: Int,
  newText: String
) {
  require(startOffset >= 0, s"startOffset must be non-negative, got $startOffset")
  require(endOffset >= startOffset, s"endOffset ($endOffset) must be >= startOffset ($startOffset)")

  /**
   * The length of text being deleted.
   */
  def deleteLength: Int = endOffset - startOffset

  /**
   * The length of text being inserted.
   */
  def insertLength: Int = newText.length

  /**
   * The net change in document length after this edit.
   * Positive means the document grows, negative means it shrinks.
   */
  def lengthDelta: Int = insertLength - deleteLength

  /**
   * Whether this edit only inserts text (no deletion).
   */
  def isInsertion: Boolean = startOffset == endOffset

  /**
   * Whether this edit only deletes text (no insertion).
   */
  def isDeletion: Boolean = newText.isEmpty

  /**
   * Whether this edit replaces text (both deletes and inserts).
   */
  def isReplacement: Boolean = deleteLength > 0 && insertLength > 0

  /**
   * Apply this edit to source text, producing new source text.
   *
   * @param source The original source text
   * @return The source text with this edit applied
   */
  def apply(source: String): String = {
    require(
      endOffset <= source.length,
      s"endOffset ($endOffset) exceeds source length (${source.length})")
    source.substring(0, startOffset) + newText + source.substring(endOffset)
  }

  /**
   * Check if this edit affects a given offset range.
   *
   * @param rangeStart Start of the range to check (inclusive)
   * @param rangeEnd End of the range to check (exclusive)
   * @return true if this edit overlaps with or is adjacent to the range
   */
  def affects(rangeStart: Int, rangeEnd: Int): Boolean =
    startOffset < rangeEnd && endOffset > rangeStart

  /**
   * Adjust an offset to account for this edit.
   *
   * Offsets before the edit are unchanged.
   * Offsets within the deleted range map to the start of the edit.
   * Offsets after the edit are shifted by the length delta.
   *
   * @param offset The offset to adjust
   * @return The adjusted offset in the post-edit document
   */
  def adjustOffset(offset: Int): Int =
    if (offset <= startOffset) offset
    else if (offset < endOffset) startOffset
    else offset + lengthDelta

  override def toString: String =
    if (isInsertion) s"TextEdit.insert($startOffset, ${newText.take(20).repr})"
    else if (isDeletion) s"TextEdit.delete($startOffset, $endOffset)"
    else s"TextEdit.replace($startOffset, $endOffset, ${newText.take(20).repr})"
}

object TextEdit {

  /**
   * Create an insertion edit (no deletion).
   *
   * @param offset Where to insert
   * @param text The text to insert
   */
  def insert(offset: Int, text: String): TextEdit =
    TextEdit(offset, offset, text)

  /**
   * Create a deletion edit (no insertion).
   *
   * @param startOffset Start of range to delete
   * @param endOffset End of range to delete
   */
  def delete(startOffset: Int, endOffset: Int): TextEdit =
    TextEdit(startOffset, endOffset, "")

  /**
   * Create a replacement edit.
   *
   * @param startOffset Start of range to replace
   * @param endOffset End of range to replace
   * @param newText The replacement text
   */
  def replace(startOffset: Int, endOffset: Int, newText: String): TextEdit =
    TextEdit(startOffset, endOffset, newText)

  /**
   * Compose multiple edits into a sequence that can be applied left-to-right.
   *
   * Edits must be non-overlapping and sorted by offset.
   * Returns edits adjusted so they can be applied sequentially.
   *
   * @param edits The edits to compose (must be sorted by startOffset)
   * @return Adjusted edits that can be applied in order
   */
  def compose(edits: List[TextEdit]): List[TextEdit] = {
    edits.sliding(2).foreach {
      case List(a, b) =>
        require(a.endOffset <= b.startOffset, s"Edits must not overlap: $a and $b")
      case _ => ()
    }

    edits
      .foldLeft((List.empty[TextEdit], 0)) { case ((acc, delta), edit) =>
        val adjusted = TextEdit(
          edit.startOffset + delta,
          edit.endOffset + delta,
          edit.newText
        )
        (acc :+ adjusted, delta + edit.lengthDelta)
      }
      ._1
  }
}

// Extension for string repr in toString
extension (s: String) {
  private[core] def repr: String = "\"" + s.flatMap {
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case '"'  => "\\\""
    case c    => c.toString
  } + "\""
}
