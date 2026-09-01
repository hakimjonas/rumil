package parser

import munit.FunSuite

import parser.core.*
import parser.core.LineIndex.*
import parser.syntax.*

/** Tests for [[LineIndex]] — on-demand 1-indexed line/column resolution for byte offsets.
  *
  * The `(1, 1)` placeholder on [[RedTree.span]] stays the cheap default; callers that want real
  * line/column bring a [[LineIndex]] into scope and use [[spanIn]] / [[locationIn]].
  */
class LineIndexTests extends FunSuite {

  test("single-line source: offsets map to column 1..len+1 on line 1") {
    val idx = LineIndex("hello")
    assertEquals(idx.locationAt(0), (line = 1, column = 1, offset = 0))
    assertEquals(idx.locationAt(3), (line = 1, column = 4, offset = 3))
    // EOF: one past the last character.
    assertEquals(idx.locationAt(5), (line = 1, column = 6, offset = 5))
  }

  test("multi-line source: newline char belongs to its preceding line") {
    val idx = LineIndex("abc\ndef\nghi")
    assertEquals(idx.locationAt(0), (line = 1, column = 1, offset = 0))
    // The '\n' at offset 3 sits on line 1, column 4.
    assertEquals(idx.locationAt(3), (line = 1, column = 4, offset = 3))
    // The 'd' at offset 4 starts line 2.
    assertEquals(idx.locationAt(4), (line = 2, column = 1, offset = 4))
    assertEquals(idx.locationAt(7), (line = 2, column = 4, offset = 7))
    assertEquals(idx.locationAt(8), (line = 3, column = 1, offset = 8))
    // EOF.
    assertEquals(idx.locationAt(11), (line = 3, column = 4, offset = 11))
  }

  test("empty source: offset 0 is (1, 1)") {
    val idx = LineIndex("")
    assertEquals(idx.locationAt(0), (line = 1, column = 1, offset = 0))
  }

  test("multiple consecutive newlines: each blank line is its own line") {
    val idx = LineIndex("a\n\n\nb")
    assertEquals(idx.locationAt(0), (line = 1, column = 1, offset = 0))
    // Offset 1 is the first '\n' — on line 1, column 2.
    assertEquals(idx.locationAt(1), (line = 1, column = 2, offset = 1))
    // Offset 2 is the second '\n' — start of line 2 (which is blank).
    assertEquals(idx.locationAt(2), (line = 2, column = 1, offset = 2))
    // Offset 3 is the third '\n' — start of line 3 (also blank).
    assertEquals(idx.locationAt(3), (line = 3, column = 1, offset = 3))
    // Offset 4 is 'b' — start of line 4.
    assertEquals(idx.locationAt(4), (line = 4, column = 1, offset = 4))
  }

  test("offset past source.length: not clamped; resolves via last newline") {
    val idx = LineIndex("abc\ndef")
    // Source has one '\n' at offset 3. locationAt(7) = column 7 - 3 = 4 on line 2.
    assertEquals(idx.locationAt(7), (line = 2, column = 4, offset = 7))
    // Past the last newline, column grows linearly with offset.
    assertEquals(idx.locationAt(100), (line = 2, column = 97, offset = 100))
  }

  test("source without any newline: column = offset + 1 on line 1 for any offset") {
    val idx = LineIndex("abc")
    assertEquals(idx.locationAt(0), (line = 1, column = 1, offset = 0))
    assertEquals(idx.locationAt(3), (line = 1, column = 4, offset = 3))
  }

  test("locationAt past end of a single-line source uses offset + 1 as the column") {
    val idx = LineIndex("abc")
    assertEquals(idx.locationAt(100), (line = 1, column = 101, offset = 100))
  }

  test("negative offset: clamped to 0 → (1, 1)") {
    val idx = LineIndex("anything\nhere")
    assertEquals(idx.locationAt(-1), (line = 1, column = 1, offset = 0))
    assertEquals(idx.locationAt(-999), (line = 1, column = 1, offset = 0))
  }

  test("spanAt: both endpoints carry real line/column") {
    val idx = LineIndex("hello\nworld")
    val span = idx.spanAt(0, 11)
    assertEquals(span.start, (line = 1, column = 1, offset = 0))
    assertEquals(span.end, (line = 2, column = 6, offset = 11))
  }

  test("spanAt with start == end: zero-width span at a single location") {
    val idx = LineIndex("ab\ncd")
    val span = idx.spanAt(3, 3)
    assertEquals(span.start, (line = 2, column = 1, offset = 3))
    assertEquals(span.end, (line = 2, column = 1, offset = 3))
  }

  test("RedTree.spanIn: with LineIndex in scope returns real line/column; .span unchanged") {
    // Tree spanning two lines: "ab\ncd" as SourceFile(Block(Token("ab"), Whitespace("\n"), Token("cd"))).
    val green: GreenNode =
      GreenNode.treeOfVec(
        SyntaxKind.SourceFile,
        Vector(
          GreenNode.Token(TokenKind.Identifier, "ab"),
          GreenNode.Token(TokenKind.Whitespace, "\n"),
          GreenNode.Token(TokenKind.Identifier, "cd")
        )
      )
    val red = RedTree(green)
    // .span stays (1, 1) placeholder for both endpoints — this is the contract.
    assertEquals(red.span.start, (line = 1, column = 1, offset = 0))
    assertEquals(red.span.end, (line = 1, column = 1, offset = 5))

    // spanIn pulls real line/column from the index.
    given LineIndex = LineIndex("ab\ncd")
    val real = red.spanIn
    assertEquals(real.start, (line = 1, column = 1, offset = 0))
    assertEquals(real.end, (line = 2, column = 3, offset = 5))
    assertEquals(red.locationIn, (line = 1, column = 1, offset = 0))

    // A child: the second "cd" token starts at offset 3, which is line 2, column 1.
    val cdRed = red.children(2)
    assertEquals(cdRed.span.start.offset, 3)
    assertEquals(cdRed.span.start.line, 1) // placeholder still
    assertEquals(cdRed.locationIn, (line = 2, column = 1, offset = 3))
    assertEquals(cdRed.spanIn.end, (line = 2, column = 3, offset = 5))
  }

  test("tab characters count as a single column (no tabstop expansion)") {
    val idx = LineIndex("a\tb")
    // Offset 2 is 'b'; two columns after the start, regardless of tab width.
    assertEquals(idx.locationAt(2), (line = 1, column = 3, offset = 2))
  }

  test("UTF-16 code units: column counts code units, not codepoints") {
    // '\u{1F600}' (emoji) occupies two UTF-16 code units in a Scala String.
    val src = "a😀b" // a + 😀 + b, length 4 in code units
    val idx = LineIndex(src)
    assertEquals(src.length, 4)
    // 'b' sits at code-unit offset 3 → column 4.
    assertEquals(idx.locationAt(3), (line = 1, column = 4, offset = 3))
  }

  test("surrogate pair: each UTF-16 code unit is its own column") {
    val src = "a😀b" // a + 😀 (U+1F600 encoded as high+low surrogates) + b
    val idx = LineIndex(src)
    assertEquals(src.length, 4)
    // a — line 1, column 1.
    assertEquals(idx.locationAt(0), (line = 1, column = 1, offset = 0))
    // High surrogate — line 1, column 2.
    assertEquals(idx.locationAt(1), (line = 1, column = 2, offset = 1))
    // Low surrogate — line 1, column 3. The surrogate pair contributes two columns, not one.
    assertEquals(idx.locationAt(2), (line = 1, column = 3, offset = 2))
    // b — line 1, column 4.
    assertEquals(idx.locationAt(3), (line = 1, column = 4, offset = 3))
  }

  // --- Line-terminator policy: only \n is a terminator; \r is a regular character. ---

  test("CRLF input: \\r counts as a trailing character of the preceding line; \\n ends the line") {
    val idx = LineIndex("a\r\nb")
    // 'a' at offset 0 — line 1, column 1.
    assertEquals(idx.locationAt(0), (line = 1, column = 1, offset = 0))
    // '\r' at offset 1 — still line 1, column 2 (a regular character).
    assertEquals(idx.locationAt(1), (line = 1, column = 2, offset = 1))
    // '\n' at offset 2 — still line 1, column 3 (the newline belongs to its own line per the
    // existing newline-sits-on-its-preceding-line semantics).
    assertEquals(idx.locationAt(2), (line = 1, column = 3, offset = 2))
    // 'b' at offset 3 — first character of line 2.
    assertEquals(idx.locationAt(3), (line = 2, column = 1, offset = 3))
  }

  test("CRLF input with larger prose: each \\r consumes a column on its line") {
    val idx = LineIndex("abc\r\ndef")
    assertEquals(idx.locationAt(0), (line = 1, column = 1, offset = 0))
    // '\r' at offset 3 — column 4 of line 1 (not a terminator).
    assertEquals(idx.locationAt(3), (line = 1, column = 4, offset = 3))
    // '\n' at offset 4 — column 5 of line 1 (the terminator itself sits on line 1).
    assertEquals(idx.locationAt(4), (line = 1, column = 5, offset = 4))
    // 'd' at offset 5 — first character of line 2.
    assertEquals(idx.locationAt(5), (line = 2, column = 1, offset = 5))
  }

  test("CR-only input (legacy Mac Classic): \\r is not a terminator, all characters stay on line 1") {
    val idx = LineIndex("a\rb")
    assertEquals(idx.locationAt(0), (line = 1, column = 1, offset = 0))
    // '\r' at offset 1 — still line 1, column 2.
    assertEquals(idx.locationAt(1), (line = 1, column = 2, offset = 1))
    // 'b' at offset 2 — still line 1, column 3.
    assertEquals(idx.locationAt(2), (line = 1, column = 3, offset = 2))
  }

  test("LF-only input (the canonical case) is unchanged") {
    val idx = LineIndex("a\nb")
    assertEquals(idx.locationAt(0), (line = 1, column = 1, offset = 0))
    assertEquals(idx.locationAt(1), (line = 1, column = 2, offset = 1))
    assertEquals(idx.locationAt(2), (line = 2, column = 1, offset = 2))
  }
}
