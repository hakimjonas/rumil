package parser.benchmarks

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized
import scala.util.Random

import parser.core.*

/** JMH comparison of cached vs rebuilt [[LineIndex]] lookups.
  *
  *   - [[buildAndLookup1000]]: build the index once per invocation, then 1000 random lookups. Cost
  *     is O(n) construction + O(1000 log n) lookups. Should scale roughly linearly with source
  *     size.
  *   - [[rebuildPerLookup]]: for each of 1000 lookups, build a fresh index. Cost is O(1000 × n) —
  *     simulates the naïve "no caching" strategy.
  *
  * The whole point of [[LineIndex]] is that lookups amortize against one construction. Expect the
  * ratio between the two to scale linearly with source size: at 100 KB the cached path should
  * dominate the rebuild path by roughly two orders of magnitude.
  *
  * Seed and offset pre-generation live in `@Setup(Trial)` so randomness doesn't skew measurements.
  *
  * Run with: `sbt "benchmarks/Jmh/run -i 3 -wi 2 -f 1 -tu us -bm avgt LineIndexBenchmarks"`
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class LineIndexBenchmarks {

  @Param(Array("1024", "10240", "102400"))
  var sourceSize: Int = uninitialized

  var source: String = uninitialized
  var offsets: Array[Int] = uninitialized
  private val lookups = 1000

  @Setup(Level.Trial)
  def setup(): Unit = {
    // Synthesize a source with ~40-char lines (realistic code width). Deterministic content so the
    // bench is reproducible; uses only ASCII so each char is one code unit.
    val sb = new StringBuilder(sourceSize)
    var i = 0
    while sb.length < sourceSize do {
      val lineLen = 32 + (i % 16)
      var j = 0
      while j < lineLen && sb.length < sourceSize do {
        sb.append(('a' + ((i + j) % 26)).toChar)
        j += 1
      }
      if sb.length < sourceSize then sb.append('\n')
      i += 1
    }
    source = sb.result()

    val rng = new Random(0xc0ffeeL)
    offsets = Array.fill(lookups)(rng.nextInt(source.length + 1))
  }

  /** Cached index + 1000 lookups — the intended usage pattern. */
  @Benchmark
  def buildAndLookup1000(bh: Blackhole): Unit = {
    val idx = LineIndex(source)
    var k = 0
    while k < lookups do {
      bh.consume(idx.locationAt(offsets(k)))
      k += 1
    }
  }

  /** Rebuild index per lookup — the "naïve caller" baseline. */
  @Benchmark
  def rebuildPerLookup(bh: Blackhole): Unit = {
    var k = 0
    while k < lookups do {
      val idx = LineIndex(source)
      bh.consume(idx.locationAt(offsets(k)))
      k += 1
    }
  }
}
