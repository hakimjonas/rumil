//> using scala "3.7.4"
//> using dep "net.ghoula::rumil-core:1.0.0-alpha"

package examples.debuggingparsers

import parser.core.*
import parser.syntax.*

/**
 * Example: Debugging Parsers with .trace() and .debug()
 *
 * This example demonstrates how to use Rumil's debugging combinators
 * to understand parser behavior and diagnose issues.
 */
@main def debuggingExample(): Unit = {
  println("=== Debugging Parsers Examples ===")
  println("(Debug output goes to stderr)\n")

  // Example 1: Basic tracing
  println("--- Example 1: Basic .trace() ---")
  println("Input: '42'\n")

  val number = digit.many1.trace("number").map(_.mkString.toInt)
  val result1 = number.run("42")

  println(s"Result: $result1\n")
  // Stderr shows:
  // [TRACE] number: trying at offset 0
  // [TRACE] number: success, consumed 2 chars

  // Example 2: Debugging parsed values
  println("--- Example 2: Using .debug() to see values ---")
  println("Input: '1+2'\n")

  val num = digit.many1.map(_.mkString.toInt).debug("parsed-number")
  val plus = char('+').debug("plus-sign")
  val expr = (num ~ plus ~ num).debug("full-expression")

  val result2 = expr.run("1+2")

  println(s"Result: $result2\n")
  // Stderr shows:
  // [DEBUG] parsed-number: trying at offset 0
  // [DEBUG] parsed-number: success, parsed 1
  // [DEBUG] plus-sign: trying at offset 1
  // [DEBUG] plus-sign: success, parsed +
  // [DEBUG] parsed-number: trying at offset 2
  // [DEBUG] parsed-number: success, parsed 2
  // [DEBUG] full-expression: trying at offset 0
  // [DEBUG] full-expression: success, parsed ((1,+),2)

  // Example 3: Debugging alternatives (shows backtracking)
  println("--- Example 3: Debugging Alternatives ---")
  println("Input: 'false'\n")

  val trueParser = string("true").trace("try-true").as(true)
  val falseParser = string("false").trace("try-false").as(false)
  val bool = (trueParser | falseParser).debug("boolean")

  val result3 = bool.run("false")

  println(s"Result: $result3\n")
  // Stderr shows:
  // [TRACE] try-true: trying at offset 0
  // [TRACE] try-true: failure
  // [TRACE] try-false: trying at offset 0
  // [TRACE] try-false: success, consumed 5 chars
  // [DEBUG] boolean: trying at offset 0
  // [DEBUG] boolean: success, parsed false

  // Example 4: Debugging a complex parser
  println("--- Example 4: Complex Expression Parser ---")
  println("Input: '(5+3)'\n")

  val digitParser = digit.trace("digit").map(_.toString.toInt)
  val parenOpen = char('(').trace("open-paren")
  val parenClose = char(')').trace("close-paren")
  val plusSign = char('+').trace("plus")

  val simpleExpr = (digitParser ~ plusSign ~ digitParser).debug("simple-add")
    .map { case ((a, _), b) => a + b }

  val parenExpr = (parenOpen *> simpleExpr <* parenClose).debug("paren-expr")

  val result4 = parenExpr.run("(5+3)")

  println(s"Result: $result4\n")
  // Stderr shows the execution order clearly

  // Example 5: Debugging failures
  println("--- Example 5: Debugging Failures ---")
  println("Input: 'abc' (expected number)\n")

  val strictNum = digit.many1.trace("strict-number").map(_.mkString.toInt)
  val result5 = strictNum.run("abc")

  println(s"Result: $result5\n")
  // Stderr shows:
  // [TRACE] strict-number: trying at offset 0
  // [TRACE] strict-number: failure

  // Example 6: Multiple debug points in a pipeline
  println("--- Example 6: Pipeline Debugging ---")
  println("Input: 'x=42'\n")

  val varName = letter.trace("var-name")
  val equals = char('=').trace("equals")
  val value = digit.many1.trace("value").map(_.mkString.toInt)

  val assignment = (varName ~ equals ~ value).debug("assignment")
    .map { case ((name, _), value) => s"$name -> $value" }

  val result6 = assignment.run("x=42")

  println(s"Result: $result6\n")

  // Example 7: Performance debugging
  println("--- Example 7: Performance Debugging ---")
  println("(Looking for repeated attempts)\n")
  println("Input: 'aaab'\n")

  // A poorly written parser that might backtrack a lot
  val inefficientParser = (
    string("aaaa").trace("try-4-as") |
    string("aaa").trace("try-3-as") |
    string("aa").trace("try-2-as") |
    string("a").trace("try-1-a")
  ).debug("pick-best")

  val result7 = inefficientParser.run("aaab")

  println(s"Result: $result7")
  println()
  // Stderr will show all the attempts and backtracking

  // Summary
  println("=== Summary ===")
  println("Debug combinators help you:")
  println("  1. Understand execution order")
  println("  2. See what values are parsed")
  println("  3. Track down failures")
  println("  4. Identify performance issues")
  println("  5. Verify backtracking behavior")
  println()
  println("Remember: Remove debug calls in production code!")
}
