package parser.benchmarks

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit

/**
 * Comparative benchmarks: Rumil vs zio-parser
 *
 * Both libraries use trampolining for stack safety. This benchmark
 * measures the performance differences in their implementations.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
class TrampolineComparison {

  // Test inputs
  val shortDigits  = "1234567890"
  val longDigits   = "1234567890" * 100 // 1000 digits
  val manyDigits   = "1" * 10000         // 10K repetitions

  //
  // Benchmark 1: Simple sequential parsing (digit recognition)
  //

  @Benchmark
  def rumil_digitSequence(): Any = {
    import parser.core._
    import parser.syntax._
    import parser.runtime.run

    val digit = satisfy(_.isDigit, "digit")
    val p = parser.core.many(digit)
    run(p, longDigits)
  }

  @Benchmark
  def zio_digitSequence(): Any = {
    import zio.parser._

    val digit = Syntax.digit
    val p = digit.repeat
    p.parseString(longDigits)
  }

  //
  // Benchmark 2: Deep flatMap chains
  //
  // IMPORTANT: flatMap chains need N+1 chars (initial parse + N continuations)
  //

  @Benchmark
  def rumil_deepFlatMap(): Any = {
    import parser.core._
    import parser.syntax._
    import parser.runtime.run

    var p: Parser[ParseError, Char] = char('1')
    for (_ <- 1 to 100) {
      p = p.flatMap(_ => char('1'))
    }
    val input = "1" * 101  // Fixed: need 101 chars for 100 flatMaps
    run(p, input)
  }

  @Benchmark
  def zio_deepFlatMap(): Any = {
    import zio.parser._

    // zio-parser doesn't have flatMap on Syntax, use ~> (zipRight) instead
    var p = Syntax.char('1')
    for (_ <- 1 to 100) {
      p = p ~> Syntax.char('1')
    }
    val input = "1" * 101  // Fixed: need 101 chars
    p.parseString(input)
  }

  //
  // Benchmark 3: Repetition with collection (many/repeat)
  //

  @Benchmark
  def rumil_manyRepetition(): Any = {
    import parser.core._
    import parser.syntax._
    import parser.runtime.run

    val p = parser.core.many(char('1'))
    run(p, manyDigits)
  }

  @Benchmark
  def zio_manyRepetition(): Any = {
    import zio.parser._

    val p = Syntax.char('1').repeat
    p.parseString(manyDigits)
  }

  //
  // Benchmark 4: Choice with backtracking
  //

  @Benchmark
  def rumil_choice10(): Any = {
    import parser.core._
    import parser.syntax._
    import parser.runtime.run

    val p =
      string("apple") | string("banana") | string("cherry") |
      string("date") | string("elderberry") | string("fig") |
      string("grape") | string("honeydew") | string("kiwi") |
      string("lemon")

    // Test last option to exercise all branches
    run(p, "lemon")
  }

  @Benchmark
  def zio_choice10(): Any = {
    import zio.parser._

    // Syntax.string requires result value parameter
    val p =
      Syntax.string("apple", "apple") | Syntax.string("banana", "banana") | Syntax.string("cherry", "cherry") |
      Syntax.string("date", "date") | Syntax.string("elderberry", "elderberry") | Syntax.string("fig", "fig") |
      Syntax.string("grape", "grape") | Syntax.string("honeydew", "honeydew") | Syntax.string("kiwi", "kiwi") |
      Syntax.string("lemon", "lemon")

    p.parseString("lemon")
  }

  //
  // Benchmark 5: Sequential parsing (zip)
  //

  @Benchmark
  def rumil_seq10(): Any = {
    import parser.core._
    import parser.syntax._
    import parser.runtime.run

    val digit = satisfy(_.isDigit, "digit")
    val p = digit ~ digit ~ digit ~ digit ~ digit ~
            digit ~ digit ~ digit ~ digit ~ digit

    run(p, "1234567890")
  }

  @Benchmark
  def zio_seq10(): Any = {
    import zio.parser._

    val digit = Syntax.digit
    val p = digit ~ digit ~ digit ~ digit ~ digit ~
            digit ~ digit ~ digit ~ digit ~ digit

    p.parseString("1234567890")
  }
}
