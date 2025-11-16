//> using scala "3.7.4"
//> using dep "net.ghoula::rumil-core:0.2.0"
//> using dep "net.ghoula::rumil-parsers:0.2.0"

package examples.jsontocaseclass

import parser.core._
import parser.syntax._
import parsers.json.{JsonValue, JsonParser}

/**
 * Example: Parsing JSON to Case Classes (Structural Way)
 *
 * This example demonstrates the "Structural Approach" - using pure combinators
 * to parse JSON and manually map the result to case classes.
 *
 * This approach gives you:
 * - Full control over the parsing process
 * - Access to the intermediate JsonValue representation
 * - Ability to customize parsing logic per-field
 * - No macro magic, just explicit code
 *
 * Input:  {"name": "Alice", "age": 30, "admin": true}
 * Output: User("Alice", 30, true)
 */
@main def structuralJsonExample(): Unit = {
  // Define a case class for our domain model
  case class User(name: String, age: Int, admin: Boolean)

  val input = """{"name": "Alice", "age": 30, "admin": true}"""

  // Step 1: Parse JSON string to JsonValue
  val jsonResult: Result[ParseError, JsonValue] = JsonParser.parseValue.run(input)

  // Step 2: Manually extract fields and map to case class
  val userResult: Result[String, User] = jsonResult match {
    case Result.Success(jsonValue, _) =>
      extractUser(jsonValue)

    case Result.Failure(errors, _) =>
      Result.Failure(errors.map(_.toString), (1, 1, 0))

    case Result.Partial(jsonValue, errors, _) =>
      println(s"Parse warnings: $errors")
      extractUser(jsonValue)
  }

  // Step 3: Handle the result
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

  // Example 2: Parsing with validation
  println("\n--- Example 2: With Custom Validation ---")

  val input2 = """{"name": "Bob", "age": 17, "admin": false}"""
  val jsonResult2 = JsonParser.parseValue.run(input2)

  val validatedUserResult = jsonResult2 match {
    case Result.Success(jsonValue, _) =>
      extractUser(jsonValue) match {
        case Result.Success(user, consumed) =>
          // Custom validation: age must be >= 18
          if (user.age >= 18) {
            Result.Success(user, consumed)
          } else {
            Result.Failure(
              List(s"User must be at least 18 years old, got ${user.age}"),
              (1, 1, 0)
            )
          }
        case other => other
      }
    case Result.Failure(errors, furthest) =>
      Result.Failure(errors.map(_.toString), furthest)
    case Result.Partial(jsonValue, errors, consumed) =>
      println(s"Parse warnings: $errors")
      extractUser(jsonValue)
  }

  validatedUserResult match {
    case Result.Success(user, _) =>
      println(s"✓ Valid user: $user")
    case Result.Failure(errors, _) =>
      println(s"✗ Validation failed:")
      errors.foreach(err => println(s"  - $err"))
    case Result.Partial(user, errors, _) =>
      println(s"⚠ Partial: $user, errors: $errors")
  }
}

/**
 * Extract a User from a JsonValue.
 *
 * This function demonstrates manual field extraction with proper error handling.
 */
def extractUser(json: JsonValue): Result[String, User] = {
  json match {
    case JsonValue.Object(fields) =>
      // Extract each field
      val nameResult = fields.get("name") match {
        case Some(JsonValue.Str(name)) => Right(name)
        case Some(other) => Left(s"Expected string for 'name', got $other")
        case None => Left("Missing required field 'name'")
      }

      val ageResult = fields.get("age") match {
        case Some(JsonValue.Number(age)) if age.isWhole && age >= 0 =>
          Right(age.toInt)
        case Some(JsonValue.Number(age)) =>
          Left(s"Age must be a non-negative whole number, got $age")
        case Some(other) =>
          Left(s"Expected number for 'age', got $other")
        case None =>
          Left("Missing required field 'age'")
      }

      val adminResult = fields.get("admin") match {
        case Some(JsonValue.Bool(admin)) => Right(admin)
        case Some(other) => Left(s"Expected boolean for 'admin', got $other")
        case None => Left("Missing required field 'admin'")
      }

      // Combine results
      (nameResult, ageResult, adminResult) match {
        case (Right(name), Right(age), Right(admin)) =>
          Result.Success(User(name, age, admin), 0)

        case _ =>
          val errors = List(nameResult, ageResult, adminResult).collect {
            case Left(err) => err
          }
          Result.Failure(errors, (1, 1, 0))
      }

    case other =>
      Result.Failure(List(s"Expected JSON object, got $other"), (1, 1, 0))
  }
}

/**
 * Helper: A case class representing a User
 */
case class User(name: String, age: Int, admin: Boolean)
