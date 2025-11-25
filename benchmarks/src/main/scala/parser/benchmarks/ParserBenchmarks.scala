package parser.benchmarks

import java.util.concurrent.TimeUnit

import scala.compiletime.uninitialized

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import parser.core._
import parser.runtime.{run, runStackSafe, runStackSafeOpt}
import parser.syntax.{map, ~}

/**
 * JMH benchmarks for Rumil parser combinators.
 *
 * Run with: sbt "benchmarks/Jmh/run -i 5 -wi 3 -f 1 -t 1"
 *
 * Options:
 *   -i N   : Number of measurement iterations
 *   -wi N  : Number of warmup iterations
 *   -f N   : Number of forks
 *   -t N   : Number of threads
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class ParserBenchmarks {

  // ============================================================================
  // Test Data
  // ============================================================================

  var digits1000: String      = uninitialized
  var commaNumbers100: String = uninitialized
  var nestedParens50: String  = uninitialized
  var jsonSmall: String       = uninitialized
  var jsonMedium: String      = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    digits1000 = "1" * 1000
    commaNumbers100 = (1 to 100).mkString(",")
    nestedParens50 = "(" * 50 + "x" + ")" * 50
    jsonSmall = """{"name":"Alice","age":30}"""
    jsonMedium =
      """{"users":[{"name":"Alice","age":30},{"name":"Bob","age":25},{"name":"Charlie","age":35}],"count":3}"""
  }

  // ============================================================================
  // Rumil Parsers
  // ============================================================================

  val rumilDigit: Parser[ParseError, Char]        = satisfy(_.isDigit, "digit")
  val rumilDigits: Parser[ParseError, List[Char]] = many(rumilDigit)

  val rumilInt: Parser[ParseError, Int] =
    many1(satisfy(_.isDigit, "digit")).map(_.mkString.toInt)

  val rumilCommaSep: Parser[ParseError, List[Int]] =
    sepBy(rumilInt, char(','))

  val rumilStringMatch: Parser[ParseError, String] = string("hello world")

  val rumilChoice10: Parser[ParseError, String] = choice(
    List(
      string("aaa"),
      string("bbb"),
      string("ccc"),
      string("ddd"),
      string("eee"),
      string("fff"),
      string("ggg"),
      string("hhh"),
      string("iii"),
      string("jjj")
    )
  )

  // ============================================================================
  // cats-parse Parsers
  // ============================================================================

  val catsDigit  = cats.parse.Parser.charWhere(_.isDigit)
  val catsDigits = catsDigit.rep0

  val catsInt      = cats.parse.Numbers.digits.map(_.toInt)
  val catsCommaSep = catsInt.repSep0(cats.parse.Parser.char(','))

  val catsStringMatch = cats.parse.Parser.string("hello world")

  val catsChoice10 = cats.parse.Parser.oneOf(
    List(
      cats.parse.Parser.string("aaa"),
      cats.parse.Parser.string("bbb"),
      cats.parse.Parser.string("ccc"),
      cats.parse.Parser.string("ddd"),
      cats.parse.Parser.string("eee"),
      cats.parse.Parser.string("fff"),
      cats.parse.Parser.string("ggg"),
      cats.parse.Parser.string("hhh"),
      cats.parse.Parser.string("iii"),
      cats.parse.Parser.string("jjj")
    )
  )

  // ============================================================================
  // Benchmarks: Digit Parsing
  // ============================================================================

  @Benchmark
  def rumil_parseDigits1000(bh: Blackhole): Unit = {
    val result = run(rumilDigits, digits1000)
    bh.consume(result)
  }

  @Benchmark
  def rumil_opt_parseDigits1000(bh: Blackhole): Unit = {
    val result = runStackSafeOpt(rumilDigits, digits1000)
    bh.consume(result)
  }

  @Benchmark
  def cats_parseDigits1000(bh: Blackhole): Unit = {
    val result = catsDigits.parseAll(digits1000)
    bh.consume(result)
  }

  // ============================================================================
  // Benchmarks: Comma-Separated Numbers
  // ============================================================================

  @Benchmark
  def rumil_parseCommaSep100(bh: Blackhole): Unit = {
    val result = run(rumilCommaSep, commaNumbers100)
    bh.consume(result)
  }

  @Benchmark
  def rumil_opt_parseCommaSep100(bh: Blackhole): Unit = {
    val result = runStackSafeOpt(rumilCommaSep, commaNumbers100)
    bh.consume(result)
  }

  @Benchmark
  def cats_parseCommaSep100(bh: Blackhole): Unit = {
    val result = catsCommaSep.parseAll(commaNumbers100)
    bh.consume(result)
  }

  // ============================================================================
  // Benchmarks: String Matching
  // ============================================================================

  @Benchmark
  def rumil_stringMatch(bh: Blackhole): Unit = {
    val result = run(rumilStringMatch, "hello world")
    bh.consume(result)
  }

  @Benchmark
  def rumil_opt_stringMatch(bh: Blackhole): Unit = {
    val result = runStackSafeOpt(rumilStringMatch, "hello world")
    bh.consume(result)
  }

  @Benchmark
  def cats_stringMatch(bh: Blackhole): Unit = {
    val result = catsStringMatch.parseAll("hello world")
    bh.consume(result)
  }

  // ============================================================================
  // Benchmarks: Choice (Alternative)
  // ============================================================================

  // Test worst case (last alternative - linear scan for Rumil)
  @Benchmark
  def rumil_choice10(bh: Blackhole): Unit = {
    val result = run(rumilChoice10, "jjj")
    bh.consume(result)
  }

  @Benchmark
  def rumil_opt_choice10(bh: Blackhole): Unit = {
    val result = runStackSafeOpt(rumilChoice10, "jjj")
    bh.consume(result)
  }

  @Benchmark
  def cats_choice10(bh: Blackhole): Unit = {
    val result = catsChoice10.parseAll("jjj")
    bh.consume(result)
  }

  // Test best case (first alternative)
  @Benchmark
  def rumil_choice10_first(bh: Blackhole): Unit = {
    val result = run(rumilChoice10, "aaa")
    bh.consume(result)
  }

  @Benchmark
  def rumil_opt_choice10_first(bh: Blackhole): Unit = {
    val result = runStackSafeOpt(rumilChoice10, "aaa")
    bh.consume(result)
  }

  @Benchmark
  def cats_choice10_first(bh: Blackhole): Unit = {
    val result = catsChoice10.parseAll("aaa")
    bh.consume(result)
  }

  // ============================================================================
  // Benchmarks: Sequential Chains (FlatMap depth)
  // ============================================================================

  // Build sequential parser chains of various depths
  // These measure FlatMap interpretation overhead

  val rumilSeq10: Parser[ParseError, Any] = {
    var p: Parser[ParseError, Any] = satisfy(_.isDigit, "digit")
    for (_ <- 1 until 10) p = p ~ satisfy(_.isDigit, "digit")
    p
  }

  val rumilSeq50: Parser[ParseError, Any] = {
    var p: Parser[ParseError, Any] = satisfy(_.isDigit, "digit")
    for (_ <- 1 until 50) p = p ~ satisfy(_.isDigit, "digit")
    p
  }

  val rumilSeq100: Parser[ParseError, Any] = {
    var p: Parser[ParseError, Any] = satisfy(_.isDigit, "digit")
    for (_ <- 1 until 100) p = p ~ satisfy(_.isDigit, "digit")
    p
  }

  val catsSeq10: cats.parse.Parser[Any] = {
    var p: cats.parse.Parser[Any] = cats.parse.Parser.charWhere(_.isDigit)
    for (_ <- 1 until 10) p = (p ~ cats.parse.Parser.charWhere(_.isDigit)).map { case (a, b) => (a, b) }
    p
  }

  val catsSeq50: cats.parse.Parser[Any] = {
    var p: cats.parse.Parser[Any] = cats.parse.Parser.charWhere(_.isDigit)
    for (_ <- 1 until 50) p = (p ~ cats.parse.Parser.charWhere(_.isDigit)).map { case (a, b) => (a, b) }
    p
  }

  val catsSeq100: cats.parse.Parser[Any] = {
    var p: cats.parse.Parser[Any] = cats.parse.Parser.charWhere(_.isDigit)
    for (_ <- 1 until 100) p = (p ~ cats.parse.Parser.charWhere(_.isDigit)).map { case (a, b) => (a, b) }
    p
  }

  var digits10: String  = uninitialized
  var digits50: String  = uninitialized
  var digits100: String = uninitialized

  @Setup(Level.Trial)
  def setupSeq(): Unit = {
    digits10 = "1" * 10
    digits50 = "1" * 50
    digits100 = "1" * 100
  }

  @Benchmark
  def rumil_seq10(bh: Blackhole): Unit = {
    val result = run(rumilSeq10, digits10)
    bh.consume(result)
  }

  @Benchmark
  def cats_seq10(bh: Blackhole): Unit = {
    val result = catsSeq10.parseAll(digits10)
    bh.consume(result)
  }

  @Benchmark
  def rumil_seq50(bh: Blackhole): Unit = {
    val result = run(rumilSeq50, digits50)
    bh.consume(result)
  }

  @Benchmark
  def cats_seq50(bh: Blackhole): Unit = {
    val result = catsSeq50.parseAll(digits50)
    bh.consume(result)
  }

  @Benchmark
  def rumil_seq100(bh: Blackhole): Unit = {
    val result = run(rumilSeq100, digits100)
    bh.consume(result)
  }

  @Benchmark
  def cats_seq100(bh: Blackhole): Unit = {
    val result = catsSeq100.parseAll(digits100)
    bh.consume(result)
  }

  // ============================================================================
  // Benchmarks: Stack-Safe (Trampolined) Sequential Chains
  // ============================================================================

  @Benchmark
  def rumil_stacksafe_seq10(bh: Blackhole): Unit = {
    val result = runStackSafe(rumilSeq10, digits10)
    bh.consume(result)
  }

  @Benchmark
  def rumil_stacksafe_seq50(bh: Blackhole): Unit = {
    val result = runStackSafe(rumilSeq50, digits50)
    bh.consume(result)
  }

  @Benchmark
  def rumil_stacksafe_seq100(bh: Blackhole): Unit = {
    val result = runStackSafe(rumilSeq100, digits100)
    bh.consume(result)
  }

  // ============================================================================
  // Benchmarks: Optimized Stack-Safe Sequential Chains
  // ============================================================================

  @Benchmark
  def rumil_stacksafe_opt_seq10(bh: Blackhole): Unit = {
    val result = runStackSafeOpt(rumilSeq10, digits10)
    bh.consume(result)
  }

  @Benchmark
  def rumil_stacksafe_opt_seq50(bh: Blackhole): Unit = {
    val result = runStackSafeOpt(rumilSeq50, digits50)
    bh.consume(result)
  }

  @Benchmark
  def rumil_stacksafe_opt_seq100(bh: Blackhole): Unit = {
    val result = runStackSafeOpt(rumilSeq100, digits100)
    bh.consume(result)
  }

}
