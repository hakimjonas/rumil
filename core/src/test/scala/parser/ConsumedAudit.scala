package parser
import parser.core.*
import parser.syntax.*

/** AUDITOR probe: committed-input backtracking through trampolined Or/Choice. The flagged risk: a
  * left branch that CONSUMES chars then fails must not let the top-down `consumed` accumulator
  * over-count the surviving (right/next) result.
  */
class ConsumedAudit extends munit.FunSuite {

  // left = "ab" (consumes 'a' then needs 'b'); on input "ac" it commits 'a' then fails.
  // right = "ac". Or(left, right) on "ac" must backtrack and succeed via right with consumed=2.
  test("Or: left consumes then fails, right succeeds — consumed correct") {
    val left = char('a') ~ char('b') // commits 'a', fails at 'c'
    val right = (char('a') ~ char('c')).map(_ => "ac")
    val p = left.map(_ => "ab").orElse(right)
    val r = p.run("ac")
    assertEquals(r.toOption, Some("ac"))
    // consumed must be exactly 2, not 3 (1 from committed 'a' + 2 from right)
    r match {
      case Result.Success(_, c) => assertEquals(c, 2, s"consumed over/under-counted: $c")
      case other => fail(s"expected success, got $other")
    }
  }

  // Nested deeper: Or inside a Zip, left of inner Or commits then fails.
  test("Or committed-fail nested in Zip — consumed + value correct") {
    val inner = (char('x') ~ char('y')).map(_ => "xy").orElse(char('x').map(_ => "x"))
    val p = (inner ~ char('z')).map { case (a, _) => a }
    val r = p.run("xz") // inner: left 'x''y' commits 'x' fails at 'z'; right 'x' ok; then 'z'
    assertEquals(r.toOption, Some("x"))
    r match {
      case Result.Success(_, c) => assertEquals(c, 2, s"consumed wrong: $c")
      case other => fail(s"expected success, got $other")
    }
  }

  // Choice committed-input: first alt commits then fails, third matches.
  test("Choice: committed-fail alt then later match — consumed correct") {
    val a = (char('a') ~ char('b')).map(_ => "ab") // commits 'a' fails
    val b = (char('a') ~ char('c')).map(_ => "ac") // commits 'a' fails
    val c = (char('a') ~ char('d')).map(_ => "ad") // matches "ad"
    val p = choice(List(a, b, c))
    val r = p.run("ad")
    assertEquals(r.toOption, Some("ad"))
    r match {
      case Result.Success(_, cc) => assertEquals(cc, 2, s"consumed wrong: $cc")
      case other => fail(s"expected success, got $other")
    }
  }

  // Compare against runRecursive (the non-trampolined reference) for byte-identical consumed.
  test("trampolined vs recursive agree on consumed (committed backtrack)") {
    val left = (char('a') ~ char('b')).map(_ => "ab")
    val right = (char('a') ~ char('c')).map(_ => "ac")
    val p = left.orElse(right)
    val tramp = p.run("ac")
    val rec = parser.runtime.runRecursive(p, "ac")
    assertEquals(tramp.toOption, rec.toOption)
    (tramp, rec) match {
      case (Result.Success(_, c1), Result.Success(_, c2)) => assertEquals(c1, c2, s"tramp=$c1 rec=$c2")
      case _ => fail(s"mismatch: tramp=$tramp rec=$rec")
    }
  }
}
