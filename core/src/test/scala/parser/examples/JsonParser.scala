package parser.examples

import munit.FunSuite

import parser.core.*
import parser.syntax.*

// Simple JSON ADT
enum JsonValue {
  case Null
  case Bool(value: Boolean)
  case Number(value: Double)
  case Str(value: String)
  case Array(elements: List[JsonValue])
  case Object(fields: Map[String, JsonValue])
}

class JsonParserTests extends FunSuite {
  import JsonValue.*

  test("parse null") {
    val parser = string("null").as(Null)
    val result = parser.run("null")
    assertEquals(result.toOption, Some(Null))
  }

  test("parse true") {
    val parser = string("true").as(Bool(true))
    val result = parser.run("true")
    assertEquals(result.toOption, Some(Bool(true)))
  }

  test("parse false") {
    val parser = string("false").as(Bool(false))
    val result = parser.run("false")
    assertEquals(result.toOption, Some(Bool(false)))
  }

  test("parse integer") {
    val parser = digit.many1.map(chars => Number(chars.mkString.toDouble))
    val result = parser.run("42")
    assertEquals(result.toOption, Some(Number(42.0)))
  }

  test("parse float") {
    val parser =
      (digit.many1 ~ (char('.') *> digit.many1)).map { case (whole, frac) =>
        Number(s"${whole.mkString}.${frac.mkString}".toDouble)
      }
    val result = parser.run("3.14")
    assertEquals(result.toOption, Some(Number(3.14)))
  }

  test("parse string") {
    val parser =
      (char('"') *> satisfy(_ != '"', "string char").many <* char('"'))
        .map(chars => Str(chars.mkString))
    val result = parser.run("\"hello\"")
    assertEquals(result.toOption, Some(Str("hello")))
  }

  test("parse empty array") {
    val parser =
      (char('[') *> char(']')).as(Array(List()))
    val result = parser.run("[]")
    assertEquals(result.toOption, Some(Array(List())))
  }

  test("parse simple array") {
    val number = digit.many1.map(chars => Number(chars.mkString.toDouble))
    val parser =
      (char('[') *> number.sepBy(char(',')) <* char(']'))
        .map(Array.apply)
    val result = parser.run("[1,2,3]")
    assertEquals(
      result.toOption,
      Some(Array(List(Number(1), Number(2), Number(3))))
    )
  }

  test("parse empty object") {
    val parser =
      (char('{') *> char('}')).as(Object(Map()))
    val result = parser.run("{}")
    assertEquals(result.toOption, Some(Object(Map())))
  }

  test("parse simple object") {
    val parser =
      for {
        _ <- char('{')
        _ <- char('"')
        key <- satisfy(_ != '"', "char").many.map(_.mkString)
        _ <- char('"')
        _ <- char(':')
        _ <- char('"')
        value <- satisfy(_ != '"', "char").many.map(_.mkString)
        _ <- char('"')
        _ <- char('}')
      } yield Object(Map(key -> Str(value)))
    val result = parser.run("{\"name\":\"Alice\"}")
    assertEquals(
      result.toOption,
      Some(Object(Map("name" -> Str("Alice"))))
    )
  }
}
