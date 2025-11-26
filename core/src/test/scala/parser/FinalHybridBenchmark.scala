package parser

import parser.core._
import parser.runtime._
import parser.syntax._

/**
 * Comprehensive benchmark comparing TrampolineOpt vs TrampolineHybrid.
 * This is the FINAL benchmark with the fixed Hybrid implementation.
 */
class FinalHybridBenchmark extends munit.FunSuite {

  def makeState(input: String) = parserState(input)

  def benchmark(warmupIters: Int, measureIters: Int)(f: => Unit): Long = {
    (0 until warmupIters).foreach(_ => f)
    System.gc()
    Thread.sleep(10)
    val start = System.nanoTime()
    (0 until measureIters).foreach(_ => f)
    val end = System.nanoTime()
    (end - start) / 1_000_000
  }

  // Test parsers
  val manyA = char('a').many
  
  val flatMapChain50 = {
    var p: Parser[ParseError, Int] = succeed(0)
    (1 until 50).foreach { _ => p = p.flatMap((n: Int) => succeed(n + 1)) }
    p
  }

  val flatMapChain1000 = {
    var p: Parser[ParseError, Int] = succeed(0)
    (1 until 1000).foreach { _ => p = p.flatMap((n: Int) => succeed(n + 1)) }
    p
  }

  val mapChain50 = {
    var p: Parser[ParseError, Int] = succeed(0)
    (1 until 50).foreach { _ => p = p.map((n: Int) => n + 1) }
    p
  }

  val choice10 = (string("apple") | string("banana") | string("cherry") |
    string("date") | string("elderberry") | string("fig") |
    string("grape") | string("honeydew") | string("kiwi") | string("lemon"))

  test("benchmark 1: FlatMap chain (50 deep, 10K iterations)") {
    val input = ""

    val optTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineOpt.run(flatMapChain50, makeState(input))
    }

    val hybridTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineHybrid.run(flatMapChain50, makeState(input))
    }

    println(s"\n=== FlatMap Chain (50 deep, 10K iterations) ===")
    println(f"  TrampolineOpt:    ${optTime}ms")
    println(f"  TrampolineHybrid: ${hybridTime}ms")
    val improvement = 100.0 * (optTime - hybridTime) / optTime
    println(f"  Improvement: ${if (hybridTime < optTime) "+" else ""}${improvement}%.1f%%")
    println(f"  Winner: ${if (hybridTime < optTime) "Hybrid" else if (optTime < hybridTime) "Opt" else "Tied"}")
  }

  test("benchmark 2: FlatMap chain (1000 deep, 1K iterations)") {
    val input = ""

    val optTime = benchmark(warmupIters = 100, measureIters = 1000) {
      val _ = TrampolineOpt.run(flatMapChain1000, makeState(input))
    }

    val hybridTime = benchmark(warmupIters = 100, measureIters = 1000) {
      val _ = TrampolineHybrid.run(flatMapChain1000, makeState(input))
    }

    println(s"\n=== FlatMap Chain (1000 deep, 1K iterations) ===")
    println(f"  TrampolineOpt:    ${optTime}ms")
    println(f"  TrampolineHybrid: ${hybridTime}ms")
    val improvement = 100.0 * (optTime - hybridTime) / optTime
    println(f"  Improvement: ${if (hybridTime < optTime) "+" else ""}${improvement}%.1f%%")
    println(f"  Winner: ${if (hybridTime < optTime) "Hybrid" else if (optTime < hybridTime) "Opt" else "Tied"}")
  }

  test("benchmark 3: Many (100 chars, 10K iterations)") {
    val input = "a" * 100

    val optTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineOpt.run(manyA, makeState(input))
    }

    val hybridTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineHybrid.run(manyA, makeState(input))
    }

    println(s"\n=== Many (100 chars, 10K iterations) ===")
    println(f"  TrampolineOpt:    ${optTime}ms")
    println(f"  TrampolineHybrid: ${hybridTime}ms")
    val improvement = 100.0 * (optTime - hybridTime) / optTime
    println(f"  Improvement: ${if (hybridTime < optTime) "+" else ""}${improvement}%.1f%%")
    println(f"  Winner: ${if (hybridTime < optTime) "Hybrid" else if (optTime < hybridTime) "Opt" else "Tied"}")
  }

  test("benchmark 4: Many (1000 chars, 1K iterations)") {
    val input = "a" * 1000

    val optTime = benchmark(warmupIters = 100, measureIters = 1000) {
      val _ = TrampolineOpt.run(manyA, makeState(input))
    }

    val hybridTime = benchmark(warmupIters = 100, measureIters = 1000) {
      val _ = TrampolineHybrid.run(manyA, makeState(input))
    }

    println(s"\n=== Many (1000 chars, 1K iterations) ===")
    println(f"  TrampolineOpt:    ${optTime}ms")
    println(f"  TrampolineHybrid: ${hybridTime}ms")
    val improvement = 100.0 * (optTime - hybridTime) / optTime
    println(f"  Improvement: ${if (hybridTime < optTime) "+" else ""}${improvement}%.1f%%")
    println(f"  Winner: ${if (hybridTime < optTime) "Hybrid" else if (optTime < hybridTime) "Opt" else "Tied"}")
  }

  test("benchmark 5: Map chain (50 deep, 10K iterations)") {
    val input = ""

    val optTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineOpt.run(mapChain50, makeState(input))
    }

    val hybridTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineHybrid.run(mapChain50, makeState(input))
    }

    println(s"\n=== Map Chain (50 deep, 10K iterations) ===")
    println(f"  TrampolineOpt:    ${optTime}ms")
    println(f"  TrampolineHybrid: ${hybridTime}ms")
    val improvement = 100.0 * (optTime - hybridTime) / optTime
    println(f"  Improvement: ${if (hybridTime < optTime) "+" else ""}${improvement}%.1f%%")
    println(f"  Winner: ${if (hybridTime < optTime) "Hybrid" else if (optTime < hybridTime) "Opt" else "Tied"}")
  }

  test("benchmark 6: Choice (10 alternatives, 10K iterations)") {
    val input = "lemon"

    val optTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineOpt.run(choice10, makeState(input))
    }

    val hybridTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineHybrid.run(choice10, makeState(input))
    }

    println(s"\n=== Choice (10 alternatives, 10K iterations) ===")
    println(f"  TrampolineOpt:    ${optTime}ms")
    println(f"  TrampolineHybrid: ${hybridTime}ms")
    val improvement = 100.0 * (optTime - hybridTime) / optTime
    println(f"  Improvement: ${if (hybridTime < optTime) "+" else ""}${improvement}%.1f%%")
    println(f"  Winner: ${if (hybridTime < optTime) "Hybrid" else if (optTime < hybridTime) "Opt" else "Tied"}")
  }

  test("benchmark 7: Sequential parsers (10K using ~, 100 iterations)") {
    var p: Parser[ParseError, Any] = char('1')
    (1 until 10000).foreach { _ => p = p ~ char('1') }
    val input = "1" * 10000

    val optTime = benchmark(warmupIters = 10, measureIters = 100) {
      val _ = TrampolineOpt.run(p, makeState(input))
    }

    val hybridTime = benchmark(warmupIters = 10, measureIters = 100) {
      val _ = TrampolineHybrid.run(p, makeState(input))
    }

    println(s"\n=== Sequential Parsers (10K using ~, 100 iterations) ===")
    println(f"  TrampolineOpt:    ${optTime}ms")
    println(f"  TrampolineHybrid: ${hybridTime}ms")
    val improvement = 100.0 * (optTime - hybridTime) / optTime
    println(f"  Improvement: ${if (hybridTime < optTime) "+" else ""}${improvement}%.1f%%")
    println(f"  Winner: ${if (hybridTime < optTime) "Hybrid" else if (optTime < hybridTime) "Opt" else "Tied"}")
  }

  test("benchmark 8: SUMMARY") {
    println("\n" + "=" * 70)
    println("FINAL BENCHMARK SUMMARY - Fixed TrampolineHybrid")
    println("=" * 70)
    println("All tests run with proper warmup and high iteration counts.")
    println("TrampolineHybrid now has the Success/Partial bug fixed.")
    println("")
  }
}
