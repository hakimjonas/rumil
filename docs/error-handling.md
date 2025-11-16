# Error Handling and Resilient Parsing

## Overview

Rumil provides comprehensive error handling capabilities including:

- **Multi-error accumulation** - collect all errors, not just the first
- **Error recovery** - continue parsing after failures
- **Position tracking** - precise line, column, and offset information
- **Partial results** - return what was successfully parsed alongside errors

## The Result Type

```scala
enum Result[+E, +A] {
  case Success(value: A, consumed: Int)
  case Failure(errors: List[E], furthest: Location)
  case Partial(value: A, errors: List[E], consumed: Int)
}
```

- **Success**: Parsing succeeded completely
- **Failure**: Parsing failed, no value returned
- **Partial**: Parsing succeeded but with errors (resilient parsing)

## Error Recovery Combinators

### .attempt - Capture Failures as Values

```scala
val lenientNumber = strictNumber.attempt

lenientNumber.run("abc") match {
  case Success(innerResult, _) =>
    innerResult match {
      case Failure(errors, _) => println(s"Captured error: $errors")
      case Success(n, _) => println(s"Parsed: $n")
    }
}
```

### .recover - Provide Fallback Values

```scala
val numberWithDefault = strictNumber.recover { errors =>
  println(s"Parse failed: $errors, using default 0")
  0
}

numberWithDefault.run("xyz")  // Success(0, 0)
```

### .recoverWith - Alternative Parsers

```scala
val flexibleNumber = hexNumber.recoverWith { _ =>
  decimalNumber
}

flexibleNumber.run("0xFF")  // Try hex first
flexibleNumber.run("255")   // Fall back to decimal
```

## Position Tracking

Every error includes precise position information:

```scala
type Location = (line: Int, column: Int, offset: Int)

case class ParseError(
  expected: String,
  actual: Option[String],
  location: Location
)
```

Example:

```
Error at line 3, column 15, offset 42:
Expected: "number"
Found: "abc"
```

## Multi-Error Accumulation

Traditional parsers fail on the first error. Rumil can collect all errors:

```scala
case class Config(port: Int, host: String, timeout: Int)

// Even if port parsing fails, continue to parse host and timeout
// Result.Partial will contain what succeeded and list all errors
```

This is crucial for:
- **IDE tooling** - show all errors at once
- **Data validation** - report all problems to the user
- **Batch processing** - log all invalid records

## Best Practices

### 1. Handle All Result Cases

```scala
// ✓ Good
parser.run(input) match {
  case Success(value, _) => // handle success
  case Failure(errors, _) => // handle failure
  case Partial(value, errors, _) => // handle partial
}

// ✗ Bad - might crash on Partial or Failure
val Success(value, _) = parser.run(input)
```

### 2. Use .named() for Better Errors

```scala
val port = digit.many1.map(_.mkString.toInt).named("port number")

// Error messages will say "Expected: port number" instead of generic errors
```

### 3. Provide Context in Custom Errors

```scala
case class ValidationError(message: String, location: Location)

def validateAge(age: Int, location: Location): Result[ValidationError, Int] =
  if (age >= 0 && age <= 150)
    Success(age, 0)
  else
    Failure(List(ValidationError(s"Age $age is out of range", location)), location)
```

## See Also

- **[Getting Started](getting-started.md)** - Basic error handling
- **[Examples: Error Recovery](../examples/error-recovery/)** - Runnable examples
- **[Debugging Guide](debugging.md)** - Finding the source of errors
