# Debugging

Two combinators print parse progress without touching parse results:

```scala
import parser.core._
import parser.syntax._

val number = digit.many1.trace("number").map(_.mkString.toInt)
number.run("42")
// [TRACE] number: trying at offset 0
// [TRACE] number: success, consumed 2 chars

val expr = (number ~ char('+') ~ number).debug("expression")
expr.run("1+2")
// [DEBUG] expression: trying at offset 0
// [DEBUG] expression: success, parsed ((1,+),2)
```

- `.trace(label)` reports attempts and consumption; `.debug(label)` adds parsed values and
  failures.
- Both write to **stderr** (`System.err`), so stdout stays clean.
- Errors print their position through the `Show[ParseError]` instance:
  `line L, column C (offset O)` — line/column 1-based, offset 0-based.

For error-shape decisions (fast alternation vs error tracking, recovery), see
[Error Handling](./error-handling.md) and the README's debugging section.
