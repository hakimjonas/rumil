# Parser Combinator Library Manifesto

**Version**: 0.1.0
**Target**: Scala 3.7.4 + Java 25 LTS → Fungal
**Philosophy**: Pure Core + Imperative Shell

---

## Vision

A pure functional parser combinator library built from first principles. Every design decision prioritizes Fungal portability and radical simplicity.

---

## The Three Types

We use exactly three kinds of types:

### 1. Enums (Sum Types)
```scala
enum Parser[+E, +A] {
  case Succeed[A](value: A) extends Parser[Nothing, A]
  case Fail[E](error: E) extends Parser[E, Nothing]
  // ...
}
```

### 2. Named Tuples (Product Types)
```scala
type Location = (line: Int, column: Int, offset: Int)
type StateSnapshot = (offset: Int, line: Int, column: Int)
```

### 3. Regular Classes (Controlled Mutation Only)
```scala
// Ref - encapsulates var (Eru pattern)
private final class Ref[A](private var value: A) {
  def get: A = value
  def set(newValue: A): Unit = { value = newValue }
  def update(f: A => A): Unit = { value = f(value) }
}

// ParserState - uses Refs for controlled mutation
final class ParserState(/* ... */) {
  private val offsetRef: Ref[Int]
  def advance(): Unit = offsetRef.update(_ + 1)
}
```

---

## What We Never Use

❌ **Case classes** - Use named tuples instead
❌ **Sealed traits** - Use enums instead
❌ **Naked vars** - Use Ref for controlled mutation
❌ **Opaque types** - Keep it simple
❌ **Objects as namespaces** - Use top-level functions

---

## Architecture: Pure Core + Imperative Shell

### Pure Core (User API)
- Enums (Parser, Result, ParseError)
- Named tuples (Location, Span)
- Top-level functions (combinators, primitives)
- Extensions (ergonomic syntax)
- Type classes (lawful abstractions)

### Imperative Shell (Internal)
- Ref class (encapsulated mutation)
- ParserState class (controlled mutation via Refs)
- Interpreter (executes pure descriptions with mutation)

**Clear Boundary**: Users never see mutation. The imperative shell is isolated.

---

## Design Principles

### Foundational Correctness
- Parsers are pure data (descriptions, not procedures)
- Type-safe (union types, GADTs via enums)
- Lawful (Functor, Monad laws verified)

### Radical Ergonomics
- For-comprehension native
- Extension methods for fluency
- Clear error messages

### Guided Correctness
- Stack-safe by default (via Ref-based state)
- Explicit backtracking (save/restore)
- Type errors prevent common mistakes

### Exceptional Observability
- Structured errors (ParseError enum)
- Location tracking (line, column, offset)
- Pretty printing via Show type class

---

## Fungal Portability

Every design choice translates directly to Fungal:

```fungal
// Enums → Fungal ADTs
type Parser[E, A] = Succeed(A) | Fail(E) | ...

// Named tuples → Fungal records
type Location = (line: Int, column: Int, offset: Int)

// Ref → Fungal Ref (like Haskell's IORef)
type Ref[A] = ... // Built-in mutable reference

// Top-level functions → Fungal top-level functions
fun succeed[A](value: A): Parser[Nothing, A] = Succeed(value)
```

Zero impedance mismatch!

---

## Technical Stack

**Scala 3.7.4** - Latest features:
- Enhanced named tuples
- Improved inline optimization
- Safe initialization checking
- Better union type handling

**Java 25 LTS** - Optimal runtime:
- G1GC (best for parsing workloads)
- String deduplication (saves memory on tokens)
- Modern JVM optimizations

**Braces-only syntax** - Enforced via compiler flags for consistency and Fungal portability.

---

## Success Metrics

1. ✅ Zero case classes in the codebase
2. ✅ Zero sealed traits (use enums)
3. ✅ All monad laws verified (ScalaCheck)
4. ✅ Arithmetic parser in <50 lines
5. ✅ JSON parser in <100 lines
6. ✅ Ports to Fungal in <1 week

---

## Non-Goals

- ❌ Backwards compatibility with Scala 2
- ❌ Parser generation from grammars
- ❌ Packrat parsing (opt-in, not built-in)
- ❌ Competing with parser generators on raw speed

---

**This manifesto is law. Every line of code must align with these principles.**
