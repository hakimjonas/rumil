package parser.benchmarks

import parser.core._
import parser.syntax._

/**
 * Benchmarks comparing .memoize vs rule performance.
 *
 * These benchmarks measure:
 * 1. Cache hit performance: .memoize should be ~50% faster than rule
 * 2. Backtracking scenarios: both should reduce redundant work
 * 3. Memory overhead: separate cache tables vs unified LR tables
 */
object MemoizationBenchmarks {

  // ============================================================================
  // Benchmark 1: Cache Hit Performance
  // ============================================================================

  /**
   * Measures pure cache hit performance without parsing overhead.
   *
   * Creates a scenario where the same parser is tried multiple times
   * at the same position through backtracking. The first attempt caches
   * the result, subsequent attempts should hit the cache.
   *
   * Expected: .memoize ~50% faster than rule due to:
   * - No heads.get(pos) lookup
   * - No lrStack manipulation
   * - No Either unpacking
   * - Direct Result storage
   */
  def cacheHitPerformance(): Unit = {
    println("\n=== Benchmark 1: Cache Hit Performance ===")

    // Expensive parser that we'll memoize
    val expensiveWork = (char('a') ~ char('b') ~ char('c')).map { case ((a, b), c) =>
      // Simulate some work
      var sum = 0
      for (i <- 0 until 10) sum += i
      s"$a$b$c-$sum"
    }

    // Version 1: Using .memoize (fast path)
    val memoizedParser = expensiveWork.memoize
    val memoizeTest = (memoizedParser ~ char('x')) |
                      (memoizedParser ~ char('y')) |
                      (memoizedParser ~ char('z'))

    // Version 2: Using rule (LR path)
    lazy val ruledParser: Parser[ParseError, String] = rule { expensiveWork }
    val ruleTest = (ruledParser ~ char('x')) |
                   (ruledParser ~ char('y')) |
                   (ruledParser ~ char('z'))

    val input = "abcz"
    val iterations = 10000

    // Warmup
    for (_ <- 0 until 1000) {
      memoizeTest.run(input)
      ruleTest.run(input)
    }

    // Benchmark .memoize
    val memoizeStart = System.nanoTime()
    for (_ <- 0 until iterations) {
      memoizeTest.run(input)
    }
    val memoizeTime = (System.nanoTime() - memoizeStart) / 1_000_000

    // Benchmark rule
    val ruleStart = System.nanoTime()
    for (_ <- 0 until iterations) {
      ruleTest.run(input)
    }
    val ruleTime = (System.nanoTime() - ruleStart) / 1_000_000

    println(s"  Input: '$input' (forces backtracking, tests cache hits)")
    println(s"  Iterations: $iterations")
    println(s"  .memoize: ${memoizeTime}ms")
    println(s"  rule:     ${ruleTime}ms")
    println(s"  Speedup:  ${ruleTime.toDouble / memoizeTime.toDouble}x")
    println(s"  Improvement: ${((ruleTime - memoizeTime).toDouble / ruleTime.toDouble * 100).toInt}%")
  }

  // ============================================================================
  // Benchmark 2: Backtracking Without Cache Collisions
  // ============================================================================

  /**
   * Measures performance when backtracking occurs but at different positions.
   *
   * This tests the overhead of memoization infrastructure when cache hits
   * don't occur (different positions), but backtracking happens.
   *
   * Expected: .memoize and rule should be similar since cache misses occur,
   * but .memoize might be slightly faster due to simpler cache structure.
   */
  def backtrackingPerformance(): Unit = {
    println("\n=== Benchmark 2: Backtracking Without Cache Collisions ===")

    val letter = satisfy(_.isLetter, "letter")
    val digit = satisfy(_.isDigit, "digit")

    // Parser that tries letters or digits with backtracking
    val memoizedLetter = letter.memoize
    val memoizedDigit = digit.memoize
    val memoizeParser = (memoizedLetter.many1 ~ char('-') ~ memoizedDigit.many1)
      .map { case ((letters, _), digits) => (letters.mkString, digits.mkString) }

    lazy val ruledLetter: Parser[ParseError, Char] = rule { letter }
    lazy val ruledDigit: Parser[ParseError, Char] = rule { digit }
    val ruleParser = (ruledLetter.many1 ~ char('-') ~ ruledDigit.many1)
      .map { case ((letters, _), digits) => (letters.mkString, digits.mkString) }

    val input = "hello-123"
    val iterations = 10000

    // Warmup
    for (_ <- 0 until 1000) {
      memoizeParser.run(input)
      ruleParser.run(input)
    }

    // Benchmark .memoize
    val memoizeStart = System.nanoTime()
    for (_ <- 0 until iterations) {
      memoizeParser.run(input)
    }
    val memoizeTime = (System.nanoTime() - memoizeStart) / 1_000_000

    // Benchmark rule
    val ruleStart = System.nanoTime()
    for (_ <- 0 until iterations) {
      ruleParser.run(input)
    }
    val ruleTime = (System.nanoTime() - ruleStart) / 1_000_000

    println(s"  Input: '$input'")
    println(s"  Iterations: $iterations")
    println(s"  .memoize: ${memoizeTime}ms")
    println(s"  rule:     ${ruleTime}ms")
    println(s"  Ratio:    ${ruleTime.toDouble / memoizeTime.toDouble}x")
  }

  // ============================================================================
  // Benchmark 3: Complex Expression with Many Cache Hits
  // ============================================================================

  /**
   * Real-world scenario: parsing expressions with memoized components.
   *
   * This tests a more realistic parser where memoization provides benefits
   * by caching expensive sub-parsers that are tried multiple times.
   *
   * Expected: .memoize should show clear benefits over non-memoized,
   * and be faster than rule due to cache hit performance.
   */
  def complexExpressionPerformance(): Unit = {
    println("\n=== Benchmark 3: Complex Expression Parsing ===")

    val digit = satisfy(_.isDigit, "digit")
    val whitespace = char(' ').many.void

    // Non-memoized version (baseline)
    val numberBaseline = digit.many1.map(_.mkString.toInt)
    val parserBaseline = (numberBaseline ~ whitespace ~ char('+') ~ whitespace ~ numberBaseline)
      .map { case ((((a, _), _), _), b) => a + b }

    // Memoized version
    val numberMemoized = digit.many1.map(_.mkString.toInt).memoize
    val parserMemoized = (numberMemoized ~ whitespace ~ char('+') ~ whitespace ~ numberMemoized)
      .map { case ((((a, _), _), _), b) => a + b }

    // Rule version
    lazy val numberRuled: Parser[ParseError, Int] = rule {
      digit.many1.map(_.mkString.toInt)
    }
    val parserRuled = (numberRuled ~ whitespace ~ char('+') ~ whitespace ~ numberRuled)
      .map { case ((((a, _), _), _), b) => a + b }

    val input = "123 + 456"
    val iterations = 10000

    // Warmup
    for (_ <- 0 until 1000) {
      parserBaseline.run(input)
      parserMemoized.run(input)
      parserRuled.run(input)
    }

    // Benchmark baseline
    val baselineStart = System.nanoTime()
    for (_ <- 0 until iterations) {
      parserBaseline.run(input)
    }
    val baselineTime = (System.nanoTime() - baselineStart) / 1_000_000

    // Benchmark .memoize
    val memoizeStart = System.nanoTime()
    for (_ <- 0 until iterations) {
      parserMemoized.run(input)
    }
    val memoizeTime = (System.nanoTime() - memoizeStart) / 1_000_000

    // Benchmark rule
    val ruleStart = System.nanoTime()
    for (_ <- 0 until iterations) {
      parserRuled.run(input)
    }
    val ruleTime = (System.nanoTime() - ruleStart) / 1_000_000

    println(s"  Input: '$input'")
    println(s"  Iterations: $iterations")
    println(s"  Baseline (no memo): ${baselineTime}ms")
    println(s"  .memoize:           ${memoizeTime}ms")
    println(s"  rule:               ${ruleTime}ms")
    println(s"  ")
    println(s"  .memoize vs baseline: ${baselineTime.toDouble / memoizeTime.toDouble}x faster")
    println(s"  .memoize vs rule:     ${ruleTime.toDouble / memoizeTime.toDouble}x faster")
  }

  // ============================================================================
  // Benchmark 4: Memory Allocation
  // ============================================================================

  /**
   * Measures memory allocation patterns.
   *
   * This doesn't directly measure performance but shows allocation overhead.
   * Useful for understanding GC pressure.
   */
  def memoryAllocationTest(): Unit = {
    println("\n=== Benchmark 4: Memory Allocation Characteristics ===")

    val parser = (char('a') ~ char('b') ~ char('c'))

    val memoizedParser = parser.memoize
    val ruleParser = rule { parser }

    val input = "abc"
    val iterations = 1000

    // Force GC before measurement
    System.gc()
    Thread.sleep(100)

    val runtime = Runtime.getRuntime

    // Measure .memoize allocations
    val memoizeBefore = runtime.totalMemory() - runtime.freeMemory()
    for (_ <- 0 until iterations) {
      memoizedParser.run(input)
    }
    val memoizeAfter = runtime.totalMemory() - runtime.freeMemory()
    val memoizeAlloc = memoizeAfter - memoizeBefore

    // Force GC
    System.gc()
    Thread.sleep(100)

    // Measure rule allocations
    val ruleBefore = runtime.totalMemory() - runtime.freeMemory()
    for (_ <- 0 until iterations) {
      ruleParser.run(input)
    }
    val ruleAfter = runtime.totalMemory() - runtime.freeMemory()
    val ruleAlloc = ruleAfter - ruleBefore

    println(s"  Iterations: $iterations")
    println(s"  .memoize allocated: ~${memoizeAlloc / 1024}KB")
    println(s"  rule allocated:     ~${ruleAlloc / 1024}KB")
    println(s"  Note: Allocation measurements are approximate due to GC behavior")
  }

  // ============================================================================
  // Main Entry Point
  // ============================================================================

  def main(args: Array[String]): Unit = {
    println("=============================================================")
    println("  Memoization Performance Benchmarks")
    println("  Comparing .memoize (fast path) vs rule (LR-capable)")
    println("=============================================================")

    cacheHitPerformance()
    backtrackingPerformance()
    complexExpressionPerformance()
    memoryAllocationTest()

    println("\n=============================================================")
    println("  Summary")
    println("=============================================================")
    println("  .memoize provides:")
    println("  - Faster cache hits (~50% improvement expected)")
    println("  - Lower memory overhead (no LR infrastructure)")
    println("  - Simpler code paths (no heads lookup, lrStack, etc.)")
    println()
    println("  Use .memoize for:")
    println("  - Expensive non-recursive parsers")
    println("  - Performance-critical sections without LR needs")
    println("  - Inline combinations you want to cache")
    println()
    println("  Use rule for:")
    println("  - Left-recursive grammars")
    println("  - Named recursive rules")
    println("=============================================================")
  }
}
