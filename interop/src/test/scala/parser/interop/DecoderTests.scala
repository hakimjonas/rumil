package parser.interop

import munit.FunSuite
import parser.core._
import parsers.json.JsonValue

/**
 * Comprehensive test suite for the Decoder typeclass.
 *
 * Tests cover:
 * - Primitive type decoding (String, Int, Boolean, etc.)
 * - Case class decoding (simple, nested, with options/lists)
 * - Collection decoding (List, Option, Vector, etc.)
 * - Error cases (missing fields, type mismatches)
 * - Integration (full JSON parsing + decoding pipeline)
 */
class DecoderTests extends FunSuite {

  import JsonDecoders.given

  // ============================================================================
  // Primitive Type Decoding Tests (5+ tests)
  // ============================================================================

  test("decode JSON string to String") {
    val json   = JsonValue.Str("hello")
    val result = Decoder[JsonValue, String].decode(json)
    assertEquals(result, Result.Success("hello", 0))
  }

  test("decode JSON number to Int") {
    val json   = JsonValue.Number(42.0)
    val result = Decoder[JsonValue, Int].decode(json)
    assertEquals(result, Result.Success(42, 0))
  }

  test("decode JSON number to Long") {
    val json   = JsonValue.Number(123456789.0)
    val result = Decoder[JsonValue, Long].decode(json)
    assertEquals(result, Result.Success(123456789L, 0))
  }

  test("decode JSON number to Double") {
    val json   = JsonValue.Number(3.14159)
    val result = Decoder[JsonValue, Double].decode(json)
    assertEquals(result, Result.Success(3.14159, 0))
  }

  test("decode JSON boolean to Boolean") {
    val jsonTrue  = JsonValue.Bool(true)
    val jsonFalse = JsonValue.Bool(false)
    assertEquals(Decoder[JsonValue, Boolean].decode(jsonTrue), Result.Success(true, 0))
    assertEquals(Decoder[JsonValue, Boolean].decode(jsonFalse), Result.Success(false, 0))
  }

  test("decode JSON null to Option[A]") {
    val json   = JsonValue.Null
    val result = Decoder[JsonValue, Option[String]].decode(json)
    assertEquals(result, Result.Success(None, 0))
  }

  test("type mismatch error - string expected, number found") {
    val json   = JsonValue.Number(42.0)
    val result = Decoder[JsonValue, String].decode(json)
    result match {
      case Result.Failure(errors, _) =>
        assert(errors.exists {
          case DecodeError.TypeMismatch("String", actual, _) => actual.contains("Number")
          case _                                             => false
        })
      case _ => fail("Expected Failure")
    }
  }

  test("type mismatch error - int expected, non-whole number found") {
    val json   = JsonValue.Number(3.14)
    val result = Decoder[JsonValue, Int].decode(json)
    result match {
      case Result.Failure(errors, _) =>
        assert(errors.exists {
          case DecodeError.TypeMismatch("Int", actual, _) => actual.contains("not a whole number")
          case _                                          => false
        })
      case _ => fail("Expected Failure")
    }
  }

  // ============================================================================
  // Case Class Decoding Tests (8+ tests)
  // ============================================================================

  test("decode simple case class with 2 fields") {
    case class Point(x: Int, y: Int)
    given Decoder[JsonValue, Point] = Decoder.derived

    val json = JsonValue.Object(
      Map(
        "x" -> JsonValue.Number(10.0),
        "y" -> JsonValue.Number(20.0)
      ))

    val result = Decoder[JsonValue, Point].decode(json)
    assertEquals(result, Result.Success(Point(10, 20), 0))
  }

  test("decode case class with 3 fields of mixed types") {
    case class Person(name: String, age: Int, active: Boolean)
    given Decoder[JsonValue, Person] = Decoder.derived

    val json = JsonValue.Object(
      Map(
        "name"   -> JsonValue.Str("Alice"),
        "age"    -> JsonValue.Number(30.0),
        "active" -> JsonValue.Bool(true)
      ))

    val result = Decoder[JsonValue, Person].decode(json)
    assertEquals(result, Result.Success(Person("Alice", 30, true), 0))
  }

  test("decode case class with optional fields") {
    case class User(name: String, email: Option[String])
    given Decoder[JsonValue, User] = Decoder.derived

    val jsonWithEmail = JsonValue.Object(
      Map(
        "name"  -> JsonValue.Str("Bob"),
        "email" -> JsonValue.Str("bob@example.com")
      ))

    val jsonWithoutEmail = JsonValue.Object(
      Map(
        "name"  -> JsonValue.Str("Bob"),
        "email" -> JsonValue.Null
      ))

    val resultWith    = Decoder[JsonValue, User].decode(jsonWithEmail)
    val resultWithout = Decoder[JsonValue, User].decode(jsonWithoutEmail)

    assertEquals(resultWith, Result.Success(User("Bob", Some("bob@example.com")), 0))
    assertEquals(resultWithout, Result.Success(User("Bob", None), 0))
  }

  test("decode case class with list field") {
    case class Team(name: String, members: List[String])
    given Decoder[JsonValue, Team] = Decoder.derived

    val json = JsonValue.Object(
      Map(
        "name" -> JsonValue.Str("Avengers"),
        "members" -> JsonValue.Array(
          List(
            JsonValue.Str("Iron Man"),
            JsonValue.Str("Captain America"),
            JsonValue.Str("Thor")
          ))
      ))

    val result = Decoder[JsonValue, Team].decode(json)
    assertEquals(
      result,
      Result.Success(Team("Avengers", List("Iron Man", "Captain America", "Thor")), 0))
  }

  test("decode nested case classes") {
    case class Address(street: String, city: String)
    case class Company(name: String, address: Address)

    given Decoder[JsonValue, Address] = Decoder.derived
    given Decoder[JsonValue, Company] = Decoder.derived

    val json = JsonValue.Object(
      Map(
        "name" -> JsonValue.Str("Acme Corp"),
        "address" -> JsonValue.Object(
          Map(
            "street" -> JsonValue.Str("123 Main St"),
            "city"   -> JsonValue.Str("Springfield")
          ))
      ))

    val result   = Decoder[JsonValue, Company].decode(json)
    val expected = Company("Acme Corp", Address("123 Main St", "Springfield"))
    assertEquals(result, Result.Success(expected, 0))
  }

  test("missing field error") {
    case class Book(title: String, author: String)
    given Decoder[JsonValue, Book] = Decoder.derived

    val json = JsonValue.Object(
      Map(
        "title" -> JsonValue.Str("1984")
        // Missing "author" field
      ))

    val result = Decoder[JsonValue, Book].decode(json)
    result match {
      case Result.Partial(_, errors, _) =>
        assert(errors.exists {
          case DecodeError.MissingField("author", _) => true
          case _                                     => false
        })
      case Result.Failure(errors, _) =>
        assert(errors.exists {
          case DecodeError.MissingField("author", _) => true
          case _                                     => false
        })
      case _ => fail("Expected Partial or Failure with missing field error")
    }
  }

  test("invalid field type error") {
    case class Product(name: String, price: Int)
    given Decoder[JsonValue, Product] = Decoder.derived

    val json = JsonValue.Object(
      Map(
        "name"  -> JsonValue.Str("Widget"),
        "price" -> JsonValue.Str("not a number") // Should be a number
      ))

    val result = Decoder[JsonValue, Product].decode(json)
    result match {
      case Result.Partial(_, errors, _) =>
        assert(errors.exists {
          case DecodeError.TypeMismatch("Int", _, _) => true
          case _                                     => false
        })
      case Result.Failure(errors, _) =>
        assert(errors.exists {
          case DecodeError.TypeMismatch("Int", _, _) => true
          case _                                     => false
        })
      case _ => fail("Expected Partial or Failure with type mismatch")
    }
  }

  test("decode empty object") {
    case class Empty()
    given Decoder[JsonValue, Empty] = Decoder.derived

    val json   = JsonValue.Object(Map.empty)
    val result = Decoder[JsonValue, Empty].decode(json)
    assertEquals(result, Result.Success(Empty(), 0))
  }

  test("decode complex nested structure") {
    case class Tag(name: String)
    case class Post(title: String, content: String, tags: List[Tag])
    case class Author(name: String, posts: List[Post])

    given Decoder[JsonValue, Tag]    = Decoder.derived
    given Decoder[JsonValue, Post]   = Decoder.derived
    given Decoder[JsonValue, Author] = Decoder.derived

    val json = JsonValue.Object(
      Map(
        "name" -> JsonValue.Str("Alice"),
        "posts" -> JsonValue.Array(
          List(
            JsonValue.Object(Map(
              "title"   -> JsonValue.Str("Hello World"),
              "content" -> JsonValue.Str("This is my first post"),
              "tags" -> JsonValue.Array(List(
                JsonValue.Object(Map("name" -> JsonValue.Str("intro"))),
                JsonValue.Object(Map("name" -> JsonValue.Str("hello")))
              ))
            ))
          ))
      ))

    val result = Decoder[JsonValue, Author].decode(json)
    val expected = Author(
      "Alice",
      List(Post("Hello World", "This is my first post", List(Tag("intro"), Tag("hello"))))
    )
    assertEquals(result, Result.Success(expected, 0))
  }

  test("type mismatch - object expected, array found") {
    case class Item(id: Int)
    given Decoder[JsonValue, Item] = Decoder.derived

    val json   = JsonValue.Array(List(JsonValue.Number(1.0)))
    val result = Decoder[JsonValue, Item].decode(json)

    result match {
      case Result.Failure(errors, _) =>
        assert(errors.exists {
          case DecodeError.TypeMismatch("Object", "Array", _) => true
          case _                                              => false
        })
      case _ => fail("Expected Failure with type mismatch")
    }
  }

  // ============================================================================
  // Collection Decoding Tests (4+ tests)
  // ============================================================================

  test("decode JSON array to List[Int]") {
    val json = JsonValue.Array(
      List(
        JsonValue.Number(1.0),
        JsonValue.Number(2.0),
        JsonValue.Number(3.0)
      ))

    val result = Decoder[JsonValue, List[Int]].decode(json)
    assertEquals(result, Result.Success(List(1, 2, 3), 0))
  }

  test("decode JSON array to Seq[String]") {
    val json = JsonValue.Array(
      List(
        JsonValue.Str("apple"),
        JsonValue.Str("banana"),
        JsonValue.Str("cherry")
      ))

    val result = Decoder[JsonValue, Seq[String]].decode(json)
    assertEquals(result, Result.Success(Seq("apple", "banana", "cherry"), 0))
  }

  test("decode JSON array to Vector[Boolean]") {
    val json = JsonValue.Array(
      List(
        JsonValue.Bool(true),
        JsonValue.Bool(false),
        JsonValue.Bool(true)
      ))

    val result = Decoder[JsonValue, Vector[Boolean]].decode(json)
    assertEquals(result, Result.Success(Vector(true, false, true), 0))
  }

  test("decode empty JSON array") {
    val json   = JsonValue.Array(List.empty)
    val result = Decoder[JsonValue, List[String]].decode(json)
    assertEquals(result, Result.Success(List.empty, 0))
  }

  test("decode nested arrays") {
    val json = JsonValue.Array(
      List(
        JsonValue.Array(List(JsonValue.Number(1.0), JsonValue.Number(2.0))),
        JsonValue.Array(List(JsonValue.Number(3.0), JsonValue.Number(4.0)))
      ))

    val result = Decoder[JsonValue, List[List[Int]]].decode(json)
    assertEquals(result, Result.Success(List(List(1, 2), List(3, 4)), 0))
  }

  test("decode JSON object to Map[String, Int]") {
    val json = JsonValue.Object(
      Map(
        "a" -> JsonValue.Number(1.0),
        "b" -> JsonValue.Number(2.0),
        "c" -> JsonValue.Number(3.0)
      ))

    val result = Decoder[JsonValue, Map[String, Int]].decode(json)
    assertEquals(result, Result.Success(Map("a" -> 1, "b" -> 2, "c" -> 3), 0))
  }

  // ============================================================================
  // Integration Tests (3+ tests)
  // ============================================================================

  test("integration: full pipeline from JSON string to case class") {
    import parsers.json.parseJson

    case class Coordinate(lat: Double, lon: Double)
    given Decoder[JsonValue, Coordinate] = Decoder.derived

    val input = """{"lat": 37.7749, "lon": -122.4194}"""

    // Step 1: Parse JSON string to JsonValue
    val parseResult = parseJson(input)

    // Step 2: Decode JsonValue to case class
    val finalResult = parseResult match {
      case Result.Success(jsonValue, _)         => Decoder[JsonValue, Coordinate].decode(jsonValue)
      case Result.Partial(jsonValue, errors, _) => Decoder[JsonValue, Coordinate].decode(jsonValue)
      case Result.Failure(errors, furthest)     => Result.Failure(errors, furthest)
    }

    finalResult match {
      case Result.Success(coord, _) =>
        assertEquals(coord.lat, 37.7749)
        assertEquals(coord.lon, -122.4194)
      case _ => fail(s"Expected Success, got $finalResult")
    }
  }

  test("integration: parse and decode complex JSON") {
    import parsers.json.parseJson

    case class User(name: String, age: Int, emails: List[String])
    given Decoder[JsonValue, User] = Decoder.derived

    val input =
      """{"name": "Charlie", "age": 25, "emails": ["charlie@example.com", "c@test.com"]}"""

    val parseResult = parseJson(input)
    val finalResult = parseResult match {
      case Result.Success(jsonValue, _) => Decoder[JsonValue, User].decode(jsonValue)
      case other                        => fail(s"Parse failed: $other")
    }

    val expected = User("Charlie", 25, List("charlie@example.com", "c@test.com"))
    assertEquals(finalResult, Result.Success(expected, 0))
  }

  test("integration: error propagation through pipeline") {
    import parsers.json.parseJson

    case class Config(timeout: Int)
    given Decoder[JsonValue, Config] = Decoder.derived

    // Valid JSON, but "timeout" is a string instead of int
    val input = """{"timeout": "not a number"}"""

    val parseResult = parseJson(input)
    val finalResult = parseResult match {
      case Result.Success(jsonValue, _) => Decoder[JsonValue, Config].decode(jsonValue)
      case other                        => fail(s"Parse failed: $other")
    }

    finalResult match {
      case Result.Failure(errors, _) =>
        assert(errors.exists {
          case DecodeError.TypeMismatch("Int", _, _) => true
          case _                                     => false
        })
      case Result.Partial(_, errors, _) =>
        assert(errors.exists {
          case DecodeError.TypeMismatch("Int", _, _) => true
          case _                                     => false
        })
      case _ => fail("Expected Failure or Partial with type mismatch")
    }
  }

  // ============================================================================
  // Additional Edge Cases
  // ============================================================================

  test("decode case class with all primitive types") {
    case class AllTypes(
      s: String,
      i: Int,
      l: Long,
      d: Double,
      b: Boolean
    )
    given Decoder[JsonValue, AllTypes] = Decoder.derived

    val json = JsonValue.Object(
      Map(
        "s" -> JsonValue.Str("test"),
        "i" -> JsonValue.Number(42.0),
        "l" -> JsonValue.Number(1000000.0),
        "d" -> JsonValue.Number(3.14),
        "b" -> JsonValue.Bool(true)
      ))

    val result = Decoder[JsonValue, AllTypes].decode(json)
    assertEquals(result, Result.Success(AllTypes("test", 42, 1000000L, 3.14, true), 0))
  }

  test("decode Option[List[Int]]") {
    val jsonSome = JsonValue.Array(
      List(
        JsonValue.Number(1.0),
        JsonValue.Number(2.0)
      ))
    val jsonNone = JsonValue.Null

    val resultSome = Decoder[JsonValue, Option[List[Int]]].decode(jsonSome)
    val resultNone = Decoder[JsonValue, Option[List[Int]]].decode(jsonNone)

    assertEquals(resultSome, Result.Success(Some(List(1, 2)), 0))
    assertEquals(resultNone, Result.Success(None, 0))
  }

  test("decode List[Option[String]]") {
    val json = JsonValue.Array(
      List(
        JsonValue.Str("hello"),
        JsonValue.Null,
        JsonValue.Str("world")
      ))

    val result = Decoder[JsonValue, List[Option[String]]].decode(json)
    assertEquals(result, Result.Success(List(Some("hello"), None, Some("world")), 0))
  }

  test("decode case class with Byte, Short, and Float") {
    case class SmallNumbers(b: Byte, s: Short, f: Float)
    given Decoder[JsonValue, SmallNumbers] = Decoder.derived

    val json = JsonValue.Object(
      Map(
        "b" -> JsonValue.Number(127.0),
        "s" -> JsonValue.Number(32000.0),
        "f" -> JsonValue.Number(1.5)
      ))

    val result = Decoder[JsonValue, SmallNumbers].decode(json)
    assertEquals(result, Result.Success(SmallNumbers(127.toByte, 32000.toShort, 1.5f), 0))
  }

  test("decode case class with BigInt and BigDecimal") {
    case class BigNumbers(bi: BigInt, bd: BigDecimal)
    given Decoder[JsonValue, BigNumbers] = Decoder.derived

    val json = JsonValue.Object(
      Map(
        "bi" -> JsonValue.Number(123456789.0),
        "bd" -> JsonValue.Number(123.456)
      ))

    val result = Decoder[JsonValue, BigNumbers].decode(json)
    assertEquals(result, Result.Success(BigNumbers(BigInt(123456789), BigDecimal(123.456)), 0))
  }
}
