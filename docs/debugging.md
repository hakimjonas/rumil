# Debugging Parsers

## Overview

Rumil provides two built-in debugging combinators to help you understand parser behavior:

- `.trace(name)` - Track execution (when parser starts/succeeds/fails)
- `.debug(name)` - Inspect values (show what was parsed)

Debug output goes to **stderr**, keeping it separate from normal program output.

## Using .trace()

Shows parser execution flow:

```scala
val number = digit.many1.trace("number").map(_.mkString.toInt)

number.run("42")
// Stderr output:
// [TRACE] number: trying at offset 0
// [TRACE] number: success, consumed 2 chars
```

Use `.trace()` to:
- Understand execution order
- See which parsers are being tried
- Track backtracking behavior
- Find performance bottlenecks

## Using .debug()

Shows actual parsed values:

```scala
val expr = (number ~ char('+') ~ number).debug("expression")

expr.run("1+2")
// Stderr output:
// [DEBUG] expression: trying at offset 0
// [DEBUG] expression: success, parsed ((1,+),2)
```

Use `.debug()` to:
- Verify correct values are being parsed
- Inspect intermediate results
- Understand complex parsers

## Debugging Alternatives

See backtracking in action:

```scala
val trueParser = string("true").trace("try-true")
val falseParser = string("false").trace("try-false")
val bool = (trueParser | falseParser).debug("boolean")

bool.run("false")
// [TRACE] try-true: trying at offset 0
// [TRACE] try-true: failure
// [TRACE] try-false: trying at offset 0
// [TRACE] try-false: success, consumed 5 chars
// [DEBUG] boolean: success, parsed false
```

## Debugging Complex Parsers

Combine multiple debug points:

```scala
val assignment = (
  varName.trace("var") ~
  equals.trace("=") ~
  value.debug("value")
).debug("full-assignment")
```

## Best Practices

1. **Start broad, then narrow** - Debug high-level parsers first, then drill down
2. **Use meaningful names** - `"user-email"` not `"p1"`
3. **Remove before production** - Debug calls have overhead
4. **Check stderr** - Output goes there, not stdout

## Performance Debugging

Find slow parsers by looking for repeated attempts:

```scala
val inefficient = (
  string("aaaa").trace("try-4") |
  string("aaa").trace("try-3") |
  string("aa").trace("try-2")
)

inefficient.run("aaab")
// Stderr shows all backtracking attempts
```

## See Also

- **[Example: Debugging Parsers](../examples/debugging-parsers/)** - Runnable examples
- **[Error Handling](error-handling.md)** - Understanding failures
- **[Performance Guide](performance.md)** - Optimization techniques
