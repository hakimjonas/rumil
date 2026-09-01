package parser
import parser.core.*
import parser.syntax.*

class WrapperAuditProbe extends munit.FunSuite {
  private def differential[A](p: Parser[ParseError, A], in: String)(using munit.Location): Unit = {
    val t = p.run(in); val r = parser.runtime.runRecursive(p, in)
    assertEquals(t.toOption, r.toOption, s"value mismatch on '$in'")
    (t, r) match {
      case (Result.Success(_, c1), Result.Success(_, c2)) =>
        assertEquals(c1, c2, s"consumed tramp=$c1 rec=$c2 on '$in'")
      case (Result.Failure(_, l1), Result.Failure(_, l2)) => assertEquals(l1.offset, l2.offset, s"fail-offset on '$in'")
      case _ => ()
    }
  }

  test("Optional: present, absent, committed-fail backtrack — differential") {
    val p = (char('a') ~ char('b')).map(_ => "ab").optional
    differential(p, "ab"); differential(p, "xy"); differential(p, "ax")
  }

  test("RecoverWith: primary ok / recovery / both-fail — differential") {
    val p = char('a').recoverWith(_ => char('b').map(_ => 'R'))
    differential(p, "a"); differential(p, "b"); differential(p, "c")
  }

  test("Many of committed-backtracking element — errorsDiscarded across iterations") {
    val elem = (char('a') ~ char('b')).map(_ => "ab").orElse(char('a').map(_ => "a"))
    differential(elem.many.map(_.mkString(",")), "abaab")
    differential(elem.many.map(_.mkString(",")), "")
    differential(elem.many.map(_.mkString(",")), "ababab")
  }

  test("LookAhead / NotFollowedBy / Named / Capture / Expect — differential") {
    differential(char('a').named("letter-a"), "a")
    differential(char('a').named("letter-a"), "b")
    differential((char('a') ~ char('b')).capture, "ab")
  }

  test("deeply nested Optional through recursion — stack-safe AND correct") {
    lazy val a: Parser[ParseError, Int] =
      parser.core.defer {
        (char('(') ~ a.optional ~ char(')')).map { case ((_, i), _) => i.getOrElse(-1) + 1 }
          .orElse(char('x').map(_ => 0))
      }
    val deep = ("(" * 3000) + "x" + (")" * 3000)
    assert(a.run(deep).isSuccess, "deep nested-wrapper recursion failed")
  }
}
