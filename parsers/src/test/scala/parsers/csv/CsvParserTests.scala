package parsers.csv

import munit.FunSuite
import parser.core.*
import parser.syntax.*
import org.scalacheck.{Prop, Gen, Arbitrary}
import org.scalacheck.Prop.forAll

class CsvParserTests extends FunSuite {

  // ============================================================================
  // Basic Parsing Tests
  // ============================================================================

  test("parse empty CSV") {
    val result = parseCsv("")
    assertEquals(result.toOption, Some(List(List(""))))
  }

  test("parse single field") {
    val result = parseCsv("hello")
    assertEquals(result.toOption, Some(List(List("hello"))))
  }

  test("parse single row with multiple fields") {
    val result = parseCsv("a,b,c")
    assertEquals(result.toOption, Some(List(List("a", "b", "c"))))
  }

  test("parse multiple rows") {
    val input = "a,b,c\n1,2,3\n4,5,6"
    val result = parseCsv(input)
    assertEquals(result.toOption, Some(List(
      List("a", "b", "c"),
      List("1", "2", "3"),
      List("4", "5", "6")
    )))
  }

  test("parse with CRLF line endings") {
    val input = "a,b,c\r\n1,2,3\r\n4,5,6"
    val result = parseCsv(input)
    assertEquals(result.toOption, Some(List(
      List("a", "b", "c"),
      List("1", "2", "3"),
      List("4", "5", "6")
    )))
  }

  // ============================================================================
  // Quoted Fields Tests (RFC 4180)
  // ============================================================================

  test("parse quoted field") {
    val result = parseCsv("\"hello\"")
    assertEquals(result.toOption, Some(List(List("hello"))))
  }

  test("parse quoted field with comma") {
    val result = parseCsv("\"hello, world\"")
    assertEquals(result.toOption, Some(List(List("hello, world"))))
  }

  test("parse quoted field with newline") {
    val result = parseCsv("\"hello\nworld\"")
    assertEquals(result.toOption, Some(List(List("hello\nworld"))))
  }

  test("parse quoted field with escaped quotes") {
    val result = parseCsv("\"hello \"\"world\"\"\"")
    assertEquals(result.toOption, Some(List(List("hello \"world\""))))
  }

  test("parse mixed quoted and unquoted fields") {
    val result = parseCsv("a,\"b\",c")
    assertEquals(result.toOption, Some(List(List("a", "b", "c"))))
  }

  test("parse empty quoted field") {
    val result = parseCsv("\"\",a,\"\"")
    assertEquals(result.toOption, Some(List(List("", "a", ""))))
  }

  // ============================================================================
  // RFC 4180 Compliance Tests
  // ============================================================================

  test("RFC 4180 Example 1: standard fields") {
    val input = "aaa,bbb,ccc"
    val result = parseCsv(input)
    assertEquals(result.toOption, Some(List(List("aaa", "bbb", "ccc"))))
  }

  test("RFC 4180 Example 2: fields with quotes") {
    val input = "\"aaa\",\"bbb\",\"ccc\""
    val result = parseCsv(input)
    assertEquals(result.toOption, Some(List(List("aaa", "bbb", "ccc"))))
  }

  test("RFC 4180 Example 3: field with embedded comma") {
    val input = "\"aaa\",\"b,bb\",\"ccc\""
    val result = parseCsv(input)
    assertEquals(result.toOption, Some(List(List("aaa", "b,bb", "ccc"))))
  }

  test("RFC 4180 Example 4: field with embedded newline") {
    val input = "\"aaa\",\"b\nbb\",\"ccc\""
    val result = parseCsv(input)
    assertEquals(result.toOption, Some(List(List("aaa", "b\nbb", "ccc"))))
  }

  test("RFC 4180 Example 5: field with embedded quotes") {
    val input = "\"aaa\",\"b\"\"bb\",\"ccc\""
    val result = parseCsv(input)
    assertEquals(result.toOption, Some(List(List("aaa", "b\"bb", "ccc"))))
  }

  // ============================================================================
  // TSV Tests
  // ============================================================================

  test("parse TSV single row") {
    val input = "a\tb\tc"
    val result = parseTsv(input)
    assertEquals(result.toOption, Some(List(List("a", "b", "c"))))
  }

  test("parse TSV multiple rows") {
    val input = "name\tage\tcity\nAlice\t30\tNYC\nBob\t25\tSF"
    val result = parseTsv(input)
    assertEquals(result.toOption, Some(List(
      List("name", "age", "city"),
      List("Alice", "30", "NYC"),
      List("Bob", "25", "SF")
    )))
  }

  // ============================================================================
  // Advanced Features Tests
  // ============================================================================

  test("parse with headers") {
    val input = "name,age,city\nAlice,30,NYC\nBob,25,SF"
    val result = parseCsvWithHeaders(input)
    assertEquals(result.toOption, Some((
      List("name", "age", "city"),
      List(
        List("Alice", "30", "NYC"),
        List("Bob", "25", "SF")
      )
    )))
  }

  test("parse as maps") {
    val input = "name,age,city\nAlice,30,NYC\nBob,25,SF"
    val result = parseCsvAsMaps(input)
    assertEquals(result.toOption, Some(List(
      Map("name" -> "Alice", "age" -> "30", "city" -> "NYC"),
      Map("name" -> "Bob", "age" -> "25", "city" -> "SF")
    )))
  }

  test("parse strict validates consistent columns") {
    val input = "a,b,c\n1,2,3\n4,5,6"
    val result = parseCsvStrict(input)
    assert(result.isSuccess)
  }

  test("parse strict fails on inconsistent columns") {
    val input = "a,b,c\n1,2,3\n4,5"
    val result = parseCsvStrict(input)
    assert(result.isFailure)
  }

  // ============================================================================
  // Custom Configuration Tests
  // ============================================================================

  test("parse with custom delimiter (semicolon)") {
    val config = (
      delimiter = ';',
      quote = '"',
      escape = '"',
      trimWhitespace = false,
      skipEmptyLines = false
    )
    val input = "a;b;c\n1;2;3"
    val result = parseCsv(input, config)
    assertEquals(result.toOption, Some(List(
      List("a", "b", "c"),
      List("1", "2", "3")
    )))
  }

  test("parse with trim whitespace") {
    val config = (
      delimiter = ',',
      quote = '"',
      escape = '"',
      trimWhitespace = true,
      skipEmptyLines = false
    )
    val input = " a , b , c "
    val result = parseCsv(input, config)
    assertEquals(result.toOption, Some(List(List("a", "b", "c"))))
  }

  test("parse with skip empty lines") {
    val config = (
      delimiter = ',',
      quote = '"',
      escape = '"',
      trimWhitespace = false,
      skipEmptyLines = true
    )
    val input = "a,b,c\n\n1,2,3"
    val result = parseCsv(input, config)
    assertEquals(result.toOption, Some(List(
      List("a", "b", "c"),
      List("1", "2", "3")
    )))
  }

  // ============================================================================
  // Edge Cases Tests
  // ============================================================================

  test("parse empty fields") {
    val result = parseCsv(",,")
    assertEquals(result.toOption, Some(List(List("", "", ""))))
  }

  test("parse single empty field") {
    val result = parseCsv("")
    assertEquals(result.toOption, Some(List(List(""))))
  }

  test("parse row with trailing comma") {
    val result = parseCsv("a,b,")
    assertEquals(result.toOption, Some(List(List("a", "b", ""))))
  }

  test("parse row with leading comma") {
    val result = parseCsv(",a,b")
    assertEquals(result.toOption, Some(List(List("", "a", "b"))))
  }

  // ============================================================================
  // Real-World Examples
  // ============================================================================

  test("parse real-world contact list") {
    val input = """name,email,phone
"Smith, John",john.smith@example.com,555-1234
"Doe, Jane",jane.doe@example.com,555-5678
"O'Brien, Patrick",patrick.obrien@example.com,"""

    val result = parseCsvAsMaps(input)
    assert(result.isSuccess)
    val maps = result.toOption.get
    assertEquals(maps.length, 3)
    assertEquals(maps(0)("name"), "Smith, John")
    assertEquals(maps(1)("name"), "Doe, Jane")
    assertEquals(maps(2)("name"), "O'Brien, Patrick")
  }

  test("parse real-world product catalog") {
    val input = """SKU,Product,Description,Price
ABC-123,"Widget, Large","High-quality widget for industrial use
Includes mounting hardware",29.99
XYZ-789,"Gadget ""Pro""","Professional-grade gadget",199.99""""

    val result = parseCsvWithHeaders(input)
    assert(result.isSuccess)
    val (headers, rows) = result.toOption.get
    assertEquals(headers, List("SKU", "Product", "Description", "Price"))
    assertEquals(rows.length, 2)
    assertEquals(rows(0)(1), "Widget, Large")
    assertEquals(rows(1)(1), "Gadget \"Pro\"")
  }

  // ============================================================================
  // Property-Based Tests
  // ============================================================================

  test("property: parsing simple fields is identity") {
    val gen = Gen.listOfN(5, Gen.alphaNumStr.filter(s => s.nonEmpty && !s.contains(',')))
    val prop = forAll(gen) { fields =>
      val input = fields.mkString(",")
      val result = parseCsv(input)
      result.toOption.exists(csv => csv == List(fields))
    }
    prop.check()
  }

  test("property: quoted fields preserve content") {
    val gen = Gen.alphaNumStr
    val prop = forAll(gen) { content =>
      val input = s"\"$content\""
      val result = parseCsv(input)
      result.toOption.exists(csv => csv == List(List(content)))
    }
    prop.check()
  }

  test("property: number of rows equals number of newlines + 1") {
    val gen = Gen.choose(0, 10).flatMap { n =>
      Gen.listOfN(n, Gen.const("a,b,c"))
    }
    val prop = forAll(gen) { rows =>
      val input = rows.mkString("\n")
      if (rows.isEmpty) {
        true // Empty input edge case
      } else {
        val result = parseCsv(input)
        result.toOption.exists(csv => csv.length == rows.length)
      }
    }
    prop.check()
  }
}
