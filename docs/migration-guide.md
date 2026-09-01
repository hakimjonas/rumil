# Migration Guide

## `orElse` semantics changed (breaking)

`orElse` is now **fast alternation without error tracking** (`Parser.Or`). It previously always
used `Parser.RecoverWith`, which surfaced the primary's errors as a `Partial` when the fallback
succeeded.

```scala
// Before
char('a').orElse(char('b')).run("b")   // Partial('b', List(Unexpected('b', expected 'a')), 1)

// After
char('a').orElse(char('b')).run("b")   // Success('b', 1)
```

If you relied on those errors (resilient parsing, IDE diagnostics), switch to `recover`:

```scala
char('a').recover(char('b')).run("b")  // Partial('b', List(Unexpected('b', expected 'a')), 1)
```

Decision guide: simple alternation between valid options → keep `.orElse`; fallback / default
value where the failure details matter → `.recover`. See `docs/error-handling.md`.

## `.recover` overload note

`recover` exists in two shapes, selected by argument type:

- `p.recover(fallback: Parser[E, A])` — parser-level alternation with error tracking (new).
- `p.recover(f: E => A)` — value-level recovery from the error (unchanged).

`recoverWith(p)(f: E => Parser[E2, A])` is unchanged.
