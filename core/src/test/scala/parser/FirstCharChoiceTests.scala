package parser

import parser.core.*
import parser.syntax.*

class FirstCharChoiceTests extends munit.FunSuite {

  test("dispatches on the first character") {
    val p = firstCharChoice(
      List(
        "a" -> char('a'),
        "b" -> char('b'),
        "c" -> char('c')
      )
    )
    assertEquals(p.run("a"), Result.Success('a', 1))
    assertEquals(p.run("b"), Result.Success('b', 1))
    assertEquals(p.run("c"), Result.Success('c', 1))
  }

  test("routes every character in a key to the same parser") {
    val p = firstCharChoice(
      List(
        "abc" -> succeed("ABC")
      )
    )
    assertEquals(p.run("a"), Result.Success("ABC", 0))
    assertEquals(p.run("b"), Result.Success("ABC", 0))
    assertEquals(p.run("c"), Result.Success("ABC", 0))
  }

  test("does not consume the dispatched character itself") {
    val p = firstCharChoice(List("a" -> succeed("kept")))
    assertEquals(p.run("a"), Result.Success("kept", 0))
  }

  test("runs the fallback when no key matches") {
    val p = firstCharChoice(List("a" -> char('a')), fallback = Some(char('z')))
    assertEquals(p.run("z"), Result.Success('z', 1))
  }

  test("fails with the dispatch characters named when no key matches and no fallback") {
    val p = firstCharChoice(List("ab" -> char('a'), "c" -> char('c')))
    p.run("x") match {
      case Result.Failure(errors, _) =>
        errors match {
          case ParseError.Unexpected(found, expected, _) :: Nil =>
            assertEquals(found, "x")
            assertEquals(expected, Set("one of \"abc\""))
          case other => fail(s"expected a single Unexpected error, got $other")
        }
      case other => fail(s"expected Failure, got $other")
    }
  }

  test("fails with EndOfInput at end of input") {
    val p = firstCharChoice(List("a" -> char('a')))
    p.run("") match {
      case Result.Failure(errors, _) =>
        errors match {
          case ParseError.EndOfInput(expected, _) :: Nil =>
            assertEquals(expected, "one of \"a\"")
          case other => fail(s"expected a single EndOfInput error, got $other")
        }
      case other => fail(s"expected Failure, got $other")
    }
  }

  test("builds the expected string in declaration order") {
    firstCharChoice(
      List(
        "n" -> succeed(1),
        "tf" -> succeed(1),
        "-0123456789" -> succeed(1)
      )
    ) match {
      case Parser.FirstCharChoice(table, expected, fallback) =>
        assertEquals(expected, "ntf-0123456789")
        assertEquals(table.keySet, Set('n', 't', 'f', '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9'))
        assertEquals(fallback, None)
      case other => fail(s"expected FirstCharChoice, got $other")
    }
  }

  test("rejects empty dispatch keys") {
    intercept[IllegalArgumentException] {
      firstCharChoice(List("" -> char('a')))
    }
  }

  test("rejects duplicate leading characters across keys") {
    intercept[IllegalArgumentException] {
      firstCharChoice(List("ab" -> char('a'), "b" -> char('b')))
    }
  }
}
