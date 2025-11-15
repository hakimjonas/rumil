package parsers.json

import munit.FunSuite
import parser.core.*
import org.scalacheck.{Prop, Gen, Arbitrary}
import org.scalacheck.Prop.forAll

class JsonParserTests extends FunSuite {
  import JsonValue.*

  // ============================================================================
  // Literals Tests
  // ============================================================================

  test("parse null") {
    val result = JsonParser.parse("null")
    assertEquals(result.toOption, Some(Null))
  }

  test("parse true") {
    val result = JsonParser.parse("true")
    assertEquals(result.toOption, Some(Bool(true)))
  }

  test("parse false") {
    val result = JsonParser.parse("false")
    assertEquals(result.toOption, Some(Bool(false)))
  }

  // ============================================================================
  // Number Tests (RFC 8259 Section 6)
  // ============================================================================

  test("parse integer zero") {
    val result = JsonParser.parse("0")
    assertEquals(result.toOption, Some(Number(0.0)))
  }

  test("parse positive integer") {
    val result = JsonParser.parse("42")
    assertEquals(result.toOption, Some(Number(42.0)))
  }

  test("parse negative integer") {
    val result = JsonParser.parse("-17")
    assertEquals(result.toOption, Some(Number(-17.0)))
  }

  test("parse decimal number") {
    val result = JsonParser.parse("3.14159")
    assertEquals(result.toOption, Some(Number(3.14159)))
  }

  test("parse negative decimal") {
    val result = JsonParser.parse("-2.5")
    assertEquals(result.toOption, Some(Number(-2.5)))
  }

  test("parse number with exponent") {
    val result = JsonParser.parse("1e10")
    assertEquals(result.toOption, Some(Number(1e10)))
  }

  test("parse number with positive exponent") {
    val result = JsonParser.parse("1.5e+3")
    assertEquals(result.toOption, Some(Number(1500.0)))
  }

  test("parse number with negative exponent") {
    val result = JsonParser.parse("2e-3")
    assertEquals(result.toOption, Some(Number(0.002)))
  }

  test("parse large number") {
    val result = JsonParser.parse("1.7976931348623157e+308")
    assert(result.isSuccess)
  }

  // ============================================================================
  // String Tests (RFC 8259 Section 7)
  // ============================================================================

  test("parse empty string") {
    val result = JsonParser.parse("\"\"")
    assertEquals(result.toOption, Some(Str("")))
  }

  test("parse simple string") {
    val result = JsonParser.parse("\"hello\"")
    assertEquals(result.toOption, Some(Str("hello")))
  }

  test("parse string with spaces") {
    val result = JsonParser.parse("\"hello world\"")
    assertEquals(result.toOption, Some(Str("hello world")))
  }

  test("parse string with escaped quote") {
    val result = JsonParser.parse("\"hello \\\"world\\\"\"")
    assertEquals(result.toOption, Some(Str("hello \"world\"")))
  }

  test("parse string with escaped backslash") {
    val result = JsonParser.parse("\"path\\\\to\\\\file\"")
    assertEquals(result.toOption, Some(Str("path\\to\\file")))
  }

  test("parse string with newline escape") {
    val result = JsonParser.parse("\"line1\\nline2\"")
    assertEquals(result.toOption, Some(Str("line1\nline2")))
  }

  test("parse string with tab escape") {
    val result = JsonParser.parse("\"col1\\tcol2\"")
    assertEquals(result.toOption, Some(Str("col1\tcol2")))
  }

  test("parse string with all escapes") {
    val result = JsonParser.parse("\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t\"")
    assertEquals(result.toOption, Some(Str("\" \\ / \b \f \n \r \t")))
  }

  test("parse string with Unicode escape") {
    val result = JsonParser.parse("\"\\u0041\\u0042\\u0043\"")
    assertEquals(result.toOption, Some(Str("ABC")))
  }

  test("parse string with emoji via Unicode") {
    val result = JsonParser.parse("\"\\u263A\"")
    assert(result.isSuccess)
  }

  // ============================================================================
  // Array Tests (RFC 8259 Section 5)
  // ============================================================================

  test("parse empty array") {
    val result = JsonParser.parse("[]")
    assertEquals(result.toOption, Some(Array(List())))
  }

  test("parse array with one element") {
    val result = JsonParser.parse("[1]")
    assertEquals(result.toOption, Some(Array(List(Number(1)))))
  }

  test("parse array with multiple elements") {
    val result = JsonParser.parse("[1,2,3]")
    assertEquals(result.toOption, Some(Array(List(Number(1), Number(2), Number(3)))))
  }

  test("parse array with mixed types") {
    val result = JsonParser.parse("[1,\"hello\",true,null]")
    assertEquals(result.toOption, Some(Array(List(
      Number(1),
      Str("hello"),
      Bool(true),
      Null
    ))))
  }

  test("parse nested arrays") {
    val result = JsonParser.parse("[[1,2],[3,4]]")
    assertEquals(result.toOption, Some(Array(List(
      Array(List(Number(1), Number(2))),
      Array(List(Number(3), Number(4)))
    ))))
  }

  test("parse array with whitespace") {
    val result = JsonParser.parse("[ 1 , 2 , 3 ]")
    assertEquals(result.toOption, Some(Array(List(Number(1), Number(2), Number(3)))))
  }

  // ============================================================================
  // Object Tests (RFC 8259 Section 4)
  // ============================================================================

  test("parse empty object") {
    val result = JsonParser.parse("{}")
    assertEquals(result.toOption, Some(Object(Map())))
  }

  test("parse object with one field") {
    val result = JsonParser.parse("""{"name":"Alice"}""")
    assertEquals(result.toOption, Some(Object(Map("name" -> Str("Alice")))))
  }

  test("parse object with multiple fields") {
    val result = JsonParser.parse("""{"name":"Alice","age":30}""")
    assertEquals(result.toOption, Some(Object(Map(
      "name" -> Str("Alice"),
      "age" -> Number(30)
    ))))
  }

  test("parse object with mixed value types") {
    val result = JsonParser.parse("""{"str":"hello","num":42,"bool":true,"null":null}""")
    assertEquals(result.toOption, Some(Object(Map(
      "str" -> Str("hello"),
      "num" -> Number(42),
      "bool" -> Bool(true),
      "null" -> Null
    ))))
  }

  test("parse nested objects") {
    val result = JsonParser.parse("""{"outer":{"inner":"value"}}""")
    assertEquals(result.toOption, Some(Object(Map(
      "outer" -> Object(Map("inner" -> Str("value")))
    ))))
  }

  test("parse object with array value") {
    val result = JsonParser.parse("""{"numbers":[1,2,3]}""")
    assertEquals(result.toOption, Some(Object(Map(
      "numbers" -> Array(List(Number(1), Number(2), Number(3)))
    ))))
  }

  test("parse object with whitespace") {
    val result = JsonParser.parse("""{ "name" : "Alice" , "age" : 30 }""")
    assertEquals(result.toOption, Some(Object(Map(
      "name" -> Str("Alice"),
      "age" -> Number(30)
    ))))
  }

  // ============================================================================
  // RFC 8259 Compliance Tests
  // ============================================================================

  test("RFC 8259: JSON text must be object or array") {
    // Actually RFC 8259 allows any value at top level
    val tests = List(
      "null" -> Null,
      "true" -> Bool(true),
      "42" -> Number(42),
      "\"hello\"" -> Str("hello"),
      "[]" -> Array(List()),
      "{}" -> Object(Map())
    )

    tests.foreach { case (input, expected) =>
      val result = JsonParser.parse(input)
      assert(result.isSuccess, s"Failed to parse: $input")
      assertEquals(result.toOption, Some(expected))
    }
  }

  test("RFC 8259: whitespace is allowed before and after values") {
    val result = JsonParser.parse("  \n\t 42 \n\t  ")
    assertEquals(result.toOption, Some(Number(42)))
  }

  test("RFC 8259: numbers cannot have leading zeros") {
    val result = JsonParser.parse("00")
    assert(result.isFailure)
  }

  test("RFC 8259: strings must be properly escaped") {
    // Control characters must be escaped
    val result = JsonParser.parse("\"hello\nworld\"")
    assert(result.isFailure)
  }

  // ============================================================================
  // Complex Real-World Examples
  // ============================================================================

  test("parse person object") {
    val json = """{
      "name": "Alice Smith",
      "age": 30,
      "email": "alice@example.com",
      "active": true,
      "address": {
        "street": "123 Main St",
        "city": "New York",
        "zip": "10001"
      },
      "phoneNumbers": [
        "555-1234",
        "555-5678"
      ]
    }"""

    val result = JsonParser.parse(json)
    assert(result.isSuccess)
    val obj = result.toOption.get.asInstanceOf[Object]
    assertEquals(obj.fields("name"), Str("Alice Smith"))
    assertEquals(obj.fields("age"), Number(30))
  }

  test("parse array of objects") {
    val json = """[
      {"id": 1, "name": "Alice"},
      {"id": 2, "name": "Bob"},
      {"id": 3, "name": "Charlie"}
    ]"""

    val result = JsonParser.parse(json)
    assert(result.isSuccess)
    val arr = result.toOption.get.asInstanceOf[Array]
    assertEquals(arr.elements.length, 3)
  }

  test("parse deeply nested structure") {
    val json = """{"a":{"b":{"c":{"d":{"e":"value"}}}}}"""
    val result = JsonParser.parse(json)
    assert(result.isSuccess)
  }

  test("parse GitHub API response example") {
    val json = """{
      "login": "octocat",
      "id": 1,
      "node_id": "MDQ6VXNlcjE=",
      "avatar_url": "https://github.com/images/error/octocat_happy.gif",
      "type": "User",
      "site_admin": false,
      "name": "The Octocat",
      "company": "@github",
      "blog": "https://github.blog",
      "location": "San Francisco",
      "email": null,
      "hireable": null,
      "bio": null,
      "public_repos": 2,
      "followers": 20,
      "following": 0,
      "created_at": "2008-01-14T04:33:35Z"
    }"""

    val result = JsonParser.parse(json)
    assert(result.isSuccess)
  }

  // ============================================================================
  // Formatting Tests
  // ============================================================================

  test("format null") {
    assertEquals(JsonParser.format(Null), "null")
  }

  test("format boolean") {
    assertEquals(JsonParser.format(Bool(true)), "true")
    assertEquals(JsonParser.format(Bool(false)), "false")
  }

  test("format number") {
    assertEquals(JsonParser.format(Number(42)), "42")
    assertEquals(JsonParser.format(Number(3.14)), "3.14")
  }

  test("format string") {
    assertEquals(JsonParser.format(Str("hello")), "\"hello\"")
  }

  test("format string with escapes") {
    assertEquals(JsonParser.format(Str("line1\nline2")), "\"line1\\nline2\"")
  }

  test("format empty array") {
    assertEquals(JsonParser.format(Array(List())), "[]")
  }

  test("format array compact") {
    val arr = Array(List(Number(1), Number(2), Number(3)))
    assertEquals(JsonParser.format(arr), "[1,2,3]")
  }

  test("format empty object") {
    assertEquals(JsonParser.format(Object(Map())), "{}")
  }

  test("format object compact") {
    val obj = Object(Map("name" -> Str("Alice"), "age" -> Number(30)))
    val result = JsonParser.format(obj)
    assert(result.contains("\"name\":\"Alice\""))
    assert(result.contains("\"age\":30"))
  }

  test("format with pretty print") {
    val obj = Object(Map("name" -> Str("Alice"), "items" -> Array(List(Number(1), Number(2)))))
    val result = JsonParser.format(obj, prettyFormat)
    assert(result.contains("\n"))
  }

  // ============================================================================
  // Round-Trip Tests
  // ============================================================================

  test("round-trip: parse and format") {
    val original = """{"name":"Alice","age":30,"active":true}"""
    val result = JsonParser.parse(original)
    assert(result.isSuccess)
    val formatted = JsonParser.format(result.toOption.get)
    val reparsed = JsonParser.parse(formatted)
    assertEquals(reparsed, result)
  }

  // ============================================================================
  // Property-Based Tests
  // ============================================================================

  test("property: null always parses") {
    val prop = forAll(Gen.const("null")) { input =>
      JsonParser.parse(input).isSuccess
    }
    prop.check()
  }

  test("property: booleans always parse") {
    val prop = forAll(Gen.oneOf("true", "false")) { input =>
      JsonParser.parse(input).isSuccess
    }
    prop.check()
  }

  test("property: integers round-trip") {
    val prop = forAll(Gen.choose(-1000, 1000)) { n =>
      val input = n.toString
      JsonParser.parse(input).toOption.contains(Number(n.toDouble))
    }
    prop.check()
  }

  test("property: simple strings round-trip") {
    val gen = Gen.alphaNumStr.filter(_.nonEmpty)
    val prop = forAll(gen) { s =>
      val input = s"\"$s\""
      JsonParser.parse(input).toOption.contains(Str(s))
    }
    prop.check()
  }

  test("property: empty array always parses") {
    val prop = forAll(Gen.const("[]")) { input =>
      JsonParser.parse(input).toOption.contains(Array(List()))
    }
    prop.check()
  }

  test("property: empty object always parses") {
    val prop = forAll(Gen.const("{}")) { input =>
      JsonParser.parse(input).toOption.contains(Object(Map()))
    }
    prop.check()
  }

  test("property: format then parse is identity") {
    val valueGen: Gen[JsonValue] = Gen.oneOf(
      Gen.const(Null),
      Gen.oneOf(true, false).map(Bool.apply),
      Gen.choose(-100.0, 100.0).map(Number.apply),
      Gen.alphaNumStr.map(Str.apply)
    )

    val prop = forAll(valueGen) { value =>
      val formatted = JsonParser.format(value)
      val parsed = JsonParser.parse(formatted)
      parsed.toOption.contains(value)
    }
    prop.check()
  }
}
