package parser.benchmarks

import munit.FunSuite

import parser.core.{*, given}
import parser.syntax.*

/** Matches the input and warm-up shape of `CatsParseComparison.test("comparison: arithmetic
  * expression parsing")` but uses our new `pratt` combinator instead of `chainl1`. Reports a direct
  * time ratio vs the cats-parse parser, so we can compare apples-to-apples.
  */
class PrattVsCatsArithmetic extends FunSuite {

  private def timed[A](label: String, iterations: Int)(body: => A): (A, Long) = {
    // warm-up
    var i = 0
    while i < iterations / 10 do { body; i += 1 }
    val start = System.currentTimeMillis()
    i = 0
    var last: A = body
    while i < iterations do { last = body; i += 1 }
    val elapsed = System.currentTimeMillis() - start
    println(s"  $label: ${elapsed}ms for $iterations iterations")
    (last, elapsed)
  }

  test("pratt vs cats: arithmetic expression parsing") {
    val input = "1+2*3+4*5+6*7+8*9+10"

    println("\n--- Pratt arithmetic (same input as CatsParseComparison) ---")

    lazy val atom: Parser[ParseError, Int] = {
      val num = digit.many1.map(_.mkString.toInt)
      num | (char('(') *> defer(prattExpr) <* char(')'))
    }
    lazy val prattExpr: Parser[ParseError, Int] =
      pratt(
        defer(atom),
        List(
          Operator.InfixLeft(char('+'), 10, (a: Int, b: Int) => a + b),
          Operator.InfixLeft(char('-'), 10, (a: Int, b: Int) => a - b),
          Operator.InfixLeft(char('*'), 20, (a: Int, b: Int) => a * b),
          Operator.InfixLeft(char('/'), 20, (a: Int, b: Int) => a / b)
        )
      )

    val (prattResult, prattTime) = timed("Pratt", 1000) { prattExpr.run(input) }

    import cats.parse.Parser as P
    lazy val catsExpr: P[Int] = P.recursive[Int] { recurse =>
      val num = P.charsWhile(_.isDigit).map(_.toInt)
      val factor: P[Int] = num | (P.char('(') *> recurse <* P.char(')'))
      val mulDiv = factor.repSep(P.charIn("*/")).map(_.toList.reduceLeft((a, b) => a * b))
      mulDiv.repSep(P.charIn("+-")).map(_.toList.reduceLeft((a, b) => a + b))
    }
    val (_, catsTime) = timed("cats-parse", 1000) { catsExpr.parseAll(input) }

    assert(prattResult.isSuccess, s"Pratt failed: $prattResult")
    println(s"  Ratio (Pratt/cats): ${prattTime.toDouble / catsTime.toDouble}x")
  }
}
