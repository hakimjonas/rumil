package parser.interop

import munit.FunSuite

import parser.core.*
import parser.interop.Parser.derived
import parser.interop.Primitives.given
import parser.syntax.*

/** Test suite for automatic parser derivation.
  *
  * Tests derivation with various case class shapes and validates that parsers are correctly
  * generated and can parse the expected format.
  */
class DerivationTests extends FunSuite {

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

  test("derive parser for simple case class with two fields") {
    val parser = summon[Parser[ParseError, Person]]
    parser.run("Person(Alice,30)") match {
      case Result.Success(person, consumed) =>
        assertEquals(person.name, "Alice")
        assertEquals(person.age, 30)
        assertEquals(consumed, 16)
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
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
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
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
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
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
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("derive parser for Counter with Long field") {
    val parser = summon[Parser[ParseError, Counter]]
    parser.run("Counter(1234567890)") match {
      case Result.Success(counter, consumed) =>
        assertEquals(counter.count, 1234567890L)
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("derive parser for SingleField case class") {
    val parser = summon[Parser[ParseError, SingleField]]
    parser.run("SingleField(42)") match {
      case Result.Success(sf, consumed) =>
        assertEquals(sf.value, 42)
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
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
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
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
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
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
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
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
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("fail when class name is wrong") {
    val parser = summon[Parser[ParseError, Person]]
    parser.run("NotPerson(Alice,30)") match {
      case Result.Success(_, _) =>
        fail("Expected failure, got success")
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
    }
  }

  test("fail when missing opening parenthesis") {
    val parser = summon[Parser[ParseError, Person]]
    parser.run("PersonAlice,30)") match {
      case Result.Success(_, _) =>
        fail("Expected failure, got success")
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
    }
  }

  test("fail when missing closing parenthesis") {
    val parser = summon[Parser[ParseError, Person]]
    parser.run("Person(Alice,30") match {
      case Result.Success(_, _) =>
        fail("Expected failure, got success")
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
    }
  }

  test("fail when missing comma separator") {
    val parser = summon[Parser[ParseError, Person]]
    parser.run("Person(Alice30)") match {
      case Result.Success(_, _) =>
        fail("Expected failure, got success")
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
    }
  }

  test("fail when field type is incorrect") {
    val parser = summon[Parser[ParseError, Person]]
    parser.run("Person(Alice,NotANumber)") match {
      case Result.Success(_, _) =>
        fail("Expected failure, got success")
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
    }
  }

  test("fail on empty input") {
    val parser = summon[Parser[ParseError, Person]]
    parser.run("") match {
      case Result.Success(_, _) =>
        fail("Expected failure, got success")
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
    }
  }

  test("fail when too few fields provided") {
    val parser = summon[Parser[ParseError, Person]]
    parser.run("Person(Alice)") match {
      case Result.Success(_, _) =>
        fail("Expected failure, got success")
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
    }
  }

  test("fail when invalid boolean value") {
    val parser = summon[Parser[ParseError, Config]]
    parser.run("Config(host,80,yes)") match {
      case Result.Success(_, _) =>
        fail("Expected failure, got success")
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
    }
  }

  case class PersonWithAge(name: String, age: Option[Int])
  given Parser[ParseError, PersonWithAge] = derived[PersonWithAge]

  case class OptionalFields(x: Option[Int], y: Option[String])
  given Parser[ParseError, OptionalFields] = derived[OptionalFields]

  test("parse case class with Some(value)") {
    val parser = summon[Parser[ParseError, PersonWithAge]]
    parser.run("PersonWithAge(Alice,Some(30))") match {
      case Result.Success(person, _) =>
        assertEquals(person.name, "Alice")
        assertEquals(person.age, Some(30))
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("parse case class with None") {
    val parser = summon[Parser[ParseError, PersonWithAge]]
    parser.run("PersonWithAge(Bob,None)") match {
      case Result.Success(person, _) =>
        assertEquals(person.name, "Bob")
        assertEquals(person.age, None)
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("parse case class with multiple Option fields") {
    val parser = summon[Parser[ParseError, OptionalFields]]
    parser.run("OptionalFields(Some(42),Some(test))") match {
      case Result.Success(fields, _) =>
        assertEquals(fields.x, Some(42))
        assertEquals(fields.y, Some("test"))
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("parse case class with mixed Some and None") {
    val parser = summon[Parser[ParseError, OptionalFields]]
    parser.run("OptionalFields(Some(42),None)") match {
      case Result.Success(fields, _) =>
        assertEquals(fields.x, Some(42))
        assertEquals(fields.y, None)
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("fail on invalid Option syntax") {
    val parser = summon[Parser[ParseError, PersonWithAge]]
    parser.run("PersonWithAge(Alice,Just(30))") match {
      case Result.Success(_, _) =>
        fail("Expected failure, got success")
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
    }
  }

  case class Tags(items: List[String])
  given Parser[ParseError, Tags] = derived[Tags]

  case class Numbers(values: Seq[Int])
  given Parser[ParseError, Numbers] = derived[Numbers]

  case class Coordinates(points: Vector[Int])
  given Parser[ParseError, Coordinates] = derived[Coordinates]

  test("parse List with multiple elements") {
    val parser = summon[Parser[ParseError, Tags]]
    parser.run("Tags(List(scala,fp,parsing))") match {
      case Result.Success(tags, _) =>
        assertEquals(tags.items, List("scala", "fp", "parsing"))
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("parse empty List") {
    val parser = summon[Parser[ParseError, Tags]]
    parser.run("Tags(List())") match {
      case Result.Success(tags, _) =>
        assertEquals(tags.items, List.empty[String])
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("parse Seq with elements") {
    val parser = summon[Parser[ParseError, Numbers]]
    parser.run("Numbers(Seq(1,2,3,4,5))") match {
      case Result.Success(numbers, _) =>
        assertEquals(numbers.values, Seq(1, 2, 3, 4, 5))
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("parse Vector with elements") {
    val parser = summon[Parser[ParseError, Coordinates]]
    parser.run("Coordinates(Vector(10,20,30))") match {
      case Result.Success(coords, _) =>
        assertEquals(coords.points, Vector(10, 20, 30))
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("fail on unclosed List") {
    val parser = summon[Parser[ParseError, Tags]]
    parser.run("Tags(List(a,b,c)") match {
      case Result.Success(_, _) =>
        fail("Expected failure, got success")
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
    }
  }

  test("parse List of complex types") {
    val parser = summon[Parser[ParseError, Numbers]]
    parser.run("Numbers(Seq(-5,0,10,-20))") match {
      case Result.Success(numbers, _) =>
        assertEquals(numbers.values, Seq(-5, 0, 10, -20))
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  case class Address(street: String, city: String)
  given Parser[ParseError, Address] = derived[Address]

  case class PersonWithAddress(name: String, address: Address)
  given Parser[ParseError, PersonWithAddress] = derived[PersonWithAddress]

  case class Company(name: String, address: Address)
  given Parser[ParseError, Company] = derived[Company]

  case class Department(name: String, company: Company)
  given Parser[ParseError, Department] = derived[Department]

  case class Employee(name: String, address: Option[Address])
  given Parser[ParseError, Employee] = derived[Employee]

  test("parse nested case class") {
    val parser = summon[Parser[ParseError, PersonWithAddress]]
    parser.run("PersonWithAddress(Alice,Address(MainSt,NYC))") match {
      case Result.Success(person, _) =>
        assertEquals(person.name, "Alice")
        assertEquals(person.address.street, "MainSt")
        assertEquals(person.address.city, "NYC")
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("parse doubly nested case classes") {
    val parser = summon[Parser[ParseError, Department]]
    parser.run("Department(Engineering,Company(Acme,Address(MainSt,NYC)))") match {
      case Result.Success(dept, _) =>
        assertEquals(dept.name, "Engineering")
        assertEquals(dept.company.name, "Acme")
        assertEquals(dept.company.address.street, "MainSt")
        assertEquals(dept.company.address.city, "NYC")
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("parse nested with Option") {
    val parser = summon[Parser[ParseError, Employee]]
    parser.run("Employee(Bob,Some(Address(OakAve,SF)))") match {
      case Result.Success(emp, _) =>
        assertEquals(emp.name, "Bob")
        assertEquals(emp.address.map(_.street), Some("OakAve"))
        assertEquals(emp.address.map(_.city), Some("SF"))
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("parse nested with None") {
    val parser = summon[Parser[ParseError, Employee]]
    parser.run("Employee(Charlie,None)") match {
      case Result.Success(emp, _) =>
        assertEquals(emp.name, "Charlie")
        assertEquals(emp.address, None)
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("fail on mismatched nested types") {
    val parser = summon[Parser[ParseError, PersonWithAddress]]
    parser.run("PersonWithAddress(Alice,Point(1,2))") match {
      case Result.Success(_, _) =>
        fail("Expected failure, got success")
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        assert(errors.nonEmpty)
    }
  }

  case class CharValue(c: Char)
  given Parser[ParseError, CharValue] = derived[CharValue]

  case class ByteAndShort(b: Byte, s: Short)
  given Parser[ParseError, ByteAndShort] = derived[ByteAndShort]

  case class BigIntValue(value: BigInt)
  given Parser[ParseError, BigIntValue] = derived[BigIntValue]

  case class BigDecimalValue(value: BigDecimal)
  given Parser[ParseError, BigDecimalValue] = derived[BigDecimalValue]

  test("parse Char field") {
    val parser = summon[Parser[ParseError, CharValue]]
    parser.run("CharValue('a')") match {
      case Result.Success(cv, _) =>
        assertEquals(cv.c, 'a')
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("parse Byte and Short fields") {
    val parser = summon[Parser[ParseError, ByteAndShort]]
    parser.run("ByteAndShort(127,32000)") match {
      case Result.Success(bs, _) =>
        assertEquals(bs.b, 127.toByte)
        assertEquals(bs.s, 32000.toShort)
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("parse BigInt field") {
    val parser = summon[Parser[ParseError, BigIntValue]]
    parser.run("BigIntValue(123456789012345678901234567890)") match {
      case Result.Success(bi, _) =>
        assertEquals(bi.value, BigInt("123456789012345678901234567890"))
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("parse BigDecimal field") {
    val parser = summon[Parser[ParseError, BigDecimalValue]]
    parser.run("BigDecimalValue(123.456)") match {
      case Result.Success(bd, _) =>
        assertEquals(bd.value, BigDecimal("123.456"))
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  case class User(
    name: String,
    age: Option[Int],
    emails: List[String],
    address: Option[Address]
  )
  given Parser[ParseError, User] = derived[User]

  case class ComplexNested(
    id: Int,
    tags: Vector[String],
    metadata: Option[Config]
  )
  given Parser[ParseError, ComplexNested] = derived[ComplexNested]

  case class MixedTypes(
    name: String,
    count: BigInt,
    precision: BigDecimal,
    flag: Option[Boolean],
    items: List[Int]
  )
  given Parser[ParseError, MixedTypes] = derived[MixedTypes]

  test("parse User with all fields populated") {
    val parser = summon[Parser[ParseError, User]]
    parser.run("User(Alice,Some(30),List(a,b),Some(Address(Main,NYC)))") match {
      case Result.Success(user, _) =>
        assertEquals(user.name, "Alice")
        assertEquals(user.age, Some(30))
        assertEquals(user.emails, List("a", "b"))
        assertEquals(user.address.map(_.street), Some("Main"))
        assertEquals(user.address.map(_.city), Some("NYC"))
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("parse User with None values and empty lists") {
    val parser = summon[Parser[ParseError, User]]
    parser.run("User(Bob,None,List(),None)") match {
      case Result.Success(user, _) =>
        assertEquals(user.name, "Bob")
        assertEquals(user.age, None)
        assertEquals(user.emails, List.empty[String])
        assertEquals(user.address, None)
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("parse ComplexNested with nested optional case class") {
    val parser = summon[Parser[ParseError, ComplexNested]]
    parser.run("ComplexNested(42,Vector(alpha,beta),Some(Config(localhost,8080,true)))") match {
      case Result.Success(cn, _) =>
        assertEquals(cn.id, 42)
        assertEquals(cn.tags, Vector("alpha", "beta"))
        assert(cn.metadata.isDefined)
        assertEquals(cn.metadata.get.host, "localhost")
        assertEquals(cn.metadata.get.port, 8080)
        assertEquals(cn.metadata.get.enabled, true)
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("parse MixedTypes with all primitive variations") {
    val parser = summon[Parser[ParseError, MixedTypes]]
    parser.run("MixedTypes(test,999999999,123.456,Some(true),List(1,2,3))") match {
      case Result.Success(mt, _) =>
        assertEquals(mt.name, "test")
        assertEquals(mt.count, BigInt("999999999"))
        assertEquals(mt.precision, BigDecimal("123.456"))
        assertEquals(mt.flag, Some(true))
        assertEquals(mt.items, List(1, 2, 3))
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }

  test("parse MixedTypes with None and negative values") {
    val parser = summon[Parser[ParseError, MixedTypes]]
    parser.run("MixedTypes(data,-12345,-99.99,None,List(-1,-2))") match {
      case Result.Success(mt, _) =>
        assertEquals(mt.name, "data")
        assertEquals(mt.count, BigInt("-12345"))
        assertEquals(mt.precision, BigDecimal("-99.99"))
        assertEquals(mt.flag, None)
        assertEquals(mt.items, List(-1, -2))
      case Result.Partial(_, errors, _) =>
        fail(s"Expected success, got partial with errors: $errors")
      case Result.Failure(errors, _) =>
        fail(s"Expected success, got errors: $errors")
    }
  }
}
