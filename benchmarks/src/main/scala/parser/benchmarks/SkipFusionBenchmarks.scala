package parser.benchmarks

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

import parser.core.*
import parser.runtime.run
import parser.syntax.{*>, <*, ~}

/** JMH benchmarks for skip fusion: `zipLeft`/`zipRight` (`<*` / `*>`) fused into the dedicated
  * `SkipRight`/`SkipLeft` ADT cases (no tuple allocation, no discarding closure per token) instead
  * of the pre-fusion `FlatMap` + `Map` lowering.
  *
  * Factor-isolated A/B: this exact file is benchmarked on HEAD (fused) and on the pre-fusion commit
  * (bf9994a^ = 074e526) via a git worktree; only the library differs, the benchmark source is
  * byte-identical. The plain `~` (zip) shape is a control — zip is not fused on either side, so it
  * pins the harness cost and validates the comparison.
  *
  * Run with: java -jar <rumil-bench.jar> "... SkipFusionBenchmarks"
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class SkipFusionBenchmarks {

  var sepListInput: String = uninitialized
  var prefixListInput: String = uninitialized
  var deepInput: String = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    sepListInput = "a," * 1000 + "a"
    prefixListInput = ",a" * 1000
    deepInput = "a" + ";" * 500

    // Correctness guards
    run(zipLeftList, sepListInput) match {
      case Result.Success(vs, c) =>
        assert(vs.size == 1000 && c == 2000, "zipLeftList: 1000 items, trailing item backtracked")
      case other => throw new IllegalStateException(s"zipLeftList must succeed, got $other")
    }
    run(zipRightList, prefixListInput) match {
      case Result.Success(vs, c) =>
        assert(vs.size == 1000 && c == 2000, "zipRightList: 1000 items, fully consumed")
      case other => throw new IllegalStateException(s"zipRightList must succeed, got $other")
    }
    run(zipPairList, sepListInput) match {
      case Result.Success(vs, c) =>
        assert(vs.size == 1000 && c == 2000, "zipPairList (control): 1000 pairs")
      case other => throw new IllegalStateException(s"zipPairList must succeed, got $other")
    }
    run(deepSkipChain, deepInput) match {
      case Result.Success(v, c) =>
        assert(v == 'a' && c == 501, "deepSkipChain: 500 fused skips, fully consumed")
      case other => throw new IllegalStateException(s"deepSkipChain must succeed, got $other")
    }
  }

  // Fused on HEAD: `p <* q` = zipLeft = keep left, discard right (emits SkipRight)
  val zipLeftList: Parser[ParseError, List[Char]] =
    many1(char('a') <* char(','))

  // Fused on HEAD: `p *> q` = zipRight = keep right, discard left (emits SkipLeft)
  val zipRightList: Parser[ParseError, List[Char]] =
    many1(char(',') *> char('a'))

  // Control: plain zip keeps both sides — not fused on either side
  val zipPairList: Parser[ParseError, List[(Char, Char)]] =
    many1(char('a') ~ char(','))

  // Deep fused chain: atom <* ';' <* ';' ... (500 levels)
  val deepSkipChain: Parser[ParseError, Char] =
    (1 to 500).foldLeft(char('a'))((acc, _) => acc <* char(';'))

  @Benchmark
  def zipLeft_sepList1000(bh: Blackhole): Unit =
    bh.consume(run(zipLeftList, sepListInput))

  @Benchmark
  def zipRight_prefixList1000(bh: Blackhole): Unit =
    bh.consume(run(zipRightList, prefixListInput))

  @Benchmark
  def zipPair_sepList1000(bh: Blackhole): Unit =
    bh.consume(run(zipPairList, sepListInput))

  @Benchmark
  def deepSkipChain_500(bh: Blackhole): Unit =
    bh.consume(run(deepSkipChain, deepInput))
}
