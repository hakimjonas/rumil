package parser.runtime

import parser.core.{*, given}
import parser.runtime.experimental.TrampolineZeroCast

/**
 * Test the hybrid interpreter on the critical FlatMap chain workload.
 */
class InterpreterHybridTest extends munit.FunSuite {

  def makeState(input: String) = new ParserState(input, 0, 1, 1)

  def benchmark(warmupIters: Int, measureIters: Int)(f: => Unit): Long = {
    (0.until(warmupIters)).foreach(_ => f)
    System.gc()
    Thread.sleep(10)
    val start = System.nanoTime()
    (0.until(measureIters)).foreach(_ => f)
    val end = System.nanoTime()
    (end - start) / 1_000_000
  }

  // FlatMap chain - TrampolineOpt's winning workload
  val flatMapChain = {
    var p: Parser[ParseError, Int] = succeed(0)
    (1.until(50)).foreach { _ =>
      p = flatMap(p, (n: Int) => succeed(n + 1))
    }
    p
  }

  // Many - TrampolineZeroCast's winning workload
  val manyA = many(char('a'))
  val manyChunkA = manyChunk(char('a'))

  test("hybrid: flatMap chain (TrampolineOpt's strength)") {
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
    println()
    println(f"  Hybrid vs Opt:  ${if (hybridTime < optTime) "+" else ""}${100.0 * (optTime - hybridTime) / optTime}%.1f%%")
    println(f"  Hybrid vs Zero: ${if (hybridTime < zeroTime) "+" else ""}${100.0 * (zeroTime - hybridTime) / zeroTime}%.1f%%")
    println()

    val results = List(("Opt", optTime), ("Zero", zeroTime), ("Hybrid", hybridTime))
    val sorted = results.sortBy(_._2)
    println(s"  Ranking: ${sorted.map(r => s"${r._1}(${r._2}ms)").mkString(" < ")}")
  }

  test("hybrid: many short (TrampolineZeroCast's strength)") {
    val input = "a" * 100

    val optTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineOpt.run(manyA, makeState(input))
    }

    val zeroTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineZeroCast.run(manyA, makeState(input))
    }

    val hybridTime = benchmark(warmupIters = 1000, measureIters = 10000) {
      val _ = TrampolineHybrid.run(manyA, makeState(input))
    }

    println(s"\n--- Many (100 chars, 10K iterations) ---")
    println(f"  TrampolineOpt:      ${optTime}ms")
    println(f"  TrampolineZeroCast: ${zeroTime}ms")
    println(f"  TrampolineHybrid:   ${hybridTime}ms")
    println()
    println(f"  Hybrid vs Opt:  ${if (hybridTime < optTime) "+" else ""}${100.0 * (optTime - hybridTime) / optTime}%.1f%%")
    println(f"  Hybrid vs Zero: ${if (hybridTime < zeroTime) "+" else ""}${100.0 * (zeroTime - hybridTime) / zeroTime}%.1f%%")
    println()

    val results = List(("Opt", optTime), ("Zero", zeroTime), ("Hybrid", hybridTime))
    val sorted = results.sortBy(_._2)
    println(s"  Ranking: ${sorted.map(r => s"${r._1}(${r._2}ms)").mkString(" < ")}")
  }

  test("hybrid: manyChunk") {
    val input = "a" * 1000

    val optTime = benchmark(warmupIters = 100, measureIters = 1000) {
      val _ = TrampolineOpt.run(manyChunkA, makeState(input))
    }

    val zeroTime = benchmark(warmupIters = 100, measureIters = 1000) {
      val _ = TrampolineZeroCast.run(manyChunkA, makeState(input))
    }

    val hybridTime = benchmark(warmupIters = 100, measureIters = 1000) {
      val _ = TrampolineHybrid.run(manyChunkA, makeState(input))
    }

    println(s"\n--- ManyChunk (1000 chars, 1K iterations) ---")
    println(f"  TrampolineOpt:      ${optTime}ms")
    println(f"  TrampolineZeroCast: ${zeroTime}ms")
    println(f"  TrampolineHybrid:   ${hybridTime}ms")
    println()
    println(f"  Hybrid vs Opt:  ${if (hybridTime < optTime) "+" else ""}${100.0 * (optTime - hybridTime) / optTime}%.1f%%")
    println(f"  Hybrid vs Zero: ${if (hybridTime < zeroTime) "+" else ""}${100.0 * (zeroTime - hybridTime) / zeroTime}%.1f%%")
    println()

    val results = List(("Opt", optTime), ("Zero", zeroTime), ("Hybrid", hybridTime))
    val sorted = results.sortBy(_._2)
    println(s"  Ranking: ${sorted.map(r => s"${r._1}(${r._2}ms)").mkString(" < ")}")

    println("\n" + "=" * 70)
    println("HYBRID ASSESSMENT")
    println("=" * 70)
    println("TrampolineHybrid combines:")
    println("  - GADT continuations (like ZeroCast) - fewer casts")
    println("  - Manual loop + Array stack (like Opt) - no TailRec allocation")
    println("")
  }

  test("correctness: hybrid matches other interpreters") {
    val testCases = List(
      (flatMapChain, "", "flatMap chain"),
      (manyA, "a" * 100, "many short"),
      (manyChunkA, "a" * 1000, "manyChunk")
    )

    testCases.foreach { case (parser, input, name) =>
      val optResult = TrampolineOpt.run(parser, makeState(input))
      val zeroResult = TrampolineZeroCast.run(parser, makeState(input))
      val hybridResult = TrampolineHybrid.run(parser, makeState(input))

      // All should produce same result type and consumed count
      (optResult, zeroResult, hybridResult) match {
        case (Result.Success(_, c1), Result.Success(_, c2), Result.Success(_, c3)) =>
          assertEquals(c1, c2, s"$name: Opt vs Zero consumed")
          assertEquals(c1, c3, s"$name: Opt vs Hybrid consumed")

        case (Result.Partial(_, e1, c1), Result.Partial(_, e2, c2), Result.Partial(_, e3, c3)) =>
          assertEquals(c1, c2, s"$name: Opt vs Zero consumed")
          assertEquals(c1, c3, s"$name: Opt vs Hybrid consumed")
          assertEquals(e1.length, e2.length, s"$name: Opt vs Zero errors")
          assertEquals(e1.length, e3.length, s"$name: Opt vs Hybrid errors")

        case _ =>
          // All three should match - if not, fail
          fail(s"$name: result type mismatch - opt: $optResult, zero: $zeroResult, hybrid: $hybridResult")
      }
    }
  }
}
