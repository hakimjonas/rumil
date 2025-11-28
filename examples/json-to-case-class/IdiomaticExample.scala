//> using scala "3.7.4"
//> using dep "net.ghoula::rumil-core:0.2.0"
//> using dep "net.ghoula::rumil-interop:0.2.0"
//> using dep "net.ghoula::rumil-parsers:0.2.0"

package examples.jsontocaseclass

import parser.core._
import parser.interop._
import parser.interop.JsonDecoders.given
import parsers.json.{parseJson, JsonValue}

/**
 * Example: Parsing JSON to Case Classes (Idiomatic Way)
 *
 * This example demonstrates the "Idiomatic Approach" - using automatic
 * decoder derivation to parse JSON into Scala case classes with minimal
 * boilerplate.
 *
 * Input:  {"name": "Alice", "age": 30, "admin": true}
 * Output: User("Alice", 30, true)
 */
@main def idiomaticJsonExample(): Unit = {
  // Define a case class for our domain model
  case class User(name: String, age: Int, admin: Boolean)

  // Derive a decoder automatically using macros
  // This inspects the case class at compile time and generates
  // the decoding logic for each field
  given Decoder[JsonValue, User] = Decoder.derived

  val input = """{"name": "Alice", "age": 30, "admin": true}"""

  // Step 1: Parse JSON string to JsonValue (intermediate representation)
  val jsonResult: Result[ParseError, JsonValue] = parseJson(input)

  // Step 2: Decode JsonValue to case class
  val userResult: Result[DecodeError, User] = jsonResult match {
    case Result.Success(jsonValue, _) =>
      // Successfully parsed JSON, now decode to case class
      Decoder[JsonValue, User].decode(jsonValue)

    case Result.Partial(jsonValue, parseErrors, _) =>
      // Partial parse (recovered from errors)
      println(s"Parse warnings: $parseErrors")
      Decoder[JsonValue, User].decode(jsonValue)

    case Result.Failure(parseErrors, _) =>
      // Parse failed completely
      Result.Failure(
        parseErrors.map(pe => DecodeError.Custom(s"Parse error: $pe", (1, 1, 0))),
        (1, 1, 0)
      )
  }

  // Step 3: Handle the final result
  userResult match {
    case Result.Success(user, _) =>
      println(s"✓ Parsed user: $user")

    case Result.Failure(errors, _) =>
      println(s"✗ Failed to parse:")
      errors.foreach(err => println(s"  - $err"))

    case Result.Partial(user, errors, _) =>
      println(s"⚠ Partially parsed: $user")
      println(s"  Errors: $errors")
  }

  // Example 2: Parsing with nested structures
  println("\n--- Example 2: Nested Structures ---")

  case class Address(street: String, city: String, zip: String)
  case class Employee(name: String, email: String, address: Address)

  given Decoder[JsonValue, Address] = Decoder.derived
  given Decoder[JsonValue, Employee] = Decoder.derived

  val nestedInput = """{
    "name": "Bob",
    "email": "bob@example.com",
    "address": {
      "street": "123 Main St",
      "city": "Springfield",
      "zip": "12345"
    }
  }"""

  val nestedJsonResult = parseJson(nestedInput)
  val employeeResult = nestedJsonResult.flatMap(json =>
    Decoder[JsonValue, Employee].decode(json)
  )

  employeeResult match {
    case Result.Success(employee, _) =>
      println(s"✓ Parsed employee: $employee")
    case Result.Failure(errors, _) =>
      println(s"✗ Failed: $errors")
    case Result.Partial(employee, errors, _) =>
      println(s"⚠ Partial: $employee, errors: $errors")
  }

  // Example 3: Handling errors
  println("\n--- Example 3: Error Handling ---")

  val invalidInput = """{"name": "Charlie", "age": "not a number"}"""

  val invalidJsonResult = parseJson(invalidInput)
  val invalidUserResult = invalidJsonResult.flatMap(json =>
    Decoder[JsonValue, User].decode(json)
  )

  invalidUserResult match {
    case Result.Success(user, _) =>
      println(s"✓ Parsed: $user")
    case Result.Failure(errors, _) =>
      println(s"✗ Decoding failed:")
      errors.foreach(err => println(s"  - $err"))
    case Result.Partial(user, errors, _) =>
      println(s"⚠ Partial parse: $user")
      println(s"  Errors:")
      errors.foreach(err => println(s"  - $err"))
  }
}
