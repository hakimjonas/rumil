package parser.interop

import parser.core.*
import parser.syntax.*

/** Primitive parsers for basic Scala types.
  *
  * These given instances provide automatic parser derivation for primitive types. They are used by
  * the derivation to build parsers for case class fields.
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

  /** Parser for String values.
    *
    * Parses one or more letters => a string. For POC purposes, this is simplified - a production
    * version would handle quoted strings, escapes, etc.
    */
  given Parser[ParseError, String] =
    letter.many1.map(_.mkString).named("String")

  /** Parser for Int values.
    *
    * Parses one or more digits and converts to Int. Handles positive integers only for POC.
    */
  given Parser[ParseError, Int] = {
    val positiveInt = digit.many1.map(_.mkString.toInt)
    val negativeInt = char('-') *> digit.many1.map(chars => -chars.mkString.toInt)
    (negativeInt | positiveInt).named("Int")
  }

  /** Parser for Boolean values.
    *
    * Parses "true" or "false" (case-sensitive).
    */
  given Parser[ParseError, Boolean] =
    (string("true").as(true) | string("false").as(false)).named("Boolean")

  /** Parser for Long values.
    *
    * Parses one or more digits and converts to Long. Handles positive and negative longs.
    */
  given Parser[ParseError, Long] = {
    val positiveLong = digit.many1.map(_.mkString.toLong)
    val negativeLong = char('-') *> digit.many1.map(chars => -chars.mkString.toLong)
    (negativeLong | positiveLong).named("Long")
  }

  /** Parser for Double values.
    *
    * Parses floating point numbers in format: [-]digits.digits Simplified for POC - doesn't handle
    * scientific notation, infinity, NaN, etc.
    */
  given Parser[ParseError, Double] = {
    val wholePart = digit.many1
    val fractionalPart = char('.') *> digit.many1
    val positiveDouble = (wholePart ~ fractionalPart).map { case (whole, frac) =>
      s"${whole.mkString}.${frac.mkString}".toDouble
    }
    val negativeDouble = char('-') *> (wholePart ~ fractionalPart).map { case (whole, frac) =>
      s"-${whole.mkString}.${frac.mkString}".toDouble
    }
    (negativeDouble | positiveDouble).named("Double")
  }

  /** Parser for Char values.
    *
    * Parses a single character enclosed in single quotes. Format: 'a', 'b', etc.
    */
  given Parser[ParseError, Char] =
    (char('\'') *> anyChar <* char('\'')).named("Char")

  /** Parser for Byte values.
    *
    * Parses one or more digits and converts to Byte. Handles positive and negative bytes.
    */
  given Parser[ParseError, Byte] = {
    val positiveByte: Parser[ParseError, Byte] = digit.many1.map(_.mkString.toByte)
    val negativeByte: Parser[ParseError, Byte] =
      char('-') *> digit.many1.map(chars => ("-" + chars.mkString).toByte)
    (negativeByte | positiveByte).named("Byte")
  }

  /** Parser for Short values.
    *
    * Parses one or more digits and converts to Short. Handles positive and negative shorts.
    */
  given Parser[ParseError, Short] = {
    val positiveShort: Parser[ParseError, Short] = digit.many1.map(_.mkString.toShort)
    val negativeShort: Parser[ParseError, Short] =
      char('-') *> digit.many1.map(chars => ("-" + chars.mkString).toShort)
    (negativeShort | positiveShort).named("Short")
  }

  /** Parser for BigInt values.
    *
    * Parses one or more digits and converts to BigInt. Handles positive and negative BigInts.
    */
  given Parser[ParseError, BigInt] = {
    val positiveBigInt = digit.many1.map(chars => BigInt(chars.mkString))
    val negativeBigInt = char('-') *> digit.many1.map(chars => BigInt("-" + chars.mkString))
    (negativeBigInt | positiveBigInt).named("BigInt")
  }

  /** Parser for BigDecimal values.
    *
    * Parses floating point numbers in format: [-]digits.digits Converts to BigDecimal for arbitrary
    * precision.
    */
  given Parser[ParseError, BigDecimal] = {
    val wholePart = digit.many1
    val fractionalPart = char('.') *> digit.many1
    val positiveBigDecimal = (wholePart ~ fractionalPart).map { case (whole, frac) =>
      BigDecimal(s"${whole.mkString}.${frac.mkString}")
    }
    val negativeBigDecimal = char('-') *> (wholePart ~ fractionalPart).map { case (whole, frac) =>
      BigDecimal(s"-${whole.mkString}.${frac.mkString}")
    }
    (negativeBigDecimal | positiveBigDecimal).named("BigDecimal")
  }

  /** Parser for Option[A] values.
    *
    * Parses explicit Scala Option syntax:
    *   - Some(value) - where value is parsed using the given parser for A
    *   - None - represents absence of a value
    *
    * Examples:
    *   - Some(42) -> Some(42)
    *   - None -> None
    */
  given [A] => (p: Parser[ParseError, A]) => Parser[ParseError, Option[A]] = {
    val someParser = string("Some(") *> p <* char(')')
    val noneParser = string("None").as(None)
    (someParser.map(Some(_)) | noneParser).named("Option")
  }

  /** Parser for List[A] values.
    *
    * Parses explicit Scala List constructor syntax:
    *   - List() - empty list
    *   - List(a) - single element
    *   - List(a,b,c) - multiple elements separated by commas
    *
    * Elements are parsed using the given parser for A.
    */
  given [A] => (p: Parser[ParseError, A]) => Parser[ParseError, List[A]] = {
    val elementsParser = p.sepBy(char(','))
    (string("List(") *> elementsParser <* char(')')).named("List")
  }

  /** Parser for Seq[A] values.
    *
    * Parses explicit Scala Seq constructor syntax:
    *   - Seq() - empty sequence
    *   - Seq(a) - single element
    *   - Seq(a,b,c) - multiple elements separated by commas
    *
    * Elements are parsed using the given parser for A.
    */
  given [A] => (p: Parser[ParseError, A]) => Parser[ParseError, Seq[A]] = {
    val elementsParser = p.sepBy(char(','))
    (string("Seq(") *> elementsParser <* char(')')).named("Seq")
  }

  /** Parser for Vector[A] values.
    *
    * Parses explicit Scala Vector constructor syntax:
    *   - Vector() - empty vector
    *   - Vector(a) - single element
    *   - Vector(a,b,c) - multiple elements separated by commas
    *
    * Elements are parsed using the given parser for A.
    */
  given [A] => (p: Parser[ParseError, A]) => Parser[ParseError, Vector[A]] = {
    val elementsParser = p.sepBy(char(','))
    (string("Vector(") *> elementsParser <* char(')'))
      .named("Vector")
      .map(_.toVector)
  }
}
