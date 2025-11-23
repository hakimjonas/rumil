package parsers.json

import munit.FunSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.{Gen, Prop}
import parser.core._
import parser.syntax._

class JsonParserTests extends FunSuite {
  import JsonValue.*

  // ============================================================================
  // Literals Tests
  // ============================================================================

  test("parse null") {
    val result = parseJson("null")
    assertEquals(result.toOption, Some(Null))
  }

  test("parse true") {
    val result = parseJson("true")
    assertEquals(result.toOption, Some(Bool(true)))
  }

  test("parse false") {
    val result = parseJson("false")
    assertEquals(result.toOption, Some(Bool(false)))
  }

  // ============================================================================
  // Number Tests (RFC 8259 Section 6)
  // ============================================================================

  test("parse integer zero") {
    val result = parseJson("0")
    assertEquals(result.toOption, Some(Number(0.0)))
  }

  test("parse positive integer") {
    val result = parseJson("42")
    assertEquals(result.toOption, Some(Number(42.0)))
  }

  test("parse negative integer") {
    val result = parseJson("-17")
    assertEquals(result.toOption, Some(Number(-17.0)))
  }

  test("parse decimal number") {
    val result = parseJson("3.14159")
    assertEquals(result.toOption, Some(Number(3.14159)))
  }

  test("parse negative decimal") {
    val result = parseJson("-2.5")
    assertEquals(result.toOption, Some(Number(-2.5)))
  }

  test("parse number with exponent") {
    val result = parseJson("1e10")
    assertEquals(result.toOption, Some(Number(1e10)))
  }

  test("parse number with positive exponent") {
    val result = parseJson("1.5e+3")
    assertEquals(result.toOption, Some(Number(1500.0)))
  }

  test("parse number with negative exponent") {
    val result = parseJson("2e-3")
    assertEquals(result.toOption, Some(Number(0.002)))
  }

  test("parse large number") {
    val result = parseJson("1.7976931348623157e+308")
    assert(result.isSuccess)
  }

  // ============================================================================
  // String Tests (RFC 8259 Section 7)
  // ============================================================================

  test("parse empty string") {
    val result = parseJson("\"\"")
    assertEquals(result.toOption, Some(Str("")))
  }

  test("parse simple string") {
    val result = parseJson("\"hello\"")
    assertEquals(result.toOption, Some(Str("hello")))
  }

  test("parse string with spaces") {
    val result = parseJson("\"hello world\"")
    assertEquals(result.toOption, Some(Str("hello world")))
  }

  test("parse string with escaped quote") {
    val result = parseJson("\"hello \\\"world\\\"\"")
    assertEquals(result.toOption, Some(Str("hello \"world\"")))
  }

  test("parse string with escaped backslash") {
    val result = parseJson("\"path\\\\to\\\\file\"")
    assertEquals(result.toOption, Some(Str("path\\to\\file")))
  }

  test("parse string with newline escape") {
    val result = parseJson("\"line1\\nline2\"")
    assertEquals(result.toOption, Some(Str("line1\nline2")))
  }

  test("parse string with tab escape") {
    val result = parseJson("\"col1\\tcol2\"")
    assertEquals(result.toOption, Some(Str("col1\tcol2")))
  }

  test("parse string with all escapes") {
    val result = parseJson("\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t\"")
    assertEquals(result.toOption, Some(Str("\" \\ / \b \f \n \r \t")))
  }

  test("parse string with Unicode escape") {
    val result = parseJson("\"\\u0041\\u0042\\u0043\"")
    assertEquals(result.toOption, Some(Str("ABC")))
  }

  test("parse string with emoji via Unicode") {
    val result = parseJson("\"\\u263A\"")
    assert(result.isSuccess)
  }

  // ============================================================================
  // Array Tests (RFC 8259 Section 5)
  // ============================================================================

  test("parse empty array") {
    val result = parseJson("[]")
    assertEquals(result.toOption, Some(Array(List())))
  }

  test("parse array with one element") {
    val result = parseJson("[1]")
    assertEquals(result.toOption, Some(Array(List(Number(1)))))
  }

  test("parse array with multiple elements") {
    val result = parseJson("[1,2,3]")
    assertEquals(result.toOption, Some(Array(List(Number(1), Number(2), Number(3)))))
  }

  test("parse array with mixed types") {
    val result = parseJson("[1,\"hello\",true,null]")
    assertEquals(
      result.toOption,
      Some(
        Array(
          List(
            Number(1),
            Str("hello"),
            Bool(true),
            Null
          ))))
  }

  test("parse nested arrays") {
    val result = parseJson("[[1,2],[3,4]]")
    assertEquals(
      result.toOption,
      Some(
        Array(
          List(
            Array(List(Number(1), Number(2))),
            Array(List(Number(3), Number(4)))
          ))))
  }

  test("parse array with whitespace") {
    val result = parseJson("[ 1 , 2 , 3 ]")
    assertEquals(result.toOption, Some(Array(List(Number(1), Number(2), Number(3)))))
  }

  // ============================================================================
  // Object Tests (RFC 8259 Section 4)
  // ============================================================================

  test("parse empty object") {
    val result = parseJson("{}")
    assertEquals(result.toOption, Some(Object(Map())))
  }

  test("parse object with one field") {
    val result = parseJson("""{"name":"Alice"}""")
    assertEquals(result.toOption, Some(Object(Map("name" -> Str("Alice")))))
  }

  test("parse object with multiple fields") {
    val result = parseJson("""{"name":"Alice","age":30}""")
    assertEquals(
      result.toOption,
      Some(
        Object(
          Map(
            "name" -> Str("Alice"),
            "age"  -> Number(30)
          ))))
  }

  test("parse object with mixed value types") {
    val result = parseJson("""{"str":"hello","num":42,"bool":true,"null":null}""")
    assertEquals(
      result.toOption,
      Some(
        Object(
          Map(
            "str"  -> Str("hello"),
            "num"  -> Number(42),
            "bool" -> Bool(true),
            "null" -> Null
          ))))
  }

  test("parse nested objects") {
    val result = parseJson("""{"outer":{"inner":"value"}}""")
    assertEquals(
      result.toOption,
      Some(
        Object(
          Map(
            "outer" -> Object(Map("inner" -> Str("value")))
          ))))
  }

  test("parse object with array value") {
    val result = parseJson("""{"numbers":[1,2,3]}""")
    assertEquals(
      result.toOption,
      Some(
        Object(
          Map(
            "numbers" -> Array(List(Number(1), Number(2), Number(3)))
          ))))
  }

  test("parse object with whitespace") {
    val result = parseJson("""{ "name" : "Alice" , "age" : 30 }""")
    assertEquals(
      result.toOption,
      Some(
        Object(
          Map(
            "name" -> Str("Alice"),
            "age"  -> Number(30)
          ))))
  }

  // ============================================================================
  // RFC 8259 Compliance Tests
  // ============================================================================

  test("RFC 8259: JSON text must be object or array") {
    // Actually RFC 8259 allows any value at top level
    val tests = List(
      "null"      -> Null,
      "true"      -> Bool(true),
      "42"        -> Number(42),
      "\"hello\"" -> Str("hello"),
      "[]"        -> Array(List()),
      "{}"        -> Object(Map())
    )

    tests.foreach { case (input, expected) =>
      val result = parseJson(input)
      assert(result.isSuccess, s"Failed to parse: $input")
      assertEquals(result.toOption, Some(expected))
    }
  }

  test("RFC 8259: whitespace is allowed before and after values") {
    val result = parseJson("  \n\t 42 \n\t  ")
    assertEquals(result.toOption, Some(Number(42)))
  }

  test("RFC 8259: numbers cannot have leading zeros") {
    val result = parseJson("00")
    assert(result.isFailure)
  }

  test("RFC 8259: strings must be properly escaped") {
    // Control characters must be escaped
    val result = parseJson("\"hello\nworld\"")
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

    val result = parseJson(json)
    assert(result.isSuccess)
    result.toOption.get match {
      case Object(fields) =>
        assertEquals(fields("name"), Str("Alice Smith"))
        assertEquals(fields("age"), Number(30))
      case _ => fail("Expected Object")
    }
  }

  test("parse array of objects") {
    val json = """[
      {"id": 1, "name": "Alice"},
      {"id": 2, "name": "Bob"},
      {"id": 3, "name": "Charlie"}
    ]"""

    val result = parseJson(json)
    assert(result.isSuccess)
    result.toOption.get match {
      case Array(elements) => assertEquals(elements.length, 3)
      case _               => fail("Expected Array")
    }
  }

  test("parse deeply nested structure") {
    val json   = """{"a":{"b":{"c":{"d":{"e":"value"}}}}}"""
    val result = parseJson(json)
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

    val result = parseJson(json)
    assert(result.isSuccess)
  }

  // ============================================================================
  // Formatting Tests
  // ============================================================================

  test("format null") {
    assertEquals(formatJson(Null), "null")
  }

  test("format boolean") {
    assertEquals(formatJson(Bool(true)), "true")
    assertEquals(formatJson(Bool(false)), "false")
  }

  test("format number") {
    assertEquals(formatJson(Number(42)), "42")
    assertEquals(formatJson(Number(3.14)), "3.14")
  }

  test("format string") {
    assertEquals(formatJson(Str("hello")), "\"hello\"")
  }

  test("format string with escapes") {
    assertEquals(formatJson(Str("line1\nline2")), "\"line1\\nline2\"")
  }

  test("format empty array") {
    assertEquals(formatJson(Array(List())), "[]")
  }

  test("format array compact") {
    val arr = Array(List(Number(1), Number(2), Number(3)))
    assertEquals(formatJson(arr), "[1,2,3]")
  }

  test("format empty object") {
    assertEquals(formatJson(Object(Map())), "{}")
  }

  test("format object compact") {
    val obj    = Object(Map("name" -> Str("Alice"), "age" -> Number(30)))
    val result = formatJson(obj)
    assert(result.contains("\"name\":\"Alice\""))
    assert(result.contains("\"age\":30"))
  }

  test("format with pretty print") {
    val obj    = Object(Map("name" -> Str("Alice"), "items" -> Array(List(Number(1), Number(2)))))
    val result = formatJson(obj, prettyFormat)
    assert(result.contains("\n"))
  }

  // ============================================================================
  // Round-Trip Tests
  // ============================================================================

  test("round-trip: parse and format") {
    val original = """{"name":"Alice","age":30,"active":true}"""
    val result   = parseJson(original)
    assert(result.isSuccess)
    val formatted = formatJson(result.toOption.get)
    val reparsed  = parseJson(formatted)
    assertEquals(reparsed, result)
  }

  // ============================================================================
  // Property-Based Tests
  // ============================================================================

  test("property: null always parses") {
    val prop = forAll(Gen.const("null")) { input =>
      parseJson(input).isSuccess
    }
    prop.check()
  }

  test("property: booleans always parse") {
    val prop = forAll(Gen.oneOf("true", "false")) { input =>
      parseJson(input).isSuccess
    }
    prop.check()
  }

  test("property: integers round-trip") {
    val prop = forAll(Gen.choose(-1000, 1000)) { n =>
      val input = n.toString
      parseJson(input).toOption.contains(Number(n.toDouble))
    }
    prop.check()
  }

  test("property: simple strings round-trip") {
    val gen = Gen.alphaNumStr.filter(_.nonEmpty)
    val prop = forAll(gen) { s =>
      val input = s"\"$s\""
      parseJson(input).toOption.contains(Str(s))
    }
    prop.check()
  }

  test("property: empty array always parses") {
    val prop = forAll(Gen.const("[]")) { input =>
      parseJson(input).toOption.contains(Array(List()))
    }
    prop.check()
  }

  test("property: empty object always parses") {
    val prop = forAll(Gen.const("{}")) { input =>
      parseJson(input).toOption.contains(Object(Map()))
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
      val formatted = formatJson(value)
      val parsed    = parseJson(formatted)
      parsed.toOption.contains(value)
    }
    prop.check()
  }
}
