# Debugging Parsers Example

This example demonstrates Rumil's built-in debugging combinators for understanding parser behavior during development.

## What This Example Shows

- Using `.trace(name)` to track parser execution
- Using `.debug(name)` to inspect parsed values
- Debugging complex parsers with multiple stages
- Understanding backtracking and alternative parsers
- Finding performance bottlenecks

## Debugging Combinators

### `.trace(name)`

Prints messages showing:
- When the parser starts
- Whether it succeeded or failed
- How many characters were consumed

Example output:
```
[TRACE] number: trying at offset 0
[TRACE] number: success, consumed 2 chars
```

### `.debug(name)`

Prints messages showing:
- The actual parsed value (on success)
- The error details (on failure)

Example output:
```
[DEBUG] expression: trying at offset 0
[DEBUG] expression: success, parsed ((1,+),2)
```

## When to Use Debugging

1. **Development**: Understanding why a parser fails
2. **Performance**: Finding slow combinators
3. **Correctness**: Verifying backtracking behavior
4. **Teaching**: Demonstrating parser execution to learners

## Running the Example

```bash
scala-cli run Example.scala
```

The debug output goes to **stderr**, keeping it separate from your program's normal output.

## Key Insights

- Debug output shows the **order of execution** clearly
- You can see **backtracking** when alternatives are tried
- **Performance** issues become obvious (repeated attempts)
- **Position tracking** helps locate errors in the input

## Best Practices

1. **Remove debug calls** before production (they have overhead)
2. **Use specific names** that identify the parser's purpose
3. **Combine strategically** - don't debug every combinator
4. **Start broad, then narrow** - debug high-level parsers first
