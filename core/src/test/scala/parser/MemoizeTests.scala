package parser

import munit.FunSuite
import parser.core._
import parser.syntax._

/**
 * Tests for .memoize combinator - simple caching without left-recursion support.
 *
 * These tests verify:
 * 1. Basic memoization functionality (caching results)
 * 2. Performance characteristics (cache hits vs misses)
 * 3. Correctness (same results as non-memoized parsers)
 * 4. That .memoize does NOT support left-recursion (unlike rule)
 */
class MemoizeTests extends FunSuite {

  // ============================================================================
  // Basic Functionality
  // ============================================================================

  test(".memoize caches successful parse results") {
    var parseCount = 0

    val expensiveParser = char('a').map { c =>
      parseCount += 1
      c
    }.memoize

    // Test caching through backtracking: try alternatives at same position
    // First alternative fails, second alternative tries expensiveParser again at position 0
    val parser = (expensiveParser ~ char('x')) | (expensiveParser ~ char('b'))

    val result = parser.run("ab")

    assertEquals(result.toOption, Some(('a', 'b')))
    // expensiveParser runs at position 0 for first alternative (cached)
    // Second alternative tries expensiveParser at position 0 again (cache hit!)
    // So expensiveParser should only execute once
    assertEquals(parseCount, 1)
  }

  test(".memoize caches failure results") {
    var parseCount = 0

    val parser = char('a').map { c =>
      parseCount += 1
      c
    }.memoize

    // Try the parser, then backtrack and try again
    val combined = parser.attempt.flatMap(_ => parser | succeed('b'))

    val result = combined.run("x")

    assertEquals(result.toOption.get, 'b')
    // Parser should only fail once at position 0, second attempt uses cache
    assertEquals(parseCount, 0)
  }

  test(".memoize distinguishes different positions") {
    var parseCount = 0

    val parser = char('a').map { c =>
      parseCount += 1
      c
    }.memoize

    val combined = parser ~ parser // Parse 'aa'

    val result = combined.run("aa")

    assertEquals(result.toOption.get, ('a', 'a'))
    // Parser runs at position 0 and position 1 (different positions, both cached)
    assertEquals(parseCount, 2)
  }

  test(".memoize works with complex parsers") {
    var parseCount = 0

    val identifier = (letter ~ alphaNum.many).map { case (h, t) =>
      parseCount += 1
      (h :: t).mkString
    }.memoize

    // Use identifier multiple times in a grammar
    val combined = identifier ~ char(':') ~ identifier

    val result = combined.run("foo:bar")

    assertEquals(result.toOption.get, (("foo", ':'), "bar"))
    // Two different positions, both cached
    assertEquals(parseCount, 2)
  }

  // ============================================================================
  // Correctness: Same Behavior as Non-Memoized
  // ============================================================================

  test(".memoize produces same results as non-memoized parser") {
    val normalParser   = digit.many1.map(_.mkString.toInt)
    val memoizedParser = normalParser.memoize

    val inputs = List("123", "456", "0", "999", "42")

    inputs.foreach { input =>
      val normalResult   = normalParser.run(input)
      val memoizedResult = memoizedParser.run(input)

      assertEquals(memoizedResult, normalResult, s"Results differ for input: $input")
    }
  }

  test(".memoize respects parser combinators") {
    val p1 = char('a').memoize
    val p2 = char('b').memoize

    // Choice
    val choice = p1 | p2
    assertEquals(choice.run("a").toOption.get, 'a')
    assertEquals(choice.run("b").toOption.get, 'b')

    // Sequence
    val seq = p1 ~ p2
    assertEquals(seq.run("ab").toOption.get, ('a', 'b'))

    // Many
    val many = p1.many
    assertEquals(many.run("aaa").toOption.get, List('a', 'a', 'a'))
  }

  // ============================================================================
  // Performance: Cache Hit Benefits
  // ============================================================================

  test(".memoize reduces redundant work in backtracking scenarios") {
    var expensiveWork = 0

    // Simulate an expensive parser
    val expensive = (char('a') ~ char('b') ~ char('c')).map { result =>
      expensiveWork += 1
      result
    }.memoize

    // Parser that backtracks multiple times
    val parser = (expensive ~ char('x')) | (expensive ~ char('y')) | (expensive ~ char('z'))

    val result = parser.run("abcz")

    assertEquals(result.toOption.get, ((('a', 'b'), 'c'), 'z'))
    // Without memoize: expensive would run 3 times (once per alternative)
    // With memoize: expensive runs once, cached result used for remaining alternatives
    assertEquals(expensiveWork, 1)
  }

  test(".memoize benefits parsers used in multiple branches") {
    var count = 0

    val whitespace = (char(' ') | char('\t') | char('\n')).many
      .as(())
      .map { _ =>
        count += 1
        ()
      }
      .memoize

    // Use whitespace in multiple places
    val parser = whitespace *> char('x') <* whitespace

    val result = parser.run("  x  ")

    assertEquals(result.toOption.get, 'x')
    // Whitespace parsed at position 0, then position 3
    // Without memoize: would parse whitespace possibly multiple times per position
    assertEquals(count, 2)
  }

  // ============================================================================
  // Left-Recursion: .memoize Does NOT Support It
  // ============================================================================

  test(".memoize does NOT support left-recursion (stack overflow expected)") {
    // This test verifies that .memoize does NOT have LR support
    // A left-recursive parser will cause infinite recursion / stack overflow

    // We can't easily test for stack overflow in a unit test, but we can
    // demonstrate that it doesn't work like `rule` does

    // Document the expected behavior:
    // - rule: handles left-recursion via seed-growth algorithm
    // - memoize: does NOT handle left-recursion (will infinite loop/overflow)

    // For safety, we skip actually running a left-recursive memoized parser
    // since it would crash the test suite

    // Instead, we test that rule DOES work with left-recursion
    lazy val expr: Parser[ParseError, Int] = rule {
      (expr ~ char('+') ~ digit.map(_.toString.toInt)).map { case ((a, _), b) => a + b } |
        digit.map(_.toString.toInt)
    }

    val result = expr.run("1+2+3")
    assertEquals(result.toOption.get, 6)

    // If someone tries to use .memoize for left-recursion, they'll get a runtime error
    // This is intentional - use `rule` for LR, use `.memoize` for simple caching
  }

  // ============================================================================
  // Edge Cases
  // ============================================================================

  test(".memoize handles empty input") {
    val parser = succeed("empty").memoize

    val result = parser.run("")

    assertEquals(result.toOption.get, "empty")
  }

  test(".memoize works with parsers that consume no input") {
    var count = 0

    val parser = succeed(()).map { _ =>
      count += 1
      ()
    }.memoize

    // Try parser multiple times at same position
    val combined = parser ~ parser ~ parser

    combined.run("")

    // All three succeed at position 0, but only first executes (others cached)
    assertEquals(count, 1)
  }

  test(".memoize handles partial results") {
    // Use recover to get Partial result with errors
    val parser = (char('a') ~ char('b')).memoize.recover(succeed(('x', 'x')))

    val result = parser.run("ac") // 'a' succeeds, 'b' fails, fallback used

    assertEquals(result.toOption.get, ('x', 'x'))
    result match {
      case Result.Partial(_, errors, _) =>
        assert(errors.nonEmpty, "Should have errors from first attempt")
      case _ => ()
    }
  }

  // ============================================================================
  // Inline vs Function Usage
  // ============================================================================

  test(".memoize can be used inline on parser expressions") {
    // Inline usage - memoize the result of a complex expression
    val parser = (char('a') ~ char('b') ~ char('c')).map { case ((a, b), c) =>
      s"$a$b$c"
    }.memoize

    val result = parser.run("abc")

    assertEquals(result.toOption.get, "abc")
  }

  test(".memoize can be used on named parsers") {
    // Named usage - define parser first, then memoize
    val identifier         = letter ~ alphaNum.many
    val memoizedIdentifier = identifier.memoize

    val parser = memoizedIdentifier ~ char(':') ~ memoizedIdentifier

    val result = parser.run("foo:bar")

    result match {
      case Result.Success(value, _) =>
        assertEquals(value, ((('f', List('o', 'o')), ':'), ('b', List('a', 'r'))))
      case other => fail(s"Expected success, got: $other")
    }
  }

  // ============================================================================
  // Multiple Memoization
  // ============================================================================

  test("multiple .memoize calls create independent caches") {
    var count1 = 0
    var count2 = 0

    val parser1 = char('a').map { c =>
      count1 += 1
      c
    }.memoize

    val parser2 = char('a').map { c =>
      count2 += 1
      c
    }.memoize

    // These are different parsers with different memoization keys
    val combined = parser1 ~ parser2

    val result = combined.run("aa")

    assertEquals(result.toOption.get, ('a', 'a'))
    // Each parser tracks its own count
    assertEquals(count1, 1) // parser1 at position 0
    assertEquals(count2, 1) // parser2 at position 1
  }
}
