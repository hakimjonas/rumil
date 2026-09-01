# Rumil

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A parser combinator library for Scala 3 with lossless syntax trees, error recovery, and left-recursion support.

## Overview

Rumil is a parser combinator library for Scala 3. Parsers are pure, immutable values combined with operators; running one returns a `Result` that carries a value, positioned errors, or both (resilient parsing with `Partial`).

## Features

- Parsers are immutable, composable values
- 40+ combinators for building complex parsers
- **Left Recursion Support** — write natural grammars via the `rule` combinator (seed-growth algorithm)
- **Stack-Safe Parsing** — sequential chains, `flatMap` chains, deep `many`, and recursive grammars all run on a heap trampoline: a 7,000,000-parser `~` chain and 200,000-deep structural nesting pass in the test suite
- Error tracking with line, column, and offset information
- Memoized interpreter (`memoize`) and left-recursive rules (`rule`)
- Monadic interface with for-comprehension support
- Green/red lossless syntax trees with splicing, and an incremental parser

## Installation

Add to your `build.sbt`:

```scala
// Core parser combinators (required)
libraryDependencies += "net.ghoula" %% "rumil-core" % "1.0.0-alpha"

// Parsers for JSON, XML, TOML, CSV, YAML, Protobuf, XPath (optional)
libraryDependencies += "net.ghoula" %% "rumil-parsers" % "1.0.0-alpha"

// Structured decoding of the parsed ASTs into case classes is sarati's
// codec layer (net.ghoula::sarati). The rumil-interop module is deprecated
// for 0.4.0 and its codec API is removed; only its direct text-to-case-class
// derivation remains.
libraryDependencies += "net.ghoula" %% "sarati" % "1.0.0-alpha"
```

Or with scala-cli:

```scala
//> using dep "net.ghoula::rumil-core:1.0.0-alpha"
//> using dep "net.ghoula::rumil-parsers:1.0.0-alpha"
//> using dep "net.ghoula::sarati:1.0.0-alpha"
```

> **Deprecation notice:** the `rumil-interop` module (`parser.interop`) is deprecated for 0.4.0
> and scheduled for removal in 1.0 — it is a pre-Sarati twin of the codec layer, and its
> `Decoder`/`Encoder`/`FieldTransformer` API has already been removed. Use
> [sarati](https://github.com/hakimjonas/sarati) (`net.ghoula:sarati`) instead: the same derivation
> shape, with the Eval-trampoline stack safety and typed derivation via inline metaprogramming.

## Philosophy: Two Paths to Parsing

Rumil offers two complementary approaches:

### The Structural Way

Pure combinators with named tuples and enums. Maximum control and portability.

- **Use when:** Building language tooling, need lossless trees, maximum type safety
- **Style:** Explicit, composable, portable

### The Idiomatic Way

Automatic derivation for Scala case classes. Maximum convenience.

- **Use when:** Standard data parsing, REST APIs, configuration files
- **Style:** Ergonomic, concise, Scala-friendly

Both approaches use the same core library - choose based on your needs.

## Quick Start

### The Structural Way

```scala
import parser.core._
import parser.syntax._

// Parse a simple number
val number = digit.many1.map(_.mkString.toInt)
number.run("42")  // Success(42, 2)

// Parse arithmetic expressions
val expr = number.chainLeft1(char('+').as((a: Int, b: Int) => a + b))
expr.run("1+2+3")  // Success(6, 5)

// Combine parsers with operators
val pair = char('(') *> number ~ (char(',') *> number) <* char(')')
pair.run("(1,2)")  // Success((1, 2), 7)

// Left-recursive grammars
lazy val expr: Parser[ParseError, Expr] = rule {
  (expr ~ char('+') ~ term).map { case ((l, _), r) => Add(l, r) } |
  term
}
```

### The Idiomatic Way

```scala
import parser.core._
import net.ghoula.sarati.codec.{Decoder, JsonDecoders.given}
import parsers.json.{parseJson, JsonValue}

// Parse JSON to case classes with automatic derivation
case class User(name: String, age: Int, admin: Boolean)
given Decoder[JsonValue, User] = Decoder.derived

val input = """{"name": "Alice", "age": 30, "admin": true}"""

// Parse JSON string to JsonValue
val jsonResult = parseJson(input)

// Decode JsonValue to case class
val userResult = jsonResult.flatMap(json =>
  Decoder[JsonValue, User].decode(json)
)
```

## Core Concepts

### Parsers

A `Parser[E, A]` consumes input and either:
- Succeeds with a value of type `A`
- Fails with an error of type `E`

Parsers are pure descriptions - they don't execute until you call `.run(input)`.

### Results

Running a parser produces a `Result[E, A]`:

```scala
enum Result[+E, +A] {
  case Success(value: A, consumed: Int)
  case Partial(value: A, errors: List[E], consumed: Int)  // For resilient parsing
  case Failure(errors: List[E], furthest: Location)
}
```

### Combinators

Build complex parsers from simple ones:

```scala
// Sequence two parsers
val ab = char('a') ~ char('b')  // Parser[(Char, Char)]

// Alternative parsers
val aOrB = char('a') | char('b')  // Parser[Char]

// Repetition
val many = char('a').many           // Parser[List[Char]]
val some = char('a').many1   // Parser[List[Char]] (at least one)

// Transform results
val upper = letter.map(_.toUpper)  // Parser[Char]

// Sequence with for-comprehension
val parser = for {
  a <- char('a')
  b <- char('b')
} yield (a, b)
```

## Examples

### Parsing JSON

#### The Structural Way

Explicit control using combinators and named tuples:

```scala
import parser.core._
import parser.syntax._

enum JsonValue {
  case Null
  case Bool(value: Boolean)
  case Number(value: Double)
  case Str(value: String)
  case Array(elements: List[JsonValue])
  case Object(fields: Map[String, JsonValue])
}

val jsonNull = string("null").as(JsonValue.Null)
val jsonBool =
  string("true").as(JsonValue.Bool(true)) |
  string("false").as(JsonValue.Bool(false))

val jsonNumber = digit.many1.map { chars =>
  JsonValue.Number(chars.mkString.toDouble)
}

val jsonString =
  (char('"') *> satisfy(_ != '"', "string char").many <* char('"'))
    .map(chars => JsonValue.Str(chars.mkString))
```

**When to use:** Building JSON tools, need custom representations, maximum control

#### The Idiomatic Way

Automatic parsing to case classes:

```scala
import parser.core._
import net.ghoula.sarati.codec.{Decoder, JsonDecoders.given}
import parsers.json.{parseJson, JsonValue}

case class Person(name: String, age: Int)
given Decoder[JsonValue, Person] = Decoder.derived

val input = """{"name": "Alice", "age": 30}"""
val jsonResult = parseJson(input)
val personResult = jsonResult.flatMap(json =>
  Decoder[JsonValue, Person].decode(json)
)

// With nested structures
case class Address(street: String, city: String, zip: String)
case class User(name: String, email: String, address: Address)

given Decoder[JsonValue, Address] = Decoder.derived
given Decoder[JsonValue, User] = Decoder.derived

val nestedInput = """{"name": "Bob", "email": "bob@example.com", "address": {"street": "123 Main St", "city": "Springfield", "zip": "12345"}}"""
val nestedJsonResult = parseJson(nestedInput)
val userResult = nestedJsonResult.flatMap(json =>
  Decoder[JsonValue, User].decode(json)
)
```

**When to use:** REST APIs, configuration parsing, standard CRUD

### Arithmetic with Precedence

#### The Structural Way

```scala
import parser.core._
import parser.syntax._

// Direct left recursion - "just works" with rule combinator!
lazy val expr: Parser[ParseError, Int] = rule {
  val addSub = for {
    left  <- expr
    op    <- char('+') | char('-')
    right <- term
  } yield if (op == '+') left + right else left - right

  addSub | term
}

lazy val term: Parser[ParseError, Int] = rule {
  val mulDiv = for {
    left  <- term
    op    <- char('*') | char('/')
    right <- factor
  } yield if (op == '*') left * right else left / right

  mulDiv | factor
}

lazy val factor: Parser[ParseError, Int] = {
  val number = digit.many1.map(_.mkString.toInt)
  val parens = char('(') *> defer(expr) <* char(')')
  number | parens
}

// Correctly handles precedence and left-associativity
expr.run("2+3*4")   // Success(14, 5)   // 2 + (3*4)
expr.run("(2+3)*4") // Success(20, 7)   // (2+3) * 4
expr.run("5-3-1")   // Success(1, 5)    // (5-3) - 1, not 5 - (3-1)
```

**When to use:** Building language parsers, need custom AST representation

#### The Idiomatic Way

```scala
import parser.core._
import parser.syntax._

// For now, use the structural approach for expression parsing
// Automatic derivation for expression ASTs is planned for a future release

// See the structural example above for a working implementation
```

**When to use:** Calculator apps, expression evaluators (use structural approach for now)

### CSV Parser

#### The Structural Way

```scala
import parser.core._
import parser.syntax._

val cell = satisfy(_ != ',', "cell char").many.map(_.mkString)
val row = cell.separatedBy(char(','))
val csv = row.endedBy(char('\n'))

val input = """name,age,city
alice,30,nyc
bob,25,sf
"""

csv.run(input)
// Success(
//   List(
//     List("name", "age", "city"),
//     List("alice", "30", "nyc"),
//     List("bob", "25", "sf")
//   ),
//   consumed
// )
```

**When to use:** Custom CSV dialects, need raw string data

#### The Idiomatic Way

```scala
// CSV parsing with automatic case class derivation is planned for a future release
// For now, use the structural approach shown above to parse CSV files
// Then manually map the parsed data to case classes

import parser.core._
import parser.syntax._

case class Person(name: String, age: Int, city: String)

// Parse using structural approach
val cell = satisfy(_ != ',', "cell char").many.map(_.mkString)
val row = cell.separatedBy(char(','))
val csv = row.endedBy(char('\n'))

// Then map to case classes
val result = csv.run(input).map { rows =>
  rows.tail.map { row =>
    Person(row(0), row(1).toInt, row(2))
  }
}
```

**When to use:** Standard CSV files, database imports, data analysis

### Customizing Field Mapping

Field-name mapping and field exclusion belong to the codec layer (sarati), whose `FieldTransformer` trait is consulted by `Encoder.derived`/`Decoder.derived`:

```scala
import net.ghoula.sarati.codec.{FieldTransformer, FieldTransformers}

// Built-in transformers for naming conventions
FieldTransformers.SnakeCase           // camelCase → snake_case
FieldTransformers.KebabCase           // camelCase → kebab-case
FieldTransformers.ScreamingSnakeCase  // camelCase → SCREAMING_SNAKE_CASE

// Custom transformer example
val customTransformer = new FieldTransformer {
  def transformFieldName(fieldName: String): String =
    s"api_$fieldName"  // Add prefix to all fields

  def shouldIncludeField(fieldName: String): Boolean =
    !fieldName.startsWith("internal")  // Skip internal fields
}

// Example: converting names
FieldTransformers.SnakeCase.transformFieldName("userName")  // "user_name"
FieldTransformers.KebabCase.transformFieldName("isAdmin")   // "is-admin"
```

An excluded field (`shouldIncludeField` returning false) is not written on encode and decodes exactly like a missing key: `None` for an `Option` field, a `MissingField` error for a required one.

**Why a transformer instead of baked-in annotations?**

A decoder maps fields; it does not need to own an annotation API. A transformer is:
- a clean extension point (two methods),
- no commitment to specific annotation APIs,
- user-controlled field mapping logic.

Scala 3 annotation examples (`@Rename`, `@Ignore`, `@Aliases`) live in rumil's `parser.interop.examples` as implementation templates for annotation-driven transformers.

## Choosing an Approach

| Requirement | Recommended Approach |
|-------------|---------------------|
| Parsing JSON to case classes | Idiomatic (Decoder.derived) |
| Building language tools | Structural |
| Lossless syntax trees | Structural |
| REST API parsing | Idiomatic (Decoder.derived) |
| Custom data structures | Structural |
| Maximum type safety | Structural |
| Error recovery / resilient parsing | Structural |
| Debugging parsers | Both (debug combinators work everywhere) |
| IDE tooling / lossless trees | Structural |

## API Reference

### Primitive Parsers

| Function                  | Description                               |
|---------------------------|-------------------------------------------|
| `char(c)`                 | Matches a specific character              |
| `string(s)`               | Matches an exact string                   |
| `satisfy(pred, expected)` | Matches characters satisfying a predicate |
| `digit`                   | Matches 0-9                               |
| `letter`                  | Matches a-z, A-Z                          |
| `alphaNum`                | Matches letters or digits                 |
| `whitespace`              | Matches whitespace characters             |
| `anyChar`                 | Matches any single character              |
| `eof`                     | Succeeds only at end of input             |

### Combinators

| Combinator      | Description                             |
|-----------------|-----------------------------------------|
| `p1 ~ p2`       | Sequence: parse p1 then p2, return both |
| `p1 <* p2`      | Parse both, keep left result            |
| `p1 *> p2`      | Parse both, keep right result           |
| `p1 \| p2`      | Alternative: try p1, if it fails try p2 |
| `p.map(f)`      | Transform parser result                 |
| `p.flatMap(f)`  | Monadic sequencing                      |
| `p.many`        | Zero or more repetitions                |
| `p.many1`       | One or more repetitions                 |
| `p.optional`    | Zero or one occurrence                  |
| `p.separatedBy(sep)`  | Parse p separated by sep                |
| `p.separatedByNonEmpty(sep)` | Parse p separated by sep (at least one) |
| `p.endedBy(end)`  | Parse p terminated by end               |
| `p.count(n)`    | Exactly n repetitions                   |
| `p.chainLeft1(op)` | Left-associative operator chain         |
| `p.chainRight1(op)` | Right-associative operator chain        |
| `rule { p }`    | Memoized parser with left recursion support |
| `defer(p)`      | Lazy evaluation for recursive parsers   |

### Error Handling

| Function           | Description                            |
|--------------------|----------------------------------------|
| `p.attempt`        | Capture result as value (never fails)  |
| `p.recover(f)`     | Provide fallback value on failure      |
| `p.recoverWith(f)` | Provide fallback parser on failure     |
| `p.orElse(fallback)` | Fast alternation: try fallback on failure (no error tracking) |
| `p.recover(fallback)` | Alternation with error tracking: fallback hit returns `Partial` |
| `p.expect(msg)`    | Replace errors with custom message     |
| `p.named(name)`    | Label parser for better error messages |
| `lookAhead(p)`     | Parse without consuming input          |
| `notFollowedBy(p)` | Succeed only if p fails                |

### Debugging

| Function        | Description                                    |
|-----------------|------------------------------------------------|
| `p.trace(name)` | Print trace messages showing parse progress    |
| `p.debug(name)` | Print debug messages with parsed values/errors |

The debugging combinators help you understand parser behavior during development:

```scala
import parser.core._
import parser.syntax._

// Trace shows parse attempts and consumption
val number = digit.many1.trace("number").map(_.mkString.toInt)
number.run("42")
// [TRACE] number: trying at offset 0
// [TRACE] number: success, consumed 2 chars

// Debug shows actual parsed values
val expr = (number ~ char('+') ~ number).debug("expression")
expr.run("1+2")
// [DEBUG] expression: trying at offset 0
// [DEBUG] expression: success, parsed ((1,+),2)

// Combine multiple debug points
val complex = char('(').trace("open") *>
  number.debug("left") ~
  char(',').trace("comma") *>
  number.debug("right") <*
  char(')').trace("close")
complex.run("(5,3)")
// [TRACE] open: trying at offset 0
// [TRACE] open: success, consumed 1 chars
// [DEBUG] left: trying at offset 1
// [DEBUG] left: success, parsed 5
// [TRACE] comma: trying at offset 2
// [TRACE] comma: success, consumed 1 chars
// [DEBUG] right: trying at offset 3
// [DEBUG] right: success, parsed 3
// [TRACE] close: trying at offset 4
// [TRACE] close: success, consumed 1 chars
```

Note: Debug output goes to stderr, keeping it separate from normal program output.

## Performance

### Design priorities: allocation efficiency on failure paths

Rumil defers error materialization: failed backtracking branches do not allocate error objects until an error actually surfaces (`LazyFailure`), and recovery paths defer their error construction too (`LazyPartial`). Or-chains flatten into a single dispatch (`Choice`, radix `StringChoice` for string alternatives), so nested alternatives pay one walk, not one per branch.

**Stack safety:** everything runs on a heap trampoline (`TrampolineOpt`): sequential `~`/`flatMap` chains to 7,000,000 operations and structural recursion (deferred grammars) to 200,000 nesting depth pass in the test suite — depth is bounded by heap, not the JVM stack. Left recursion runs through `rule`'s seed-growth memo table.

**Measured results:** the library's benchmarks run under the CI smoke step (fat jar runnable,
no measurement) and the measurement sources live under `benchmarks/`. The comparison against
cats-parse and zio-parser is competitive on most workloads, with wins on number parsing and
first-match choice, and repetition/sequential shapes where cats-parse is ahead.

**When to use Rumil:**
- Grammars with heavy backtracking (ambiguous or complex rules)
- Parsers where allocation on failure paths matters
- Left-recursive grammars (natural expression syntax)
- Debuggability and error quality are priorities

**When to use alternatives:**
- Pure throughput on simple grammars → cats-parse
- Maximum speed with compile-time generation → fastparse

## Testing

The library includes:
- Property-based tests verifying monad laws (`core/src/test/scala/parser/laws/MonadLaws.scala`) and ScalaCheck-driven parser tests per format
- Example parsers (JSON, arithmetic, CSV)
- JMH benchmarks with a CI smoke step (fat jar runnable, no measurement)

Run tests:
```bash
sbt check          // scalafix + scalafmt gates
sbt core/Test/testFull
sbt parsers/Test/testFull
sbt interop/Test/testFull
```

## Requirements

- Scala 3.8.4 (the version the build compiles with)
- A JVM; development and CI run on current JVMs

## License

MIT License

## Contributing

Contributions are welcome. Please:
1. Ensure all tests pass
2. Add tests for new features
3. Follow the existing code style
4. Update documentation

## Acknowledgments

Inspired by parser combinator libraries including Parsec, Megaparsec, and Cats-Parse.
