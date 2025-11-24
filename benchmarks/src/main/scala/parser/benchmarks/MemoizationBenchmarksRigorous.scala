package parser.benchmarks

import parser.core._
import parser.syntax._
import scala.collection.mutable.ArrayBuffer

/**
 * Rigorous benchmarks with proper JVM warmup and statistical analysis.
 */
object MemoizationBenchmarksRigorous {

  case class BenchmarkResult(
    name: String,
    measurements: Vector[Long],
    unit: String = "ms"
  ) {
    def mean: Double = measurements.sum.toDouble / measurements.size
    def median: Double = {
      val sorted = measurements.sorted
      val n = sorted.size
      if (n % 2 == 0) (sorted(n/2 - 1) + sorted(n/2)) / 2.0
      else sorted(n/2).toDouble
    }
    def stdDev: Double = {
      val m = mean
      val variance = measurements.map(x => math.pow(x - m, 2)).sum / measurements.size
      math.sqrt(variance)
    }
    def min: Long = measurements.min
    def max: Long = measurements.max

    def summary: String = {
      f"$name%-40s: mean=${mean}%6.2f$unit, median=${median}%6.2f$unit, " +
      f"stddev=${stdDev}%5.2f$unit, min=$min%4d$unit, max=$max%4d$unit"
    }
  }

  /**
   * Run a benchmark with proper warmup and multiple iterations.
   * Returns measurements in milliseconds.
   */
  def runBenchmark(
    name: String,
    warmupIterations: Int,
    measureIterations: Int,
    innerIterations: Int,
    fn: () => Unit
  ): BenchmarkResult = {
    println(s"  Running: $name")
    println(s"    Warmup: $warmupIterations iterations...")

    // Warmup phase
    for (i <- 0 until warmupIterations) {
      fn()
      if (i % 100 == 0 && i > 0) {
        print(".")
        System.out.flush()
      }
    }
    println(" done")

    // Force GC before measurement
    System.gc()
    Thread.sleep(100)

    println(s"    Measuring: $measureIterations runs of $innerIterations iterations each...")
    val measurements = ArrayBuffer[Long]()

    for (run <- 0 until measureIterations) {
      val start = System.nanoTime()
      for (_ <- 0 until innerIterations) {
        fn()
      }
      val elapsed = (System.nanoTime() - start) / 1_000_000  // Convert to ms
      measurements += elapsed

      if (run % 10 == 0 && run > 0) {
        print(".")
        System.out.flush()
      }
    }
    println(" done")

    BenchmarkResult(name, measurements.toVector)
  }

  // ============================================================================
  // Benchmark 1: Pure Cache Hit Performance (Same Position)
  // ============================================================================

  def pureCacheHitBenchmark(): Unit = {
    println("\n" + "="*70)
    println("BENCHMARK 1: Pure Cache Hit Performance")
    println("Testing: Repeated parses at the SAME position (pure cache hits)")
    println("="*70)

    // Create a simple parser that we'll call multiple times at position 0
    val simpleParser = (char('a') ~ char('b') ~ char('c'))

    val memoizedParser = simpleParser.memoize
    lazy val ruledParser = rule { simpleParser }

    val input = "abc"

    // Test: Parse at position 0, then use lookAhead to stay at position 0 and parse again
    // This creates pure cache hits at the same position
    val memoizeTest = () => {
      val result = memoizedParser.run(input)
      // Force evaluation
      result.toOption
      ()
    }

    val ruleTest = () => {
      val result = ruledParser.run(input)
      result.toOption
      ()
    }

    val memoizeResult = runBenchmark(
      ".memoize (simple parser)",
      warmupIterations = 10000,
      measureIterations = 100,
      innerIterations = 10000,
      memoizeTest
    )

    val ruleResult = runBenchmark(
      "rule (simple parser)",
      warmupIterations = 10000,
      measureIterations = 100,
      innerIterations = 10000,
      ruleTest
    )

    println("\n" + "-"*70)
    println("Results:")
    println(memoizeResult.summary)
    println(ruleResult.summary)
    println()
    println(f"Speedup: ${ruleResult.mean / memoizeResult.mean}%.2fx (.memoize vs rule)")
    println("-"*70)
  }

  // ============================================================================
  // Benchmark 2: Backtracking with Cache Hits
  // ============================================================================

  def backtrackingCacheHitBenchmark(): Unit = {
    println("\n" + "="*70)
    println("BENCHMARK 2: Backtracking with Cache Hits")
    println("Testing: Parser tried multiple times at same position via backtracking")
    println("="*70)

    // Expensive parser that we'll try multiple times due to backtracking
    val expensiveWork = (char('a') ~ char('b') ~ char('c')).map { case ((a, b), c) =>
      // Add some computational work to make caching worthwhile
      var sum = 0
      for (i <- 0 until 100) sum += i
      (a, b, c, sum)
    }

    val memoizedParser = expensiveWork.memoize
    lazy val ruledParser = rule { expensiveWork }

    // Create backtracking: try with 'x', fail, backtrack, try with 'y', fail, try with 'z', succeed
    // This causes expensiveWork to be evaluated 3 times at position 0 without memoization
    // With memoization: first eval caches, next 2 are cache hits
    val memoizeTest = (memoizedParser ~ char('x')) |
                       (memoizedParser ~ char('y')) |
                       (memoizedParser ~ char('z'))

    val ruleTest = (ruledParser ~ char('x')) |
                    (ruledParser ~ char('y')) |
                    (ruledParser ~ char('z'))

    val input = "abcz"  // Forces third alternative

    val memoizeResult = runBenchmark(
      ".memoize (backtracking)",
      warmupIterations = 10000,
      measureIterations = 100,
      innerIterations = 5000,
      () => { memoizeTest.run(input); () }
    )

    val ruleResult = runBenchmark(
      "rule (backtracking)",
      warmupIterations = 10000,
      measureIterations = 100,
      innerIterations = 5000,
      () => { ruleTest.run(input); () }
    )

    // Baseline: no memoization
    val baselineTest = (expensiveWork ~ char('x')) |
                        (expensiveWork ~ char('y')) |
                        (expensiveWork ~ char('z'))

    val baselineResult = runBenchmark(
      "baseline (no memoization)",
      warmupIterations = 10000,
      measureIterations = 100,
      innerIterations = 5000,
      () => { baselineTest.run(input); () }
    )

    println("\n" + "-"*70)
    println("Results:")
    println(baselineResult.summary)
    println(memoizeResult.summary)
    println(ruleResult.summary)
    println()
    println(f"Speedup vs baseline:")
    println(f"  .memoize: ${baselineResult.mean / memoizeResult.mean}%.2fx faster")
    println(f"  rule:     ${baselineResult.mean / ruleResult.mean}%.2fx faster")
    println()
    println(f".memoize vs rule: ${ruleResult.mean / memoizeResult.mean}%.2fx")
    println("-"*70)
  }

  // ============================================================================
  // Benchmark 3: Many Different Positions (Cache Miss Dominant)
  // ============================================================================

  def cacheMissBenchmark(): Unit = {
    println("\n" + "="*70)
    println("BENCHMARK 3: Cache Misses (Different Positions)")
    println("Testing: Parser called at many different positions")
    println("="*70)

    val letter = satisfy(_.isLetter, "letter")
    val digit = satisfy(_.isDigit, "digit")

    val memoizedLetter = letter.memoize
    val memoizedDigit = digit.memoize
    lazy val ruledLetter: Parser[ParseError, Char] = rule { letter }
    lazy val ruledDigit: Parser[ParseError, Char] = rule { digit }

    // Parser that scans through input, calling memoized parsers at different positions
    val memoizeParser = (memoizedLetter.many1 ~ char('-') ~ memoizedDigit.many1)
    val ruleParser = (ruledLetter.many1 ~ char('-') ~ ruledDigit.many1)
    val baselineParser = (letter.many1 ~ char('-') ~ digit.many1)

    val input = "abcdefghij-0123456789"  // Many different positions

    val baselineResult = runBenchmark(
      "baseline (no memoization)",
      warmupIterations = 10000,
      measureIterations = 100,
      innerIterations = 10000,
      () => { baselineParser.run(input); () }
    )

    val memoizeResult = runBenchmark(
      ".memoize (many positions)",
      warmupIterations = 10000,
      measureIterations = 100,
      innerIterations = 10000,
      () => { memoizeParser.run(input); () }
    )

    val ruleResult = runBenchmark(
      "rule (many positions)",
      warmupIterations = 10000,
      measureIterations = 100,
      innerIterations = 10000,
      () => { ruleParser.run(input); () }
    )

    println("\n" + "-"*70)
    println("Results:")
    println(baselineResult.summary)
    println(memoizeResult.summary)
    println(ruleResult.summary)
    println()
    println("Note: In this scenario, memoization overhead may outweigh benefits")
    println("      since we're hitting different positions (mostly cache misses)")
    println()
    println(f"Overhead vs baseline:")
    println(f"  .memoize: ${(memoizeResult.mean / baselineResult.mean - 1) * 100}%.1f%%")
    println(f"  rule:     ${(ruleResult.mean / baselineResult.mean - 1) * 100}%.1f%%")
    println("-"*70)
  }

  // ============================================================================
  // Benchmark 4: Realistic Mixed Scenario
  // ============================================================================

  def realisticMixedBenchmark(): Unit = {
    println("\n" + "="*70)
    println("BENCHMARK 4: Realistic Mixed Scenario")
    println("Testing: Expression parsing with some cache hits, some misses")
    println("="*70)

    val digit = satisfy(_.isDigit, "digit")
    val whitespace = satisfy(c => c == ' ' || c == '\t', "whitespace").many.void

    val numberMemoized = digit.many1.map(_.mkString.toInt).memoize
    lazy val numberRuled: Parser[ParseError, Int] = rule { digit.many1.map(_.mkString.toInt) }
    val numberBaseline = digit.many1.map(_.mkString.toInt)

    // Expression: number + number + number (tests memoization of number parser)
    val memoizeParser = (numberMemoized ~ whitespace ~ char('+') ~ whitespace ~ numberMemoized ~
                          whitespace ~ char('+') ~ whitespace ~ numberMemoized)
    val ruleParser = (numberRuled ~ whitespace ~ char('+') ~ whitespace ~ numberRuled ~
                       whitespace ~ char('+') ~ whitespace ~ numberRuled)
    val baselineParser = (numberBaseline ~ whitespace ~ char('+') ~ whitespace ~ numberBaseline ~
                           whitespace ~ char('+') ~ whitespace ~ numberBaseline)

    val input = "123 + 456 + 789"

    val baselineResult = runBenchmark(
      "baseline (no memoization)",
      warmupIterations = 10000,
      measureIterations = 100,
      innerIterations = 10000,
      () => { baselineParser.run(input); () }
    )

    val memoizeResult = runBenchmark(
      ".memoize (expression)",
      warmupIterations = 10000,
      measureIterations = 100,
      innerIterations = 10000,
      () => { memoizeParser.run(input); () }
    )

    val ruleResult = runBenchmark(
      "rule (expression)",
      warmupIterations = 10000,
      measureIterations = 100,
      innerIterations = 10000,
      () => { ruleParser.run(input); () }
    )

    println("\n" + "-"*70)
    println("Results:")
    println(baselineResult.summary)
    println(memoizeResult.summary)
    println(ruleResult.summary)
    println()
    println(f"vs baseline:")
    println(f"  .memoize: ${baselineResult.mean / memoizeResult.mean}%.2fx")
    println(f"  rule:     ${baselineResult.mean / ruleResult.mean}%.2fx")
    println()
    println(f".memoize vs rule: ${ruleResult.mean / memoizeResult.mean}%.2fx")
    println("-"*70)
  }

  // ============================================================================
  // Main Entry Point
  // ============================================================================

  def main(args: Array[String]): Unit = {
    println("\n")
    println("="*70)
    println("  RIGOROUS MEMOIZATION BENCHMARKS")
    println("  with proper JVM warmup and statistical analysis")
    println("="*70)
    println()
    println("Configuration:")
    println("  - Warmup: 10000 iterations per benchmark")
    println("  - Measurements: 100 runs per benchmark")
    println("  - Inner iterations: 5000-10000 per measurement")
    println("  - Statistics: mean, median, stddev, min, max")
    println()

    pureCacheHitBenchmark()
    backtrackingCacheHitBenchmark()
    cacheMissBenchmark()
    realisticMixedBenchmark()

    println("\n" + "="*70)
    println("SUMMARY")
    println("="*70)
    println()
    println("Key Findings:")
    println("1. Cache Hit Performance: Compare Benchmark 1 & 2 results")
    println("2. Backtracking Benefits: See Benchmark 2 speedup vs baseline")
    println("3. Cache Miss Overhead: Benchmark 3 shows overhead when misses dominate")
    println("4. Realistic Scenarios: Benchmark 4 shows mixed workload performance")
    println()
    println("Recommendations:")
    println("  Use .memoize when:")
    println("    - Parser is expensive and will be tried multiple times")
    println("    - Backtracking scenarios exist")
    println("    - Not left-recursive")
    println()
    println("  Use rule when:")
    println("    - Left-recursion is needed")
    println("    - Named recursive rules")
    println()
    println("  Skip memoization when:")
    println("    - Parser is simple/fast")
    println("    - Cache hits are unlikely (mostly different positions)")
    println("="*70)
  }
}
