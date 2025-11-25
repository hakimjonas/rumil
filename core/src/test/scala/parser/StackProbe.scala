package parser

import parser.core._
import parser.syntax._

/** Standalone probe to test stack limits in isolation */
object StackProbe {
  def main(args: Array[String]): Unit = {
    val n = if (args.nonEmpty) args(0).toInt else 1000

    println(s"Testing $n sequential parsers using ~")

    try {
      var parser: Parser[ParseError, Any] = char('a')
      for (_ <- 1 until n)
        parser = parser ~ char('a')

      val input  = "a" * n
      val result = parser.run(input)

      if (result.isSuccess) {
        println(s"RESULT: $n PASS")
      } else {
        println(s"RESULT: $n FAIL (parse failed)")
      }
    } catch {
      case _: StackOverflowError =>
        println(s"RESULT: $n STACKOVERFLOW")
    }
  }
}
