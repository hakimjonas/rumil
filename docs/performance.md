# Performance Guide

## Implementation Characteristics

Rumil's runtime has these performance properties:

- **Tail-recursive interpreter** - No stack overflow on deep recursion
- **State snapshots** for backtracking - Efficient position tracking
- **String slicing** for substrings - No copying
- **Zero-allocation combinators** - Minimal GC pressure

## Benchmarks

Typical performance (100 iterations):

| Task | Time |
|------|------|
| Parse 1000 digits | ~20ms |
| Parse 100 separated numbers | ~25ms |
| Arithmetic expression (10 ops) | ~15ms |
| Deeply nested structures (20 levels) | ~5ms |

## Optimization Techniques

### 1. Avoid Excessive Backtracking

**Bad** - tries many alternatives:
```scala
val inefficient = string("aaaa") | string("aaa") | string("aa") | string("a")
```

**Good** - order alternatives by specificity:
```scala
val efficient = string("aaaa") | string("aa") | string("a")
```

**Better** - use longest match first:
```scala
val best = string("aaaa") | string("a")
```

### 2. Use Atomic Parsers

For mutually exclusive alternatives, parse to a common type:

```scala
// Instead of:
val keyword = string("if") | string("else") | string("while") | string("for")

// Use:
val keyword = (string("if") | string("else") | string("while") | string("for"))
  .map(Keyword(_))
```

### 3. Minimize `.many` on Complex Parsers

**Slow**:
```scala
val slow = complexParser.many  // Tries complexParser repeatedly
```

**Faster**:
```scala
val faster = satisfy(isSimpleChar).many  // Simple predicate
```

### 4. Use Parser.Custom for Recursion

**Bad** - can cause stack overflow:
```scala
lazy val expr = term | (expr ~ op ~ expr)
```

**Good** - uses trampoline:
```scala
lazy val expr = Parser.Custom { state =>
  parser.runtime.interpret(term | (expr ~ op ~ expr), state)
}
```

### 5. Decode After Parsing

**Slow** - mixing parsing and validation:
```scala
val email = (user ~ char('@') ~ domain).flatMap { case ((u, _), d) =>
  if (isValidDomain(d)) succeed((u, d)) else fail("Invalid domain")
}
```

**Fast** - parse first, validate later:
```scala
val email = user ~ char('@') ~ domain
// Validate the result after parsing completes
```

## Profiling

Use `.trace()` to identify performance bottlenecks:

```scala
val parser = (
  step1.trace("step1") *>
  step2.trace("step2") *>
  step3.trace("step3")
)

// Look for steps that are tried many times
```

## When Performance Matters

Optimize when:
- Parsing large files (>100MB)
- Real-time parsing (language servers, REPLs)
- Parsing in tight loops

Don't optimize prematurely:
- Most parsing tasks are I/O bound, not CPU bound
- Clarity > performance for one-time scripts
- Profile before optimizing

## Comparison with Other Libraries

| Library | Parser Type | Performance |
|---------|-------------|-------------|
| **Rumil** | Combinator, interpreted | Good |
| fastparse | Combinator, compiled | Excellent |
| cats-parse | Combinator, trampolined | Good |
| Parsec (Haskell) | Combinator, interpreted | Good |
| ANTLR | Generated | Excellent |

Rumil favors **clarity and correctness** over raw speed. For production parsing of massive files, consider fastparse or ANTLR.

## See Also

- **[Debugging Guide](debugging.md)** - Find slow parsers with `.trace()`
- **[Structural Approach](structural-approach.md)** - Efficient combinator patterns
