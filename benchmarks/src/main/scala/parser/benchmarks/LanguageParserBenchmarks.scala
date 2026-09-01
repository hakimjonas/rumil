package parser.benchmarks

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

import parser.runtime.run

/** Full-file throughput and allocation baselines for [[IncrementalGrammar]]'s sourceFile parser —
  * the workload that actually exercises the path a real language parser takes (Pratt expressions,
  * statement wrapping, whitespace preservation, lossless green tree construction).
  *
  * Recorded as a snapshot before any future optimization session. Two views:
  *
  *   - [[parseSourceFileSmall]] / [[parseSourceFileMedium]] / [[parseSourceFileLarge]]: wall-time
  *     throughput at 2.4 KB / 24 KB / 240 KB (200 / 2000 / 20000 statements × 5 ops each).
  *   - The same three runs under `-prof gc` report allocation rate in bytes/op.
  *
  * Run with:
  * {{{
  * sbt 'benchmarks/Jmh/run -i 5 -wi 3 -f 1 -tu ms -bm thrpt -prof gc LanguageParserBenchmarks'
  * }}}
  *
  * The `-prof gc` profiler adds an "alloc.rate.norm" column reporting bytes allocated per
  * operation, which is the number to watch across future optimization commits.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class LanguageParserBenchmarks {

  var sourceSmall: String = uninitialized // 2.4 KB — 200 statements
  var sourceMedium: String = uninitialized // 24 KB  — 2000 statements
  var sourceLarge: String = uninitialized // 240 KB — 20000 statements

  @Setup(Level.Trial)
  def setup(): Unit = {
    sourceSmall = IncrementalGrammar.synthesize(200, 5)._1
    sourceMedium = IncrementalGrammar.synthesize(2000, 5)._1
    sourceLarge = IncrementalGrammar.synthesize(20000, 5)._1
  }

  @Benchmark
  def parseSourceFileSmall(bh: Blackhole): Unit =
    bh.consume(run(IncrementalGrammar.sourceFile, sourceSmall))

  @Benchmark
  def parseSourceFileMedium(bh: Blackhole): Unit =
    bh.consume(run(IncrementalGrammar.sourceFile, sourceMedium))

  @Benchmark
  def parseSourceFileLarge(bh: Blackhole): Unit =
    bh.consume(run(IncrementalGrammar.sourceFile, sourceLarge))
}
