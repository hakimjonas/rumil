package parser

import parser.core.*
import parser.syntax.*

class SkipFusionTests extends munit.FunSuite {

  test("zipLeft fuses to SkipRight (keeps left)") {
    zipLeft(char('a'), char('b')) match {
      case Parser.SkipRight(_, _) => ()
      case other => fail(s"zipLeft should fuse to SkipRight, got $other")
    }
  }

  test("zipRight fuses to SkipLeft (keeps right)") {
    zipRight(char('a'), char('b')) match {
      case Parser.SkipLeft(_, _) => ()
      case other => fail(s"zipRight should fuse to SkipLeft, got $other")
    }
  }

  test("zipLeft keeps the left value") {
    assertEquals(zipLeft(char('a'), char('b')).run("ab"), Result.Success('a', 2))
  }

  test("zipRight keeps the right value") {
    assertEquals(zipRight(char('a'), char('b')).run("ab"), Result.Success('b', 2))
  }

  test("<* and *> match zipLeft/zipRight") {
    assertEquals((char('a') <* char('b')).run("ab"), Result.Success('a', 2))
    assertEquals((char('a') *> char('b')).run("ab"), Result.Success('b', 2))
  }

  test("skip propagates a left failure") {
    assert(zipLeft(char('a'), char('b')).run("xb").isFailure)
    assert(zipRight(char('a'), char('b')).run("xb").isFailure)
  }

  test("skip propagates a right failure") {
    assert(zipLeft(char('a'), char('b')).run("ax").isFailure)
    assert(zipRight(char('a'), char('b')).run("ax").isFailure)
  }

  test("skip keeps left partial errors when the right leg succeeds") {
    val partialLeft = char('a').recover(char('x'))
    zipLeft(partialLeft, char('b')).run("xb") match {
      case Result.Partial(v, errs, consumed) =>
        assertEquals(v, 'x')
        assertEquals(consumed, 2)
        assertEquals(errs.size, 1)
      case other => fail(s"expected Partial, got $other")
    }
  }

  test("skip keeps right partial errors when the left leg succeeds") {
    val partialRight = char('b').recover(char('y'))
    zipLeft(char('a'), partialRight).run("ay") match {
      case Result.Partial(v, errs, consumed) =>
        assertEquals(v, 'a')
        assertEquals(consumed, 2)
        assertEquals(errs.size, 1)
      case other => fail(s"expected Partial, got $other")
    }
  }

  test("skip merges partial errors from both legs") {
    val partialLeft = char('a').recover(char('x'))
    val partialRight = char('b').recover(char('y'))
    zipLeft(partialLeft, partialRight).run("xy") match {
      case Result.Partial(v, errs, consumed) =>
        assertEquals(v, 'x')
        assertEquals(consumed, 2)
        assertEquals(errs.size, 2)
      case other => fail(s"expected Partial, got $other")
    }
  }

  test("skipRight keeps the left value through partials (mirrors zipLeft error path)") {
    val partialRight = char('b').recover(char('y'))
    zipRight(char('a'), partialRight).run("ay") match {
      case Result.Partial(v, errs, consumed) =>
        assertEquals(v, 'y')
        assertEquals(consumed, 2)
        assertEquals(errs.size, 1)
      case other => fail(s"expected Partial, got $other")
    }
  }

  test("stack safety: 5,000 chained <* parsers") {
    val n = 5000
    var parser: Parser[ParseError, Char] = char('a')
    for _ <- 1 until n do parser = parser <* char('b')
    val input = "a" + ("b" * (n - 1))
    val result = parser.run(input)
    assert(result.isSuccess)
    assertEquals(result.toOption, Some('a'))
  }

  test("stack safety: 5,000 chained *> parsers") {
    val n = 5000
    var parser: Parser[ParseError, Char] = char('a')
    for _ <- 1 until n do parser = parser *> char('b')
    val input = "a" + ("b" * (n - 1))
    val result = parser.run(input)
    assert(result.isSuccess)
    assertEquals(result.toOption, Some('b'))
  }
}
