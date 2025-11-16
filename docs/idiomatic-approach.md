# The Idiomatic Approach: Automatic Case Class Derivation

## Learning Objective

Learn how to use Rumil's `Decoder.derived` macro to automatically parse structured data into Scala case classes with zero boilerplate.

## When to Use This Approach

✅ **Use when:**
- Parsing JSON/XML/TOML to domain models
- Building REST API clients
- Reading configuration files
- You want concise, maintainable code
- Standard data transformations

❌ **Don't use when:**
- Building language tooling (compilers, formatters, IDEs)
- Need lossless syntax trees
- Require custom data representations
- Need maximum control over parsing logic

## Quick Start

```scala
import parser.core._
import parser.interop.Decoder
import parser.interop.JsonDecoders.given
import parsers.json.{JsonParser, JsonValue}

// Define your domain model
case class User(name: String, age: Int, admin: Boolean)

// Derive a decoder automatically
given Decoder[JsonValue, User] = Decoder.derived

// Parse JSON
val input = """{"name": "Alice", "age": 30, "admin": true}"""
val jsonResult = JsonParser.parseValue.run(input)

// Decode to case class
val userResult = jsonResult.flatMap(json =>
  Decoder[JsonValue, User].decode(json)
)

userResult match {
  case Result.Success(user, _) =>
    println(s"Welcome, ${user.name}!")
  case Result.Failure(errors, _) =>
    println(s"Invalid user data: $errors")
  case Result.Partial(user, errors, _) =>
    println(s"User: $user, with warnings: $errors")
}
```

## How It Works

The `Decoder.derived` macro uses Scala 3's compile-time reflection to:

1. **Inspect the case class** at compile time
2. **Extract field names** and types
3. **Generate decoding logic** for each field
4. **Compose the decoders** into a single case class decoder

This happens at **compile time**, so there's zero runtime overhead.

### Under the Hood

```scala
// What you write:
case class Person(name: String, age: Int)
given Decoder[JsonValue, Person] = Decoder.derived

// What the macro generates (conceptually):
given Decoder[JsonValue, Person] = new Decoder[JsonValue, Person] {
  def decode(value: JsonValue): Result[DecodeError, Person] = value match {
    case JsonValue.Object(fields) =>
      val name = fields.get("name").flatMap(Decoder[JsonValue, String].decode)
      val age = fields.get("age").flatMap(Decoder[JsonValue, Int].decode)
      // ... combine and construct Person
  }
}
```

## Supported Types

### Primitive Types

The `JsonDecoders` object provides built-in decoders for:

- `String`, `Int`, `Long`, `Double`, `Boolean`
- `Byte`, `Short`, `Float`
- `BigInt`, `BigDecimal`

```scala
import parser.interop.JsonDecoders.given

val strDecoder = Decoder[JsonValue, String]
val intDecoder = Decoder[JsonValue, Int]
val boolDecoder = Decoder[JsonValue, Boolean]
```

### Collections

Built-in support for standard collections:

- `List[A]`, `Seq[A]`, `Vector[A]`
- `Option[A]`
- `Map[String, A]`

```scala
case class Team(name: String, members: List[String])
given Decoder[JsonValue, Team] = Decoder.derived

val json = """{"name": "Avengers", "members": ["Iron Man", "Thor"]}"""
// Decodes to: Team("Avengers", List("Iron Man", "Thor"))
```

### Nested Case Classes

Decoders compose automatically for nested structures:

```scala
case class Address(street: String, city: String, zip: String)
case class Person(name: String, address: Address)

// Define decoders in dependency order
given Decoder[JsonValue, Address] = Decoder.derived
given Decoder[JsonValue, Person] = Decoder.derived

val json = """{
  "name": "Bob",
  "address": {
    "street": "123 Main St",
    "city": "Springfield",
    "zip": "12345"
  }
}"""

// Decodes to: Person("Bob", Address("123 Main St", "Springfield", "12345"))
```

## Advanced Usage

### Optional Fields

Use `Option[T]` for optional fields:

```scala
case class User(name: String, email: Option[String])
given Decoder[JsonValue, User] = Decoder.derived

// Both of these work:
val withEmail = """{"name": "Alice", "email": "alice@example.com"}"""
// → User("Alice", Some("alice@example.com"))

val withoutEmail = """{"name": "Bob", "email": null}"""
// → User("Bob", None)
```

### Lists and Collections

```scala
case class Post(title: String, tags: List[String], views: Option[Int])
given Decoder[JsonValue, Post] = Decoder.derived

val json = """{
  "title": "Getting Started",
  "tags": ["scala", "tutorial"],
  "views": null
}"""
// → Post("Getting Started", List("scala", "tutorial"), None)
```

### Deeply Nested Structures

```scala
case class Tag(name: String)
case class Post(title: String, tags: List[Tag])
case class Author(name: String, posts: List[Post])

given Decoder[JsonValue, Tag] = Decoder.derived
given Decoder[JsonValue, Post] = Decoder.derived
given Decoder[JsonValue, Author] = Decoder.derived

// Now you can decode complex JSON with multiple levels of nesting
```

## Error Handling

The Decoder typeclass uses the same `Result` type as parsers:

### Success

```scala
case class Config(port: Int, host: String)
given Decoder[JsonValue, Config] = Decoder.derived

val json = """{"port": 8080, "host": "localhost"}"""
val result = JsonParser.parseValue.run(json).flatMap(
  Decoder[JsonValue, Config].decode
)
// Success(Config(8080, "localhost"), 0)
```

### Failure (Missing Fields)

```scala
val incomplete = """{"port": 8080}"""  // Missing "host"
val result = JsonParser.parseValue.run(incomplete).flatMap(
  Decoder[JsonValue, Config].decode
)
// Partial or Failure with MissingField error
```

### Failure (Type Mismatch)

```scala
val wrongType = """{"port": "not a number", "host": "localhost"}"""
val result = JsonParser.parseValue.run(wrongType).flatMap(
  Decoder[JsonValue, Config].decode
)
// Partial or Failure with TypeMismatch error
```

### Partial Results

Rumil supports **resilient decoding** - it will decode as much as possible even if some fields are invalid:

```scala
case class User(name: String, age: Int, email: String)
given Decoder[JsonValue, User] = Decoder.derived

val partial = """{"name": "Alice", "age": "invalid", "email": "alice@example.com"}"""
val result = JsonParser.parseValue.run(partial).flatMap(
  Decoder[JsonValue, User].decode
)
// Partial(User("Alice", 0, "alice@example.com"), List(TypeMismatch(...)), 0)
// Note: 'age' has a default value (0 for Int) and errors are accumulated
```

## Full Pipeline Example

Here's a complete example showing the typical workflow:

```scala
import parser.core._
import parser.interop.Decoder
import parser.interop.JsonDecoders.given
import parsers.json.{JsonParser, JsonValue}

// 1. Define your domain model
case class Product(id: Int, name: String, price: Double, inStock: Boolean)
given Decoder[JsonValue, Product] = Decoder.derived

// 2. Get JSON from somewhere (API, file, etc.)
val jsonString = """
{
  "id": 42,
  "name": "Widget",
  "price": 19.99,
  "inStock": true
}
"""

// 3. Parse JSON string → JsonValue
val parseResult: Result[ParseError, JsonValue] =
  JsonParser.parseValue.run(jsonString)

// 4. Decode JsonValue → Product
val decodeResult: Result[DecodeError, Product] = parseResult.flatMap { jsonValue =>
  Decoder[JsonValue, Product].decode(jsonValue)
}

// 5. Handle the result
decodeResult match {
  case Result.Success(product, _) =>
    println(s"Product: ${product.name}, $$${product.price}")
    if (product.inStock) println("✓ In stock") else println("✗ Out of stock")

  case Result.Failure(errors, _) =>
    println("Failed to decode product:")
    errors.foreach(err => println(s"  - $err"))

  case Result.Partial(product, errors, _) =>
    println(s"Partially decoded: $product")
    println("Warnings:")
    errors.foreach(err => println(s"  - $err"))
}
```

## Comparison with Structural Approach

Let's compare the two approaches for the same task:

### Idiomatic (Automatic Derivation)

```scala
case class Person(name: String, age: Int)
given Decoder[JsonValue, Person] = Decoder.derived

val result = JsonParser.parseValue.run(input).flatMap(
  Decoder[JsonValue, Person].decode
)
// ✓ Concise
// ✓ Type-safe
// ✓ Zero boilerplate
```

### Structural (Manual Extraction)

```scala
case class Person(name: String, age: Int)

val jsonResult = JsonParser.parseValue.run(input)
val personResult = jsonResult.map { json =>
  json match {
    case JsonValue.Object(fields) =>
      val name = fields.get("name") match {
        case Some(JsonValue.Str(s)) => s
        case _ => throw new Exception("Missing or invalid name")
      }
      val age = fields.get("age") match {
        case Some(JsonValue.Number(n)) => n.toInt
        case _ => throw new Exception("Missing or invalid age")
      }
      Person(name, age)
  }
}
// ✓ Full control
// ✗ Verbose
// ✗ Error-prone (easy to forget fields)
```

## Performance

Decoder derivation happens at **compile time**, so:

- ✓ **Zero runtime overhead** from macro expansion
- ✓ **No reflection** at runtime
- ✓ **Fully inlined** by the Scala compiler

At runtime, the decoder is just a plain object with a `decode` method - as fast as if you wrote it by hand.

## Limitations (Current)

The current implementation has some limitations:

1. **Source type**: Only `JsonValue` is supported (XML, TOML planned for future)
2. **Field names**: Must match exactly (no field renaming yet)
3. **Default values**: Case class default values are not used (planned)
4. **Sum types**: Enums and sealed traits are not yet supported

These limitations will be addressed in future releases.

## Custom Decoders

You can provide your own decoder instances for custom types:

```scala
import java.time.LocalDate
import scala.util.Try

// Define a custom decoder for LocalDate
given Decoder[JsonValue, LocalDate] = new Decoder[JsonValue, LocalDate] {
  def decode(value: JsonValue): Result[DecodeError, LocalDate] = value match {
    case JsonValue.Str(s) =>
      Try(LocalDate.parse(s)).toOption match {
        case Some(date) => Result.Success(date, 0)
        case None =>
          Result.Failure(
            List(DecodeError.Custom(s"Invalid date format: $s", (1, 1, 0))),
            (1, 1, 0)
          )
      }
    case other =>
      Result.Failure(
        List(DecodeError.TypeMismatch("ISO date string", "...", (1, 1, 0))),
        (1, 1, 0)
      )
  }
}

// Now you can use LocalDate in your case classes
case class Event(name: String, date: LocalDate)
given Decoder[JsonValue, Event] = Decoder.derived
```

## Next Steps

- **[Error Handling Guide](error-handling.md)** - Learn about resilient decoding
- **[Examples Directory](../examples/json-to-case-class/)** - Runnable examples
- **[Structural Approach](structural-approach.md)** - When you need more control
- **[API Reference](../README.md#api-reference)** - Complete combinator list

## Summary

The Idiomatic Approach offers:

- ✓ **Automatic derivation** with `Decoder.derived`
- ✓ **Zero boilerplate** for common use cases
- ✓ **Type-safe** decoding with compile-time guarantees
- ✓ **Composable** decoders for nested structures
- ✓ **Fast** - no runtime overhead

Use it for standard data parsing tasks where convenience matters more than custom control.
