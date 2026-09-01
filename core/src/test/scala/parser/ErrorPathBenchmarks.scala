package parser

/** Benchmarks specifically designed to stress ERROR PATHS and Partial results.
  *
  * These complement the happy-path benchmarks in ComprehensiveLibraryComparison by testing
  * scenarios where error accumulation matters.
  *
  * Categories:
  *   1. Many with recovery - Partial results accumulate errors
  *   2. sepBy with errors - Mixed success/failure in lists
  *   3. Choice with backtracking - Multiple failure paths
  *   4. Nested error recovery - Deep error accumulation
  */
class ErrorPathBenchmarks extends munit.FunSuite {

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
  // Category 1: Many with Recovery (Error Accumulation)
  // ==========================================================================

  test("error path 1.1: Many with recover (100 items, 5K iterations)") {
    val input = "a" * 50 + "x" * 50 // 50 successes, 50 recoveries

    val rumilParser = {
      import parser.core.*
      import parser.syntax.*
      val errorProne = char('a').recover(char('x')) // 'x' produces Partial with errors
      parser.core.many(errorProne)
    }

    val catsParser = {
      import cats.parse.{Parser as P}
      val errorProne = P.charIn('a').orElse(P.charIn('x')) // cats-parse has no error tracking
      errorProne.rep0
    }

    // Validate: both should recover and produce 100 elements
    val rumilResult = parser.runtime.run(rumilParser, input)
    rumilResult match {
      case parser.core.Result.Success(list: List[?], _) =>
        assert(list.length == 100, s"Rumil parsed ${list.length} items")
      case parser.core.Result.Partial(list: List[?], errors, _) =>
        assert(list.length == 100, s"Rumil parsed ${list.length} items with errors")
        println(s"  Rumil accumulated ${errors.length} errors (expected ~50)")
      case _ => fail("Rumil should succeed or partial")
    }

    val rumilTime = benchmark(500, 5000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(500, 5000) {
      val _ = catsParser.parse(input)
    }

    println("\n=== Many with Recovery (100 items, 5K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    val fastest = math.min(rumilTime, catsTime)
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
  }

  test("error path 1.2: Many with high error rate (1K items, 2K iterations)") {
    // Input: 90% errors, 10% success
    val input = ("x" * 9 + "a") * 100 // 900 recoveries, 100 successes

    val rumilParser = {
      import parser.core.*
      import parser.syntax.*
      val errorProne = char('a').recover(char('x')) // recover tracks errors
      parser.core.many(errorProne)
    }

    val catsParser = {
      import cats.parse.{Parser as P}
      val errorProne = P.charIn('a').orElse(P.charIn('x')) // cats-parse has no error tracking
      errorProne.rep0
    }

    // Validate
    val rumilResult = parser.runtime.run(rumilParser, input)
    rumilResult match {
      case parser.core.Result.Partial(list: List[?], errors, _) =>
        assert(list.length == 1000, s"Rumil parsed ${list.length} items")
        println(s"  Rumil accumulated ${errors.length} errors (expected ~900)")
      case _ => ()
    }

    val rumilTime = benchmark(200, 2000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(200, 2000) {
      val _ = catsParser.parse(input)
    }

    println("\n=== Many with High Error Rate (1K items, 2K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    val fastest = math.min(rumilTime, catsTime)
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
  }

  // ==========================================================================
  // Category 2: sepBy with Errors
  // ==========================================================================

  test("error path 2.1: sepBy with error recovery (10 numbers, 5K iterations)") {
    val input = "1,x,3,x,5,x,7,x,9,x" // Mix of valid and invalid numbers

    val rumilParser = {
      import parser.core.*
      import parser.syntax.*
      val num = digit
        .map(_.toString.toInt)
        .recover(
          char('x').map(_ => -1) // Recover with sentinel value and track errors
        )
      num.sepBy1(char(','))
    }

    val catsParser = {
      import cats.parse.{Parser as P}
      val num = P
        .charIn('0' to '9')
        .map(_.toString.toInt)
        .orElse(
          P.charIn('x').map(_ => -1) // cats-parse has no error tracking
        )
      num.repSep(P.charIn(','))
    }

    // Validate
    val rumilResult = parser.runtime.run(rumilParser, input)
    rumilResult match {
      case parser.core.Result.Partial(list: List[?], errors, _) =>
        assert(list.length == 10, s"Rumil parsed ${list.length} items")
      case parser.core.Result.Success(list: List[?], _) =>
        assert(list.length == 10, s"Rumil parsed ${list.length} items")
      case _ => fail("Should succeed or partial")
    }

    val rumilTime = benchmark(500, 5000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(500, 5000) {
      val _ = catsParser.parse(input)
    }

    println("\n=== sepBy with Error Recovery (10 numbers, 5K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    val fastest = math.min(rumilTime, catsTime)
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
  }

  // ==========================================================================
  // Category 3: Choice with Backtracking
  // ==========================================================================

  test("error path 3.1: Choice exhaustion (10 alternatives, all fail, 10K iterations)") {
    val input = "zzz" // None of the alternatives match

    val rumilParser = {
      import parser.core.*
      import parser.syntax.*
      string("apple") | string("banana") | string("cherry") |
        string("date") | string("elderberry") | string("fig") |
        string("grape") | string("honeydew") | string("kiwi") |
        string("lemon")
    }

    val catsParser = {
      import cats.parse.{Parser as P}
      P.string("apple").string | P.string("banana").string | P.string("cherry").string |
        P.string("date").string | P.string("elderberry").string | P.string("fig").string |
        P.string("grape").string | P.string("honeydew").string | P.string("kiwi").string |
        P.string("lemon").string
    }

    // Validate: both should fail
    assert(
      parser.runtime.run(rumilParser, input).isInstanceOf[parser.core.Result.Failure[?, ?]]
    ) // scalafix:ok DisableSyntax.isInstanceOf
    assert(catsParser.parse(input).isLeft)

    val rumilTime = benchmark(1000, 10000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(1000, 10000) {
      val _ = catsParser.parse(input)
    }

    println("\n=== Choice Exhaustion (10 alt, all fail, 10K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    val fastest = math.min(rumilTime, catsTime)
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
  }

  test("error path 3.2: Nested choice with partial matches (10K iterations)") {
    val input = "abcz" // Matches 'abc' but fails on 'z'

    val rumilParser = {
      import parser.core.*
      import parser.syntax.*
      // Try several partial matches before complete failure
      string("abcd") | string("abce") | string("abcf") | string("abcg")
    }

    val catsParser = {
      import cats.parse.{Parser as P}
      (P.string("abcd") | P.string("abce") | P.string("abcf") | P.string("abcg")).string
    }

    // Validate: both should fail
    assert(
      parser.runtime.run(rumilParser, input).isInstanceOf[parser.core.Result.Failure[?, ?]]
    ) // scalafix:ok DisableSyntax.isInstanceOf
    assert(catsParser.parse(input).isLeft)

    val rumilTime = benchmark(1000, 10000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(1000, 10000) {
      val _ = catsParser.parse(input)
    }

    println("\n=== Nested Choice with Partial Matches (10K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    val fastest = math.min(rumilTime, catsTime)
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
  }

  // ==========================================================================
  // Category 4: Deep Choice Backtracking (Error Accumulation)
  // ==========================================================================

  test("error path 4.1: Deep backtracking with many attempts (5K iterations)") {
    // Input requires trying many alternatives before succeeding
    val input = "zzzzza" // 4 failures then success

    val rumilParser = {
      import parser.core.*
      import parser.syntax.*
      // Each alternative tries 'z' before failing
      val alt1 = string("zzzzzb")
      val alt2 = string("zzzzc")
      val alt3 = string("zzzd")
      val alt4 = string("zze")
      val alt5 = string("zzzzza") // This one succeeds
      alt1 | alt2 | alt3 | alt4 | alt5
    }

    val catsParser = {
      import cats.parse.{Parser as P}
      val alt1 = P.string("zzzzzb").string
      val alt2 = P.string("zzzzc").string
      val alt3 = P.string("zzzd").string
      val alt4 = P.string("zze").string
      val alt5 = P.string("zzzzza").string
      alt1 | alt2 | alt3 | alt4 | alt5
    }

    // Validate
    assert(
      parser.runtime.run(rumilParser, input).isInstanceOf[parser.core.Result.Success[?, ?]]
    ) // scalafix:ok DisableSyntax.isInstanceOf
    assert(catsParser.parse(input).isRight)

    val rumilTime = benchmark(500, 5000) {
      val _ = parser.runtime.run(rumilParser, input)
    }
    val catsTime = benchmark(500, 5000) {
      val _ = catsParser.parse(input)
    }

    println("\n=== Deep Backtracking (5K iterations) ===")
    println(f"  Rumil:      ${rumilTime}ms")
    println(f"  cats-parse: ${catsTime}ms")
    val fastest = math.min(rumilTime, catsTime)
    println(f"  Rumil vs fastest: ${rumilTime.toDouble / fastest}%.2fx")
    println(f"  cats vs fastest:  ${catsTime.toDouble / fastest}%.2fx")
  }

  // ==========================================================================
  // Summary
  // ==========================================================================

  test("summary: Error Path Benchmarks") {
    println("\n" + "=" * 70)
    println("ERROR PATH BENCHMARKS COMPLETE")
    println("=" * 70)
    println("These benchmarks stress error accumulation, recovery, and backtracking.")
    println("They complement the happy-path benchmarks to reveal true bottlenecks.")
    println("")
  }
}
