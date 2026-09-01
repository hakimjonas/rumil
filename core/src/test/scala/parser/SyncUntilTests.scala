package parser

import munit.FunSuite

import parser.core.*
import parser.core.GreenNode.*
import parser.runtime.run
import parser.syntax.*

/** Tests for the [[syncUntil]] combinator — panic-mode recovery that skips characters until a
  * sync-set member appears (or end of input), wrapping the skipped region in
  * [[GreenNode.Unexpected]] so the tree stays lossless.
  *
  * Paired with [[ErrorNodeTests]] which covers the complementary [[expectToken]]/[[Missing]] half
  * of the SwiftSyntax-style resilient-parse strategy.
  */
class SyncUntilTests extends FunSuite {

  private val digitToken: Parser[ParseError, GreenNode] =
    digit.map(c => Token(TokenKind.Number, c.toString))

  /** success path: inner succeeds, no Unexpected is produced. */
  test("syncUntil: inner success returns the inner value unchanged") {
    val p = syncUntil(digitToken, Set(';', '\n'), TokenKind.Error)
    run(p, "5;") match {
      case Result.Success(tree, consumed) =>
        assertEquals(tree, Token(TokenKind.Number, "5"))
        assertEquals(consumed, 1)
        var sawUnexpected = false
        traverse(tree) {
          case Unexpected(_, _) => sawUnexpected = true
          case _ => ()
        }
        assert(!sawUnexpected, "Success path must not contain an Unexpected marker")
      case other => fail(s"expected Success, got $other")
    }
  }

  /** sync-immediate: inner fails and a sync char sits at the failure offset; the combinator returns
    * a zero-width Unexpected and consumes nothing, so the caller can match the sync char.
    */
  test("syncUntil: inner fails at sync boundary produces zero-width Unexpected") {
    val p = syncUntil(digitToken, Set(';', '\n'), TokenKind.Error)
    run(p, ";rest") match {
      case Result.Partial(tree, errors, consumed) =>
        assertEquals(tree, unexpectedOfVec(Vector.empty))
        assertEquals(consumed, 0)
        assertEquals(GreenNode.toSource(tree), "")
        assert(errors.nonEmpty, "inner errors must be surfaced in Partial")
      case other => fail(s"expected Partial, got $other")
    }
  }

  /** sync-after-tokens: inner fails, some chars are skipped to reach a sync char, which stays
    * unconsumed. Skipped chars become a single Error-kinded token inside an Unexpected wrapper.
    */
  test("syncUntil: fails mid-input, skips to sync char, leaves sync char for caller") {
    val p = syncUntil(digitToken, Set(';', '\n'), TokenKind.Error)
    val input = "xyz;3"
    run(p, input) match {
      case Result.Partial(tree, errors, consumed) =>
        tree match {
          case Unexpected(Vector(Token(TokenKind.Error, skipped)), _) =>
            assertEquals(skipped, "xyz")
          case other => fail(s"expected Unexpected(Error token), got $other")
        }
        assertEquals(consumed, 3, "consumed exactly the skipped chars, not the sync char")
        assertEquals(GreenNode.toSource(tree), "xyz")
        // originalInput == tree.toSource ++ remaining
        assertEquals(GreenNode.toSource(tree) + input.substring(consumed), input)
        // inner error + unexpected-region error both present
        assert(errors.length >= 2, s"expected inner+region errors, got ${errors.length}: $errors")
      case other => fail(s"expected Partial, got $other")
    }
  }

  /** sync-never-found: inner fails and no sync char exists in the remaining input. The combinator
    * skips everything to EOF, wrapping it all in Unexpected. Doesn't catastrophically fail.
    */
  test("syncUntil: sync never found, skips all remaining input") {
    val p = syncUntil(digitToken, Set(';', '\n'), TokenKind.Error)
    val input = "abcdef"
    run(p, input) match {
      case Result.Partial(tree, errors, consumed) =>
        tree match {
          case Unexpected(Vector(Token(TokenKind.Error, skipped)), _) =>
            assertEquals(skipped, "abcdef")
          case other => fail(s"expected Unexpected(Error token), got $other")
        }
        assertEquals(consumed, input.length)
        assertEquals(GreenNode.toSource(tree), input)
        assert(errors.length >= 2, s"expected inner+region errors, got ${errors.length}: $errors")
      case other => fail(s"expected Partial, got $other")
    }
  }

  /** error propagation: the original inner-parser errors must be reachable inside Partial.errors,
    * not discarded. Checked by inspecting error text for the known inner complaint.
    */
  test("syncUntil: original inner errors are preserved in Partial.errors") {
    val p = syncUntil(digitToken, Set(';', '\n'), TokenKind.Error)
    run(p, "xyz;") match {
      case Result.Partial(_, errors, _) =>
        assert(errors.nonEmpty, "expected at least one error")
        // The inner digitToken parser is Satisfy(isDigit, "digit") wrapped in map:
        // on 'x' the error is Unexpected("x", Set("digit"), _).
        val foundInnerError = errors.exists {
          case ParseError.Unexpected(found, expected, _) =>
            found == "x" && expected.contains("digit")
          case _ => false
        }
        assert(foundInnerError, s"inner digit error not found in: $errors")
      case other => fail(s"expected Partial, got $other")
    }
  }

  /** lossless invariant, aggregated over cases 2–4: tree.toSource ++ remaining == originalInput.
    * The sync char is unconsumed (cases 2, 3); on never-found (case 4) "remaining" is empty.
    */
  test("syncUntil: tree.toSource ++ remaining == originalInput (lossless)") {
    val p = syncUntil(digitToken, Set(';', '\n'), TokenKind.Error)

    val cases = List(
      ";rest", // sync-immediate
      "xyz;3", // sync-after-tokens
      "abc\ndef", // sync-after-tokens, newline as sync
      "abcdef" // sync-never-found
    )
    cases.foreach { input =>
      run(p, input) match {
        case Result.Success(tree, consumed) =>
          assertEquals(GreenNode.toSource(tree) + input.substring(consumed), input, clue = input)
        case Result.Partial(tree, _, consumed) =>
          assertEquals(GreenNode.toSource(tree) + input.substring(consumed), input, clue = input)
        case other => fail(s"expected Success or Partial for '$input', got $other")
      }
    }
  }

  /** The Unexpected-region error carries the skipped text, so diagnostics can show what went wrong.
    */
  test("syncUntil: unexpected-region error text contains the skipped content") {
    val p = syncUntil(digitToken, Set(';'), TokenKind.Error)
    run(p, "abc;5") match {
      case Result.Partial(_, errors, _) =>
        val regionErr = errors.collectFirst {
          case ParseError.Custom(msg, _) if msg.contains("abc") => msg
        }
        assert(regionErr.isDefined, s"expected Custom error mentioning 'abc', got: $errors")
      case other => fail(s"expected Partial, got $other")
    }
  }

  /** Multi-char sync set: first member encountered stops the skip. */
  test("syncUntil: stops at first sync char of any kind in the set") {
    val p = syncUntil(digitToken, Set(';', '\n', '}'), TokenKind.Error)
    run(p, "abc}rest") match {
      case Result.Partial(tree, _, consumed) =>
        assertEquals(GreenNode.toSource(tree), "abc")
        assertEquals(consumed, 3)
      case other => fail(s"expected Partial, got $other")
    }
  }

  /** Valid-prefix behavior: `syncUntil` is all-or-nothing recovery. If inner consumed tokens before
    * failing, those tokens become part of the Unexpected region rather than surviving as partial
    * structure.
    *
    * Pinned by this test: an inner parser that consumes two digits then requires a third char;
    * failure happens after two chars consumed. The Unexpected wrapper contains all three chars (two
    * consumed + one preceding-sync), not just the failing one. A future `syncUntilCommitted`
    * variant could behave differently; this test ensures the current `syncUntil` does not.
    */
  test("syncUntil: inner's valid prefix is rolled into the Unexpected region, not preserved") {
    // threeDigits = digit ~ digit ~ digit — consumes 2 chars on "12x" before failing on 'x'.
    val threeDigits: Parser[ParseError, GreenNode] =
      (digit ~ digit ~ digit).map { case ((a, b), c) =>
        treeOfVec(
          SyntaxKind.Expression,
          Vector(
            Token(TokenKind.Number, a.toString),
            Token(TokenKind.Number, b.toString),
            Token(TokenKind.Number, c.toString)
          )
        )
      }
    val p = syncUntil(threeDigits, Set(';'), TokenKind.Error)
    val input = "12x;rest"
    run(p, input) match {
      case Result.Partial(tree, _, consumed) =>
        // The entire failed-prefix region ("12x") becomes part of Unexpected.
        tree match {
          case Unexpected(Vector(Token(TokenKind.Error, skipped)), _) =>
            assertEquals(
              skipped,
              "12x",
              clue = "expected the valid '12' prefix to be rolled into Unexpected alongside the failing 'x', " +
                "not preserved as partial structure"
            )
          case other => fail(s"expected Unexpected(Error token), got $other")
        }
        assertEquals(consumed, 3)
        // Sync char ';' is left unconsumed; lossless round-trip still holds.
        assertEquals(GreenNode.toSource(tree) + input.substring(consumed), input)
      case other => fail(s"expected Partial, got $other")
    }
  }
}
