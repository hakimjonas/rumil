package parser.interop

import parser.core._
import parser.syntax._

/**
 * Primitive parsers for basic Scala types.
 *
 * These given instances provide automatic parser derivation for primitive types.
 * They are used by the derivation macro to build parsers for case class fields.
 *
 * Example:
 * {{{
 * import parser.interop.Primitives.given
 *
 * case class Person(name: String, age: Int)
 * given Parser[ParseError, Person] = Parser.derived[Person]
 * }}}
 */
object Primitives {

  /**
   * Parser for String values.
   *
   * Parses one or more letters as a string.
   * For POC purposes, this is simplified - a production version would handle
   * quoted strings, escapes, etc.
   */
  given Parser[ParseError, String] =
    letter.many1.map(_.mkString).named("String")

  /**
   * Parser for Int values.
   *
   * Parses one or more digits and converts to Int.
   * Handles positive integers only for POC.
   */
  given Parser[ParseError, Int] = {
    val positiveInt = digit.many1.map(_.mkString.toInt)
    val negativeInt = char('-') *> digit.many1.map(chars => -chars.mkString.toInt)
    (negativeInt | positiveInt).named("Int")
  }

  /**
   * Parser for Boolean values.
   *
   * Parses "true" or "false" (case-sensitive).
   */
  given Parser[ParseError, Boolean] =
    (string("true").as(true) | string("false").as(false)).named("Boolean")

  /**
   * Parser for Long values.
   *
   * Parses one or more digits and converts to Long.
   * Handles positive and negative longs.
   */
  given Parser[ParseError, Long] = {
    val positiveLong = digit.many1.map(_.mkString.toLong)
    val negativeLong = char('-') *> digit.many1.map(chars => -chars.mkString.toLong)
    (negativeLong | positiveLong).named("Long")
  }

  /**
   * Parser for Double values.
   *
   * Parses floating point numbers in format: [-]digits.digits
   * Simplified for POC - doesn't handle scientific notation, infinity, NaN, etc.
   */
  given Parser[ParseError, Double] = {
    val wholePart      = digit.many1
    val fractionalPart = char('.') *> digit.many1
    val positiveDouble = (wholePart ~ fractionalPart).map { case (whole, frac) =>
      s"${whole.mkString}.${frac.mkString}".toDouble
    }
    val negativeDouble = char('-') *> (wholePart ~ fractionalPart).map { case (whole, frac) =>
      s"-${whole.mkString}.${frac.mkString}".toDouble
    }
    (negativeDouble | positiveDouble).named("Double")
  }
}
