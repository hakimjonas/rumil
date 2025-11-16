//> using scala "3.7.4"
//> using dep "net.ghoula::rumil-core:0.2.0"

package examples.errorrecovery

import parser.core._
import parser.syntax._

/**
 * Example: Error Recovery and Resilient Parsing
 *
 * This example demonstrates Rumil's error recovery capabilities:
 * - .attempt: Capture failures as values
 * - .recover: Provide fallback values
 * - .recoverWith: Provide fallback parsers
 * - Result.Partial: Accumulate errors while returning partial results
 */
@main def errorRecoveryExample(): Unit = {
  println("=== Error Recovery Examples ===\n")

  // Example 1: Using .attempt to capture failures
  println("--- Example 1: .attempt (Capture Failures) ---")

  val strictNumber = digit.many1.map(_.mkString.toInt)
  val lenientNumber = strictNumber.attempt

  // This fails with strict parser
  val strictResult = strictNumber.run("abc")
  println(s"Strict parser on 'abc': $strictResult")
  // Failure(List(ParseError(...)), ...)

  // This succeeds with lenient parser (returns a Result inside Success)
  val lenientResult = lenientNumber.run("abc")
  println(s"Lenient parser on 'abc': $lenientResult")
  // Success(Failure(...), 0)

  lenientResult match {
    case Result.Success(innerResult, _) =>
      innerResult match {
        case Result.Success(num, _) =>
          println(s"  → Parsed number: $num")
        case Result.Failure(errors, _) =>
          println(s"  → Failed to parse number (captured): $errors")
        case _ => ()
      }
    case _ => ()
  }

  // Example 2: Using .recover for fallback values
  println("\n--- Example 2: .recover (Fallback Values) ---")

  val numberWithDefault = strictNumber.recover { errors =>
    println(s"  Parse failed: $errors, using default value 0")
    0
  }

  val result1 = numberWithDefault.run("42")
  val result2 = numberWithDefault.run("xyz")

  println(s"Parse '42': $result1")    // Success(42, 2)
  println(s"Parse 'xyz': $result2")   // Success(0, 0) - recovered!

  // Example 3: Using .recoverWith for alternative parsers
  println("\n--- Example 3: .recoverWith (Alternative Parsers) ---")

  val hexNumber = string("0x") *> satisfy(c => c.isDigit || "abcdefABCDEF".contains(c), "hex digit")
    .many1
    .map(chars => Integer.parseInt(chars.mkString, 16))

  val decimalNumber = digit.many1.map(_.mkString.toInt)

  val flexibleNumber = hexNumber.recoverWith { _ =>
    decimalNumber
  }

  val result3 = flexibleNumber.run("0xFF")
  val result4 = flexibleNumber.run("255")
  val result5 = flexibleNumber.run("invalid")

  println(s"Parse '0xFF': $result3")      // Success(255, 4) via hex
  println(s"Parse '255': $result4")       // Success(255, 3) via decimal
  println(s"Parse 'invalid': $result5")   // Failure

  // Example 4: Parsing CSV with error recovery
  println("\n--- Example 4: Resilient CSV Parsing ---")

  // A cell that recovers from errors by using empty string
  val resilientCell = satisfy(_ != ',', "cell char")
    .many
    .map(_.mkString)
    .recover { _ => "" }

  val resilientRow = resilientCell.sepBy(char(','))
  val resilientCsv = resilientRow.endBy(char('\n'))

  val messyCsvInput = """name,age,city
alice,30,nyc
bob,invalid,sf
charlie,25,
"""

  val csvResult = resilientCsv.run(messyCsvInput)

  csvResult match {
    case Result.Success(rows, _) =>
      println(s"✓ Parsed ${rows.length} rows:")
      rows.zipWithIndex.foreach { case (row, idx) =>
        println(s"  Row $idx: ${row.mkString("[", ", ", "]")}")
      }

    case Result.Partial(rows, errors, _) =>
      println(s"⚠ Partially parsed ${rows.length} rows:")
      rows.zipWithIndex.foreach { case (row, idx) =>
        println(s"  Row $idx: ${row.mkString("[", ", ", "]")}")
      }
      println(s"  Errors: $errors")

    case Result.Failure(errors, _) =>
      println(s"✗ Failed: $errors")
  }

  // Example 5: Multiple recovery strategies
  println("\n--- Example 5: Chained Recovery ---")

  // Try: 1) hex, 2) decimal, 3) default to -1
  val robustNumber = hexNumber
    .recoverWith { _ => decimalNumber }
    .recover { _ =>
      println("  All parsing strategies failed, using -1")
      -1
    }

  val inputs = List("0x10", "42", "invalid", "0xGG")

  inputs.foreach { input =>
    val result = robustNumber.run(input)
    println(s"Parse '$input': $result")
  }

  // Example 6: Optional fields with recovery
  println("\n--- Example 6: Optional Fields ---")

  case class Config(
    host: String,
    port: Int,
    timeout: Int  // Default to 30 if missing
  )

  // Simulate parsing a config file where timeout might be missing
  val host = string("host=") *> satisfy(_ != '\n', "host char").many1.map(_.mkString)
  val port = string("port=") *> digit.many1.map(_.mkString.toInt)
  val timeout = string("timeout=") *> digit.many1.map(_.mkString.toInt)

  // Make timeout optional with default
  val timeoutWithDefault = timeout.recover { _ => 30 }

  val configParser = for {
    h <- host <* char('\n')
    p <- port <* char('\n')
    t <- timeoutWithDefault
  } yield Config(h, p, t)

  val completeConfig = """host=localhost
port=8080
timeout=60"""

  val incompleteConfig = """host=localhost
port=8080
"""

  println(s"Complete config:")
  val r1 = configParser.run(completeConfig)
  println(s"  $r1")

  println(s"Incomplete config (timeout missing):")
  val r2 = configParser.run(incompleteConfig)
  println(s"  $r2")
}
