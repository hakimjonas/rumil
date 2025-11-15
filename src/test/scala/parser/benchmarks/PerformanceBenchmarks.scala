package parser.benchmarks

import munit.FunSuite
import parser.core.*
import parser.syntax.*

/**
 * Simple performance benchmarks for the parser combinators library.
 *
 * These are not rigorous JMH benchmarks, but provide basic performance
 * indicators during development.
 */
class PerformanceBenchmarks extends FunSuite {

  /**
   * Measures execution time of a block of code.
   *
   * @param name Description of the operation being timed
   * @param block Code block to time
   * @return Execution time in milliseconds
   */
  def time(name: String)(block: => Unit): Long = {
    val start = System.nanoTime()
    block
    val end = System.nanoTime()
    val millis = (end - start) / 1000000
    println(s"$name: ${millis}ms")
    millis
  }

  test("benchmark: parse 1000 digits") {
    val input = "0123456789" * 100 // 1000 digits
    val parser = digit.many

    val elapsed = time("Parse 1000 digits") {
      (0 until 100).foreach { _ =>
        parser.run(input)
      }
    }

    // Sanity check - should complete in reasonable time
    assert(elapsed < 5000, s"Too slow: ${elapsed}ms")
  }

  test("benchmark: parse 100 numbers with separators") {
    val numbers = (0 until 100).mkString(",")
    val parser = digit.many1.sepBy(char(','))

    val elapsed = time("Parse 100 comma-separated numbers") {
      (0 until 100).foreach { _ =>
        parser.run(numbers)
      }
    }

    assert(elapsed < 5000, s"Too slow: ${elapsed}ms")
  }

  test("benchmark: parse deeply nested JSON array") {
    val depth = 50
    val input = "[" * depth + "1" + "]" * depth

    lazy val value: Parser[ParseError, String] = {
      Parser.Custom { state =>
        val num = digit.many1.map(_.mkString)
        val arr = Parser.Custom { s =>
          parser.runtime.interpret(
            char('[') *> value <* char(']'),
            s
          )
        }
        parser.runtime.interpret(num | arr, state)
      }
    }

    val elapsed = time(s"Parse JSON array nested $depth deep") {
      (0 until 10).foreach { _ =>
        value.run(input)
      }
    }

    assert(elapsed < 5000, s"Too slow: ${elapsed}ms")
  }

  test("benchmark: arithmetic expression parsing") {
    val input = "1+2*3+4*5+6*7+8*9+10"

    lazy val expr: Parser[ParseError, Int] = {
      Parser.Custom { state =>
        parser.runtime.interpret(
          term.chainl1(
            (char('+').as((a: Int, b: Int) => a + b)) |
            (char('-').as((a: Int, b: Int) => a - b))
          ),
          state
        )
      }
    }

    lazy val term: Parser[ParseError, Int] = {
      Parser.Custom { state =>
        parser.runtime.interpret(
          factor.chainl1(
            (char('*').as((a: Int, b: Int) => a * b)) |
            (char('/').as((a: Int, b: Int) => a / b))
          ),
          state
        )
      }
    }

    lazy val factor: Parser[ParseError, Int] = {
      val number = digit.many1.map(_.mkString.toInt)
      number | Parser.Custom { state =>
        parser.runtime.interpret(char('(') *> expr <* char(')'), state)
      }
    }

    val elapsed = time("Parse arithmetic expression") {
      (0 until 1000).foreach { _ =>
        expr.run(input)
      }
    }

    assert(elapsed < 5000, s"Too slow: ${elapsed}ms")
  }

  test("benchmark: many1 with backtracking") {
    val input = "a" * 1000
    val parser = char('a').many1

    val elapsed = time("Parse 1000 'a' characters with many1") {
      (0 until 100).foreach { _ =>
        parser.run(input)
      }
    }

    assert(elapsed < 5000, s"Too slow: ${elapsed}ms")
  }

  test("benchmark: choice with many alternatives") {
    val input = "z" * 100
    val parser = choice(
      ('a' to 'z').map(c => char(c)).toList
    ).many

    val elapsed = time("Parse with 26-way choice") {
      (0 until 100).foreach { _ =>
        parser.run(input)
      }
    }

    assert(elapsed < 5000, s"Too slow: ${elapsed}ms")
  }

  test("benchmark: string matching") {
    val input = "hello" * 100
    val parser = string("hello").many

    val elapsed = time("Parse 100 'hello' strings") {
      (0 until 100).foreach { _ =>
        parser.run(input)
      }
    }

    assert(elapsed < 5000, s"Too slow: ${elapsed}ms")
  }

  test("benchmark: sepBy1 parsing") {
    val input = (1 to 50).mkString(",")
    val parser = digit.many1.sepBy1(char(','))

    val elapsed = time("Parse 50 separated numbers with sepBy1") {
      (0 until 100).foreach { _ =>
        parser.run(input)
      }
    }

    assert(elapsed < 5000, s"Too slow: ${elapsed}ms")
  }
}
