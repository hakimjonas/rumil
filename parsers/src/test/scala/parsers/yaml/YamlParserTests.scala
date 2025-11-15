package parsers.yaml

import munit.FunSuite
import parser.core.*

class YamlParserTests extends FunSuite {
  import YamlValue.*

  test("parse null") {
    val result = parseYaml("null")
    assert(result.isSuccess)
    assertEquals(result.toOption.get.root, Null)
  }

  test("parse boolean true") {
    val result = parseYaml("true")
    assert(result.isSuccess)
    assertEquals(result.toOption.get.root, Boolean(true))
  }

  test("parse boolean false") {
    val result = parseYaml("false")
    assert(result.isSuccess)
    assertEquals(result.toOption.get.root, Boolean(false))
  }

  test("parse integer") {
    val result = parseYaml("42")
    assert(result.isSuccess)
    assertEquals(result.toOption.get.root, Integer(42))
  }

  test("parse float") {
    val result = parseYaml("3.14")
    assert(result.isSuccess)
    assertEquals(result.toOption.get.root, Float(3.14))
  }

  test("parse string") {
    val result = parseYaml("hello")
    assert(result.isSuccess)
    assertEquals(result.toOption.get.root, String("hello"))
  }

  test("parse quoted string") {
    val result = parseYaml("\"hello world\"")
    assert(result.isSuccess)
    assertEquals(result.toOption.get.root, String("hello world"))
  }

  test("parse flow sequence") {
    val result = parseYaml("[1, 2, 3]")
    assert(result.isSuccess)
    val seq = result.toOption.get.root.asInstanceOf[Sequence]
    assertEquals(seq.elements.length, 3)
  }

  test("parse flow mapping") {
    val result = parseYaml("{name: Alice, age: 30}")
    assert(result.isSuccess)
    val map = result.toOption.get.root.asInstanceOf[Mapping]
    assert(map.pairs.contains("name"))
  }

  test("parse block sequence") {
    val yaml = """- item1
- item2
- item3
"""
    val result = parseYaml(yaml)
    assert(result.isSuccess)
    val seq = result.toOption.get.root.asInstanceOf[Sequence]
    assertEquals(seq.elements.length, 3)
  }

  test("parse block mapping") {
    val yaml = """name: Alice
age: 30
city: NYC
"""
    val result = parseYaml(yaml)
    assert(result.isSuccess)
    val map = result.toOption.get.root.asInstanceOf[Mapping]
    assertEquals(map.pairs("name"), String("Alice"))
  }

  test("parse with document markers") {
    val yaml = """---
name: test
..."""
    val result = parseYaml(yaml)
    assert(result.isSuccess)
  }

  test("parse with comments") {
    val yaml = """# This is a comment
name: Alice # inline comment
"""
    val result = parseYaml(yaml)
    assert(result.isSuccess)
  }
}
