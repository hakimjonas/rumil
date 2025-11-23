package parser.benchmarks

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import scala.compiletime.uninitialized

import parser.core._
import parser.interop._
import parsers.json._
import parsers.toml._
import parsers.yaml._

/**
 * JMH benchmarks for Decoder.derived case class derivation.
 *
 * Measures the performance of decoding structured data into case classes.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgs = Array("-Xms2G", "-Xmx2G"))
class DecoderBenchmarks {

  import JsonDecoders.given
  import TomlDecoders.given
  import YamlDecoders.given

  // ============================================================================
  // Case Classes
  // ============================================================================

  case class Point(x: Int, y: Int)
  case class Person(name: String, age: Int, active: Boolean)
  case class Address(street: String, city: String, zip: String)
  case class User(name: String, email: String, address: Address)

  given Decoder[JsonValue, Point]   = Decoder.derived
  given Decoder[JsonValue, Person]  = Decoder.derived
  given Decoder[JsonValue, Address] = Decoder.derived
  given Decoder[JsonValue, User]    = Decoder.derived

  given Decoder[TomlValue, Point]   = Decoder.derived
  given Decoder[TomlValue, Person]  = Decoder.derived
  given Decoder[TomlValue, Address] = Decoder.derived
  given Decoder[TomlValue, User]    = Decoder.derived

  given Decoder[YamlValue, Point]   = Decoder.derived
  given Decoder[YamlValue, Person]  = Decoder.derived
  given Decoder[YamlValue, Address] = Decoder.derived
  given Decoder[YamlValue, User]    = Decoder.derived

  // ============================================================================
  // Test Data
  // ============================================================================

  var jsonPoint: JsonValue  = uninitialized
  var jsonPerson: JsonValue = uninitialized
  var jsonUser: JsonValue   = uninitialized

  var tomlPoint: TomlValue  = uninitialized
  var tomlPerson: TomlValue = uninitialized
  var tomlUser: TomlValue   = uninitialized

  var yamlPoint: YamlValue  = uninitialized
  var yamlPerson: YamlValue = uninitialized
  var yamlUser: YamlValue   = uninitialized

  @Setup(Level.Trial)
  def setup(): Unit = {
    // JSON values
    jsonPoint = JsonValue.Object(
      Map(
        "x" -> JsonValue.Number(10.0),
        "y" -> JsonValue.Number(20.0)
      ))

    jsonPerson = JsonValue.Object(
      Map(
        "name"   -> JsonValue.Str("Alice"),
        "age"    -> JsonValue.Number(30.0),
        "active" -> JsonValue.Bool(true)
      ))

    jsonUser = JsonValue.Object(
      Map(
        "name"  -> JsonValue.Str("Bob"),
        "email" -> JsonValue.Str("bob@example.com"),
        "address" -> JsonValue.Object(
          Map(
            "street" -> JsonValue.Str("123 Main St"),
            "city"   -> JsonValue.Str("Springfield"),
            "zip"    -> JsonValue.Str("12345")
          ))
      ))

    // TOML values
    tomlPoint = TomlValue.InlineTable(
      Map(
        "x" -> TomlValue.Integer(10L),
        "y" -> TomlValue.Integer(20L)
      ))

    tomlPerson = TomlValue.InlineTable(
      Map(
        "name"   -> TomlValue.String("Alice"),
        "age"    -> TomlValue.Integer(30L),
        "active" -> TomlValue.Boolean(true)
      ))

    tomlUser = TomlValue.InlineTable(
      Map(
        "name"  -> TomlValue.String("Bob"),
        "email" -> TomlValue.String("bob@example.com"),
        "address" -> TomlValue.InlineTable(
          Map(
            "street" -> TomlValue.String("123 Main St"),
            "city"   -> TomlValue.String("Springfield"),
            "zip"    -> TomlValue.String("12345")
          ))
      ))

    // YAML values
    yamlPoint = YamlValue.Mapping(
      Map(
        "x" -> YamlValue.Integer(10L),
        "y" -> YamlValue.Integer(20L)
      ))

    yamlPerson = YamlValue.Mapping(
      Map(
        "name"   -> YamlValue.String("Alice"),
        "age"    -> YamlValue.Integer(30L),
        "active" -> YamlValue.Boolean(true)
      ))

    yamlUser = YamlValue.Mapping(
      Map(
        "name"  -> YamlValue.String("Bob"),
        "email" -> YamlValue.String("bob@example.com"),
        "address" -> YamlValue.Mapping(
          Map(
            "street" -> YamlValue.String("123 Main St"),
            "city"   -> YamlValue.String("Springfield"),
            "zip"    -> YamlValue.String("12345")
          ))
      ))
  }

  // ============================================================================
  // JSON Decoder Benchmarks
  // ============================================================================

  @Benchmark
  def json_decodePoint(bh: Blackhole): Unit = {
    val result = Decoder[JsonValue, Point].decode(jsonPoint)
    bh.consume(result)
  }

  @Benchmark
  def json_decodePerson(bh: Blackhole): Unit = {
    val result = Decoder[JsonValue, Person].decode(jsonPerson)
    bh.consume(result)
  }

  @Benchmark
  def json_decodeNestedUser(bh: Blackhole): Unit = {
    val result = Decoder[JsonValue, User].decode(jsonUser)
    bh.consume(result)
  }

  // ============================================================================
  // TOML Decoder Benchmarks
  // ============================================================================

  @Benchmark
  def toml_decodePoint(bh: Blackhole): Unit = {
    val result = Decoder[TomlValue, Point].decode(tomlPoint)
    bh.consume(result)
  }

  @Benchmark
  def toml_decodePerson(bh: Blackhole): Unit = {
    val result = Decoder[TomlValue, Person].decode(tomlPerson)
    bh.consume(result)
  }

  @Benchmark
  def toml_decodeNestedUser(bh: Blackhole): Unit = {
    val result = Decoder[TomlValue, User].decode(tomlUser)
    bh.consume(result)
  }

  // ============================================================================
  // YAML Decoder Benchmarks
  // ============================================================================

  @Benchmark
  def yaml_decodePoint(bh: Blackhole): Unit = {
    val result = Decoder[YamlValue, Point].decode(yamlPoint)
    bh.consume(result)
  }

  @Benchmark
  def yaml_decodePerson(bh: Blackhole): Unit = {
    val result = Decoder[YamlValue, Person].decode(yamlPerson)
    bh.consume(result)
  }

  @Benchmark
  def yaml_decodeNestedUser(bh: Blackhole): Unit = {
    val result = Decoder[YamlValue, User].decode(yamlUser)
    bh.consume(result)
  }
}
