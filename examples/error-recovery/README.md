# Error Recovery Example

This example demonstrates Rumil's **resilient parsing** capabilities - the ability to recover from errors and continue parsing.

## What This Example Shows

- Using `.attempt` to capture failures as values
- Using `.recover` and `.recoverWith` for fallback values
- Multi-error accumulation (Result.Partial)
- Parsing malformed input and extracting what's valid
- Position tracking for error reporting

## Why Error Recovery Matters

Traditional parsers fail on the first error. But in many real-world scenarios, you want to:

1. **Parse as much as possible** even if some parts are invalid
2. **Collect all errors** for better debugging
3. **Provide partial results** to the user
4. **Build IDE tooling** that shows all errors at once

This is especially important for:
- **Language servers** (show all syntax errors in a file)
- **Data migration** (process valid records, log invalid ones)
- **Configuration files** (highlight all problems at once)

## Running the Example

```bash
scala-cli run Example.scala
```

## Expected Output

The example shows:

1. **Successful Recovery**: Parser recovers from missing optional fields
2. **Partial Parsing**: Extracts valid data while accumulating errors
3. **Error Accumulation**: Collects multiple errors instead of failing on the first
4. **Position Tracking**: Reports where each error occurred

## Key Combinators

| Combinator | Purpose |
|------------|---------|
| `.attempt` | Converts failure to success containing Result |
| `.recover(f)` | Provides fallback value on failure |
| `.recoverWith(p2)` | Provides fallback parser on failure |
| `Result.Partial` | Holds both a value and accumulated errors |

## Comparison with Other Libraries

| Library | Error Recovery | Multi-Error Accumulation |
|---------|----------------|-------------------------|
| **Rumil** | ✓ Built-in | ✓ Result.Partial |
| fastparse | ✓ Limited | ✗ Single error |
| cats-parse | ✗ Fails fast | ✗ Single error |
| Parsec | ✗ Fails fast | ✗ Single error |
