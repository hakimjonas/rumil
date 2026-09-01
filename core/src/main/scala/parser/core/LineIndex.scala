package parser.core

/** Precomputed sorted offsets of every `\n` in a source document. Enables O(log n) offset → (line,
  * column) lookups after a single O(n) construction pass.
  *
  * Line-terminator policy. `\n` (U+000A) is the sole line terminator for indexing purposes; `\r`
  * (U+000D) is a regular character and contributes to its line's column count. On CRLF input
  * (`"abc\r\ndef"`) the `\r` at offset 3 is column 4 of line 1, the `\n` at offset 4 is column 5 of
  * line 1, and the `d` at offset 5 is column 1 of line 2. On CR-only input (legacy Mac Classic)
  * every character stays on line 1. Callers that want `\r\n` treated as a single terminator, or
  * that target lone-`\r` line endings, must normalize at the editor or LSP boundary before building
  * the index — rumil does not normalize line endings internally.
  *
  * Column encoding. Offsets are JVM `String` indexes, which are UTF-16 code units. Columns are
  * computed as `offset - prevNewlineOffset`, so a supplementary-plane codepoint encoded as a
  * surrogate pair contributes two columns. This matches the LSP default encoding (UTF-16) and the
  * behavior clients get when they use `document.offsetAt` in vscode-style APIs. Callers that need
  * Unicode-codepoint columns (or UTF-8 byte columns for LSP clients that negotiated those
  * encodings) must post-process the returned [[Location]] against the source string; rumil does not
  * convert between encodings.
  */
opaque type LineIndex = Array[Int]

object LineIndex {

  /** Two-pass scan over [[source]]: count newlines, then fill a right-sized primitive int array. */
  def apply(source: String): LineIndex = {
    val len = source.length
    var count = 0
    var i = 0
    while i < len do {
      if source.charAt(i) == '\n' then count += 1
      i += 1
    }
    val arr = new Array[Int](count)
    var j = 0
    i = 0
    while i < len do {
      if source.charAt(i) == '\n' then {
        arr(j) = i
        j += 1
      }
      i += 1
    }
    arr
  }

  extension (idx: LineIndex) {

    /** 1-indexed `(line, column)` for [[offset]].
      *
      *   - `offset = 0` → `(1, 1, 0)`.
      *   - Negative offsets clamp to 0.
      *   - Offsets at or past the last newline resolve to their column on the last line, computed
      *     as `offset - lastNewlineOffset`.
      *
      * Callers that need end-of-source clamping should clamp their input first.
      */
    def locationAt(offset: Int): Location = {
      val clamped = if offset < 0 then 0 else offset
      val raw = java.util.Arrays.binarySearch(idx, clamped)
      // Largest index strictly less than clamped:
      //   raw >= 0 → clamped equals a newline offset; the preceding newline is raw - 1.
      //   raw < 0  → raw = -(insertionPoint) - 1; the preceding newline is insertionPoint - 1.
      val prevNewlineIdx = if raw >= 0 then raw - 1 else -raw - 2
      if prevNewlineIdx < 0 then {
        (line = 1, column = clamped + 1, offset = clamped)
      } else {
        val prevNewline = idx(prevNewlineIdx)
        (line = prevNewlineIdx + 2, column = clamped - prevNewline, offset = clamped)
      }
    }

    /** Rebuilds a [[Span]] with real line/column from a pair of offsets. */
    def spanAt(startOffset: Int, endOffset: Int): Span =
      (start = idx.locationAt(startOffset), end = idx.locationAt(endOffset))
  }

  extension [Tok, Syn](tree: RedTree[Tok, Syn]) {

    /** This node's span with real line/column resolved via the provided [[LineIndex]]. */
    def spanIn(using idx: LineIndex): Span =
      idx.spanAt(tree.offset, tree.offset + tree.length)

    /** This node's starting location with real line/column resolved via the provided [[LineIndex]].
      */
    def locationIn(using idx: LineIndex): Location =
      idx.locationAt(tree.offset)
  }
}
