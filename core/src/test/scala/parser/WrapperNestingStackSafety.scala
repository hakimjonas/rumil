package parser

import parser.core.*
import parser.syntax.*

/** Dimension-2 stack safety for the WRAPPER combinators lifted into the trampoline (group 2a:
  * Capture, Named, Expect, LookAhead, NotFollowedBy).
  *
  * Companion to [[StructuralNestingStackSafety]] (which pins Or/Choice/Defer). Each consuming,
  * value-transparent wrapper (Capture/Named/Expect) is placed ON the recursion path so a frame is
  * pushed per structural level — if the wrapper still recursed through interpretI it would
  * overflow. LookAhead/NotFollowedBy are non-consuming (they cannot be a recursion vehicle and add
  * only one frame), so they are exercised wrapping a deep inner parser for correctness.
  */
class WrapperNestingStackSafety extends munit.FunSuite {

  private def parens(depth: Int): String = ("(" * depth) + "x" + (")" * depth)
  private val Depth = 5000

  // Named wraps the recursive descent at every level → one NamedFrame per structural level.
  private lazy val namedAtom: Parser[ParseError, Int] =
    defer {
      (char('(') ~ namedAtom ~ char(')')).map { case ((_, inner), _) => inner + 1 }
        .named("paren")
        .orElse(char('x').map(_ => 0))
    }

  test("structural nesting through Named: depth 5000") {
    val r = namedAtom.run(parens(Depth))
    assert(r.isSuccess, s"Named deep nesting overflowed/failed: ${r.getClass.getSimpleName}")
    assertEquals(r.toOption, Some(Depth))
  }

  // Expect wraps the recursive descent at every level → one ExpectFrame per structural level.
  private lazy val expectAtom: Parser[ParseError, Int] =
    defer {
      (char('(') ~ expectAtom ~ char(')')).map { case ((_, inner), _) => inner + 1 }
        .expect("balanced parens")
        .orElse(char('x').map(_ => 0))
    }

  test("structural nesting through Expect: depth 5000") {
    val r = expectAtom.run(parens(Depth))
    assert(r.isSuccess, s"Expect deep nesting overflowed/failed: ${r.getClass.getSimpleName}")
    assertEquals(r.toOption, Some(Depth))
  }

  // Capture wraps the recursive descent at every level → one CaptureFrame per structural level.
  // Capture returns the consumed slice; recursion is on a Parser[String] vehicle.
  private lazy val captureAtom: Parser[ParseError, String] =
    defer {
      (char('(') ~ captureAtom ~ char(')')).capture
        .orElse(char('x').capture)
    }

  test("structural nesting through Capture: depth 5000") {
    val input = parens(Depth)
    val r = captureAtom.run(input)
    assert(r.isSuccess, s"Capture deep nesting overflowed/failed: ${r.getClass.getSimpleName}")
    // The outermost capture spans the whole balanced string.
    assertEquals(r.toOption, Some(input))
  }

  // LookAhead wrapping a deep inner parser: runs the deep parse, then restores (non-consuming).
  // The deep recursion is carried by Or; LookAhead adds a single frame on top.
  private lazy val plainAtom: Parser[ParseError, Int] =
    defer {
      (char('(') ~ plainAtom ~ char(')')).map { case ((_, inner), _) => inner + 1 }
        .orElse(char('x').map(_ => 0))
    }

  test("LookAhead over a deep parser is non-consuming and correct") {
    val input = parens(Depth)
    // lookAhead(atom) succeeds with the depth but consumes nothing, so a following atom re-parses.
    val p = (plainAtom.lookAhead ~ plainAtom).map { case (peeked, parsed) => (peeked, parsed) }
    val r = p.run(input)
    assert(r.isSuccess, s"LookAhead over deep parser overflowed/failed: ${r.getClass.getSimpleName}")
    assertEquals(r.toOption, Some((Depth, Depth)))
  }

  test("NotFollowedBy over a deep parser: fails when the deep parse succeeds") {
    val input = parens(Depth)
    val r = plainAtom.notFollowedBy.run(input)
    // plainAtom succeeds on the input, so notFollowedBy must fail (without overflowing).
    assert(r.isFailure, s"NotFollowedBy should fail when inner succeeds, got: ${r.getClass.getSimpleName}")
  }

  // --- group 2b: errorsDiscarded-toggling wrappers ---

  // RecoverWith on the recursion path: a RecoverTry frame is pushed per structural level (the
  // primary `( recoverAtom )` recurses 5000 deep). At the base, primary '(' fails on 'x' so the
  // recovery fires; recovery-success returns LazyPartial (carrying the primary error) per
  // interpretI semantics, which then propagates up — so the final result is a Partial with the
  // correct depth value. Either way 5000 RecoverTry frames must not overflow the host stack.
  private lazy val recoverAtom: Parser[ParseError, Int] =
    defer {
      ((char('(') ~ recoverAtom ~ char(')')).map { case ((_, inner), _) => inner + 1 })
        .recover(char('x').map(_ => 0))
    }

  test("structural nesting through RecoverWith primary: depth 5000") {
    val r = recoverAtom.run(parens(Depth))
    assert(r.isSuccess || r.isPartial, s"RecoverWith deep nesting overflowed/failed: ${r.getClass.getSimpleName}")
    assertEquals(r.toOption, Some(Depth))
  }

  // Optional on the recursion path: each level wraps the recursive descent in `.optional`, so an
  // OptionalFrame is pushed per structural level. Depth is recovered by counting Some-nesting.
  private lazy val optionalAtom: Parser[ParseError, Option[Int]] =
    defer {
      (char('(') ~ optionalAtom ~ char(')')).map { case ((_, inner), _) => inner.getOrElse(-1) + 1 }.optional
    }

  test("structural nesting through Optional: depth 5000") {
    // Input has no 'x' base — innermost "()" makes the deepest optional parse fail → None, and each
    // enclosing level adds 1. With Depth pairs of parens and an empty core, the innermost
    // `(` `optionalAtom=None` `)` yields Some(0), then Some(1), ... up to Some(Depth-1).
    val input = ("(" * Depth) + (")" * Depth)
    val r = optionalAtom.run(input)
    assert(r.isSuccess, s"Optional deep nesting overflowed/failed: ${r.getClass.getSimpleName}")
    assertEquals(r.toOption, Some(Some(Depth - 1)))
  }

  test("RecoverWith falls back when primary fails (errors surfaced as Partial)") {
    // primary expects "ab"; on "ax" it commits 'a' then fails; recovery matches "ax".
    val p = (char('a') ~ char('b')).map(_ => "ab").recover(char('a') *> char('x').map(_ => "ax"))
    val r = p.run("ax")
    // recovery succeeds → Partial carrying the primary error, value "ax".
    assert(r.isPartial, s"expected Partial from recovery, got: ${r.getClass.getSimpleName}")
    assertEquals(r.toOption, Some("ax"))
  }

  // --- group 2c: Attempt (errorsDiscarded toggle + type-reifying) ---

  // Attempt on the recursion path. Each level wraps the recursive descent in `.attempt`, reifying
  // the inner Result; the recursion continues by inspecting the reified value. One AttemptFrame is
  // pushed per structural level → would overflow if Attempt still recursed through interpretI.
  private lazy val attemptAtom: Parser[ParseError, Int] =
    defer {
      (char('(') *> attemptAtom <* char(')')).attempt.flatMap {
        case Result.Success(inner, _) => succeed(inner + 1)
        case _ => char('x').map(_ => 0)
      }
    }

  test("structural nesting through Attempt: depth 5000") {
    val r = attemptAtom.run(parens(Depth))
    assert(r.isSuccess, s"Attempt deep nesting overflowed/failed: ${r.getClass.getSimpleName}")
    assertEquals(r.toOption, Some(Depth))
  }

  test("Attempt reifies inner failure as Success(Failure) and backtracks") {
    // attempt(p) always succeeds at the outer level, reifying p's failure; the wrapping `or` then
    // sees an outer success carrying a Failure, but consumes nothing of the committed prefix.
    val p = (char('a') ~ char('b')).map(_ => "ab")
    val r = p.attempt.run("ax")
    assert(r.isSuccess, s"attempt should always outer-succeed, got: ${r.getClass.getSimpleName}")
    r.toOption.get match {
      case Result.Failure(_, _) => () // expected: inner failed, reified
      case other => fail(s"expected reified inner Failure, got $other")
    }
  }

  test("Attempt then alternative: backtracking via attempt-or restores committed input") {
    // attempt(ab) reifies a failure on "ac" without consuming; mapping it back to a parser lets the
    // alternative re-parse from offset 0. Differential against runRecursive for the consumed count.
    val ab = (char('a') ~ char('b')).map(_ => "ab")
    val ac = (char('a') ~ char('c')).map(_ => "ac")
    val p = ab.attempt.flatMap {
      case Result.Success(v, _) => succeed(v)
      case _ => ac
    }
    val tramp = p.run("ac")
    val rec = parser.runtime.runRecursive(p, "ac")
    assertEquals(tramp.toOption, Some("ac"))
    assertEquals(tramp.toOption, rec.toOption)
  }

  // --- group 2d: Many / Many1 / SkipMany (inner parser depth, not iteration count) ---
  //
  // The repetition loop itself was already stack-safe (bounded while). The risk lifted here is the
  // DEPTH of each inner parse: `many(deepParser)` calls the inner parser per iteration, and a
  // deeply-nested inner would recurse on the host stack through interpretI. Each grammar below has
  // ONE item whose parse nests 5000 deep.

  // A single deeply-nested item parser (general, non-simple — so it does NOT hit the Satisfy/
  // StringMatch char-scan fast path).
  private lazy val deepItem: Parser[ParseError, Int] =
    defer {
      (char('(') ~ deepItem ~ char(')')).map { case ((_, inner), _) => inner + 1 }
        .orElse(char('x').map(_ => 0))
    }

  test("Many over a deeply-nested item: depth 5000") {
    // One item, 5000 deep. many() parses it once then fails to start a second (EOF) and stops.
    val r = deepItem.many.run(parens(Depth))
    assert(r.isSuccess, s"Many deep item overflowed/failed: ${r.getClass.getSimpleName}")
    assertEquals(r.toOption, Some(List(Depth)))
  }

  test("Many1 over a deeply-nested item: depth 5000") {
    val r = deepItem.many1.run(parens(Depth))
    assert(r.isSuccess, s"Many1 deep item overflowed/failed: ${r.getClass.getSimpleName}")
    assertEquals(r.toOption, Some(List(Depth)))
  }

  test("SkipMany over a deeply-nested item: depth 5000") {
    val r = deepItem.skipMany.run(parens(Depth))
    assert(r.isSuccess, s"SkipMany deep item overflowed/failed: ${r.getClass.getSimpleName}")
    assertEquals(r.toOption, Some(()))
  }

  test("Many over multiple deep items accumulates all") {
    // Two adjacent deep items: "((x))" "((x))" → List(2, 2).
    val one = ("(" * 2) + "x" + (")" * 2)
    val r = deepItem.many.run(one + one)
    assertEquals(r.toOption, Some(List(2, 2)))
  }

  test("Many general path: consumed + values match runRecursive") {
    // Differential against the non-trampolined reference on a small multi-item input.
    val item = (char('(') ~ char('x') ~ char(')')).map(_ => 1) // general (Zip), not simple
    val p = item.many
    val tramp = p.run("(x)(x)(x)")
    val rec = parser.runtime.runRecursive(p, "(x)(x)(x)")
    assertEquals(tramp.toOption, Some(List(1, 1, 1)))
    assertEquals(tramp.toOption, rec.toOption)
  }

  test("Many1 fails when the first item fails") {
    val item = (char('(') ~ char('x') ~ char(')')).map(_ => 1)
    val r = item.many1.run("nope")
    assert(r.isFailure, s"Many1 should fail with no items, got: ${r.getClass.getSimpleName}")
  }
}
