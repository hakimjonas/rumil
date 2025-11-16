# Nested Structures Example

This example demonstrates parsing deeply nested JSON into nested case classes using Rumil's automatic decoder derivation.

## What This Example Shows

- Parsing complex, multi-level JSON structures
- Automatic derivation for nested case classes
- Handling collections within nested objects
- Type-safe decoding with compile-time guarantees

## Domain Model

The example models a blog system with:

```scala
case class Tag(name: String)
case class Post(title: String, content: String, tags: List[Tag])
case class Author(name: String, email: String, posts: List[Post])
```

## Running the Example

```bash
scala-cli run Example.scala
```

## Expected Output

```
✓ Parsed author: Author(Alice Smith, alice@example.com, ...)
  Posts:
    - "Getting Started with Scala"
    - "Advanced Functional Programming"

✓ Parsed blog: Blog(My Tech Blog, ...)
  2 authors, 3 posts total
```

## Key Takeaways

1. **Automatic Derivation**: Each case class gets its own decoder via `Decoder.derived`
2. **Composition**: Decoders compose automatically - if you have `Decoder[JsonValue, Tag]` and `Decoder[JsonValue, List[Tag]]`, the macro can use them
3. **Type Safety**: All field types are checked at compile time
4. **Error Handling**: Missing or mistyped fields are reported with clear error messages
