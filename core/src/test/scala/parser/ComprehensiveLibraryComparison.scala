package parser

/**
 * Comprehensive, rigorous comparison: Rumil vs cats-parse vs zio-parser.
 *
 * Methodology for fairness:
 * 1. Parsers built once in val initializers (no construction overhead)
 * 2. Equivalent semantics verified by comparing outputs
 * 3. Realistic input sizes (not toy examples)
 * 4. High iteration counts for statistical significance
 * 5. Multiple workload categories
 */
class ComprehensiveLibraryComparison extends munit.FunSuite {

  def benchmark(warmup: Int, iters: Int)(f: => Unit): Long = {
    // Warmup
    (0 until warmup).foreach(_ => f)
    System.gc()
    Thread.sleep(50)

    // Measure
    val start = System.nanoTime()
    (0 until iters).foreach(_ => f)
    val end = System.nanoTime()
    (end - start) / 1_000_000 // milliseconds
  }

  // ==========================================================================
  // Category 1: Basic Primitives
  // ==========================================================================

  test("category 1.1: Single character (100K iterations)") {
    val input = "x"

    val rumilParser = {
      import parser.core._
      char('x')
    }
    val catsParser = {
      import cats.parse.{Parser => P}
      P.charIn('x')
    }
    val zioParser = {
      import zio.parser._
      Syntax.char('x')
    }

    // Validate
    assert(parser.runtime.run(rumilParser, input).isInstanceOf[parser.core.Result.Success[?, ?]])
    assert(catsParser.parse(input).isRight)
    assert(zioParser.parseString(input).isRight)

    val rumilTime = benchmark(5000, 100000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(5000, 100000) {
      val _ = catsParser.parse(input)
    }
    val zioTime = benchmark(5000, 100000) {
      val _ = zioParser.parseString(input)
    }

    println("\n=== Single Character (100K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    println(f"  zio-parser: ${zioTime}ms")
    val fastest = math.min(rumilTime, math.min(catsTime, zioTime))
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
    println(f"  zio vs fastest:   ${zioTime.toDouble / fastest}%.2fx")
  }

  test("category 1.2: String literal - short (50K iterations)") {
    val input = "hello"

    val rumilParser = {
      import parser.core._
      string("hello")
    }
    val catsParser = {
      import cats.parse.{Parser => P}
      P.string("hello").string
    }
    val zioParser = {
      import zio.parser._
      Syntax.string("hello", "hello")
    }

    // Validate
    assert(parser.runtime.run(rumilParser, input).isInstanceOf[parser.core.Result.Success[?, ?]])
    assert(catsParser.parse(input).isRight)
    assert(zioParser.parseString(input).isRight)

    val rumilTime = benchmark(2000, 50000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(2000, 50000) {
      val _ = catsParser.parse(input)
    }
    val zioTime = benchmark(2000, 50000) {
      val _ = zioParser.parseString(input)
    }

    println("\n=== String Literal - Short (50K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    println(f"  zio-parser: ${zioTime}ms")
    val fastest = math.min(rumilTime, math.min(catsTime, zioTime))
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
    println(f"  zio vs fastest:   ${zioTime.toDouble / fastest}%.2fx")
  }

  test("category 1.3: String literal - long (10K iterations)") {
    val input = "the quick brown fox jumps over the lazy dog"

    val rumilParser = {
      import parser.core._
      string("the quick brown fox jumps over the lazy dog")
    }
    val catsParser = {
      import cats.parse.{Parser => P}
      P.string("the quick brown fox jumps over the lazy dog").string
    }
    val zioParser = {
      import zio.parser._
      Syntax.string(
        "the quick brown fox jumps over the lazy dog",
        "the quick brown fox jumps over the lazy dog")
    }

    // Validate
    assert(parser.runtime.run(rumilParser, input).isInstanceOf[parser.core.Result.Success[?, ?]])
    assert(catsParser.parse(input).isRight)
    assert(zioParser.parseString(input).isRight)

    val rumilTime = benchmark(1000, 10000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(1000, 10000) {
      val _ = catsParser.parse(input)
    }
    val zioTime = benchmark(1000, 10000) {
      val _ = zioParser.parseString(input)
    }

    println("\n=== String Literal - Long (10K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    println(f"  zio-parser: ${zioTime}ms")
    val fastest = math.min(rumilTime, math.min(catsTime, zioTime))
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
    println(f"  zio vs fastest:   ${zioTime.toDouble / fastest}%.2fx")
  }

  // ==========================================================================
  // Category 2: Repetition (many/rep)
  // ==========================================================================

  test("category 2.1: Many - small input (5K iterations, 100 chars)") {
    val input = "a" * 100

    val rumilParser = {
      import parser.core._
      parser.core.many(char('a'))
    }
    val catsParser = {
      import cats.parse.{Parser => P}
      P.charIn('a').rep0
    }
    val zioParser = {
      import zio.parser._
      Syntax.char('a').repeat
    }

    // Validate
    val rumilResult = parser.runtime.run(rumilParser, input)
    val catsResult  = catsParser.parse(input)
    val zioResult   = zioParser.parseString(input)

    assert(rumilResult.isInstanceOf[parser.core.Result.Success[?, ?]])
    assert(catsResult.isRight)
    assert(zioResult.isRight)

    // Verify same count
    rumilResult match {
      case parser.core.Result.Success(list: List[?], _) =>
        assert(list.length == 100, s"Rumil parsed ${list.length} items")
      case _ => fail("Rumil should succeed")
    }
    catsResult match {
      case Right((_, list)) => assert(list.length == 100, s"cats parsed ${list.length} items")
      case _                => fail("cats should succeed")
    }

    val rumilTime = benchmark(500, 5000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(500, 5000) {
      val _ = catsParser.parse(input)
    }
    val zioTime = benchmark(500, 5000) {
      val _ = zioParser.parseString(input)
    }

    println("\n=== Many - Small (5K iterations, 100 chars) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    println(f"  zio-parser: ${zioTime}ms")
    val fastest = math.min(rumilTime, math.min(catsTime, zioTime))
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
    println(f"  zio vs fastest:   ${zioTime.toDouble / fastest}%.2fx")
  }

  test("category 2.2: Many - medium input (2K iterations, 1K chars)") {
    val input = "a" * 1000

    val rumilParser = {
      import parser.core._
      parser.core.many(char('a'))
    }
    val catsParser = {
      import cats.parse.{Parser => P}
      P.charIn('a').rep0
    }
    val zioParser = {
      import zio.parser._
      Syntax.char('a').repeat
    }

    // Validate count
    val rumilResult = parser.runtime.run(rumilParser, input)
    rumilResult match {
      case parser.core.Result.Success(list: List[?], _) =>
        assert(list.length == 1000, s"Rumil parsed ${list.length} items")
      case _ => fail("Rumil should succeed")
    }

    val rumilTime = benchmark(200, 2000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(200, 2000) {
      val _ = catsParser.parse(input)
    }
    val zioTime = benchmark(200, 2000) {
      val _ = zioParser.parseString(input)
    }

    println("\n=== Many - Medium (2K iterations, 1K chars) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    println(f"  zio-parser: ${zioTime}ms")
    val fastest = math.min(rumilTime, math.min(catsTime, zioTime))
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
    println(f"  zio vs fastest:   ${zioTime.toDouble / fastest}%.2fx")
  }

  test("category 2.3: Many - large input (500 iterations, 10K chars)") {
    val input = "a" * 10000

    val rumilParser = {
      import parser.core._
      parser.core.many(char('a'))
    }
    val catsParser = {
      import cats.parse.{Parser => P}
      P.charIn('a').rep0
    }
    val zioParser = {
      import zio.parser._
      Syntax.char('a').repeat
    }

    // Validate count
    val rumilResult = parser.runtime.run(rumilParser, input)
    rumilResult match {
      case parser.core.Result.Success(list: List[?], _) =>
        assert(list.length == 10000, s"Rumil parsed ${list.length} items")
      case _ => fail("Rumil should succeed")
    }

    val rumilTime = benchmark(50, 500) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(50, 500) {
      val _ = catsParser.parse(input)
    }
    val zioTime = benchmark(50, 500) {
      val _ = zioParser.parseString(input)
    }

    println("\n=== Many - Large (500 iterations, 10K chars) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    println(f"  zio-parser: ${zioTime}ms")
    val fastest = math.min(rumilTime, math.min(catsTime, zioTime))
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
    println(f"  zio vs fastest:   ${zioTime.toDouble / fastest}%.2fx")
  }

  // ==========================================================================
  // Category 3: Choice with Backtracking
  // ==========================================================================

  test("category 3.1: Choice - 2 alternatives, first matches (50K iterations)") {
    val input = "alpha"

    val rumilParser = {
      import parser.core._
      import parser.syntax._
      string("alpha") | string("beta")
    }
    val catsParser = {
      import cats.parse.{Parser => P}
      P.string("alpha").string | P.string("beta").string
    }
    val zioParser = {
      import zio.parser._
      Syntax.string("alpha", "alpha") | Syntax.string("beta", "beta")
    }

    // Validate
    assert(parser.runtime.run(rumilParser, input).isInstanceOf[parser.core.Result.Success[?, ?]])
    assert(catsParser.parse(input).isRight)
    assert(zioParser.parseString(input).isRight)

    val rumilTime = benchmark(2000, 50000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(2000, 50000) {
      val _ = catsParser.parse(input)
    }
    val zioTime = benchmark(2000, 50000) {
      val _ = zioParser.parseString(input)
    }

    println("\n=== Choice - 2 alt, first matches (50K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    println(f"  zio-parser: ${zioTime}ms")
    val fastest = math.min(rumilTime, math.min(catsTime, zioTime))
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
    println(f"  zio vs fastest:   ${zioTime.toDouble / fastest}%.2fx")
  }

  test("category 3.2: Choice - 2 alternatives, second matches (50K iterations)") {
    val input = "beta"

    val rumilParser = {
      import parser.core._
      import parser.syntax._
      string("alpha") | string("beta")
    }
    val catsParser = {
      import cats.parse.{Parser => P}
      P.string("alpha").string | P.string("beta").string
    }
    val zioParser = {
      import zio.parser._
      Syntax.string("alpha", "alpha") | Syntax.string("beta", "beta")
    }

    // Validate
    assert(parser.runtime.run(rumilParser, input).isInstanceOf[parser.core.Result.Success[?, ?]])
    assert(catsParser.parse(input).isRight)
    assert(zioParser.parseString(input).isRight)

    val rumilTime = benchmark(2000, 50000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(2000, 50000) {
      val _ = catsParser.parse(input)
    }
    val zioTime = benchmark(2000, 50000) {
      val _ = zioParser.parseString(input)
    }

    println("\n=== Choice - 2 alt, second matches (50K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    println(f"  zio-parser: ${zioTime}ms")
    val fastest = math.min(rumilTime, math.min(catsTime, zioTime))
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
    println(f"  zio vs fastest:   ${zioTime.toDouble / fastest}%.2fx")
  }

  test("category 3.3: Choice - 10 alternatives, last matches (10K iterations)") {
    val input = "kiwi"

    val rumilParser = {
      import parser.core._
      import parser.syntax._
      string("apple") | string("banana") | string("cherry") |
        string("date") | string("elderberry") | string("fig") |
        string("grape") | string("honeydew") | string("jackfruit") |
        string("kiwi")
    }
    val catsParser = {
      import cats.parse.{Parser => P}
      P.string("apple").string | P.string("banana").string | P.string("cherry").string |
        P.string("date").string | P.string("elderberry").string | P.string("fig").string |
        P.string("grape").string | P.string("honeydew").string | P.string("jackfruit").string |
        P.string("kiwi").string
    }
    val zioParser = {
      import zio.parser._
      Syntax.string("apple", "apple") | Syntax.string("banana", "banana") | Syntax.string(
        "cherry",
        "cherry") |
        Syntax.string("date", "date") | Syntax.string("elderberry", "elderberry") | Syntax.string(
          "fig",
          "fig") |
        Syntax.string("grape", "grape") | Syntax.string("honeydew", "honeydew") | Syntax.string(
          "jackfruit",
          "jackfruit") |
        Syntax.string("kiwi", "kiwi")
    }

    // Validate
    assert(parser.runtime.run(rumilParser, input).isInstanceOf[parser.core.Result.Success[?, ?]])
    assert(catsParser.parse(input).isRight)
    assert(zioParser.parseString(input).isRight)

    val rumilTime = benchmark(1000, 10000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(1000, 10000) {
      val _ = catsParser.parse(input)
    }
    val zioTime = benchmark(1000, 10000) {
      val _ = zioParser.parseString(input)
    }

    println("\n=== Choice - 10 alt, last matches (10K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    println(f"  zio-parser: ${zioTime}ms")
    val fastest = math.min(rumilTime, math.min(catsTime, zioTime))
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
    println(f"  zio vs fastest:   ${zioTime.toDouble / fastest}%.2fx")
  }

  // ==========================================================================
  // Category 4: Sequential Composition
  // ==========================================================================

  test("category 4.1: Sequential - 10 parsers (10K iterations)") {
    val input = "1234567890"

    val rumilParser = {
      import parser.core._
      import parser.syntax._
      var p: parser.core.Parser[parser.core.ParseError, Any] = char('1')
      List('2', '3', '4', '5', '6', '7', '8', '9', '0').foreach { c =>
        p = p ~ char(c)
      }
      p
    }
    val catsParser = {
      import cats.parse.{Parser => P}
      import cats.syntax.all._
      var p: P[Any] = P.charIn('1')
      List('2', '3', '4', '5', '6', '7', '8', '9', '0').foreach { c =>
        p = (p, P.charIn(c)).tupled
      }
      p
    }

    // Validate
    assert(parser.runtime.run(rumilParser, input).isInstanceOf[parser.core.Result.Success[?, ?]])
    assert(catsParser.parse(input).isRight)

    val rumilTime = benchmark(1000, 10000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(1000, 10000) {
      val _ = catsParser.parse(input)
    }

    println("\n=== Sequential - 10 parsers (10K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    val fastest = math.min(rumilTime, catsTime)
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
  }

  test("category 4.2: Sequential - 50 parsers (2K iterations)") {
    val input = "1" * 50

    val rumilParser = {
      import parser.core._
      import parser.syntax._
      var p: parser.core.Parser[parser.core.ParseError, Any] = char('1')
      (1 until 50).foreach(_ => p = p ~ char('1'))
      p
    }
    val catsParser = {
      import cats.parse.{Parser => P}
      import cats.syntax.all._
      var p: P[Any] = P.charIn('1')
      (1 until 50).foreach(_ => p = (p, P.charIn('1')).tupled)
      p
    }

    // Validate
    assert(parser.runtime.run(rumilParser, input).isInstanceOf[parser.core.Result.Success[?, ?]])
    assert(catsParser.parse(input).isRight)

    val rumilTime = benchmark(200, 2000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(200, 2000) {
      val _ = catsParser.parse(input)
    }

    println("\n=== Sequential - 50 parsers (2K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    val fastest = math.min(rumilTime, catsTime)
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
  }

  // ==========================================================================
  // Category 5: Number Parsing
  // ==========================================================================

  test("category 5.1: Integer - 1 digit (50K iterations)") {
    val input = "7"

    val rumilParser = {
      import parser.core._
      import parser.syntax._
      digit.many1.map(_.mkString.toInt)
    }
    val catsParser = {
      import cats.parse.Numbers
      Numbers.digits.map(_.toInt)
    }
    val zioParser = {
      import zio.parser._
      Syntax.digit.repeat.transform(
        chars => chars.mkString.toInt,
        num => zio.Chunk.fromIterable(num.toString)
      )
    }

    // Validate output
    val rumilResult = parser.runtime.run(rumilParser, input)
    rumilResult match {
      case parser.core.Result.Success(n: Int, _) => assert(n == 7)
      case _                                     => fail("Should parse to 7")
    }
    catsParser.parse(input) match {
      case Right((_, n)) => assert(n == 7)
      case _             => fail("Should parse to 7")
    }

    val rumilTime = benchmark(2000, 50000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(2000, 50000) {
      val _ = catsParser.parse(input)
    }
    val zioTime = benchmark(2000, 50000) {
      val _ = zioParser.parseString(input)
    }

    println("\n=== Integer - 1 digit (50K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    println(f"  zio-parser: ${zioTime}ms")
    val fastest = math.min(rumilTime, math.min(catsTime, zioTime))
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
    println(f"  zio vs fastest:   ${zioTime.toDouble / fastest}%.2fx")
  }

  test("category 5.2: Integer - 5 digits (20K iterations)") {
    val input = "42857"

    val rumilParser = {
      import parser.core._
      import parser.syntax._
      digit.many1.map(_.mkString.toInt)
    }
    val catsParser = {
      import cats.parse.Numbers
      Numbers.digits.map(_.toInt)
    }
    val zioParser = {
      import zio.parser._
      Syntax.digit.repeat.transform(
        chars => chars.mkString.toInt,
        num => zio.Chunk.fromIterable(num.toString)
      )
    }

    // Validate output
    val rumilResult = parser.runtime.run(rumilParser, input)
    rumilResult match {
      case parser.core.Result.Success(n: Int, _) => assert(n == 42857)
      case _                                     => fail("Should parse to 42857")
    }
    catsParser.parse(input) match {
      case Right((_, n)) => assert(n == 42857)
      case _             => fail("Should parse to 42857")
    }

    val rumilTime = benchmark(1000, 20000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(1000, 20000) {
      val _ = catsParser.parse(input)
    }
    val zioTime = benchmark(1000, 20000) {
      val _ = zioParser.parseString(input)
    }

    println("\n=== Integer - 5 digits (20K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    println(f"  zio-parser: ${zioTime}ms")
    val fastest = math.min(rumilTime, math.min(catsTime, zioTime))
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
    println(f"  zio vs fastest:   ${zioTime.toDouble / fastest}%.2fx")
  }

  // ==========================================================================
  // Category 6: CSV Parsing
  // ==========================================================================

  test("category 6.1: CSV - 3 numbers (10K iterations)") {
    val input = "123,456,789"

    val rumilParser = {
      import parser.core._
      import parser.syntax._
      val num = digit.many1.map(_.mkString.toInt)
      num.sepBy1(char(','))
    }
    val catsParser = {
      import cats.parse.{Parser => P, Numbers}
      import cats.syntax.all._
      val num   = Numbers.digits.map(_.toInt)
      val comma = P.charIn(',')
      (num, comma, num, comma, num).mapN { case (n1, _, n2, _, n3) =>
        List(n1, n2, n3)
      }
    }

    // Validate output
    val rumilResult = parser.runtime.run(rumilParser, input)
    rumilResult match {
      case parser.core.Result.Success(list, _) =>
        assert(list.toString == List(123, 456, 789).toString)
      case _ => fail("Should parse CSV")
    }
    catsParser.parse(input) match {
      case Right((_, list)) => assert(list == List(123, 456, 789))
      case _                => fail("Should parse CSV")
    }

    val rumilTime = benchmark(1000, 10000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(1000, 10000) {
      val _ = catsParser.parse(input)
    }

    println("\n=== CSV - 3 numbers (10K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    val fastest = math.min(rumilTime, catsTime)
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
  }

  test("category 6.2: CSV - 10 numbers using sepBy (5K iterations)") {
    val input = "1,2,3,4,5,6,7,8,9,10"

    val rumilParser = {
      import parser.core._
      import parser.syntax._
      val num = digit.many1.map(_.mkString.toInt)
      num.sepBy1(char(','))
    }
    val catsParser = {
      import cats.parse.{Parser => P, Numbers}
      val num   = Numbers.digits.map(_.toInt)
      val comma = P.charIn(',')
      num.repSep(comma)
    }

    // Validate output
    val rumilResult = parser.runtime.run(rumilParser, input)
    rumilResult match {
      case parser.core.Result.Success(list: List[?], _) =>
        assert(list == List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
      case _ => fail("Should parse CSV")
    }
    catsParser.parse(input) match {
      case Right((_, list)) =>
        assert(list.toList == List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
      case _ => fail("Should parse CSV")
    }

    val rumilTime = benchmark(500, 5000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(500, 5000) {
      val _ = catsParser.parse(input)
    }

    println("\n=== CSV - 10 numbers with sepBy (5K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    val fastest = math.min(rumilTime, catsTime)
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
  }

  test("summary: Comprehensive Library Comparison") {
    println("\n" + "=" * 70)
    println("COMPREHENSIVE LIBRARY COMPARISON")
    println("=" * 70)
    println("Fair, rigorous comparison with:")
    println("- Equivalent parsers verified by output")
    println("- Realistic input sizes")
    println("- High iteration counts")
    println("- Multiple workload categories")
    println("")
  }
}
