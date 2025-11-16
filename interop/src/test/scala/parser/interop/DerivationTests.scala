package parser.interop

import munit.FunSuite
import parser.core._
import parser.interop.Parser.derived
import parser.interop.Primitives.given
import parser.syntax._

/**
 * Test suite for automatic parser derivation.
 *
 * Tests the derivation macro with various case class shapes and validates
 * that parsers are correctly generated and can parse the expected format.
 */
class DerivationTests extends FunSuite {

  // ============================================================================
  // Test case classes
  // ============================================================================

  case class Person(name: String, age: Int)
  given Parser[ParseError, Person] = derived[Person]

  case class Point(x: Int, y: Int)
  given Parser[ParseError, Point] = derived[Point]

  case class Config(host: String, port: Int, enabled: Boolean)
  given Parser[ParseError, Config] = derived[Config]

  case class Measurement(name: String, value: Double)
  given Parser[ParseError, Measurement] = derived[Measurement]

  case class Counter(count: Long)
  given Parser[ParseError, Counter] = derived[Counter]

  case class SingleField(value: Int)
  given Parser[ParseError, SingleField] = derived[SingleField]

  case class ManyFields(a: Int, b: String, c: Boolean, d: Long, e: Double)
  given Parser[ParseError, ManyFields] = derived[ManyFields]

  // ============================================================================
  // Success cases
  // ============================================================================

  test("derive parser for simple case class with two fields") {
    val parser = summon[Parser[ParseError, Person]]
    parser.run("Person(Alice,30)") match {
      case Result.Success(person, consumed) =>
        assertEquals(person.name, "Alice")
        assertEquals(person.age, 30)
        assertEquals(consumed, 16)
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("derive parser for Point with integer coordinates") {
    val parser = summon[Parser[ParseError, Point]]
    parser.run("Point(10,20)") match {
      case Result.Success(point, consumed) =>
        assertEquals(point.x, 10)
        assertEquals(point.y, 20)
        assertEquals(consumed, 12)
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("derive parser for Config with String, Int, and Boolean fields") {
    val parser = summon[Parser[ParseError, Config]]
    parser.run("Config(localhost,8080,true)") match {
      case Result.Success(config, consumed) =>
        assertEquals(config.host, "localhost")
        assertEquals(config.port, 8080)
        assertEquals(config.enabled, true)
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("derive parser for Measurement with String and Double fields") {
    val parser = summon[Parser[ParseError, Measurement]]
    parser.run("Measurement(temperature,98.6)") match {
      case Result.Success(measurement, consumed) =>
        assertEquals(measurement.name, "temperature")
        assertEquals(measurement.value, 98.6)
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("derive parser for Counter with Long field") {
    val parser = summon[Parser[ParseError, Counter]]
    parser.run("Counter(1234567890)") match {
      case Result.Success(counter, consumed) =>
        assertEquals(counter.count, 1234567890L)
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("derive parser for SingleField case class") {
    val parser = summon[Parser[ParseError, SingleField]]
    parser.run("SingleField(42)") match {
      case Result.Success(sf, consumed) =>
        assertEquals(sf.value, 42)
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("derive parser for case class with many fields") {
    val parser = summon[Parser[ParseError, ManyFields]]
    parser.run("ManyFields(1,test,false,999,3.14)") match {
      case Result.Success(mf, consumed) =>
        assertEquals(mf.a, 1)
        assertEquals(mf.b, "test")
        assertEquals(mf.c, false)
        assertEquals(mf.d, 999L)
        assertEquals(mf.e, 3.14)
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("parse negative integers correctly") {
    val parser = summon[Parser[ParseError, Point]]
    parser.run("Point(-5,-10)") match {
      case Result.Success(point, consumed) =>
        assertEquals(point.x, -5)
        assertEquals(point.y, -10)
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("parse negative doubles correctly") {
    val parser = summon[Parser[ParseError, Measurement]]
    parser.run("Measurement(temp,-98.6)") match {
      case Result.Success(measurement, consumed) =>
        assertEquals(measurement.name, "temp")
        assertEquals(measurement.value, -98.6)
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("parse false boolean correctly") {
    val parser = summon[Parser[ParseError, Config]]
    parser.run("Config(host,80,false)") match {
      case Result.Success(config, consumed) =>
        assertEquals(config.host, "host")
        assertEquals(config.port, 80)
        assertEquals(config.enabled, false)
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  // ============================================================================
  // Failure cases
  // ============================================================================

  test("fail when class name is wrong") {
    val parser = summon[Parser[ParseError, Person]]
    parser.run("NotPerson(Alice,30)") match {
      case Result.Success(_, _) =>
        fail("Expected failure, got success")
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
    }
  }

  test("fail when missing opening parenthesis") {
    val parser = summon[Parser[ParseError, Person]]
    parser.run("PersonAlice,30)") match {
      case Result.Success(_, _) =>
        fail("Expected failure, got success")
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
    }
  }

  test("fail when missing closing parenthesis") {
    val parser = summon[Parser[ParseError, Person]]
    parser.run("Person(Alice,30") match {
      case Result.Success(_, _) =>
        fail("Expected failure, got success")
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
    }
  }

  test("fail when missing comma separator") {
    val parser = summon[Parser[ParseError, Person]]
    parser.run("Person(Alice30)") match {
      case Result.Success(_, _) =>
        fail("Expected failure, got success")
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
    }
  }

  test("fail when field type is incorrect") {
    val parser = summon[Parser[ParseError, Person]]
    parser.run("Person(Alice,NotANumber)") match {
      case Result.Success(_, _) =>
        fail("Expected failure, got success")
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
    }
  }

  test("fail on empty input") {
    val parser = summon[Parser[ParseError, Person]]
    parser.run("") match {
      case Result.Success(_, _) =>
        fail("Expected failure, got success")
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
    }
  }

  test("fail when too few fields provided") {
    val parser = summon[Parser[ParseError, Person]]
    parser.run("Person(Alice)") match {
      case Result.Success(_, _) =>
        fail("Expected failure, got success")
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
    }
  }

  test("fail when invalid boolean value") {
    val parser = summon[Parser[ParseError, Config]]
    parser.run("Config(host,80,yes)") match {
      case Result.Success(_, _) =>
        fail("Expected failure, got success")
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
    }
  }
}
