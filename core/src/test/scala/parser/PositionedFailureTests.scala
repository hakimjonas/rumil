package parser

import munit.FunSuite

import parser.core.*
import parser.syntax.*

/** The positioned-failure primitives: [[failWith]] carries the position where the failure happens
  * (unlike a pre-built `fail` error, which cannot know it), and `offset` exposes the current input
  * position for attaching source locations to parsed values.
  */
class PositionedFailureTests extends FunSuite {

  test("failWith reports the position where the failure happened") {
    val p = char('a') *> char('b') *> failWith("no third character allowed")
    p.run("abc") match {
      case Result.Failure(ParseError.Custom(message, loc) :: Nil, furthest) =>
        assertEquals(message, "no third character allowed")
        assertEquals(furthest.offset, 2)
        assertEquals(loc.offset, 2)
        assertEquals(loc.line, 1)
        assertEquals(loc.column, 3)
      case other => fail(s"expected a positioned Custom failure, got $other")
    }
  }

  test("offset exposes the current position without consuming") {
    val p = for {
      _ <- string("hello")
      at <- offset
    } yield at
    assertEquals(p.run("hello!").toOption, Some(5))
  }

  test("failWith participates in alternation like fail") {
    val p = failWith("never") | char('x')
    assertEquals(p.run("x").toOption, Some('x'))
  }
}
