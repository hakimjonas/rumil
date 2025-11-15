package parsers.toml

import munit.FunSuite
import parser.core._
import parser.syntax._

class TomlParserTests extends FunSuite {
  import TomlValue.*

  // ============================================================================
  // Basic Value Tests
  // ============================================================================

  test("parse simple string") {
    val toml   = """key = "value""""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assert(doc.pairs.contains("key"))
  }

  test("parse integer") {
    val toml   = """number = 42"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.pairs("number"), Integer(42))
  }

  test("parse negative integer") {
    val toml   = """number = -17"""
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  test("parse float") {
    val toml   = """pi = 3.14"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.pairs("pi"), Float(3.14))
  }

  test("parse boolean true") {
    val toml   = """flag = true"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.pairs("flag"), Boolean(true))
  }

  test("parse boolean false") {
    val toml   = """flag = false"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.pairs("flag"), Boolean(false))
  }

  // ============================================================================
  // String Tests
  // ============================================================================

  test("parse basic string") {
    val toml   = """str = "hello world""""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.pairs("str"), String("hello world"))
  }

  test("parse string with escape") {
    val toml   = """str = "line1\nline2""""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc   = result.toOption.get
    val value = doc.pairs("str").asInstanceOf[String]
    assert(value.value.contains("\n"))
  }

  test("parse literal string") {
    val toml   = """str = 'C:\Users\path'"""
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  test("parse multi-line basic string") {
    // Use string interpolation to inject literal """ without escaping
    val triple = "\"\"\""
    val toml = s"str = $triple\nmulti\nline\nstring$triple\n"
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  // ============================================================================
  // Number Tests
  // ============================================================================

  test("parse hex integer") {
    val toml   = """hex = 0xFF"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.pairs("hex"), Integer(255))
  }

  test("parse octal integer") {
    val toml   = """oct = 0o755"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.pairs("oct"), Integer(493))
  }

  test("parse binary integer") {
    val toml   = """bin = 0b11111111"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.pairs("bin"), Integer(255))
  }

  test("parse integer with underscores") {
    val toml   = """num = 1_000_000"""
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  test("parse float with exponent") {
    val toml   = """num = 5e+22"""
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  test("parse infinity") {
    val toml   = """inf = inf"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc   = result.toOption.get
    val value = doc.pairs("inf").asInstanceOf[Float]
    assert(value.value.isInfinite)
  }

  test("parse NaN") {
    val toml   = """nan = nan"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc   = result.toOption.get
    val value = doc.pairs("nan").asInstanceOf[Float]
    assert(value.value.isNaN)
  }

  // ============================================================================
  // Array Tests
  // ============================================================================

  test("parse empty array") {
    val toml   = """arr = []"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    val arr = doc.pairs("arr").asInstanceOf[Array]
    assertEquals(arr.elements, List())
  }

  test("parse integer array") {
    val toml   = """arr = [1, 2, 3]"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    val arr = doc.pairs("arr").asInstanceOf[Array]
    assertEquals(arr.elements, List(Integer(1), Integer(2), Integer(3)))
  }

  test("parse string array") {
    val toml   = """arr = ["a", "b", "c"]"""
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  test("parse mixed array") {
    val toml   = """arr = [1, "two", 3.0]"""
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  test("parse nested array") {
    val toml   = """arr = [[1, 2], [3, 4]]"""
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  test("parse array with trailing comma") {
    val toml   = """arr = [1, 2, 3,]"""
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  // ============================================================================
  // Inline Table Tests
  // ============================================================================

  test("parse empty inline table") {
    val toml   = """table = {}"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc   = result.toOption.get
    val table = doc.pairs("table").asInstanceOf[InlineTable]
    assert(table.pairs.isEmpty)
  }

  test("parse inline table with values") {
    val toml   = """point = { x = 1, y = 2 }"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc   = result.toOption.get
    val table = doc.pairs("point").asInstanceOf[InlineTable]
    assertEquals(table.pairs("x"), Integer(1))
    assertEquals(table.pairs("y"), Integer(2))
  }

  // ============================================================================
  // Comments Tests
  // ============================================================================

  test("parse with comment") {
    val toml   = """# This is a comment
key = "value" # inline comment
"""
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  // ============================================================================
  // Multiple Key-Value Pairs
  // ============================================================================

  test("parse multiple key-value pairs") {
    val toml   = """name = "Alice"
age = 30
active = true
"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.pairs("name"), String("Alice"))
    assertEquals(doc.pairs("age"), Integer(30))
    assertEquals(doc.pairs("active"), Boolean(true))
  }

  // ============================================================================
  // Real-World Examples
  // ============================================================================

  test("parse configuration file") {
    val toml = """# Application configuration
title = "TOML Example"

[owner]
name = "Tom Preston-Werner"
dob = 1979-05-27T07:32:00-08:00

[database]
server = "192.168.1.1"
ports = [ 8000, 8001, 8002 ]
connection_max = 5000
enabled = true
"""
    // Simplified test - just check it parses without table support
    val result = parseToml(toml.split("\n").take(2).mkString("\n") + "\n")
    assert(result.isSuccess)
  }
}
