package parser.benchmarks

import munit.FunSuite

/**
 * Comparative benchmarks between Rumil and cats-parse.
 *
 * These are simple timing comparisons for common parsing patterns.
 * For rigorous benchmarks, use JMH instead.
 */
class CatsParseComparison extends FunSuite {

  // ============================================================================
  // Helper for timing
  // ============================================================================

  def timed[A](label: String, iterations: Int)(f: => A): (A, Long) = {
    // Warmup
    (0 until 10).foreach(_ => f)

    val start = System.nanoTime()
    var result: A = null.asInstanceOf[A]
    (0 until iterations).foreach { _ =>
      result = f
    }
    val end    = System.nanoTime()
    val millis = (end - start) / 1000000
    println(s"  $label: ${millis}ms for $iterations iterations")
    (result, millis)
  }

  // ============================================================================
  // Benchmark 1: Simple character matching
  // ============================================================================

  test("comparison: parse 1000 digits") {
    val input = "0123456789" * 100

    println("\n--- Parse 1000 digits ---")

    // Rumil
    import parser.core._
    import parser.syntax._
    val rumilParser = digit.many
    val (rumilResult, rumilTime) = timed("Rumil", 100) {
      rumilParser.run(input)
    }

    // cats-parse
    import cats.parse.{Parser => P}
    val catsParser = P.charsWhile(_.isDigit)
    val (catsResult, catsTime) = timed("cats-parse", 100) {
      catsParser.parseAll(input)
    }

    // Verify both succeed
    assert(rumilResult.isSuccess, s"Rumil failed: $rumilResult")
    assert(catsResult.isRight, s"cats-parse failed: $catsResult")

    println(s"  Ratio (Rumil/cats): ${rumilTime.toDouble / catsTime.toDouble}x")
  }

  // ============================================================================
  // Benchmark 2: String matching
  // ============================================================================

  test("comparison: string matching") {
    val input = "hello" * 100

    println("\n--- Parse 100 'hello' strings ---")

    // Rumil
    import parser.core._
    import parser.syntax._
    val rumilParser = string("hello").many
    val (rumilResult, rumilTime) = timed("Rumil", 100) {
      rumilParser.run(input)
    }

    // cats-parse
    import cats.parse.{Parser => P}
    val catsParser = P.string("hello").rep
    val (catsResult, catsTime) = timed("cats-parse", 100) {
      catsParser.parseAll(input)
    }

    assert(rumilResult.isSuccess)
    assert(catsResult.isRight)

    println(s"  Ratio (Rumil/cats): ${rumilTime.toDouble / catsTime.toDouble}x")
  }

  // ============================================================================
  // Benchmark 3: Choice/alternation
  // ============================================================================

  test("comparison: choice with many alternatives") {
    val input = "z" * 100

    println("\n--- Parse with 26-way choice ---")

    // Rumil
    import parser.core._
    import parser.syntax._
    val rumilParser = choice(('a' to 'z').map(c => char(c)).toList).many
    val (rumilResult, rumilTime) = timed("Rumil", 100) {
      rumilParser.run(input)
    }

    // cats-parse
    import cats.parse.{Parser => P}
    val catsParser = P.charIn('a' to 'z').rep
    val (catsResult, catsTime) = timed("cats-parse", 100) {
      catsParser.parseAll(input)
    }

    assert(rumilResult.isSuccess)
    assert(catsResult.isRight)

    println(s"  Ratio (Rumil/cats): ${rumilTime.toDouble / catsTime.toDouble}x")
  }

  // ============================================================================
  // Benchmark 4: Separated values
  // ============================================================================

  test("comparison: comma-separated numbers") {
    val input = (1 to 100).mkString(",")

    println("\n--- Parse 100 comma-separated numbers ---")

    // Rumil
    import parser.core._
    import parser.syntax._
    val rumilParser = digit.many1.sepBy1(char(','))
    val (rumilResult, rumilTime) = timed("Rumil", 100) {
      rumilParser.run(input)
    }

    // cats-parse
    import cats.parse.{Parser => P}
    val catsDigit  = P.charsWhile(_.isDigit)
    val catsParser = catsDigit.repSep(P.char(','))
    val (catsResult, catsTime) = timed("cats-parse", 100) {
      catsParser.parseAll(input)
    }

    assert(rumilResult.isSuccess)
    assert(catsResult.isRight)

    println(s"  Ratio (Rumil/cats): ${rumilTime.toDouble / catsTime.toDouble}x")
  }

  // ============================================================================
  // Benchmark 5: Arithmetic expression (demonstrates left recursion)
  // ============================================================================

  test("comparison: arithmetic expression parsing") {
    val input = "1+2*3+4*5+6*7+8*9+10"

    println("\n--- Parse arithmetic expression ---")

    // Rumil (using chainl1 for fair comparison)
    import parser.core._
    import parser.syntax._

    lazy val rumilExpr: Parser[ParseError, Int] =
      defer(rumilTerm).chainl1(
        (char('+').as((a: Int, b: Int) => a + b)) |
          (char('-').as((a: Int, b: Int) => a - b))
      )

    lazy val rumilTerm: Parser[ParseError, Int] =
      defer(rumilFactor).chainl1(
        (char('*').as((a: Int, b: Int) => a * b)) |
          (char('/').as((a: Int, b: Int) => a / b))
      )

    lazy val rumilFactor: Parser[ParseError, Int] = {
      val number = digit.many1.map(_.mkString.toInt)
      number | (char('(') *> defer(rumilExpr) <* char(')'))
    }

    val (rumilResult, rumilTime) = timed("Rumil", 1000) {
      rumilExpr.run(input)
    }

    // cats-parse (using recursive combinators)
    import cats.parse.{Parser => P}

    lazy val catsExpr: P[Int] = P.recursive[Int] { recurse =>
      val num = P.charsWhile(_.isDigit).map(_.toInt)
      val factor: P[Int] = num | (P.char('(') *> recurse <* P.char(')'))

      val mulDiv = factor.repSep(P.charIn("*/")).map { nel =>
        nel.toList.reduceLeft { (acc, n) => acc * n } // Simplified
      }

      mulDiv.repSep(P.charIn("+-")).map { nel =>
        nel.toList.reduceLeft { (acc, n) => acc + n } // Simplified
      }
    }

    val (_, catsTime) = timed("cats-parse", 1000) {
      catsExpr.parseAll(input)
    }

    assert(rumilResult.isSuccess, s"Rumil failed: $rumilResult")
    // Note: cats-parse result may differ due to simplified expression handling
    println(s"  Ratio (Rumil/cats): ${rumilTime.toDouble / catsTime.toDouble}x")
  }

  // ============================================================================
  // Benchmark 6: JSON-like nested structure
  // ============================================================================

  test("comparison: deeply nested structure") {
    val depth = 50
    val input = "[" * depth + "1" + "]" * depth

    println(s"\n--- Parse nested structure ($depth levels) ---")

    // Rumil
    import parser.core._
    import parser.syntax._

    lazy val rumilValue: Parser[ParseError, String] = {
      val num = digit.many1.map(_.mkString)
      val arr = char('[') *> defer(rumilValue) <* char(']')
      num | arr
    }

    val (rumilResult, rumilTime) = timed("Rumil", 100) {
      rumilValue.run(input)
    }

    // cats-parse
    import cats.parse.{Parser => P}

    val catsValue: P[String] = P.recursive[String] { recurse =>
      val num = P.charsWhile(_.isDigit)
      val arr = (P.char('[') *> recurse <* P.char(']'))
      num | arr
    }

    val (catsResult, catsTime) = timed("cats-parse", 100) {
      catsValue.parseAll(input)
    }

    assert(rumilResult.isSuccess)
    assert(catsResult.isRight)

    println(s"  Ratio (Rumil/cats): ${rumilTime.toDouble / catsTime.toDouble}x")
  }
}
