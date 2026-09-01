package parser

import parser.core.*
import parser.syntax.*

/** Dimension-2 stack safety: deep STRUCTURAL nesting (sub-parse re-entry through
  * `Or`/`Choice`/wrapper combinators and recursive `defer`), as opposed to dimension-1 flat
  * `~`/`flatMap` chains (covered by StackSafetyTests).
  *
  * Before the full-trampoline-integration fix, a recursive grammar atom := '(' atom ')' | 'x'
  * overflowed the native stack between depth 30 and 100. These tests pin the fix: with
  * `TrampolineOpt` lifting `Defer` re-entry onto the heap continuation, nesting depth is bounded by
  * heap, not stack — depth 200K parses in tens of milliseconds.
  */
class StructuralNestingStackSafety extends munit.FunSuite {

  private def parens(depth: Int): String = ("(" * depth) + "x" + (")" * depth)

  // Recursion via defer; structural nesting via the parenthesized branch; the
  // recursion point is the Or between recursive and base case.
  private lazy val atom: Parser[ParseError, Int] =
    defer {
      (char('(') ~ atom ~ char(')')).map { case ((_, inner), _) => inner + 1 }
        .orElse(char('x').map(_ => 0))
    }

  test("structural nesting via Or: depth 5000") {
    val r = atom.run(parens(5000))
    assert(r.isSuccess, s"deep paren nesting overflowed/failed: ${r.getClass.getSimpleName}")
    assertEquals(r.toOption, Some(5000))
  }

  test("structural nesting via Or: depth 200000 (heap-bounded, no stack overflow)") {
    val r = atom.run(parens(200000))
    assert(r.isSuccess, s"deep paren nesting overflowed/failed: ${r.getClass.getSimpleName}")
    assertEquals(r.toOption, Some(200000))
  }
}
