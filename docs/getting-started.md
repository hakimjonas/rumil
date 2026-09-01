# Getting Started with Rumil

**Goal:** Learn the basics of Rumil in 15 minutes and start parsing structured data.

## Installation

Add to your `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "net.ghoula" %% "rumil-core"    % "1.0.0-alpha",  // Core parser combinators
  "net.ghoula" %% "rumil-parsers" % "1.0.0-alpha",  // JSON, XML, TOML, CSV, YAML, Protobuf, XPath
  "net.ghoula" %% "sarati"        % "1.0.0-alpha"   // Codec layer: decode parsed ASTs into case classes
)
```

## Your First Parser (5 minutes)

### Example 1: Parse a Simple Number

```scala
import parser.core.*
import parser.syntax.*

val numberParser: Parser[ParseError, Int] =
  digit.many1.map(_.mkString.toInt)

val result = numberParser.run("42")
// Success(42, 2)
```

### Example 2: Parse Key-Value Pairs

```scala
import parser.core.*
import parser.syntax.*

case class KeyValue(key: String, value: String)

val letter = satisfy(_.isLetter, "letter")
val kvParser: Parser[ParseError, KeyValue] = for {
  key   <- letter.many1.map(_.mkString)
  _     <- char('=')
  value <- letter.many1.map(_.mkString)
} yield KeyValue(key, value)

val result = kvParser.run("name=Alice")
// Success(KeyValue("name", "Alice"), 10)
```

### Key Concepts

1. **Parser[E, A]**: A parser that produces an `A` on success or an error of type `E`
2. **Combinators**: Functions that combine simple parsers into complex ones
   - `.map`: Transform successful results
   - `.flatMap` (for-comprehension): Chain parsers sequentially
   - `|`: Try alternatives
   - `.many`/`.many1`: Repetition (0+ or 1+)
3. **`.run(input)`**: Execute the parser on a string

## Parsing JSON (3 minutes)

### Quick Start: Use the Built-in Parser

```scala
import parsers.json.{parseJson, JsonValue}

val json = """{"name": "Alice", "age": 30}"""
val result = parseJson(json)
// Success(JsonValue.Object(...), ...)
```

### Decode to Case Classes

```scala
import net.ghoula.sarati.codec.{Decoder, JsonDecoders.given}

case class Person(name: String, age: Int)
given Decoder[JsonValue, Person] = Decoder.derived

val json = """{"name": "Alice", "age": 30}"""
for {
  jsonValue <- parseJson(json)
  person    <- Decoder[JsonValue, Person].decode(jsonValue)
} yield person
// Success(Person("Alice", 30), ...)
```

> The decode layer shown here is [sarati](https://localhost:3000/hakim/sarati)'s codec. Rumil's
> own `rumil-interop` module (`parser.interop`) is deprecated for 0.4.0 and scheduled for
> removal in 1.0 — it is a pre-Sarati twin of the same API.

## Built-in Format Parsers (2 minutes)

Rumil ships parsers for common formats and data grammars:

```scala
// JSON
import parsers.json.parseJson
parseJson("""{"x": 1}""")

// XML
import parsers.xml.parseXml
parseXml("<person><name>Alice</name></person>")

// TOML
import parsers.toml.parseToml
parseToml("name = \"Alice\"\nage = 30")

// YAML
import parsers.yaml.parseYaml
parseYaml("name: Alice\nage: 30")

// CSV
import parsers.csv.parseCsv
parseCsv("name,age\nAlice,30")

// Protobuf (text format)
import parsers.protobuf.parseProto
parseProto("message Person { required string name = 1; }")

// XPath 1.0 expressions (produces sarati's XPathExpr AST)
import parsers.xpath.parseXPath
parseXPath("//book[@id > 2]/title")
```

All parsers return `Result[ParseError, T]` with rich error information.

## Error Handling (3 minutes)

### Pattern Match on Results

```scala
val parser = digit.many1.map(_.mkString.toInt)
parser.run("abc") match {
  case Result.Success(value, consumed) =>
    println(s"Parsed: $value")

  case Result.Failure(errors, furthest) =>
    errors.foreach(e => println(s"Error at ${e.location}: ${e.message}"))

  case Result.Partial(value, errors, consumed) =>
    println(s"Partial result: $value with ${errors.size} errors")
}
```

### Use Result Methods

```scala
// Get value or default
val value: Int = parser.run("42").getOrElse(0)

// Convert to Option
val maybe: Option[Int] = parser.run("42").toOption

// Convert to Either
val either: Either[List[ParseError], Int] = parser.run("42").toEither
```

### Named Parsers for Better Errors

```scala
val email = (
  letter.many1 ~
  char('@') ~
  letter.many1 ~
  char('.') ~
  letter.many1
).named("email address")

email.run("invalid")
// Error: Expected email address at line 1, column 1
```

## Common Combinators (2 minutes)

### Sequencing

```scala
// Parse two things in sequence
val ab = char('a') ~ char('b')  // Parser[(Char, Char)]

// Discard left result
val discard_a = char('a') *> char('b')  // Parser[Char] (returns 'b')

// Discard right result
val discard_b = char('a') <* char('b')  // Parser[Char] (returns 'a')
```

### Alternatives

```scala
// Try alternatives left-to-right
val aOrB = char('a') | char('b')

// Alternative with different types (requires common supertype)
val intOrString: Parser[ParseError, Any] =
  digit.many1.map(_.mkString.toInt) |
  letter.many1.map(_.mkString)
```

`|` and `.orElse` are fast alternation: if a branch fails, its errors are discarded. When you
need those errors anyway (resilient parsing with diagnostics), use `.recover` instead — a
successful fallback then returns `Partial` with the primary's errors:

```scala
val fast = char('a').orElse(char('b'))
fast.run("b")   // Success('b', 1)

val tracked = char('a').recover(char('b'))
tracked.run("b") // Partial('b', errors from 'a', 1)
```

See the [Error Handling Guide](./error-handling.md) for the full decision guide.

### Repetition

```scala
// Zero or more
val manyDigits = digit.many  // Parser[List[Char]]

// One or more
val someDigits = digit.many1  // Parser[List[Char]]

// Exactly N
val threeDigits = digit.count(3)

// At least N
val sevenOrMore = digit.manyAtLeast(7)

// Separated by delimiter
val csvRow = letter.many1.sepBy(char(','))
```

### Optional

```scala
// Optional sign for numbers
val sign = char('-').optional  // Parser[Option[Char]]

val signedNumber = for {
  s  <- sign
  ds <- digit.many1
} yield {
  val num = ds.mkString.toInt
  if (s.isDefined) -num else num
}
```

### Lookahead

```scala
// Peek ahead without consuming
val peekChar = anyChar.lookAhead  // Parser[Char] (doesn't consume)

// Match if next input satisfies condition
val notEndOfLine = anyChar.notFollowedBy(char('\n'))
```

## Left Recursion (Advanced, 2 minutes)

Rumil supports left-recursive grammars using the `rule` combinator.

⚠️ **Performance Note:** `rule` measures ~1.4–2.5x slower than the equivalent unmemoized grammar (measured 2026-08-31). Only use it for left-recursive grammars. For other cases, use `lazy val` or `.memoize`.

```scala
case class BinOp(left: Expr, op: String, right: Expr)
enum Expr {
  case Num(n: Int)
  case Add(left: Expr, right: Expr)
  case Mul(left: Expr, right: Expr)
}

lazy val expr: Parser[ParseError, Expr] = rule {
  (expr ~ ws ~ char('+') ~ ws ~ term).map((e, _, _, _, t) => Expr.Add(e, t)) |
  (expr ~ ws ~ char('*') ~ ws ~ term).map((e, _, _, _, t) => Expr.Mul(e, t)) |
  term
}

lazy val term: Parser[ParseError, Expr] =
  digit.many1.map(ds => Expr.Num(ds.mkString.toInt))

val ws = satisfy(_.isWhitespace, "whitespace").many.void

expr.run("1 + 2 * 3")
// Parses with correct precedence!
```

**Note:** Use `rule` for left-recursive grammars. For non-recursive cases, plain combinators are faster.

## Next Steps

- **[Cookbook](./cookbook.md)**: 10 common parsing patterns
- **[Error Handling Guide](./error-handling.md)**: Advanced error recovery
- **[Performance](./performance.md)**: where the benchmark sources live and what is measured
- **[Idiomatic vs Structural](./idiomatic-approach.md)**: Two API styles

## Quick Reference

### Core Combinators

| Combinator | Description | Example |
|------------|-------------|---------|
| `char(c)` | Match single character | `char('a')` |
| `string(s)` | Match exact string | `string("hello")` |
| `satisfy(f)` | Match if predicate true | `satisfy(_.isDigit)` |
| `anyChar` | Match any character | `anyChar` |
| `digit` | Match 0-9 | `digit` |
| `letter` | Match a-zA-Z | `letter` |
| `eof` | Match end of input | `eof` |

### Combinators

| Combinator | Description | Example |
|------------|-------------|---------|
| `p1 ~ p2` | Sequence | `char('a') ~ char('b')` |
| `p1 | p2` | Alternative | `char('a') | char('b')` |
| `p.map(f)` | Transform result | `digit.map(_.asDigit)` |
| `p.flatMap(f)` | Chain parsers | `p.flatMap(x => q(x))` |
| `p.many` | Zero or more | `digit.many` |
| `p.many1` | One or more | `digit.many1` |
| `p.optional` | Optional | `char('-').optional` |
| `p.sepBy(sep)` | Separated list | `num.sepBy(char(','))` |
| `p.named(s)` | Name for errors | `p.named("email")` |

### Result Methods

| Method | Description | Example |
|--------|-------------|---------|
| `.toOption` | Convert to Option | `result.toOption` |
| `.toEither` | Convert to Either | `result.toEither` |
| `.getOrElse(default)` | Get value or default | `result.getOrElse(0)` |
| `.map(f)` | Map successful value | `result.map(_ + 1)` |
| `.flatMap(f)` | Chain results | `result.flatMap(decode)` |

## Common Patterns

### Parse CSV-like Data

```scala
val cell = satisfy(_ != ',', "cell char").many.map(_.mkString)
val row = cell.sepBy(char(','))
val csv = row.sepBy(char('\n'))
```

### Parse Quoted Strings

```scala
val escapedChar = char('\\') *> anyChar
val stringChar = escapedChar | satisfy(_ != '"', "string char")
val quotedString = char('"') *> stringChar.many.map(_.mkString) <* char('"')
```

### Parse Whitespace-Delimited Values

```scala
val ws = satisfy(_.isWhitespace, "whitespace").many.void
val lexeme = (p: Parser[ParseError, A]) => p <* ws
val number = lexeme(digit.many1.map(_.mkString.toInt))
val values = ws *> number.many
```

## Tips for Success

1. **Start small**: Build simple parsers, then combine them
2. **Name your parsers**: Use `.named("description")` for better errors
3. **Use for-comprehensions**: They're clearer than flatMap chains
4. **Test incrementally**: Test each parser before combining
5. **Check the examples**: See `examples/` directory for real-world patterns
6. **Don't overuse memoization**: Only use `.memoize` for expensive backtracking scenarios (see Performance Guide)

## Getting Help

- **Issue tracker**: https://github.com/hakimjonas/rumil/issues
- **Examples**: `/examples` directory in the repository
- **Scaladoc**: API documentation (published separately)

---

**Ready to dive deeper?** Check out the [Cookbook](./cookbook.md) for 10 common parsing patterns!
