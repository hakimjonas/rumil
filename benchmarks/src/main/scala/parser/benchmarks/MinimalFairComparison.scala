package parser.benchmarks

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit

/**
 * Minimal FAIR comparison: Only benchmarks where APIs are truly equivalent.
 *
 * Methodology:
 * 1. Parsers built ONCE in @Setup (excludes construction/compilation overhead)
 * 2. Inputs validated for correctness (both parsers succeed)
 * 3. Only operations where Rumil and zio have equivalent semantics
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class MinimalFairComparison {

  // Inputs
  var choiceInput: String = _
  var manyInput: String = _

  // Parsers (built once)
  var rumilChoice: parser.core.Parser[parser.core.ParseError, String] = _
  var zioChoice: zio.parser.Syntax[String, Char, Char, String] = _

  var rumilMany: parser.core.Parser[parser.core.ParseError, List[Char]] = _
  var zioMany: zio.parser.Syntax[String, Char, Char, zio.Chunk[Unit]] = _

  @Setup
  def setup(): Unit = {
    // Test inputs
    choiceInput = "lemon"  // Last choice, exercises all branches
    manyInput = "1" * 10000  // 10K repetitions

    // Build parsers ONCE
    {
      import parser.core._
      import parser.syntax._

      rumilChoice =
        string("apple") | string("banana") | string("cherry") |
        string("date") | string("elderberry") | string("fig") |
        string("grape") | string("honeydew") | string("kiwi") |
        string("lemon")

      rumilMany = parser.core.many(char('1'))
    }

    {
      import zio.parser._

      zioChoice =
        Syntax.string("apple", "apple") | Syntax.string("banana", "banana") |
        Syntax.string("cherry", "cherry") | Syntax.string("date", "date") |
        Syntax.string("elderberry", "elderberry") | Syntax.string("fig", "fig") |
        Syntax.string("grape", "grape") | Syntax.string("honeydew", "honeydew") |
        Syntax.string("kiwi", "kiwi") | Syntax.string("lemon", "lemon")

      zioMany = Syntax.char('1').repeat
    }

    // Validate correctness
    {
      import parser.runtime.run
      import parser.syntax._

      val r1 = run(rumilChoice, choiceInput)
      assert(r1.isSuccess, s"Rumil choice failed: $r1")

      val z1 = zioChoice.parseString(choiceInput)
      assert(z1.isRight, s"ZIO choice failed: $z1")

      val r2 = run(rumilMany, manyInput)
      assert(r2.isSuccess, s"Rumil many failed")

      val z2 = zioMany.parseString(manyInput)
      assert(z2.isRight, s"ZIO many failed")
    }

    println("✓ Setup validation passed - both libraries produce correct results")
  }

  //
  // Benchmark 1: Choice with backtracking
  //

  @Benchmark
  def rumil_choice(): Any = {
    import parser.runtime.run
    run(rumilChoice, choiceInput)
  }

  @Benchmark
  def zio_choice(): Any = {
    zioChoice.parseString(choiceInput)
  }

  //
  // Benchmark 2: Many repetition (10K elements)
  //

  @Benchmark
  def rumil_many(): Any = {
    import parser.runtime.run
    run(rumilMany, manyInput)
  }

  @Benchmark
  def zio_many(): Any = {
    zioMany.parseString(manyInput)
  }
}
