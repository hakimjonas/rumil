# Structural Approach

This section is under construction. Please refer to the main README.md for information about the structural parsing approach.

## Left Recursion

Rumil supports left-recursive grammars natively using the Warth et al. seed-growth algorithm from the paper "Packrat Parsers Can Support Left Recursion" (2008).

### Why Left Recursion Matters

Many natural grammars are left-recursive, especially for expressions with left-associative operators. Without left recursion support, you need awkward workarounds like `chainl1` or manual recursive definitions.

### Direct Left Recursion

Direct left recursion occurs when a parser immediately references itself:

```scala
enum Expr {
  case Num(value: Int)
  case Add(left: Expr, right: Expr)
}

lazy val expr: Parser[ParseError, Expr] = recursive {
  (expr ~ char('+') ~ number).map { case ((l, _), r) => Add(l, r) } |
  number
}
```

Just wrap your recursive parser with `recursive { }`, and Rumil handles the rest automatically.

### Indirect Left Recursion

Indirect left recursion occurs through mutual recursion:

```scala
lazy val a: Parser[ParseError, String] = recursive {
  (b ~ char('x')).map { case (s, c) => s + c } | char('y').map(_.toString)
}

lazy val b: Parser[ParseError, String] = recursive {
  (a ~ char('z')).map { case (s, c) => s + c } | char('w').map(_.toString)
}
```

Both parsers need to be wrapped with `recursive { }`.

### How It Works

The implementation uses the Warth et al. seed-growth algorithm:

1. **Detection**: When a parser calls itself at the same position, left recursion is detected
2. **Seed**: The algorithm starts with a failure as the initial "seed" result
3. **Growth**: It repeatedly re-parses, each time accepting larger results that consume more input
4. **Termination**: The process stops when no more progress is made, returning the largest successful parse

This approach is:
- **Automatic**: No manual workarounds needed
- **Efficient**: Memoization ensures each position is parsed at most once per growth iteration
- **Predictable**: Greedy behavior always chooses the longest match

### When to Use

Use `recursive { }` when:
- Parser directly references itself: `lazy val p = recursive { p ~ ... | ... }`
- Indirect recursion: mutually recursive parsers
- Hidden recursion through epsilon productions or optional parts

You don't need it for:
- Simple recursion through combinators: `val p = many(char('a'))`
- Right recursion: `lazy val p = char('a') ~ p | succeed(())`
- Non-recursive parsers

### Example: Expression Parser

Here's a complete expression parser with precedence:

```scala
val number = digit.many1.map(ds => Expr.Num(ds.mkString.toInt))

lazy val expr: Parser[ParseError, Expr] = recursive {
  term.chainl1(
    (char('+').as((a: Expr, b: Expr) => Expr.Add(a, b))) |
    (char('-').as((a: Expr, b: Expr) => Expr.Sub(a, b)))
  )
}

lazy val term: Parser[ParseError, Expr] = recursive {
  factor.chainl1(
    (char('*').as((a: Expr, b: Expr) => Expr.Mul(a, b))) |
    (char('/').as((a: Expr, b: Expr) => Expr.Div(a, b)))
  )
}

lazy val factor: Parser[ParseError, Expr] =
  number | (char('(') *> expr <* char(')'))
```

### Performance Considerations

Left recursion support adds memoization overhead:
- Memory: O(n × p) where n is input length and p is number of recursive parsers
- Time: Seed growth iterations add constant factor overhead

For simple grammars that don't need left recursion, avoid using `recursive { }` to minimize overhead.
