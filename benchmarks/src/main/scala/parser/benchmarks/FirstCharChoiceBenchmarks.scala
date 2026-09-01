package parser.benchmarks

import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit
import scala.compiletime.uninitialized

import parser.core.*
import parser.runtime.run
import parser.syntax.orElse

/** JMH benchmarks for `firstCharChoice` — O(1) first-character dispatch over keyword alternatives —
  * against the equivalent hand-written shapes a user would otherwise produce: a nested `orElse`
  * chain and a flattened `choice`.
  *
  * All three parsers expose the same 12 keyword alternatives (distinct first characters, no key is
  * a prefix of another) over the same cyclic keyword stream, so the delta is purely the dispatch
  * strategy.
  *
  * Run with: java -jar <rumil-bench.jar> "... FirstCharChoiceBenchmarks"
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class FirstCharChoiceBenchmarks {

  var keywordStream: String = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    keywordStream = (0 until 2400).map(i => keywords(i % keywords.size)).mkString

    run(fccDispatch, keywordStream) match {
      case Result.Success(vs, _) => assert(vs.size == 2400, "fccDispatch: 2400 keywords")
      case other => throw new IllegalStateException(s"fccDispatch must succeed, got $other")
    }
    run(orElseChain, keywordStream) match {
      case Result.Success(vs, _) => assert(vs.size == 2400, "orElseChain: 2400 keywords")
      case other => throw new IllegalStateException(s"orElseChain must succeed, got $other")
    }
    run(choiceChain, keywordStream) match {
      case Result.Success(vs, _) => assert(vs.size == 2400, "choiceChain: 2400 keywords")
      case other => throw new IllegalStateException(s"choiceChain must succeed, got $other")
    }
  }

  // 12 keywords, all with distinct first characters, none a prefix of another
  private val keywords: List[String] = List(
    "if",
    "else",
    "while",
    "return",
    "break",
    "case",
    "true",
    "null",
    "do",
    "var",
    "guard",
    "yield"
  )

  private val keywordParsers: List[Parser[ParseError, String]] =
    keywords.map(string(_))

  // O(1) first-character dispatch: one leading-char key per keyword (the JSON-style usage)
  val fccDispatch: Parser[ParseError, List[String]] =
    many1(firstCharChoice(keywords.map(k => (k.substring(0, 1), string(k)))))

  // The nested chain a user writes without firstCharChoice
  val orElseChain: Parser[ParseError, List[String]] =
    many1(keywordParsers.tail.foldLeft(keywordParsers.head)((acc, p) => acc.orElse(p)))

  // The flattened choice form
  val choiceChain: Parser[ParseError, List[String]] =
    many1(choice(keywordParsers))

  @Benchmark
  def fcc_dispatch2400(bh: Blackhole): Unit =
    bh.consume(run(fccDispatch, keywordStream))

  @Benchmark
  def orElse_chain2400(bh: Blackhole): Unit =
    bh.consume(run(orElseChain, keywordStream))

  @Benchmark
  def choice_chain2400(bh: Blackhole): Unit =
    bh.consume(run(choiceChain, keywordStream))
}
