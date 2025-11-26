package parser.runtime

import parser.core.{*, given}
import parser.runtime.experimental.TrampolineZeroCast

/**
 * Rigorous benchmark comparing TrampolineOpt vs TrampolineZeroCast.
 *
 * Both interpreters are stack-safe, but differ in:
 * - TrampolineOpt: Manual continuation stack (16 casts) - optimized for performance
 * - TrampolineZeroCast: Scala TailCalls GADT (7 casts) - optimized for type safety
 *
 * This benchmark measures which approach is faster in the post-Chunk world.
 */
class InterpreterComparisonBenchmark extends munit.FunSuite {

  // Create parser state
  def makeState(input: String) = new ParserState(input, 0, 1, 1)

  // Test inputs
  val shortInput = "a" * 100
  val mediumInput = "a" * 1000
  val longInput = "a" * 10000

  val choiceInput = "lemon"

  // Test parsers
  val manyA = many(char('a'))
  val manyChunkA = manyChunk(char('a'))

  val choice10 = choice(List(
    string("apple"), string("banana"), string("cherry"),
    string("date"), string("elderberry"), string("fig"),
    string("grape"), string("honeydew"), string("kiwi"),
    string("lemon")
  ))

  // Benchmark helper
  def benchmark(iterations: Int)(f: => Unit): Long = {
    // Warmup
    (0.until(iterations / 10)).foreach(_ => f)

    // Measure
    val start = System.nanoTime()
    (0.until(iterations)).foreach(_ => f)
    val end = System.nanoTime()

    val elapsed = (end - start) / 1_000_000 // Convert to ms
    elapsed
  }

  test("benchmark: many - short input (100 chars)") {
    val iterations = 1000

    val optTime = benchmark(iterations) {
      val _ = TrampolineOpt.run(manyA, makeState(shortInput))
    }

    val zeroTime = benchmark(iterations) {
      val _ = TrampolineZeroCast.run(manyA, makeState(shortInput))
    }

    println(s"\n--- Many (short: 100 chars, $iterations iterations) ---")
    println(f"  TrampolineOpt:      ${optTime}ms")
    println(f"  TrampolineZeroCast: ${zeroTime}ms")
    println(f"  Ratio (Opt/Zero):   ${optTime.toDouble / zeroTime}%.2fx")
    println(f"  Winner: ${if (optTime < zeroTime) "TrampolineOpt" else "TrampolineZeroCast"} (${math.abs(optTime - zeroTime)}ms faster)")
  }

  test("benchmark: many - medium input (1000 chars)") {
    val iterations = 100

    val optTime = benchmark(iterations) {
      val _ = TrampolineOpt.run(manyA, makeState(mediumInput))
    }

    val zeroTime = benchmark(iterations) {
      val _ = TrampolineZeroCast.run(manyA, makeState(mediumInput))
    }

    println(s"\n--- Many (medium: 1000 chars, $iterations iterations) ---")
    println(f"  TrampolineOpt:      ${optTime}ms")
    println(f"  TrampolineZeroCast: ${zeroTime}ms")
    println(f"  Ratio (Opt/Zero):   ${optTime.toDouble / zeroTime}%.2fx")
    println(f"  Winner: ${if (optTime < zeroTime) "TrampolineOpt" else "TrampolineZeroCast"} (${math.abs(optTime - zeroTime)}ms faster)")
  }

  test("benchmark: many - long input (10000 chars)") {
    val iterations = 10

    val optTime = benchmark(iterations) {
      val _ = TrampolineOpt.run(manyA, makeState(longInput))
    }

    val zeroTime = benchmark(iterations) {
      val _ = TrampolineZeroCast.run(manyA, makeState(longInput))
    }

    println(s"\n--- Many (long: 10000 chars, $iterations iterations) ---")
    println(f"  TrampolineOpt:      ${optTime}ms")
    println(f"  TrampolineZeroCast: ${zeroTime}ms")
    println(f"  Ratio (Opt/Zero):   ${optTime.toDouble / zeroTime}%.2fx")
    println(f"  Winner: ${if (optTime < zeroTime) "TrampolineOpt" else "TrampolineZeroCast"} (${math.abs(optTime - zeroTime)}ms faster)")
  }

  test("benchmark: manyChunk - medium input (1000 chars)") {
    val iterations = 100

    val optTime = benchmark(iterations) {
      val _ = TrampolineOpt.run(manyChunkA, makeState(mediumInput))
    }

    val zeroTime = benchmark(iterations) {
      val _ = TrampolineZeroCast.run(manyChunkA, makeState(mediumInput))
    }

    println(s"\n--- ManyChunk (medium: 1000 chars, $iterations iterations) ---")
    println(f"  TrampolineOpt:      ${optTime}ms")
    println(f"  TrampolineZeroCast: ${zeroTime}ms")
    println(f"  Ratio (Opt/Zero):   ${optTime.toDouble / zeroTime}%.2fx")
    println(f"  Winner: ${if (optTime < zeroTime) "TrampolineOpt" else "TrampolineZeroCast"} (${math.abs(optTime - zeroTime)}ms faster)")
  }

  test("benchmark: choice - 10 alternatives") {
    val iterations = 1000

    val optTime = benchmark(iterations) {
      val _ = TrampolineOpt.run(choice10, makeState(choiceInput))
    }

    val zeroTime = benchmark(iterations) {
      val _ = TrampolineZeroCast.run(choice10, makeState(choiceInput))
    }

    println(s"\n--- Choice (10 alternatives, $iterations iterations) ---")
    println(f"  TrampolineOpt:      ${optTime}ms")
    println(f"  TrampolineZeroCast: ${zeroTime}ms")
    println(f"  Ratio (Opt/Zero):   ${optTime.toDouble / zeroTime}%.2fx")
    println(f"  Winner: ${if (optTime < zeroTime) "TrampolineOpt" else "TrampolineZeroCast"} (${math.abs(optTime - zeroTime)}ms faster)")
  }

  test("benchmark: summary") {
    println("\n" + "=" * 70)
    println("SUMMARY")
    println("=" * 70)
    println("TrampolineOpt:      16 casts, manual continuation stack")
    println("TrampolineZeroCast:  7 casts, Scala TailCalls + GADT")
    println("")
  }
}
