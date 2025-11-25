# Rumil API Reference

Complete API documentation for Rumil parser combinator library.

## Table of Contents

- [Core Types](#core-types)
- [Primitive Parsers](#primitive-parsers)
- [Combinators](#combinators)
- [Repetition](#repetition)
- [Alternatives & Choice](#alternatives--choice)
- [Sequencing](#sequencing)
- [Lookahead](#lookahead)
- [Error Handling](#error-handling)
- [Debugging](#debugging)
- [Operators](#operators)
- [Memoization & Recursion](#memoization--recursion)
- [Extension Methods](#extension-methods)
- [Result Type](#result-type)
- [Decoder Typeclass](#decoder-typeclass)

---

## Core Types

### Parser[E, A]

The fundamental type representing a parser.

```scala
Parser[E, A]  // A parser that produces A on success or error E on failure
```

- `E` - Error type (usually `ParseError`)
- `A` - Success value type

Parsers are **immutable descriptions** - they don't execute until you call `.run(input)`.

### ParseError

Built-in error type with location information:

```scala
enum ParseError {
  case Unexpected(char: Char, location: Location)
  case EndOfInput(location: Location)
  case Expected(expected: String, location: Location)
  case Custom(message: String, location: Location)
}
```

### Location

Position in input with line, column, and offset:

```scala
type Location = (line: Int, column: Int, offset: Int)
```

---

## Primitive Parsers

### Character Matching

| Function | Signature | Description |
|----------|-----------|-------------|
| `char(c)` | `Char => Parser[ParseError, Char]` | Matches exact character |
| `satisfy(pred, expected)` | `(Char => Boolean, String) => Parser[ParseError, Char]` | Matches if predicate true |
| `anyChar` | `Parser[ParseError, Char]` | Matches any single character |
| `oneOf(chars)` | `String => Parser[ParseError, Char]` | Matches any char in string |
| `noneOf(chars)` | `String => Parser[ParseError, Char]` | Matches any char NOT in string |

**Examples:**

```scala
char('a').run("abc")           // Success('a', 1)
satisfy(_.isDigit, "digit").run("5")  // Success('5', 1)
oneOf("aeiou").run("e")        // Success('e', 1)
noneOf("aeiou").run("x")       // Success('x', 1)
```

### String Matching

| Function | Signature | Description |
|----------|-----------|-------------|
| `string(s)` | `String => Parser[ParseError, String]` | Matches exact string |
| `stringIn(strings*)` | `String* => Parser[ParseError, String]` | Matches any of several strings (radix tree optimized) |
| `keywords(map)` | `Map[String, A] => Parser[ParseError, A]` | Matches keywords and returns mapped values |

**Examples:**

```scala
string("hello").run("hello world")  // Success("hello", 5)
stringIn("true", "false", "null").run("true")  // Success("true", 4)
keywords(Map("yes" -> true, "no" -> false)).run("yes")  // Success(true, 3)
```

### Character Classes

| Function | Description |
|----------|-------------|
| `digit` | Matches 0-9 |
| `letter` | Matches a-z, A-Z |
| `alphaNum` | Matches letters or digits |
| `whitespace` | Matches whitespace character |
| `spaces` | Zero or more whitespace (always succeeds) |
| `spaces1` | One or more whitespace |

### Special Parsers

| Function | Signature | Description |
|----------|-----------|-------------|
| `eof` | `Parser[ParseError, Unit]` | Succeeds only at end of input |
| `succeed(value)` | `A => Parser[Nothing, A]` | Always succeeds with value |
| `fail(error)` | `E => Parser[E, Nothing]` | Always fails with error |

---

## Combinators

### Transformation

| Function | Signature | Description |
|----------|-----------|-------------|
| `map(p, f)` | `(Parser[E,A], A=>B) => Parser[E,B]` | Transform result |
| `flatMap(p, f)` | `(Parser[E,A], A=>Parser[E,B]) => Parser[E,B]` | Monadic sequencing |
| `p.as(value)` | `A => Parser[E, A]` | Replace result with constant |
| `p.void` | `Parser[E, Unit]` | Discard result |

**Examples:**

```scala
digit.map(_.asDigit).run("5")           // Success(5, 1)
char('a').as("found-a").run("a")        // Success("found-a", 1)
string("hello").void.run("hello")       // Success((), 5)
```

### Lexeme Helpers

| Function | Signature | Description |
|----------|-----------|-------------|
| `lexeme(p)` | `Parser[E,A] => Parser[E\|ParseError, A]` | Parse and consume trailing whitespace |
| `symbol(s)` | `String => Parser[ParseError, String]` | Parse string and trailing whitespace |

---

## Repetition

| Function | Signature | Description |
|----------|-----------|-------------|
| `many(p)` / `p.many` | `Parser[E, List[A]]` | Zero or more (always succeeds) |
| `many1(p)` / `p.manyNonEmpty` | `Parser[E, List[A]]` | One or more |
| `optional(p)` / `p.optional` | `Parser[E, Option[A]]` | Zero or one |
| `count(n, p)` / `p.count(n)` | `Parser[E, List[A]]` | Exactly n times |
| `times(n, p)` | `Parser[E, List[A]]` | Alias for count |
| `manyAtLeast(n)(p)` | `Parser[E, List[A]]` | At least n times |
| `skipMany(p)` | `Parser[E, Unit]` | Zero or more, discard results |
| `skipManyNonEmpty(p)` | `Parser[E, Unit]` | One or more, discard results |

### Separated/Terminated

| Function | Signature | Description |
|----------|-----------|-------------|
| `sepBy(p, sep)` / `p.separatedBy(sep)` | `Parser[E, List[A]]` | Zero+ separated by sep |
| `sepBy1(p, sep)` / `p.separatedByNonEmpty(sep)` | `Parser[E, List[A]]` | One+ separated by sep |
| `endBy(p, end)` / `p.endedBy(end)` | `Parser[E, List[A]]` | Zero+ terminated by end |

**Examples:**

```scala
digit.many.run("123x")                    // Success(List('1','2','3'), 3)
digit.manyNonEmpty.run("x")               // Failure
digit.optional.run("x")                   // Success(None, 0)
digit.count(3).run("123")                 // Success(List('1','2','3'), 3)
digit.separatedBy(char(',')).run("1,2,3") // Success(List('1','2','3'), 5)
```

---

## Alternatives & Choice

| Function | Signature | Description |
|----------|-----------|-------------|
| `or(p1, p2)` / `p1 \| p2` | `Parser[E, A]` | Try p1, if fails try p2 |
| `choice(parsers)` | `List[Parser[E,A]] => Parser[E,A]` | Try parsers in order |

**Important:** `|` backtracks on failure. If p1 consumes input then fails, position is restored before trying p2.

**Examples:**

```scala
(char('a') | char('b')).run("b")        // Success('b', 1)
choice(List(string("foo"), string("bar"), string("baz"))).run("bar")
// Success("bar", 3) - uses radix tree optimization for 3+ string alternatives
```

---

## Sequencing

| Function | Signature | Description |
|----------|-----------|-------------|
| `zip(p1, p2)` / `p1 ~ p2` | `Parser[E, (A, B)]` | Sequence, keep both |
| `zipLeft(p1, p2)` / `p1 <* p2` | `Parser[E, A]` | Sequence, keep left |
| `zipRight(p1, p2)` / `p1 *> p2` | `Parser[E, B]` | Sequence, keep right |
| `between(p, left, right)` | `Parser[E, A]` | Parse left-p-right, return p |
| `surroundedBy(delim)(p)` | `Parser[E, A]` | Same delimiter both sides |

**Examples:**

```scala
(char('a') ~ char('b')).run("ab")       // Success(('a', 'b'), 2)
(char('(') *> digit <* char(')')).run("(5)")  // Success('5', 3)
between(digit, char('['), char(']')).run("[7]")  // Success('7', 3)
```

### For-Comprehension Support

Parsers support for-comprehensions via `map` and `flatMap`:

```scala
val parser = for {
  name <- letter.manyNonEmpty.map(_.mkString)
  _    <- char('=')
  value <- digit.manyNonEmpty.map(_.mkString.toInt)
} yield (name, value)

parser.run("count=42")  // Success(("count", 42), 8)
```

---

## Lookahead

| Function | Signature | Description |
|----------|-----------|-------------|
| `lookAhead(p)` / `p.lookAhead` | `Parser[E, A]` | Parse without consuming input |
| `notFollowedBy(p)` | `Parser[ParseError, Unit]` | Succeed only if p fails |

**Examples:**

```scala
lookAhead(digit).run("5x")              // Success('5', 0) - consumed = 0!
notFollowedBy(digit).run("abc")         // Success((), 0)
notFollowedBy(digit).run("123")         // Failure
```

---

## Error Handling

### Recovery Combinators

| Function | Behavior on Failure | Returns |
|----------|---------------------|---------|
| `recover(p)(f)` | Apply f to error, return value | Always succeeds |
| `recoverWith(p)(f)` | Apply f to error, return parser | May still fail |
| `orElse(p, fallback)` | Try fallback, preserve errors | Partial with errors |

**Examples:**

```scala
// recover: always succeeds with default
recover(digit)(_ => '0').run("x")       // Success('0', 0)

// recoverWith: try alternative parser
recoverWith(digit)(_ => char('?')).run("?")  // Success('?', 1)

// orElse: resilient parsing with error accumulation
orElse(digit, succeed('0')).run("x")    // Partial('0', errors, 0)
```

### Error Messages

| Function | Signature | Description |
|----------|-----------|-------------|
| `expect(p, message)` | `Parser[ParseError, A]` | Replace all errors with message |
| `named(p, name)` / `p.named(name)` | `Parser[ParseError, A]` | Add name to expected set |

**Examples:**

```scala
expect(digit.manyNonEmpty, "number required").run("x")
// Failure: "number required"

digit.named("digit").run("x")
// Failure: expected digit
```

### Attempt (Reification)

```scala
attempt(p)  // Parser[Nothing, Result[E, A]]
```

Captures success/failure as a value instead of propagating errors. Always succeeds.

---

## Debugging

| Function | Output |
|----------|--------|
| `trace(p, label)` / `p.trace(label)` | Parse attempts and consumption |
| `debug(p, label)` / `p.debug(label)` | Parsed values and errors |

**Example output:**

```scala
digit.trace("num").run("5")
// [TRACE] num: trying at offset 0
// [TRACE] num: success, consumed 1 chars
// Success('5', 1)

digit.debug("num").run("5")
// [DEBUG] num: trying at offset 0
// [DEBUG] num: success, parsed '5'
// Success('5', 1)
```

---

## Operators

### Chain Operators (for Expression Parsing)

| Function | Associativity | Description |
|----------|---------------|-------------|
| `chainl1(p, op)` / `p.chainLeft1(op)` | Left | `((a op b) op c)` |
| `chainr1(p, op)` / `p.chainRight1(op)` | Right | `(a op (b op c))` |
| `chainLeft(p, op, default)` | Left | With default for empty |
| `chainRight(p, op, default)` | Right | With default for empty |

**Examples:**

```scala
val num = digit.map(_.asDigit)
val minus = char('-').as((a: Int, b: Int) => a - b)

chainl1(num, minus).run("5-3-1")  // Success(1, 5)  - (5-3)-1
chainr1(num, minus).run("5-3-1")  // Success(3, 5)  - 5-(3-1)
```

---

## Memoization & Recursion

### defer

```scala
def defer[E, A](p: => Parser[E, A]): Parser[E, A]
```

Lazy evaluation for recursive parsers. Prevents stack overflow during parser construction.

```scala
lazy val expr: Parser[ParseError, Expr] =
  number | defer(expr).between(char('('), char(')'))
```

### rule

```scala
def rule[E, A](p: => Parser[E, A]): Parser[E, A]
```

Memoized parser with **left recursion support**. Uses Warth et al. seed-growth algorithm.

**When to use `rule`:**
- Left-recursive grammars (`expr -> expr '+' term`)
- Expression parsers with precedence

**Performance warning:** ~479% overhead for cache misses. Only use when needed.

```scala
lazy val expr: Parser[ParseError, Int] = rule {
  (expr ~ char('+') ~ term).map { case ((e, _), t) => e + t } |
  term
}
```

### memoize

```scala
def memoize[E, A](p: Parser[E, A]): Parser[E, A]
```

Simple caching **without** left-recursion support. ~50% faster than `rule`.

**When to use `memoize`:**
- Expensive parsers with backtracking
- NOT left-recursive

---

## Extension Methods

Import `parser.syntax._` for extension methods:

```scala
import parser.syntax._
```

### Parser Extensions

| Method | Equivalent To |
|--------|---------------|
| `p ~ q` | `zip(p, q)` |
| `p *> q` | `zipRight(p, q)` |
| `p <* q` | `zipLeft(p, q)` |
| `p \| q` | `or(p, q)` |
| `p.map(f)` | `map(p, f)` |
| `p.flatMap(f)` | `flatMap(p, f)` |
| `p.many` | `many(p)` |
| `p.manyNonEmpty` | `many1(p)` |
| `p.optional` | `optional(p)` |
| `p.separatedBy(sep)` | `sepBy(p, sep)` |
| `p.count(n)` | `count(n, p)` |
| `p.between(l, r)` | `between(p, l, r)` |
| `p.chainLeft1(op)` | `chainl1(p, op)` |
| `p.named(s)` | `named(p, s)` |
| `p.trace(s)` | `trace(p, s)` |
| `p.debug(s)` | `debug(p, s)` |
| `p.memoize` | `memoize(p)` |

---

## Result Type

```scala
enum Result[+E, +A] {
  case Success(value: A, consumed: Int)
  case Partial(value: A, errors: List[E], consumed: Int)
  case Failure(errors: List[E], furthest: Location)
}
```

### Result Methods

| Method | Description |
|--------|-------------|
| `.toOption` | `Option[A]` - None on failure |
| `.toEither` | `Either[List[E], A]` |
| `.getOrElse(default)` | Get value or default |
| `.map(f)` | Transform success value |
| `.flatMap(f)` | Chain with another Result |
| `.isSuccess` | Boolean check |
| `.isFailure` | Boolean check |

---

## Decoder Typeclass

For decoding parsed values (JSON, XML, etc.) to case classes.

```scala
trait Decoder[Source, Target] {
  def decode(value: Source): Result[DecodeError, Target]
}
```

### Built-in Decoders (JsonDecoders)

Import `parser.interop.JsonDecoders.given` for:

**Primitives:**
- `String`, `Int`, `Long`, `Double`, `Float`, `Boolean`
- `Byte`, `Short`, `BigInt`, `BigDecimal`

**Java Time (ISO-8601):**
- `Instant`, `LocalDate`, `LocalDateTime`, `LocalTime`
- `OffsetDateTime`, `ZonedDateTime`

**Other:**
- `UUID`

**Collections:**
- `Option[A]`, `List[A]`, `Seq[A]`, `Vector[A]`, `Map[String, A]`

### Derived Decoders

```scala
case class Person(name: String, age: Int)
given Decoder[JsonValue, Person] = Decoder.derived
```

**Example:**

```scala
import parser.interop.{Decoder, JsonDecoders}
import parser.interop.JsonDecoders.given
import parsers.json.{parseJson, JsonValue}

case class Event(name: String, timestamp: Instant, id: UUID)
given Decoder[JsonValue, Event] = Decoder.derived

val json = """{"name": "Meeting", "timestamp": "2024-01-15T10:30:00Z", "id": "550e8400-e29b-41d4-a716-446655440000"}"""

for {
  jsonValue <- parseJson(json)
  event <- Decoder[JsonValue, Event].decode(jsonValue)
} yield event
// Success(Event("Meeting", 2024-01-15T10:30:00Z, 550e8400-...), ...)
```

---

## Quick Reference Card

### Most Common Operations

```scala
import parser.core._
import parser.syntax._

// Parse single char
char('x')
digit
letter

// Parse string
string("hello")

// Sequence
p ~ q           // Both, keep tuple
p *> q          // Both, keep right
p <* q          // Both, keep left

// Choice
p | q           // Try p, then q

// Repetition
p.many          // 0+
p.manyNonEmpty  // 1+
p.optional      // 0 or 1
p.count(n)      // Exactly n

// Transform
p.map(f)        // Transform result
p.as(x)         // Replace with constant

// Execute
p.run("input")  // Run parser
```

### Expression Parser Template

```scala
lazy val expr: Parser[ParseError, Int] = rule {
  (expr ~ char('+') ~ term).map { case ((l, _), r) => l + r } | term
}

lazy val term: Parser[ParseError, Int] = rule {
  (term ~ char('*') ~ factor).map { case ((l, _), r) => l * r } | factor
}

lazy val factor: Parser[ParseError, Int] =
  digit.map(_.asDigit) | (char('(') *> defer(expr) <* char(')'))
```

---

## See Also

- [Getting Started](./getting-started.md) - 15-minute introduction
- [Cookbook](./cookbook.md) - 10 common parsing patterns
- [Error Handling](./error-handling.md) - Advanced error recovery
- [Performance Guide](./memoization-performance-analysis.md) - When to use memoization
