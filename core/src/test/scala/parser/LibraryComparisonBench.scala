package parser

/**
 * Fair comparison benchmark: Rumil vs cats-parse vs zio-parser.
 *
 * Tests 5 common parsing workloads to give realistic performance comparison.
 * All parsers built once (excludes construction overhead).
 */
class LibraryComparisonBench extends munit.FunSuite {

  def benchmark(warmup: Int, iters: Int)(f: => Unit): Long = {
    // Warmup
    (0 until warmup).foreach(_ => f)
    System.gc()
    Thread.sleep(10)

    // Measure
    val start = System.nanoTime()
    (0 until iters).foreach(_ => f)
    val end = System.nanoTime()
    (end - start) / 1_000_000 // milliseconds
  }

  // ==========================================================================
  // Setup: Build all parsers once
  // ==========================================================================

  // Inputs
  val stringInput = "hello"
  val choiceInput = "lemon" // Last alternative (worst case for choice)
  val manyInput   = "a" * 1000
  val seqInput    = "1" * 100
  val numberInput = "42"

  // Rumil parsers
  val rumilString = {
    import parser.core._
    string("hello")
  }
  val rumilChoice = {
    import parser.core._
    import parser.syntax._
    string("apple") | string("banana") | string("cherry") |
      string("date") | string("elderberry") | string("fig") |
      string("grape") | string("honeydew") | string("kiwi") |
      string("lemon")
  }
  val rumilMany = {
    import parser.core._
    parser.core.many(char('a'))
  }
  val rumilSeq = {
    import parser.core._
    import parser.syntax._
    var p: parser.core.Parser[parser.core.ParseError, Any] = char('1')
    (1 until 100).foreach(_ => p = p ~ char('1'))
    p
  }
  val rumilNumber = {
    import parser.core._
    import parser.syntax._
    digit.many1.map(_.mkString.toInt)
  }

  // cats-parse parsers
  val catsString = {
    import cats.parse.{Parser => P}
    P.string("hello").string
  }
  val catsChoice = {
    import cats.parse.{Parser => P}
    P.string("apple").string | P.string("banana").string | P.string("cherry").string |
      P.string("date").string | P.string("elderberry").string | P.string("fig").string |
      P.string("grape").string | P.string("honeydew").string | P.string("kiwi").string |
      P.string("lemon").string
  }
  val catsMany = {
    import cats.parse.{Parser => P}
    P.charIn('a').rep.map(_.toList.map(_.toString.head))
  }
  val catsSeq = {
    import cats.parse.{Parser => P}
    import cats.syntax.all._
    var p: P[Any] = P.charIn('1')
    (1 until 100).foreach(_ => p = (p, P.charIn('1')).tupled)
    p
  }
  val catsNumber = {
    import cats.parse.Numbers
    Numbers.digits.map(_.toInt)
  }

  // zio-parser parsers
  val zioString = {
    import zio.parser._
    Syntax.string("hello", "hello")
  }
  val zioChoice = {
    import zio.parser._
    Syntax.string("apple", "apple") | Syntax.string("banana", "banana") |
      Syntax.string("cherry", "cherry") | Syntax.string("date", "date") |
      Syntax.string("elderberry", "elderberry") | Syntax.string("fig", "fig") |
      Syntax.string("grape", "grape") | Syntax.string("honeydew", "honeydew") |
      Syntax.string("kiwi", "kiwi") | Syntax.string("lemon", "lemon")
  }
  val zioMany = {
    import zio.parser._
    Syntax.char('a').repeat
  }
  val zioNumber = {
    import zio.parser._
    Syntax.digit.repeat.transform(
      chars => chars.mkString.toInt,
      num => zio.Chunk.fromIterable(num.toString)
    )
  }

  // Validate correctness
  assert(
    parser.runtime.run(rumilString, stringInput).isInstanceOf[parser.core.Result.Success[?, ?]],
    "Rumil string")
  assert(
    parser.runtime.run(rumilChoice, choiceInput).isInstanceOf[parser.core.Result.Success[?, ?]],
    "Rumil choice")
  assert(
    parser.runtime.run(rumilMany, manyInput).isInstanceOf[parser.core.Result.Success[?, ?]],
    "Rumil many")
  assert(
    parser.runtime.run(rumilSeq, seqInput).isInstanceOf[parser.core.Result.Success[?, ?]],
    "Rumil seq")
  assert(
    parser.runtime.run(rumilNumber, numberInput).isInstanceOf[parser.core.Result.Success[?, ?]],
    "Rumil number")

  assert(catsString.parse(stringInput).isRight, "cats string")
  assert(catsChoice.parse(choiceInput).isRight, "cats choice")
  assert(catsMany.parse(manyInput).isRight, "cats many")
  assert(catsSeq.parse(seqInput).isRight, "cats seq")
  assert(catsNumber.parse(numberInput).isRight, "cats number")

  assert(zioString.parseString(stringInput).isRight, "zio string")
  assert(zioChoice.parseString(choiceInput).isRight, "zio choice")
  assert(zioMany.parseString(manyInput).isRight, "zio many")
  assert(zioNumber.parseString(numberInput).isRight, "zio number")

  // ==========================================================================
  // Benchmark 1: String Matching
  // ==========================================================================

  test("benchmark 1: String Matching (10K iterations)") {
    val rumilTime = benchmark(1000, 10000) {
      val _ = parser.runtime.run(rumilString, stringInput)
    }
    val catsTime = benchmark(1000, 10000) {
      val _ = catsString.parse(stringInput)
    }
    val zioTime = benchmark(1000, 10000) {
      val _ = zioString.parseString(stringInput)
    }

    println("\n=== String Matching (10K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    println(f"  zio-parser: ${zioTime}ms")

    val fastest = math.min(rumilTime, math.min(catsTime, zioTime))
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
    println(f"  zio vs fastest:   ${zioTime.toDouble / fastest}%.2fx")
  }

  // ==========================================================================
  // Benchmark 2: Choice with Backtracking
  // ==========================================================================

  test("benchmark 2: Choice (10 alternatives, 10K iterations)") {
    val rumilTime = benchmark(1000, 10000) {
      val _ = parser.runtime.run(rumilChoice, choiceInput)
    }
    val catsTime = benchmark(1000, 10000) {
      val _ = catsChoice.parse(choiceInput)
    }
    val zioTime = benchmark(1000, 10000) {
      val _ = zioChoice.parseString(choiceInput)
    }

    println("\n=== Choice (10 alternatives, 10K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    println(f"  zio-parser: ${zioTime}ms")

    val fastest = math.min(rumilTime, math.min(catsTime, zioTime))
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
    println(f"  zio vs fastest:   ${zioTime.toDouble / fastest}%.2fx")
  }

  // ==========================================================================
  // Benchmark 3: Many Repetition
  // ==========================================================================

  test("benchmark 3: Many (1K chars, 1K iterations)") {
    val rumilTime = benchmark(100, 1000) {
      val _ = parser.runtime.run(rumilMany, manyInput)
    }
    val catsTime = benchmark(100, 1000) {
      val _ = catsMany.parse(manyInput)
    }
    val zioTime = benchmark(100, 1000) {
      val _ = zioMany.parseString(manyInput)
    }

    println("\n=== Many (1K chars, 1K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    println(f"  zio-parser: ${zioTime}ms")

    val fastest = math.min(rumilTime, math.min(catsTime, zioTime))
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
    println(f"  zio vs fastest:   ${zioTime.toDouble / fastest}%.2fx")
  }

  // ==========================================================================
  // Benchmark 4: Sequential Composition
  // ==========================================================================

  test("benchmark 4: Sequential (100 using ~, 1K iterations)") {
    val rumilTime = benchmark(100, 1000) {
      val _ = parser.runtime.run(rumilSeq, seqInput)
    }
    val catsTime = benchmark(100, 1000) {
      val _ = catsSeq.parse(seqInput)
    }

    println("\n=== Sequential (100 using ~, 1K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")

    val fastest = math.min(rumilTime, catsTime)
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
  }

  // ==========================================================================
  // Benchmark 5: Number Parsing
  // ==========================================================================

  test("benchmark 5: Number Parsing (10K iterations)") {
    val rumilTime = benchmark(1000, 10000) {
      val _ = parser.runtime.run(rumilNumber, numberInput)
    }
    val catsTime = benchmark(1000, 10000) {
      val _ = catsNumber.parse(numberInput)
    }
    val zioTime = benchmark(1000, 10000) {
      val _ = zioNumber.parseString(numberInput)
    }

    println("\n=== Number Parsing (10K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    println(f"  zio-parser: ${zioTime}ms")

    val fastest = math.min(rumilTime, math.min(catsTime, zioTime))
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
    println(f"  zio vs fastest:   ${zioTime.toDouble / fastest}%.2fx")
  }

  test("benchmark 6: SUMMARY") {
    println("\n" + "=" * 70)
    println("LIBRARY COMPARISON SUMMARY")
    println("=" * 70)
    println("Rumil vs cats-parse vs zio-parser")
    println("All libraries validated for correctness before benchmarking.")
    println("")
  }
}
