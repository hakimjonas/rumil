# JSON to Case Class Examples

This directory demonstrates two approaches for parsing JSON and converting it to Scala case classes:

1. **StructuralExample.scala** - Pure combinators with manual mapping
2. **IdiomaticExample.scala** - Automatic decoder derivation

## Running the Examples

With scala-cli:

```bash
# Run the idiomatic example (automatic derivation)
scala-cli run IdiomaticExample.scala

# Run the structural example (manual mapping)
scala-cli run StructuralExample.scala
```

With sbt (from project root):

```bash
# First add examples as a subproject, then run
sbt "examples/runMain examples.jsontocaseclass.idiomaticJsonExample"
sbt "examples/runMain examples.jsontocaseclass.structuralJsonExample"
```

## What Each Example Shows

### Idiomatic Example

- Automatic case class derivation using `Decoder.derived`
- Clean, concise code with minimal boilerplate
- Type-safe JSON decoding with compile-time guarantees
- Proper error handling with Result types

**Use when:**
- Building REST API clients
- Parsing configuration files
- Standard CRUD operations
- You want maximum convenience

### Structural Example

- Manual JSON parsing with pure combinators
- Full control over the parsing process
- Custom JSON value representation
- Explicit mapping to case classes

**Use when:**
- Building JSON tooling (formatters, linters, etc.)
- Need custom JSON representations
- Require lossless syntax trees
- Maximum control and transparency

## Expected Output

Both examples parse the same JSON input:

```json
{"name": "Alice", "age": 30, "admin": true}
```

And produce the same output:

```
Parsed user: User(Alice, 30, true)
```

The difference is in **how** they achieve this result.
