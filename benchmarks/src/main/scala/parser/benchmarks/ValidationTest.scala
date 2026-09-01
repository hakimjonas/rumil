package parser.benchmarks

/** Validate that both Rumil and zio-parser are actually doing equivalent work.
  *
  * Before trusting benchmark results, we need to verify:
  *   1. Both parsers succeed on valid input
  *   2. Both parsers fail on invalid input
  *   3. Both parsers produce equivalent results
  *   4. We're not accidentally short-circuiting in one implementation
  */
object ValidationTest {

  def main(args: Array[String]): Unit = {
    println("=== Benchmark Validation Tests ===\n")

    testChoice10()
    testDeepFlatMap()
    testDigitSequence()
    testManyRepetition()
    testSeq10()

    println("\n=== All validations passed ===")
  }

  def testChoice10(): Unit = {
    println("Testing choice10...")

    // Rumil
    import parser.core.*
    import parser.syntax.*
    import parser.runtime.run

    val rumilParser =
      string("apple") | string("banana") | string("cherry") |
        string("date") | string("elderberry") | string("fig") |
        string("grape") | string("honeydew") | string("kiwi") |
        string("lemon")

    val rumilResult1 = run(rumilParser, "lemon")
    val rumilResult2 = run(rumilParser, "apple")
    val rumilResult3 = run(rumilParser, "invalid")

    println(s"  Rumil 'lemon': ${rumilResult1}")
    println(s"  Rumil 'apple': ${rumilResult2}")
    println(s"  Rumil 'invalid': ${rumilResult3.isSuccess}")

    // zio-parser
    import zio.parser.*

    val zioParser =
      Syntax.string("apple", "apple") | Syntax.string("banana", "banana") |
        Syntax.string("cherry", "cherry") | Syntax.string("date", "date") |
        Syntax.string("elderberry", "elderberry") | Syntax.string("fig", "fig") |
        Syntax.string("grape", "grape") | Syntax.string("honeydew", "honeydew") |
        Syntax.string("kiwi", "kiwi") | Syntax.string("lemon", "lemon")

    val zioResult1 = zioParser.parseString("lemon")
    val zioResult2 = zioParser.parseString("apple")
    val zioResult3 = zioParser.parseString("invalid")

    println(s"  ZIO 'lemon': ${zioResult1}")
    println(s"  ZIO 'apple': ${zioResult2}")
    println(s"  ZIO 'invalid': ${zioResult3.isRight}")

    assert(rumilResult1.isSuccess == zioResult1.isRight, "choice10: success mismatch on 'lemon'")
    assert(rumilResult2.isSuccess == zioResult2.isRight, "choice10: success mismatch on 'apple'")
    assert(rumilResult3.isSuccess == zioResult3.isRight, "choice10: failure mismatch on 'invalid'")

    println("  ✓ Passed\n")
  }

  def testDeepFlatMap(): Unit = {
    println("Testing deepFlatMap...")

    // Rumil - flatMap chains need N+1 characters (initial + N continuations)
    import parser.core.*
    import parser.syntax.*
    import parser.runtime.run

    var rumilP: Parser[ParseError, Char] = char('1')
    for _ <- 1 to 100 do rumilP = rumilP.flatMap(_ => char('1'))
    val input = "1" * 101 // Need 101 chars for 100 flatMaps
    val rumilResult = run(rumilP, input)

    println(s"  Rumil result: ${rumilResult}")
    println(s"  Rumil success: ${rumilResult.isSuccess}")

    // zio-parser - ~> is zipRight, similar semantics
    import zio.parser.*

    var zioP = Syntax.char('1')
    for _ <- 1 to 100 do zioP = zioP ~> Syntax.char('1')
    val zioResult = zioP.parseString(input)

    println(s"  ZIO result: ${zioResult}")
    println(s"  ZIO success: ${zioResult.isRight}")

    assert(rumilResult.isSuccess == zioResult.isRight, "deepFlatMap: result mismatch")

    println("  ✓ Passed\n")
  }

  def testDigitSequence(): Unit = {
    println("Testing digitSequence...")

    val input = "1234567890" * 100 // 1000 digits

    // Rumil
    import parser.core.*
    import parser.runtime.run

    val rumilDigit = satisfy(_.isDigit, "digit")
    val rumilParser = parser.core.many(rumilDigit)
    val rumilResult = run(rumilParser, input)

    val rumilCount = rumilResult match {
      case parser.core.Result.Success(list: List[_], _) => list.size
      case _ => -1
    }

    println(s"  Rumil parsed ${rumilCount} digits")

    // zio-parser
    import zio.parser.*

    val zioDigit = Syntax.digit
    val zioParser = zioDigit.repeat
    val zioResult = zioParser.parseString(input)

    val zioCount = zioResult match {
      case Right(chunk) => chunk.asInstanceOf[zio.Chunk[?]].size // scalafix:ok DisableSyntax.asInstanceOf
      case Left(_) => -1
    }

    println(s"  ZIO parsed ${zioCount} digits")

    assert(rumilCount == 1000, s"Rumil parsed wrong count: $rumilCount")
    assert(zioCount == 1000, s"ZIO parsed wrong count: $zioCount")
    assert(rumilCount == zioCount, "digitSequence: count mismatch")

    println("  ✓ Passed\n")
  }

  def testManyRepetition(): Unit = {
    println("Testing manyRepetition...")

    val input = "1" * 10000

    // Rumil
    import parser.core.*
    import parser.runtime.run

    val rumilParser = parser.core.many(char('1'))
    val rumilResult = run(rumilParser, input)

    val rumilCount = rumilResult match {
      case parser.core.Result.Success(list: List[_], _) => list.size
      case _ => -1
    }

    println(s"  Rumil parsed ${rumilCount} chars")

    // zio-parser
    import zio.parser.*

    val zioParser = Syntax.char('1').repeat
    val zioResult = zioParser.parseString(input)

    val zioCount = zioResult match {
      case Right(chunk) => chunk.asInstanceOf[zio.Chunk[?]].size // scalafix:ok DisableSyntax.asInstanceOf
      case Left(_) => -1
    }

    println(s"  ZIO parsed ${zioCount} chars")

    assert(rumilCount == 10000, s"Rumil parsed wrong count: $rumilCount")
    assert(zioCount == 10000, s"ZIO parsed wrong count: $zioCount")
    assert(rumilCount == zioCount, "manyRepetition: count mismatch")

    println("  ✓ Passed\n")
  }

  def testSeq10(): Unit = {
    println("Testing seq10...")

    // Rumil
    import parser.core.*
    import parser.syntax.*
    import parser.runtime.run

    val rumilDigit = satisfy(_.isDigit, "digit")
    val rumilParser = rumilDigit ~ rumilDigit ~ rumilDigit ~ rumilDigit ~ rumilDigit ~
      rumilDigit ~ rumilDigit ~ rumilDigit ~ rumilDigit ~ rumilDigit

    val rumilResult = run(rumilParser, "1234567890")

    println(s"  Rumil result: ${rumilResult.isSuccess}")

    // zio-parser
    import zio.parser.*

    val zioDigit = Syntax.digit
    val zioParser = zioDigit ~ zioDigit ~ zioDigit ~ zioDigit ~ zioDigit ~
      zioDigit ~ zioDigit ~ zioDigit ~ zioDigit ~ zioDigit

    val zioResult = zioParser.parseString("1234567890")

    println(s"  ZIO result: ${zioResult.isRight}")

    assert(rumilResult.isSuccess == zioResult.isRight, "seq10: result mismatch")

    println("  ✓ Passed\n")
  }
}
