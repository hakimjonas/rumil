package parser.benchmarks

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

import parser.core.*
import parser.runtime.run
import parser.syntax.{orElse, recover}

/** JMH benchmarks for the error-tracking cost of alternation: `orElse` (`Parser.Or`, no error
  * tracking) vs `recover` (`Parser.RecoverWith`, `LazyPartial` error tracking).
  *
  * Validates the Option-5 claim (docs/OPTION_5_ORELSE_SEMANTICS_DESIGN.md): the orElse/recover
  * split removes the error-tracking tax from alternation workloads, while users who need
  * diagnostics opt into it explicitly with `recover`. The `many90Miss` shape reproduces the design
  * doc's headline workload ("many with ~90% fallback hits / 900 recoveries").
  *
  * Shapes:
  *   - singleFallback: `many1(char('a').orElse(char('b')))`, ~50% fallback hits
  *   - chain10: ten-way nested alternation on the worst-case input (last alternative wins)
  *   - choice10: the same ten alternatives via flattened `choice` (core.or flattening)
  *   - kwMiss: ten keyword alternatives, input matches none (both-fail error merge)
  *   - many90Miss: `many1` over 1000 items, ~90% served by the fallback branch
  *   - cats90Miss: cats-parse `|` on the many90Miss input (alternation with no error tracking)
  *
  * Run with: sbt "benchmarks/Jmh/run -i 3 -wi 3 -f 1 -t 1 AlternationBenchmarks"
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class AlternationBenchmarks {

  // ============================================================================
  // Test Inputs
  // ============================================================================

  var abInput: String = uninitialized
  var digitInput: String = uninitialized
  var missInput: String = uninitialized
  var missy90Input: String = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    abInput = "ab" * 1000
    digitInput = "9" * 2000
    missInput = "case"
    missy90Input = (0 until 1000).map(i => if i % 10 == 0 then "valid" else "fallback").mkString

    // Correctness guards: if a shape does not behave as designed, its numbers are meaningless
    run(orSingle, abInput) match {
      case Result.Success(vs, _) => assert(vs.size == 2000, "orSingle: 2000 items expected")
      case other => throw new IllegalStateException(s"orSingle must succeed, got $other")
    }
    run(recSingle, abInput) match {
      case Result.Partial(vs, errors, _) =>
        assert(vs.size == 2000 && errors.nonEmpty, "recSingle: Partial with tracked errors")
      case other => throw new IllegalStateException(s"recSingle must be Partial, got $other")
    }
    run(many1(orChain), digitInput) match {
      case Result.Success(vs, _) => assert(vs.size == 2000, "orChain: 2000 digits")
      case other => throw new IllegalStateException(s"orChain must succeed, got $other")
    }
    run(many1(recChain), digitInput) match {
      case Result.Partial(vs, errors, _) =>
        assert(vs.size == 2000 && errors.nonEmpty, "recChain: Partial with tracked errors")
      case other => throw new IllegalStateException(s"recChain must be Partial, got $other")
    }
    run(many1(choice10), digitInput) match {
      case Result.Success(vs, _) => assert(vs.size == 2000, "choice10: 2000 digits")
      case other => throw new IllegalStateException(s"choice10 must succeed, got $other")
    }
    run(choiceKw, missInput) match {
      case Result.Failure(_, _) => ()
      case other => throw new IllegalStateException(s"choiceKw must fail on miss, got $other")
    }
    run(many1(orItem), missy90Input) match {
      case Result.Success(vs, _) => assert(vs.size == 1000, "orItem: 1000 items")
      case other => throw new IllegalStateException(s"orItem must succeed, got $other")
    }
    run(many1(recItem), missy90Input) match {
      case Result.Partial(vs, errors, _) =>
        assert(vs.size == 1000 && errors.nonEmpty, "recItem: Partial with tracked errors (~900)")
      case other => throw new IllegalStateException(s"recItem must be Partial, got $other")
    }
    catsItem.parseAll(missy90Input) match {
      case Right(_) => ()
      case other => throw new IllegalStateException(s"catsItem must parse all, got $other")
    }
  }

  // ============================================================================
  // Parsers (built once per trial; construction is not measured)
  // ============================================================================

  // Shape 1: single fallback, 50% fallback hits
  val orSingle: Parser[ParseError, List[Char]] =
    many1(char('a').orElse(char('b')))
  val recSingle: Parser[ParseError, List[Char]] =
    many1(char('a').recover(char('b')))

  // Shape 2: ten-way nested chain, exactly as a user writes it
  private val digitParsers: List[Parser[ParseError, Char]] =
    ('0' to '9').toList.map(char(_))
  private val orChain: Parser[ParseError, Char] =
    digitParsers.tail.foldLeft(digitParsers.head)((acc, p) => acc.orElse(p))
  private val recChain: Parser[ParseError, Char] =
    digitParsers.tail.foldLeft(digitParsers.head)((acc, p) => acc.recover(p))

  // Shape 2b: the same alternatives via flattened choice
  private val choice10: Parser[ParseError, Char] =
    choice(digitParsers)

  // Shape 3: keyword miss — input matches none of the ten alternatives
  private val choiceKw: Parser[ParseError, String] =
    choice(
      List("if", "else", "while", "for", "return", "break", "continue", "true", "false", "null")
        .map(string(_))
    )

  // Shape 4: many with ~90% fallback hits (the design doc's "900 recoveries")
  private val orItem: Parser[ParseError, String] =
    string("valid").orElse(string("fallback"))
  private val recItem: Parser[ParseError, String] =
    string("valid").recover(string("fallback"))

  // cats-parse comparator: alternation without any error tracking
  private val catsItem = {
    import cats.parse.Parser as P
    (P.string("valid").orElse(P.string("fallback"))).rep
  }

  // ============================================================================
  // Shape 1: single fallback
  // ============================================================================

  @Benchmark
  def orElse_singleFallback(bh: Blackhole): Unit =
    bh.consume(run(orSingle, abInput))

  @Benchmark
  def recover_singleFallback(bh: Blackhole): Unit =
    bh.consume(run(recSingle, abInput))

  // ============================================================================
  // Shape 2: ten-way nested chain (worst-case walk to the last alternative)
  // ============================================================================

  @Benchmark
  def orElse_chain10(bh: Blackhole): Unit =
    bh.consume(run(many1(orChain), digitInput))

  @Benchmark
  def recover_chain10(bh: Blackhole): Unit =
    bh.consume(run(many1(recChain), digitInput))

  // ============================================================================
  // Shape 2b: flattened choice
  // ============================================================================

  @Benchmark
  def choice10(bh: Blackhole): Unit =
    bh.consume(run(many1(choice10), digitInput))

  // ============================================================================
  // Shape 3: keyword miss (both-fail error merge)
  // ============================================================================

  @Benchmark
  def choice10_miss(bh: Blackhole): Unit =
    bh.consume(run(choiceKw, missInput))

  // ============================================================================
  // Shape 4: many with ~90% fallback hits (the headline workload)
  // ============================================================================

  @Benchmark
  def orElse_many90Miss(bh: Blackhole): Unit =
    bh.consume(run(many1(orItem), missy90Input))

  @Benchmark
  def recover_many90Miss(bh: Blackhole): Unit =
    bh.consume(run(many1(recItem), missy90Input))

  @Benchmark
  def cats_many90Miss(bh: Blackhole): Unit =
    bh.consume(catsItem.parseAll(missy90Input))

  // Control: the many1 loop + StringMatch cost alone, no alternation — subtract to isolate
  // the Or/Choice dispatch cost in the many90Miss shapes.
  private val stringOnlyMany: Parser[ParseError, List[String]] =
    many1(string("fallback"))

  @Benchmark
  def stringMany_control(bh: Blackhole): Unit =
    bh.consume(run(stringOnlyMany, missy90Input))
}
