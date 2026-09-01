# Error Handling

## Alternation vs Error Recovery

Rumil provides two combinators for trying a fallback parser when the primary fails. They differ
in exactly one thing: whether errors from the failed primary are tracked.

### `orElse` — fast alternation (use for choice between valid options)

Tries alternatives until one succeeds. Errors from failed branches are discarded. Matches the
`<|>` semantics of cats-parse and Parsec.

```scala
val letter = char('a').orElse(char('b')).orElse(char('c'))
letter.run("b")   // Success('b', 1) — no error tracking

val keyword = string("if").orElse(string("else")).orElse(string("while"))
```

### `recover` — alternation with error tracking (use when diagnostics matter)

Same fallback behavior, but when the fallback succeeds the result is a `Partial` carrying the
primary's errors. Use this for resilient parsing where you need error diagnostics even on
recovered input (IDE squiggles, lossless trees).

```scala
val number = digit.many1.map(_.mkString.toInt)
val resilient = number.recover(succeed(0))
resilient.run("abc")   // Partial(0, errors, 0) — recovered, with errors
resilient.run("42")    // Success(42, 2) — primary succeeded
```

### Decision tree

1. **Alternation between valid options?** (`upper.orElse(lower)`, keyword choice, JSON value
   dispatch) → `.orElse` — fast, no tracking. The common case.
2. **Fallback / default value on failure, with diagnostics?** (`field.recover(succeed(""))`,
   `number.recover(succeed(0))`) → `.recover` — tracks errors as `Partial`.
3. **Skip-and-continue inside `many` with frequent failures?**
   → `.orElse` unless you need the errors; error tracking has a measured cost on
   fallback-heavy workloads.

Note: the function-shaped overloads `recover(p)(f)` (fallback value from the error) and
`recoverWith(p)(f)` (fallback parser built from the error) always succeed / may still fail
respectively — they are value-level recovery, distinct from the parser-level `recover(p, fallback)`.

## Other error combinators

- `expect(p, message)` — replace all errors with a single custom message on failure.
- `named(p, name)` / `p.named(name)` — add a label to the expected set.
- `p.attempt` — capture the result as a value; never fails.
- `lookAhead(p)`, `notFollowedBy(p)` — zero-width combinators for context checks.

See `docs/api-reference.md` for the full tables and `docs/migration-guide.md` for the
orElse/recover semantics change.
