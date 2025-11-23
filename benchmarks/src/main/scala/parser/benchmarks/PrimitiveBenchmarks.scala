package parser.benchmarks

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import parser.core._
import parser.runtime.run

/**
 * Benchmarks for primitive/atomic parser operations.
 *
 * Tests individual combinators in isolation to identify
 * which operations have the largest performance gaps.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class PrimitiveBenchmarks {

  // ============================================================================
  // Test Data
  // ============================================================================

  var singleChar: String = "a"
  var shortString: String = "abc"
  var mediumString: String = "hello world"

  @Setup(Level.Trial)
  def setup(): Unit = {
    singleChar = "a"
    shortString = "abc"
    mediumString = "hello world"
  }

  // ============================================================================
  // 1. Single Character Match (satisfy/charWhere)
  // ============================================================================

  val rumilCharA = char('a')
  val catsCharA = cats.parse.Parser.char('a')

  @Benchmark
  def rumil_singleChar(bh: Blackhole): Unit = {
    bh.consume(run(rumilCharA, singleChar))
  }

  @Benchmark
  def cats_singleChar(bh: Blackhole): Unit = {
    bh.consume(catsCharA.parseAll(singleChar))
  }

  // ============================================================================
  // 2. Single Character with Predicate (satisfy/charWhere)
  // ============================================================================

  val rumilDigit = satisfy(_.isDigit, "digit")
  val catsDigit = cats.parse.Parser.charWhere(_.isDigit)

  @Benchmark
  def rumil_satisfyDigit(bh: Blackhole): Unit = {
    bh.consume(run(rumilDigit, "5"))
  }

  @Benchmark
  def cats_satisfyDigit(bh: Blackhole): Unit = {
    bh.consume(catsDigit.parseAll("5"))
  }

  // ============================================================================
  // 3. Short String Match (3 chars)
  // ============================================================================

  val rumilAbc = string("abc")
  val catsAbc = cats.parse.Parser.string("abc")

  @Benchmark
  def rumil_stringShort(bh: Blackhole): Unit = {
    bh.consume(run(rumilAbc, shortString))
  }

  @Benchmark
  def cats_stringShort(bh: Blackhole): Unit = {
    bh.consume(catsAbc.parseAll(shortString))
  }

  // ============================================================================
  // 4. Medium String Match (11 chars)
  // ============================================================================

  val rumilHello = string("hello world")
  val catsHello = cats.parse.Parser.string("hello world")

  @Benchmark
  def rumil_stringMedium(bh: Blackhole): Unit = {
    bh.consume(run(rumilHello, mediumString))
  }

  @Benchmark
  def cats_stringMedium(bh: Blackhole): Unit = {
    bh.consume(catsHello.parseAll(mediumString))
  }

  // ============================================================================
  // 5. Binary Or (first alternative succeeds)
  // ============================================================================

  val rumilOrFirst = or(char('a'), char('b'))
  val catsOrFirst = cats.parse.Parser.char('a') | cats.parse.Parser.char('b')

  @Benchmark
  def rumil_orFirst(bh: Blackhole): Unit = {
    bh.consume(run(rumilOrFirst, "a"))
  }

  @Benchmark
  def cats_orFirst(bh: Blackhole): Unit = {
    bh.consume(catsOrFirst.parseAll("a"))
  }

  // ============================================================================
  // 6. Binary Or (second alternative succeeds)
  // ============================================================================

  @Benchmark
  def rumil_orSecond(bh: Blackhole): Unit = {
    bh.consume(run(rumilOrFirst, "b"))
  }

  @Benchmark
  def cats_orSecond(bh: Blackhole): Unit = {
    bh.consume(catsOrFirst.parseAll("b"))
  }

  // ============================================================================
  // 7. Choice of 3 strings (first succeeds)
  // ============================================================================

  val rumilChoice3 = choice(List(string("aaa"), string("bbb"), string("ccc")))
  val catsChoice3 = cats.parse.Parser.oneOf(List(
    cats.parse.Parser.string("aaa"),
    cats.parse.Parser.string("bbb"),
    cats.parse.Parser.string("ccc")
  ))

  @Benchmark
  def rumil_choice3First(bh: Blackhole): Unit = {
    bh.consume(run(rumilChoice3, "aaa"))
  }

  @Benchmark
  def cats_choice3First(bh: Blackhole): Unit = {
    bh.consume(catsChoice3.parseAll("aaa"))
  }

  // ============================================================================
  // 8. Choice of 3 strings (last succeeds)
  // ============================================================================

  @Benchmark
  def rumil_choice3Last(bh: Blackhole): Unit = {
    bh.consume(run(rumilChoice3, "ccc"))
  }

  @Benchmark
  def cats_choice3Last(bh: Blackhole): Unit = {
    bh.consume(catsChoice3.parseAll("ccc"))
  }

  // ============================================================================
  // 9. Sequence of 2 chars (flatMap/~)
  // ============================================================================

  import parser.syntax.~
  val rumilSeq2 = char('a') ~ char('b')
  val catsSeq2 = cats.parse.Parser.char('a') ~ cats.parse.Parser.char('b')

  @Benchmark
  def rumil_seq2(bh: Blackhole): Unit = {
    bh.consume(run(rumilSeq2, "ab"))
  }

  @Benchmark
  def cats_seq2(bh: Blackhole): Unit = {
    bh.consume(catsSeq2.parseAll("ab"))
  }

  // ============================================================================
  // 10. Map (single char + transform)
  // ============================================================================

  import parser.syntax.map
  val rumilMapChar = satisfy(_.isDigit, "digit").map(_.asDigit)
  val catsMapChar = cats.parse.Parser.charWhere(_.isDigit).map(_.asDigit)

  @Benchmark
  def rumil_mapChar(bh: Blackhole): Unit = {
    bh.consume(run(rumilMapChar, "5"))
  }

  @Benchmark
  def cats_mapChar(bh: Blackhole): Unit = {
    bh.consume(catsMapChar.parseAll("5"))
  }

  // ============================================================================
  // 11. Many (10 repetitions)
  // ============================================================================

  val rumilMany10 = many(char('a'))
  val catsMany10 = cats.parse.Parser.char('a').rep0
  val tenAs = "aaaaaaaaaa"

  @Benchmark
  def rumil_many10(bh: Blackhole): Unit = {
    bh.consume(run(rumilMany10, tenAs))
  }

  @Benchmark
  def cats_many10(bh: Blackhole): Unit = {
    bh.consume(catsMany10.parseAll(tenAs))
  }

  // ============================================================================
  // 12. Optional (present)
  // ============================================================================

  val rumilOptional = optional(char('a'))
  val catsOptional = cats.parse.Parser.char('a').?

  @Benchmark
  def rumil_optionalPresent(bh: Blackhole): Unit = {
    bh.consume(run(rumilOptional, "a"))
  }

  @Benchmark
  def cats_optionalPresent(bh: Blackhole): Unit = {
    bh.consume(catsOptional.parseAll("a"))
  }

  // ============================================================================
  // 13. Optional (absent)
  // ============================================================================

  @Benchmark
  def rumil_optionalAbsent(bh: Blackhole): Unit = {
    bh.consume(run(rumilOptional, ""))
  }

  @Benchmark
  def cats_optionalAbsent(bh: Blackhole): Unit = {
    bh.consume(catsOptional.parseAll(""))
  }

  // ============================================================================
  // 14. Succeed (pure value, no parsing) - measures pure overhead
  // ============================================================================

  val rumilSucceed = succeed(42)
  val catsSucceed = cats.parse.Parser.pure(42)

  @Benchmark
  def rumil_succeed(bh: Blackhole): Unit = {
    bh.consume(run(rumilSucceed, ""))
  }

  @Benchmark
  def cats_succeed(bh: Blackhole): Unit = {
    bh.consume(catsSucceed.parseAll(""))
  }

  // ============================================================================
  // 15. Pre-created state - isolate state creation overhead
  // ============================================================================

  import parser.runtime.{parserState, interpret}

  @Benchmark
  def rumil_singleCharPreState(bh: Blackhole): Unit = {
    val state = parserState(singleChar)
    bh.consume(interpret(rumilCharA, state))
  }
}
