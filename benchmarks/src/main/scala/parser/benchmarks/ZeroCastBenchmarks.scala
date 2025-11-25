package parser.benchmarks

import java.util.concurrent.TimeUnit

import scala.compiletime.uninitialized

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import parser.core._
import parser.runtime.{run, runZeroCast}
import parser.syntax._

/**
 * JMH benchmarks comparing run() vs runZeroCast().
 *
 * Tests the performance tradeoff of the zero-cast (minimal-cast) approach
 * which uses GADT Continuations + TailCalls vs the optimized manual trampoline.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class ZeroCastBenchmarks {

  // ============================================================================
  // Test Data
  // ============================================================================

  var digits10: String  = uninitialized
  var digits50: String  = uninitialized
  var digits100: String = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    digits10 = "1234567890"
    digits50 = "12345678901234567890123456789012345678901234567890"
    digits100 = "1234567890" * 10
  }

  // ============================================================================
  // Parsers
  // ============================================================================

  def digitParser: Parser[ParseError, Int] = satisfy(_.isDigit, "digit").map(_.asDigit)

  // Build a parser that's a sequence of N parsers using flatMap (stack stress test)
  def seqParser(n: Int): Parser[ParseError, List[Int]] =
    (1 to n).foldLeft(succeed(List.empty[Int])) { (acc, _) =>
      acc.flatMap(list => digitParser.map(d => list :+ d))
    }

  lazy val seq10Parser: Parser[ParseError, List[Int]]  = seqParser(10)
  lazy val seq50Parser: Parser[ParseError, List[Int]]  = seqParser(50)
  lazy val seq100Parser: Parser[ParseError, List[Int]] = seqParser(100)

  // ============================================================================
  // Benchmarks: seq10 (10 digits)
  // ============================================================================

  @Benchmark
  def seq10_run(bh: Blackhole): Unit = {
    val result = run(seq10Parser, digits10)
    bh.consume(result)
  }

  @Benchmark
  def seq10_zerocast(bh: Blackhole): Unit = {
    val result = runZeroCast(seq10Parser, digits10)
    bh.consume(result)
  }

  // ============================================================================
  // Benchmarks: seq50 (50 digits)
  // ============================================================================

  @Benchmark
  def seq50_run(bh: Blackhole): Unit = {
    val result = run(seq50Parser, digits50)
    bh.consume(result)
  }

  @Benchmark
  def seq50_zerocast(bh: Blackhole): Unit = {
    val result = runZeroCast(seq50Parser, digits50)
    bh.consume(result)
  }

  // ============================================================================
  // Benchmarks: seq100 (100 digits)
  // ============================================================================

  @Benchmark
  def seq100_run(bh: Blackhole): Unit = {
    val result = run(seq100Parser, digits100)
    bh.consume(result)
  }

  @Benchmark
  def seq100_zerocast(bh: Blackhole): Unit = {
    val result = runZeroCast(seq100Parser, digits100)
    bh.consume(result)
  }
}
