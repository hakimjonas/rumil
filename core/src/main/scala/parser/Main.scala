package parser

import parser.core._
import parser.syntax._

@main def testParser(): Unit = {
  println("Testing parser combinator library...")

  // Test 1: Simple number parser
  val number: Parser[ParseError, Int] =
    digit.manyNonEmpty.map(_.mkString.toInt).named("number")

  val result1 = number.run("42")
  println(s"Test 1 - Parse '42': $result1")
  assert(result1.toOption == Some(42), "Failed to parse 42")

  // Test 2: Addition
  val expr =
    for {
      n1 <- number
      _  <- char('+')
      n2 <- number
    } yield n1 + n2

  val result2 = expr.run("1+2")
  println(s"Test 2 - Parse '1+2': $result2")
  assert(result2.toOption == Some(3), "Failed to parse 1+2")

  // Test 3: String parser
  val hello   = string("hello")
  val result3 = hello.run("hello")
  println(s"Test 3 - Parse 'hello': $result3")
  assert(result3.toOption == Some("hello"), "Failed to parse hello")

  println("\nAll tests passed! ✓")
}
