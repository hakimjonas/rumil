package parser.runtime

import parser.core.{*, given}
import parser.runtime.experimental.TrampolineZeroCast
import parser.runtime.TrampolineHybrid

/**
 * Thorough benchmark comparing TrampolineOpt vs TrampolineZeroCast.
 *
 * More iterations, better warmup, diverse workloads.
 */
class InterpreterComparisonBenchmarkThorough extends munit.FunSuite {

  // Create parser state
  def makeState(input: String) = new ParserState(input, 0, 1, 1)

  // Benchmark helper with better warmup
  def benchmark(warmupIters: Int, measureIters: Int)(f: => Unit): Long = {
    // Longer warmup to ensure JIT compilation
    (0.until(warmupIters)).foreach(_ => f)

    // Force GC before measurement
    System.gc()
    Thread.sleep(10)

    // Measure
    val start = System.nanoTime()
    (0.until(measureIters)).foreach(_ => f)
    val end = System.nanoTime()

    (end - start) / 1_000_000 // Convert to ms
  }

  // Test parsers
  val manyA = many(char('a'))
  val manyChunkA = manyChunk(char('a'))

  val choice10 = choice(List(
    string("apple"), string("banana"), string("cherry"),
    string("date"), string("elderberry"), string("fig"),
    string("grape"), string("honeydew"), string("kiwi"),
    string("lemon")
  ))

  // FlatMap chain test
  val flatMapChain = {
    var p: Parser[ParseError, Int] = succeed(0)
    (1.until(50)).foreach { _ =>
      p = flatMap(p, (n: Int) => succeed(n + 1))
    }
    p
  }

  // Map chain test
  val mapChain = {
    var p: Parser[ParseError, Int] = succeed(0)
    (1.until(50)).foreach { _ =>
      p = map(p, (n: Int) => n + 1)
    }
    p
  }

  test("benchmark 1: many - short (100 chars, 10K iterations)") {
    val input = "a" * 100

    val optTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineOpt.run(manyA, makeState(input))
    }

    val zeroTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineZeroCast.run(manyA, makeState(input))
    }

    println(s"\n--- Many (100 chars, 10K iterations) ---")
    println(f"  TrampolineOpt:      ${optTime}ms")
    println(f"  TrampolineZeroCast: ${zeroTime}ms")
    println(f"  Speedup (Zero/Opt): ${optTime.toDouble / zeroTime}%.2fx")
    if (zeroTime < optTime) {
      println(f"  Winner: ZeroCast (${optTime - zeroTime}ms faster, ${100.0 * (optTime - zeroTime) / optTime}%.1f%% improvement)")
    } else if (optTime < zeroTime) {
      println(f"  Winner: Opt (${zeroTime - optTime}ms faster, ${100.0 * (zeroTime - optTime) / zeroTime}%.1f%% improvement)")
    } else {
      println("  Winner: Tied")
    }
  }

  test("benchmark 2: many - medium (1000 chars, 1K iterations)") {
    val input = "a" * 1000

    val optTime = benchmark(warmupIters = 100, measureIters = 1000) {
      val _ = TrampolineOpt.run(manyA, makeState(input))
    }

    val zeroTime = benchmark(warmupIters = 100, measureIters = 1000) {
      val _ = TrampolineZeroCast.run(manyA, makeState(input))
    }

    println(s"\n--- Many (1000 chars, 1K iterations) ---")
    println(f"  TrampolineOpt:      ${optTime}ms")
    println(f"  TrampolineZeroCast: ${zeroTime}ms")
    println(f"  Speedup (Zero/Opt): ${optTime.toDouble / zeroTime}%.2fx")
    if (zeroTime < optTime) {
      println(f"  Winner: ZeroCast (${optTime - zeroTime}ms faster, ${100.0 * (optTime - zeroTime) / optTime}%.1f%% improvement)")
    } else if (optTime < zeroTime) {
      println(f"  Winner: Opt (${zeroTime - optTime}ms faster, ${100.0 * (zeroTime - optTime) / zeroTime}%.1f%% improvement)")
    } else {
      println("  Winner: Tied")
    }
  }

  test("benchmark 3: many - long (10K chars, 100 iterations)") {
    val input = "a" * 10000

    val optTime = benchmark(warmupIters = 10, measureIters = 100) {
      val _ = TrampolineOpt.run(manyA, makeState(input))
    }

    val zeroTime = benchmark(warmupIters = 10, measureIters = 100) {
      val _ = TrampolineZeroCast.run(manyA, makeState(input))
    }

    println(s"\n--- Many (10K chars, 100 iterations) ---")
    println(f"  TrampolineOpt:      ${optTime}ms")
    println(f"  TrampolineZeroCast: ${zeroTime}ms")
    println(f"  Speedup (Zero/Opt): ${optTime.toDouble / zeroTime}%.2fx")
    if (zeroTime < optTime) {
      println(f"  Winner: ZeroCast (${optTime - zeroTime}ms faster, ${100.0 * (optTime - zeroTime) / optTime}%.1f%% improvement)")
    } else if (optTime < zeroTime) {
      println(f"  Winner: Opt (${zeroTime - optTime}ms faster, ${100.0 * (zeroTime - optTime) / zeroTime}%.1f%% improvement)")
    } else {
      println("  Winner: Tied")
    }
  }

  test("benchmark 4: manyChunk (1000 chars, 1K iterations)") {
    val input = "a" * 1000

    val optTime = benchmark(warmupIters = 100, measureIters = 1000) {
      val _ = TrampolineOpt.run(manyChunkA, makeState(input))
    }

    val zeroTime = benchmark(warmupIters = 100, measureIters = 1000) {
      val _ = TrampolineZeroCast.run(manyChunkA, makeState(input))
    }

    println(s"\n--- ManyChunk (1000 chars, 1K iterations) ---")
    println(f"  TrampolineOpt:      ${optTime}ms")
    println(f"  TrampolineZeroCast: ${zeroTime}ms")
    println(f"  Speedup (Zero/Opt): ${optTime.toDouble / zeroTime}%.2fx")
    if (zeroTime < optTime) {
      println(f"  Winner: ZeroCast (${optTime - zeroTime}ms faster, ${100.0 * (optTime - zeroTime) / optTime}%.1f%% improvement)")
    } else if (optTime < zeroTime) {
      println(f"  Winner: Opt (${zeroTime - optTime}ms faster, ${100.0 * (zeroTime - optTime) / zeroTime}%.1f%% improvement)")
    } else {
      println("  Winner: Tied")
    }
  }

  test("benchmark 5: choice (10 alternatives, 10K iterations)") {
    val input = "lemon"

    val optTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineOpt.run(choice10, makeState(input))
    }

    val zeroTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineZeroCast.run(choice10, makeState(input))
    }

    println(s"\n--- Choice (10 alternatives, 10K iterations) ---")
    println(f"  TrampolineOpt:      ${optTime}ms")
    println(f"  TrampolineZeroCast: ${zeroTime}ms")
    println(f"  Speedup (Zero/Opt): ${optTime.toDouble / zeroTime}%.2fx")
    if (zeroTime < optTime) {
      println(f"  Winner: ZeroCast (${optTime - zeroTime}ms faster, ${100.0 * (optTime - zeroTime) / optTime}%.1f%% improvement)")
    } else if (optTime < zeroTime) {
      println(f"  Winner: Opt (${zeroTime - optTime}ms faster, ${100.0 * (zeroTime - optTime) / zeroTime}%.1f%% improvement)")
    } else {
      println("  Winner: Tied")
    }
  }

  test("benchmark 6: flatMap chain (50 deep, 10K iterations)") {
    val input = ""

    val optTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineOpt.run(flatMapChain, makeState(input))
    }

    val zeroTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineZeroCast.run(flatMapChain, makeState(input))
    }

    val hybridTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineHybrid.run(flatMapChain, makeState(input))
    }

    println(s"\n--- FlatMap chain (50 deep, 10K iterations) ---")
    println(f"  TrampolineOpt:      ${optTime}ms")
    println(f"  TrampolineZeroCast: ${zeroTime}ms")
    println(f"  TrampolineHybrid:   ${hybridTime}ms")
    println(f"  Speedup (Hybrid/Opt):  ${optTime.toDouble / hybridTime}%.2fx")
    println(f"  Speedup (Hybrid/Zero): ${zeroTime.toDouble / hybridTime}%.2fx")

    val best = List(("Opt", optTime), ("Zero", zeroTime), ("Hybrid", hybridTime)).minBy(_._2)
    println(f"  Winner: ${best._1}")
  }

  test("benchmark 7: map chain (50 deep, 10K iterations)") {
    val input = ""

    val optTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineOpt.run(mapChain, makeState(input))
    }

    val zeroTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineZeroCast.run(mapChain, makeState(input))
    }

    println(s"\n--- Map chain (50 deep, 10K iterations) ---")
    println(f"  TrampolineOpt:      ${optTime}ms")
    println(f"  TrampolineZeroCast: ${zeroTime}ms")
    println(f"  Speedup (Zero/Opt): ${optTime.toDouble / zeroTime}%.2fx")
    if (zeroTime < optTime) {
      println(f"  Winner: ZeroCast (${optTime - zeroTime}ms faster, ${100.0 * (optTime - zeroTime) / optTime}%.1f%% improvement)")
    } else if (optTime < zeroTime) {
      println(f"  Winner: Opt (${zeroTime - optTime}ms faster, ${100.0 * (zeroTime - optTime) / zeroTime}%.1f%% improvement)")
    } else {
      println("  Winner: Tied")
    }
  }

  test("benchmark 8: summary") {
    println("\n" + "=" * 70)
    println("THOROUGH BENCHMARK SUMMARY")
    println("=" * 70)
    println("TrampolineOpt:      16 casts, manual continuation stack")
    println("TrampolineZeroCast:  7 casts, Scala TailCalls + GADT")
    println("")
    println("All tests run with:")
    println("  - Proper warmup (100-1000 iterations)")
    println("  - GC between tests")
    println("  - High iteration counts (100-10K)")
    println("")
  }
}
