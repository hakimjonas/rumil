package parser.interop

import munit.FunSuite
import parser.core._
import parsers.json.JsonValue

class AnnotationTests extends FunSuite {
  import Decoder.derived
  import JsonDecoders.given

  // ============================================================================
  // @Rename Tests
  // ============================================================================

  test("@Rename changes field name in JSON") {
    case class User(
      @Rename("user_name") name: String,
      @Rename("user_age") age: Int
    )

    given Decoder[JsonValue, User] = derived

    val json = JsonValue.Object(Map(
      "user_name" -> JsonValue.Str("Alice"),
      "user_age" -> JsonValue.Number(30)
    ))

    Decoder[JsonValue, User].decode(json) match {
      case Result.Success(user, _) =>
        assertEquals(user.name, "Alice")
        assertEquals(user.age, 30)
      case other =>
        fail(s"Expected Success, got $other")
    }
  }

  test("@Rename multiple fields") {
    case class Person(
      @Rename("first_name") firstName: String,
      @Rename("last_name") lastName: String,
      @Rename("age_years") age: Int
    )

    given Decoder[JsonValue, Person] = derived

    val json = JsonValue.Object(Map(
      "first_name" -> JsonValue.Str("Bob"),
      "last_name" -> JsonValue.Str("Smith"),
      "age_years" -> JsonValue.Number(25)
    ))

    Decoder[JsonValue, Person].decode(json) match {
      case Result.Success(person, _) =>
        assertEquals(person.firstName, "Bob")
        assertEquals(person.lastName, "Smith")
        assertEquals(person.age, 25)
      case other =>
        fail(s"Expected Success, got $other")
    }
  }

  test("@Rename with missing field produces error") {
    case class Config(
      @Rename("server_host") host: String,
      @Rename("server_port") port: Int
    )

    given Decoder[JsonValue, Config] = derived

    val json = JsonValue.Object(Map(
      "server_host" -> JsonValue.Str("localhost")
      // missing server_port
    ))

    Decoder[JsonValue, Config].decode(json) match {
      case Result.Failure(errors, _) =>
        assert(errors.exists {
          case DecodeError.MissingField("server_port", _) => true
          case _ => false
        })
      case Result.Partial(_, errors, _) =>
        assert(errors.exists {
          case DecodeError.MissingField("server_port", _) => true
          case _ => false
        })
      case other =>
        fail(s"Expected Failure or Partial with missing server_port, got $other")
    }
  }

  test("@Rename mixed with normal fields") {
    case class Mixed(
      @Rename("custom_name") renamed: String,
      normal: Int
    )

    given Decoder[JsonValue, Mixed] = derived

    val json = JsonValue.Object(Map(
      "custom_name" -> JsonValue.Str("test"),
      "normal" -> JsonValue.Number(42)
    ))

    Decoder[JsonValue, Mixed].decode(json) match {
      case Result.Success(mixed, _) =>
        assertEquals(mixed.renamed, "test")
        assertEquals(mixed.normal, 42)
      case other =>
        fail(s"Expected Success, got $other")
    }
  }

  // ============================================================================
  // Edge Cases
  // ============================================================================

  test("@Rename to empty string") {
    case class EmptyName(
      @Rename("") field: String
    )

    given Decoder[JsonValue, EmptyName] = derived

    val json = JsonValue.Object(Map(
      "" -> JsonValue.Str("value")
    ))

    Decoder[JsonValue, EmptyName].decode(json) match {
      case Result.Success(obj, _) =>
        assertEquals(obj.field, "value")
      case other =>
        fail(s"Expected Success, got $other")
    }
  }

  test("@Rename with special characters") {
    case class SpecialChars(
      @Rename("my-field") field1: String,
      @Rename("field.name") field2: Int,
      @Rename("field:value") field3: Boolean
    )

    given Decoder[JsonValue, SpecialChars] = derived

    val json = JsonValue.Object(Map(
      "my-field" -> JsonValue.Str("test"),
      "field.name" -> JsonValue.Number(42),
      "field:value" -> JsonValue.Bool(true)
    ))

    Decoder[JsonValue, SpecialChars].decode(json) match {
      case Result.Success(obj, _) =>
        assertEquals(obj.field1, "test")
        assertEquals(obj.field2, 42)
        assertEquals(obj.field3, true)
      case other =>
        fail(s"Expected Success, got $other")
    }
  }
}
