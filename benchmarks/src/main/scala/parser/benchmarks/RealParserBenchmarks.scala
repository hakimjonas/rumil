package parser.benchmarks

import java.util.concurrent.TimeUnit

import scala.compiletime.uninitialized

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import parser.core._
import parser.runtime.{run, runStackSafeOpt}
import parser.syntax._
import parsers.common._
import parsers.json.{jsonParser, JsonValue}

/**
 * JMH benchmarks for real-world parser patterns comparing run() vs runStackSafeOpt().
 *
 * Instead of using the full JSON/CSV parsers which have complex recursive structure,
 * we test realistic parser patterns that represent common use cases.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class RealParserBenchmarks {

  // ============================================================================
  // Test Data
  // ============================================================================

  var keyValuePairs: String = uninitialized
  var nestedBrackets: String = uninitialized
  var csvData: String = uninitialized
  var numberList: String = uninitialized
  var mixedTokens: String = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    // Key-value pairs like "key1=value1,key2=value2,..."
    keyValuePairs = (1 to 50).map(i => s"key$i=value$i").mkString(",")

    // Nested brackets: [[[[...x...]]]]
    nestedBrackets = "[" * 30 + "x" + "]" * 30

    // CSV-like data
    csvData = {
      val header = "id,name,age,city,score"
      val rows = (1 to 100).map(i => s"$i,name$i,${20 + i % 50},city${i % 10},${i * 10}")
      (header +: rows).mkString("\n")
    }

    // List of numbers: 1,2,3,...,200
    numberList = (1 to 200).mkString(",")

    // Mixed tokens with various combinators
    mixedTokens = "foo bar 123 baz 456 qux 789 " * 20
  }

  // ============================================================================
  // Parsers
  // ============================================================================

  // Key-value parser: key=value pairs separated by commas
  val keyValueParser: Parser[ParseError, List[(String, String)]] = {
    val identifier = letter.many1.map(_.mkString)
    val pair = (identifier <* char('=')) ~ identifier
    pair.sepBy1(char(','))
  }

  // Nested brackets parser using recursion
  lazy val nestedBracketsParser: Parser[ParseError, Any] = {
    val inner: Parser[ParseError, Any] = defer(nestedBracketsParser) | letter
    char('[') *> inner <* char(']')
  }

  // CSV row parser
  val csvRowParser: Parser[ParseError, List[List[String]]] = {
    val field = satisfy(c => c != ',' && c != '\n', "field char").many.map(_.mkString)
    val row = field.sepBy1(char(','))
    val newline = char('\n')
    row.sepBy1(newline)
  }

  // Number list parser
  val numberListParser: Parser[ParseError, List[Int]] = {
    val num = digit.many1.map(_.mkString.toInt)
    num.sepBy1(char(','))
  }

  // Mixed token parser (words and numbers)
  val mixedTokenParser: Parser[ParseError, List[Either[String, Int]]] = {
    val ws = char(' ').many
    val word = letter.many1.map(cs => Left(cs.mkString))
    val num = digit.many1.map(ds => Right(ds.mkString.toInt))
    val token = word | num
    ws *> token.sepBy1(char(' ').many1) <* ws
  }

  // ============================================================================
  // Benchmarks: Key-Value Pairs
  // ============================================================================

  @Benchmark
  def keyvalue_run(bh: Blackhole): Unit = {
    val result = run(keyValueParser, keyValuePairs)
    bh.consume(result)
  }

  @Benchmark
  def keyvalue_opt(bh: Blackhole): Unit = {
    val result = runStackSafeOpt(keyValueParser, keyValuePairs)
    bh.consume(result)
  }

  // ============================================================================
  // Benchmarks: Nested Brackets
  // ============================================================================

  @Benchmark
  def nested_run(bh: Blackhole): Unit = {
    val result = run(nestedBracketsParser, nestedBrackets)
    bh.consume(result)
  }

  @Benchmark
  def nested_opt(bh: Blackhole): Unit = {
    val result = runStackSafeOpt(nestedBracketsParser, nestedBrackets)
    bh.consume(result)
  }

  // ============================================================================
  // Benchmarks: CSV Data
  // ============================================================================

  @Benchmark
  def csv_run(bh: Blackhole): Unit = {
    val result = run(csvRowParser, csvData)
    bh.consume(result)
  }

  @Benchmark
  def csv_opt(bh: Blackhole): Unit = {
    val result = runStackSafeOpt(csvRowParser, csvData)
    bh.consume(result)
  }

  // ============================================================================
  // Benchmarks: Number List
  // ============================================================================

  @Benchmark
  def numbers_run(bh: Blackhole): Unit = {
    val result = run(numberListParser, numberList)
    bh.consume(result)
  }

  @Benchmark
  def numbers_opt(bh: Blackhole): Unit = {
    val result = runStackSafeOpt(numberListParser, numberList)
    bh.consume(result)
  }

  // ============================================================================
  // Benchmarks: Mixed Tokens
  // ============================================================================

  @Benchmark
  def mixed_run(bh: Blackhole): Unit = {
    val result = run(mixedTokenParser, mixedTokens)
    bh.consume(result)
  }

  @Benchmark
  def mixed_opt(bh: Blackhole): Unit = {
    val result = runStackSafeOpt(mixedTokenParser, mixedTokens)
    bh.consume(result)
  }

  // ============================================================================
  // JSON Parser Benchmarks
  // ============================================================================

  var jsonSmall: String = uninitialized
  var jsonMedium: String = uninitialized
  var jsonLarge: String = uninitialized
  var jsonDeep: String = uninitialized

  @Setup(Level.Trial)
  def setupJson(): Unit = {
    jsonSmall = """{"name":"Alice","age":30,"active":true}"""

    jsonMedium = """{"users":[{"name":"Alice","age":30},{"name":"Bob","age":25},{"name":"Charlie","age":35}],"count":3}"""

    jsonLarge = {
      val users = (1 to 50).map { i =>
        s"""{"id":$i,"name":"User$i","age":${20 + i},"active":${i % 2 == 0},"score":${i * 10.5}}"""
      }.mkString(",")
      s"""{"users":[$users],"total":50}"""
    }

    // Deeply nested JSON: {"a":{"a":{"a":...}}}
    jsonDeep = {
      val depth = 20
      "{\"a\":" * depth + "1" + "}" * depth
    }
  }

  @Benchmark
  def json_small_run(bh: Blackhole): Unit = {
    val result = run(jsonParser, jsonSmall)
    bh.consume(result)
  }

  @Benchmark
  def json_small_opt(bh: Blackhole): Unit = {
    val result = runStackSafeOpt(jsonParser, jsonSmall)
    bh.consume(result)
  }

  @Benchmark
  def json_medium_run(bh: Blackhole): Unit = {
    val result = run(jsonParser, jsonMedium)
    bh.consume(result)
  }

  @Benchmark
  def json_medium_opt(bh: Blackhole): Unit = {
    val result = runStackSafeOpt(jsonParser, jsonMedium)
    bh.consume(result)
  }

  @Benchmark
  def json_large_run(bh: Blackhole): Unit = {
    val result = run(jsonParser, jsonLarge)
    bh.consume(result)
  }

  @Benchmark
  def json_large_opt(bh: Blackhole): Unit = {
    val result = runStackSafeOpt(jsonParser, jsonLarge)
    bh.consume(result)
  }

  @Benchmark
  def json_deep_run(bh: Blackhole): Unit = {
    val result = run(jsonParser, jsonDeep)
    bh.consume(result)
  }

  @Benchmark
  def json_deep_opt(bh: Blackhole): Unit = {
    val result = runStackSafeOpt(jsonParser, jsonDeep)
    bh.consume(result)
  }
}
