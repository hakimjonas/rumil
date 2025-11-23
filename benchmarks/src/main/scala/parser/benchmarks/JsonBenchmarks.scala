package parser.benchmarks

import java.util.concurrent.TimeUnit

import scala.compiletime.uninitialized

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import parsers.json._

/**
 * JMH benchmarks for JSON parsing.
 *
 * Compares Rumil's JSON parser against cats-parse based JSON parser.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class JsonBenchmarks {

  // ============================================================================
  // Test Data
  // ============================================================================

  var jsonTiny: String     = uninitialized
  var jsonSmall: String    = uninitialized
  var jsonMedium: String   = uninitialized
  var jsonArray100: String = uninitialized
  var jsonNested10: String = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    jsonTiny = """{"x":1}"""

    jsonSmall = """{"name":"Alice","age":30,"active":true}"""

    jsonMedium =
      """{"users":[{"name":"Alice","age":30,"email":"alice@example.com"},{"name":"Bob","age":25,"email":"bob@example.com"},{"name":"Charlie","age":35,"email":"charlie@example.com"}],"total":3,"page":1}"""

    jsonArray100 = "[" + (1 to 100).map(i => s"""{"id":$i,"value":"item$i"}""").mkString(",") + "]"

    // 10 levels of nesting
    jsonNested10 = (1 to 10).foldLeft("42") { (inner, i) =>
      s"""{"level$i":$inner}"""
    }
  }

  // ============================================================================
  // Rumil JSON Benchmarks
  // ============================================================================

  @Benchmark
  def rumil_jsonTiny(bh: Blackhole): Unit = {
    val result = parseJson(jsonTiny)
    bh.consume(result)
  }

  @Benchmark
  def rumil_jsonSmall(bh: Blackhole): Unit = {
    val result = parseJson(jsonSmall)
    bh.consume(result)
  }

  @Benchmark
  def rumil_jsonMedium(bh: Blackhole): Unit = {
    val result = parseJson(jsonMedium)
    bh.consume(result)
  }

  @Benchmark
  def rumil_jsonArray100(bh: Blackhole): Unit = {
    val result = parseJson(jsonArray100)
    bh.consume(result)
  }

  @Benchmark
  def rumil_jsonNested10(bh: Blackhole): Unit = {
    val result = parseJson(jsonNested10)
    bh.consume(result)
  }

  // ============================================================================
  // cats-parse JSON Benchmarks (simplified JSON parser for comparison)
  // ============================================================================

  // A minimal JSON parser using cats-parse for fair comparison
  import cats.parse.{Parser => P, Parser0, Numbers}

  private val catsWhitespace: Parser0[Unit] = P.charIn(" \t\n\r").rep0.void

  private def catsToken[A](p: P[A]): P[A] = p <* catsWhitespace

  private val catsJnull: P[Any] = P.string("null").as(None: Any)
  private val catsJbool: P[Any] = P.string("true").as(true) | P.string("false").as(false)
  private val catsJnum: P[Any]  = Numbers.jsonNumber.map(_.toDouble)

  private val catsJstring: P[String] = {
    val strChar = P.charWhere(c => c != '"' && c != '\\')
    val escaped = P.char('\\') *> P.anyChar
    P.char('"') *> (strChar | escaped).rep0.map(_.mkString) <* P.char('"')
  }

  private lazy val catsJarray: P[Any] = P.defer {
    val elements = catsJvalue.repSep0(catsToken(P.char(',')))
    (catsToken(P.char('[')) *> elements <* catsToken(P.char(']'))).map(_.toList)
  }

  private lazy val catsJobject: P[Any] = P.defer {
    val pair  = (catsToken(catsJstring) <* catsToken(P.char(':'))) ~ catsJvalue
    val pairs = pair.repSep0(catsToken(P.char(',')))
    (catsToken(P.char('{')) *> pairs <* catsToken(P.char('}'))).map(_.toMap)
  }

  private lazy val catsJvalue: P[Any] = P.defer {
    catsWhitespace.with1 *> (catsJnull | catsJbool | catsJnum | catsJstring.map(
      identity[Any]) | catsJarray | catsJobject) <* catsWhitespace
  }

  val catsJsonParser: P[Any] = catsJvalue

  @Benchmark
  def cats_jsonTiny(bh: Blackhole): Unit = {
    val result = catsJsonParser.parseAll(jsonTiny)
    bh.consume(result)
  }

  @Benchmark
  def cats_jsonSmall(bh: Blackhole): Unit = {
    val result = catsJsonParser.parseAll(jsonSmall)
    bh.consume(result)
  }

  @Benchmark
  def cats_jsonMedium(bh: Blackhole): Unit = {
    val result = catsJsonParser.parseAll(jsonMedium)
    bh.consume(result)
  }

  @Benchmark
  def cats_jsonArray100(bh: Blackhole): Unit = {
    val result = catsJsonParser.parseAll(jsonArray100)
    bh.consume(result)
  }

  @Benchmark
  def cats_jsonNested10(bh: Blackhole): Unit = {
    val result = catsJsonParser.parseAll(jsonNested10)
    bh.consume(result)
  }
}
