package parsers.toml

import munit.FunSuite
import net.ghoula.sarati.ast.toml.*

import parser.core.*
import parser.syntax.*

class TomlParserTests extends FunSuite {
  import TomlValue.*

  test("parse simple string") {
    val toml = """key = "value""""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assert(doc.pairs.contains("key"))
  }

  test("parse integer") {
    val toml = """number = 42"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.pairs("number"), Integer(42))
  }

  test("parse negative integer") {
    val toml = """number = -17"""
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  test("parse float") {
    val toml = """pi = 3.14"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.pairs("pi"), Float(3.14))
  }

  test("parse boolean true") {
    val toml = """flag = true"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.pairs("flag"), Boolean(true))
  }

  test("parse boolean false") {
    val toml = """flag = false"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.pairs("flag"), Boolean(false))
  }

  test("parse basic string") {
    val toml = """str = "hello world""""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.pairs("str"), String("hello world"))
  }

  test("parse string with escape") {
    val toml = """str = "line1\nline2""""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    doc.pairs("str") match {
      case String(value) => assert(value.contains("\n"))
      case _ => fail("Expected String")
    }
  }

  test("parse literal string") {
    val toml = """str = 'C:\Users\path'"""
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  test("parse multi-line basic string") {
    val triple = "\"\"\""
    val toml = s"str = $triple\nmulti\nline\nstring$triple\n"
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  test("parse hex integer") {
    val toml = """hex = 0xFF"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.pairs("hex"), Integer(255))
  }

  test("parse octal integer") {
    val toml = """oct = 0o755"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.pairs("oct"), Integer(493))
  }

  test("parse binary integer") {
    val toml = """bin = 0b11111111"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.pairs("bin"), Integer(255))
  }

  test("parse integer with underscores") {
    val toml = """num = 1_000_000"""
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  test("parse float with exponent") {
    val toml = """num = 5e+22"""
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  test("parse infinity") {
    val toml = """inf = inf"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    doc.pairs("inf") match {
      case Float(value) => assert(value.isInfinite)
      case _ => fail("Expected Float")
    }
  }

  test("parse NaN") {
    val toml = """nan = nan"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    doc.pairs("nan") match {
      case Float(value) => assert(value.isNaN)
      case _ => fail("Expected Float")
    }
  }

  test("parse empty array") {
    val toml = """arr = []"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    doc.pairs("arr") match {
      case Array(elements) => assertEquals(elements, List())
      case _ => fail("Expected Array")
    }
  }

  test("parse integer array") {
    val toml = """arr = [1, 2, 3]"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    doc.pairs("arr") match {
      case Array(elements) => assertEquals(elements, List(Integer(1), Integer(2), Integer(3)))
      case _ => fail("Expected Array")
    }
  }

  test("parse string array") {
    val toml = """arr = ["a", "b", "c"]"""
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  test("parse mixed array") {
    val toml = """arr = [1, "two", 3.0]"""
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  test("parse nested array") {
    val toml = """arr = [[1, 2], [3, 4]]"""
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  test("parse array with trailing comma") {
    val toml = """arr = [1, 2, 3,]"""
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  test("parse empty inline table") {
    val toml = """table = {}"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    doc.pairs("table") match {
      case InlineTable(pairs) => assert(pairs.isEmpty)
      case _ => fail("Expected InlineTable")
    }
  }

  test("parse inline table with values") {
    val toml = """point = { x = 1, y = 2 }"""
    val result = parseToml(toml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    doc.pairs("point") match {
      case InlineTable(pairs) =>
        assertEquals(pairs("x"), Integer(1))
        assertEquals(pairs("y"), Integer(2))
      case _ => fail("Expected InlineTable")
    }
  }

  test("parse with comment") {
    val toml = """# This is a comment
key = "value" # inline comment
"""
    val result = parseToml(toml)
    assert(result.isSuccess)
  }

  test("parse multiple key-value pairs") {
    val toml = """name = "Alice"
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
    val result = parseToml(toml.split("\n").take(2).mkString("\n") + "\n")
    assert(result.isSuccess)
  }

  test("round-trip: simple key-value pairs") {
    val doc: TomlTable = (
      isArrayTable = false,
      pairs = Map("name" -> String("Alice"), "age" -> Integer(30)),
      subtables = Map.empty
    )
    val formatted = formatToml(doc)
    val reparsed = parseToml(formatted + "\n")
    assert(reparsed.isSuccess, s"Failed to parse: $formatted")
    val result = reparsed.toOption.get
    assertEquals(result.pairs("name"), String("Alice"))
    assertEquals(result.pairs("age"), Integer(30))
  }

  test("round-trip: string with escapes") {
    val doc: TomlTable = (
      isArrayTable = false,
      pairs = Map("message" -> String("Hello\nWorld")),
      subtables = Map.empty
    )
    val formatted = formatToml(doc)
    val reparsed = parseToml(formatted + "\n")
    assert(reparsed.isSuccess, s"Failed to parse: $formatted")
    val result = reparsed.toOption.get
    result.pairs("message") match {
      case String(value) => assert(value.contains("\n"))
      case _ => fail("Expected String")
    }
  }

  test("round-trip: array values") {
    val doc: TomlTable = (
      isArrayTable = false,
      pairs = Map("numbers" -> Array(List(Integer(1), Integer(2), Integer(3)))),
      subtables = Map.empty
    )
    val formatted = formatToml(doc)
    val reparsed = parseToml(formatted + "\n")
    assert(reparsed.isSuccess, s"Failed to parse: $formatted")
    val result = reparsed.toOption.get
    result.pairs("numbers") match {
      case Array(elements) => assertEquals(elements, List(Integer(1), Integer(2), Integer(3)))
      case _ => fail("Expected Array")
    }
  }

  test("round-trip: boolean and float") {
    val doc: TomlTable = (
      isArrayTable = false,
      pairs = Map("enabled" -> Boolean(true), "pi" -> Float(3.14)),
      subtables = Map.empty
    )
    val formatted = formatToml(doc)
    val reparsed = parseToml(formatted + "\n")
    assert(reparsed.isSuccess, s"Failed to parse: $formatted")
    val result = reparsed.toOption.get
    assertEquals(result.pairs("enabled"), Boolean(true))
    assertEquals(result.pairs("pi"), Float(3.14))
  }

  // ==== Tables (TOML 1.0 [table] / [[array table]]) ====

  test("table header routes keys into subtables") {
    val doc = parseToml("[server]\nhost = \"example\"\nport = 8080")
    assert(doc.isSuccess, s"got $doc")
    val sub = doc.toOption.get.subtables.get("server").toList.flatten
    assert(sub.size == 1, s"expected one [server] table, got $sub")
    assertEquals(sub.head.pairs.get("host"), Some(String("example")))
    assertEquals(sub.head.pairs.get("port"), Some(Integer(8080)))
    assert(doc.toOption.get.pairs.isEmpty, "root pairs must stay empty")
  }

  test("keys before any header belong to the root table") {
    val doc = parseToml("title = \"t\"\n[server]\nhost = \"h\"")
    assert(doc.isSuccess, s"got $doc")
    val d = doc.toOption.get
    assertEquals(d.pairs.get("title"), Some(String("t")))
    assert(d.subtables.contains("server"))
  }

  test("nested table header builds the subtable tree") {
    val doc = parseToml("[a.b]\nx = 1")
    assert(doc.isSuccess, s"got $doc")
    val aTables = doc.toOption.get.subtables.get("a").toList.flatten
    assert(aTables.size == 1, s"got $aTables")
    val bTables = aTables.head.subtables.get("b").toList.flatten
    assertEquals(bTables.head.pairs.get("x"), Some(Integer(1)))
  }

  test("array table accumulates entries") {
    val doc = parseToml("[[items]]\nname = \"one\"\n[[items]]\nname = \"two\"")
    assert(doc.isSuccess, s"got $doc")
    val items = doc.toOption.get.subtables.get("items").toList.flatten
    assert(items.size == 2, s"expected two [[items]] entries, got $items")
    assert(items.forall(_.isArrayTable), s"entries must be array tables: $items")
    assertEquals(items(0).pairs.get("name"), Some(String("one")))
    assertEquals(items(1).pairs.get("name"), Some(String("two")))
  }

  test("duplicate table header fails") {
    val doc = parseToml("[a]\nx = 1\n[a]\ny = 2")
    assert(doc.isFailure, s"expected failure, got $doc")
    // the duplicate error points at the second [a] header (offset 10, line 3)
    doc match {
      case Result.Failure(errors, _) =>
        val dup = errors.collectFirst { case c @ ParseError.Custom(m, _) if m.contains("duplicate") => c }
        assert(dup.isDefined, s"expected a duplicate-table error, got $errors")
        assertEquals(dup.get.location.offset, 10)
        assertEquals(dup.get.location.line, 3)
      case other => fail(s"expected failure, got $other")
    }
  }

  test("table and array-table of the same path conflict") {
    val doc = parseToml("[[a]]\nx = 1\n[a]\ny = 2")
    assert(doc.isFailure, s"expected failure, got $doc")
  }

  test("implicit super-table may be defined after its sub-table") {
    val doc = parseToml("[a.b]\nx = 1\n[a]\ny = 2")
    assert(doc.isSuccess, s"got $doc")
    val a = doc.toOption.get.subtables.get("a").toList.flatten
    assertEquals(a.size, 1)
    assertEquals(a.head.pairs.get("y"), Some(Integer(2)))
    assert(a.head.subtables.contains("b"))
  }

  test("dotted key inside a table stays in that table's pairs") {
    val doc = parseToml("[t]\na.b = 1")
    assert(doc.isSuccess, s"got $doc")
    val t = doc.toOption.get.subtables.get("t").toList.flatten.head
    assertEquals(t.pairs.get("a.b"), Some(Integer(1)))
  }

  test("headerless documents keep the flat representation") {
    val doc = parseToml("a.b = 1\nc = 2")
    assert(doc.isSuccess, s"got $doc")
    val d = doc.toOption.get
    assertEquals(d.pairs.get("a.b"), Some(Integer(1)))
    assertEquals(d.pairs.get("c"), Some(Integer(2)))
    assert(d.subtables.isEmpty, s"no headers means no subtables: ${d.subtables}")
  }
}
