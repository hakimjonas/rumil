# Task: Implement Left Recursion Support (ROADMAP 2.1)

## Context

You are working on **Rumil**, a Scala 3 parser combinator library. Currently, left-recursive grammars require awkward workarounds using `Parser.Custom` and `chainl1`/`chainr1` combinators. This task implements proper left recursion support using the **Warth et al. seed-growth algorithm**.

**Current Status:**
- ✅ Priority 1 complete (Core features, Documentation, Debugging)
- ✅ CI/CD infrastructure setup complete
- 🎯 Now implementing Priority 2.1: Left Recursion Support

**Author:** Hakim Jonas Ghoula <hakim@ghoula.net>

## Critical Constraints

1. **NO CLAUDE/ANTHROPIC ATTRIBUTION**
   - NEVER add "Generated with Claude Code" or similar
   - NO "Co-Authored-By: Claude" in commits
   - Author MUST be: Hakim Jonas Ghoula <hakim@ghoula.net>

2. **FOLLOW EXISTING ARCHITECTURE**
   - Parser is an enum with cases like `Succeed`, `Map`, `FlatMap`, etc.
   - Interpreter pattern in `parser.runtime.Interpreter`
   - Use named tuples and enums (Structural-First Design)
   - NO runtime type casting (use covariance/contravariance)

3. **MAINTAIN BACKWARD COMPATIBILITY**
   - Existing parsers must continue to work unchanged
   - `chainl1` and `chainr1` should still work (but become optional)
   - No breaking changes to public API

## The Problem: Current Left Recursion Workaround

Currently, users must write awkward code for left-recursive grammars:

```scala
// Current workaround (AWKWARD):
lazy val expr: Parser[ParseError, Int] =
  Parser.Custom { state =>
    parser.runtime.interpret(
      term.chainl1(
        (char('+').as((a: Int, b: Int) => a + b)) |
        (char('-').as((a: Int, b: Int) => a - b))
      ),
      state
    )
  }

// What users WANT to write (NATURAL):
lazy val expr: Parser[ParseError, Expr] =
  (expr ~ char('+') ~ term).map { case ((l, _), r) => Add(l, r) } |
  (expr ~ char('-') ~ term).map { case ((l, _), r) => Sub(l, r) } |
  term
```

The natural version would cause infinite recursion without left recursion support.

## The Solution: Warth et al. Seed-Growth Algorithm

**Paper:** "Packrat Parsers Can Support Left Recursion" by Alessandro Warth, James R. Douglass, and Todd Millstein (2008)

**Key Idea:**
1. Detect when a parser calls itself at the same input position (left recursion)
2. Start with a "seed" result (usually failure)
3. "Grow" the seed by repeatedly re-parsing until no more progress is made
4. Return the largest successful parse

**Algorithm Overview:**
```
function parse(rule, pos):
  if memo[rule, pos] exists:
    return memo[rule, pos]

  if currently_parsing[rule, pos]:  // Left recursion detected!
    return FAIL  // Initial seed

  currently_parsing[rule, pos] = true
  result = evaluate(rule, pos)
  currently_parsing[rule, pos] = false

  if is_left_recursive(rule, pos):
    result = grow(rule, pos, result)  // Seed growth

  memo[rule, pos] = result
  return result

function grow(rule, pos, seed):
  while true:
    result = evaluate(rule, pos)
    if result <= seed:  // No more progress
      return seed
    seed = result
```

## Your Tasks

### Phase 1: Add Memoization Infrastructure

#### 1.1 Create MemoKey Type

Add to `core/src/main/scala/parser/core/Types.scala`:

```scala
/**
 * Key for memoization table.
 *
 * Identifies a parser at a specific input position.
 * Uses reference equality for parser identity.
 */
case class MemoKey(
  parserId: Int,      // Unique ID for each parser instance
  position: Int       // Input offset
)

/**
 * Entry in the memoization table.
 *
 * Tracks parse results and left-recursion detection.
 */
enum MemoEntry[+E, +A] {
  /** Parse is currently in progress (for cycle detection) */
  case InProgress

  /** Parse completed with this result */
  case Completed[E, A](result: Result[E, A], consumed: Int)

  /** Left-recursive parse being grown */
  case Growing[E, A](seed: Result[E, A], consumed: Int)
}
```

#### 1.2 Update ParserState

Modify `core/src/main/scala/parser/runtime/ParserState.scala`:

```scala
final class ParserState(val input: String, val debugEnabled: Boolean) {
  private var pos: Int = 0
  private var line: Int = 1
  private var col: Int = 1

  // NEW: Memoization table
  private val memo: scala.collection.mutable.Map[MemoKey, MemoEntry[?, ?]] =
    scala.collection.mutable.Map.empty

  // NEW: Stack for detecting left recursion
  private val recursionStack: scala.collection.mutable.Set[MemoKey] =
    scala.collection.mutable.Set.empty

  // Existing methods...

  // NEW: Memoization methods
  def getMemo(key: MemoKey): Option[MemoEntry[?, ?]] =
    memo.get(key)

  def setMemo(key: MemoKey, entry: MemoEntry[?, ?]): Unit =
    memo(key) = entry

  def isInProgress(key: MemoKey): Boolean =
    recursionStack.contains(key)

  def enterRecursion(key: MemoKey): Unit =
    recursionStack.add(key)

  def exitRecursion(key: MemoKey): Unit =
    recursionStack.remove(key)
}
```

### Phase 2: Add Parser Case for Left Recursion

Add to `Parser` enum in `core/src/main/scala/parser/core/Types.scala`:

```scala
enum Parser[+E, +A] {
  // ... existing cases ...

  /**
   * A parser that may be left-recursive.
   *
   * Wraps a parser to enable memoization and left-recursion handling.
   *
   * @param id Unique identifier for this parser instance
   * @param parser The underlying parser
   */
  case Recursive[E, A](id: Int, parser: () => Parser[E, A]) extends Parser[E, A]
}
```

### Phase 3: Implement Left Recursion in Interpreter

Modify `core/src/main/scala/parser/runtime/Interpreter.scala`:

Add these helper functions:

```scala
/**
 * Generates unique parser IDs.
 */
private val nextParserId = new java.util.concurrent.atomic.AtomicInteger(0)

/**
 * Attempts to parse with memoization and left-recursion support.
 *
 * Implements the Warth et al. seed-growth algorithm.
 */
private def parseWithMemo[E, A](
  parserId: Int,
  parser: () => Parser[E, A],
  state: ParserState
): Result[E, A] = {
  val key = MemoKey(parserId, state.offset)

  // Check memo table
  state.getMemo(key) match {
    case Some(MemoEntry.Completed(result, consumed)) =>
      // Cache hit: restore position and return cached result
      state.advance(consumed)
      return result.asInstanceOf[Result[E, A]]

    case Some(MemoEntry.InProgress) =>
      // Left recursion detected! Return failure as seed
      return Result.Failure(List(), state.location)

    case Some(MemoEntry.Growing(seed, _)) =>
      // Currently growing, return seed
      return seed.asInstanceOf[Result[E, A]]

    case None =>
      // Not memoized, continue
  }

  // Mark as in progress
  state.setMemo(key, MemoEntry.InProgress)
  state.enterRecursion(key)

  val startPos = state.offset
  val result = interpret(parser(), state)
  val consumed = state.offset - startPos

  state.exitRecursion(key)

  // Check if this was actually left-recursive
  state.getMemo(key) match {
    case Some(MemoEntry.InProgress) =>
      // Not left-recursive, just memoize
      state.setMemo(key, MemoEntry.Completed(result, consumed))
      result

    case _ =>
      // Was left-recursive (seed was used), grow it
      growSeed(parserId, parser, state, key, startPos, result, consumed)
  }
}

/**
 * Grows a left-recursive parse using the seed-growth algorithm.
 *
 * Repeatedly re-parses until no more progress is made.
 */
private def growSeed[E, A](
  parserId: Int,
  parser: () => Parser[E, A],
  state: ParserState,
  key: MemoKey,
  startPos: Int,
  initialResult: Result[E, A],
  initialConsumed: Int
): Result[E, A] = {
  var seed = initialResult
  var seedConsumed = initialConsumed

  // Keep growing while we make progress
  while (true) {
    state.restore(state.save.copy(offset = startPos, ...))
    state.setMemo(key, MemoEntry.Growing(seed, seedConsumed))

    val result = interpret(parser(), state)
    val consumed = state.offset - startPos

    // Check if we made progress
    result match {
      case Result.Success(_, _) if consumed > seedConsumed =>
        // Made progress, update seed
        seed = result
        seedConsumed = consumed

      case Result.Partial(_, _, _) if consumed > seedConsumed =>
        // Made progress, update seed
        seed = result
        seedConsumed = consumed

      case _ =>
        // No more progress, we're done
        state.setMemo(key, MemoEntry.Completed(seed, seedConsumed))
        state.restore(state.save.copy(offset = startPos + seedConsumed, ...))
        return seed
    }
  }

  seed // Unreachable, but needed for type checker
}
```

Add case to `interpret` function:

```scala
def interpret[E, A](parser: Parser[E, A], state: ParserState): Result[E, A] = {
  parser match {
    // ... existing cases ...

    case Parser.Recursive(id, p) =>
      parseWithMemo(id, p, state)
  }
}
```

### Phase 4: Add Helper Combinator

Add to `core/src/main/scala/parser/core/Combinators.scala`:

```scala
/**
 * Marks a parser as potentially left-recursive.
 *
 * Enables memoization and the seed-growth algorithm.
 * Use this for any parser that directly or indirectly references itself.
 *
 * @param parser A lazy parser (use `=> Parser` for recursive definitions)
 * @return A parser with left-recursion support
 *
 * Example:
 * {{{
 * lazy val expr: Parser[ParseError, Expr] = recursive {
 *   (expr ~ char('+') ~ term).map { case ((l, _), r) => Add(l, r) } |
 *   (expr ~ char('-') ~ term).map { case ((l, _), r) => Sub(l, r) } |
 *   term
 * }
 * }}}
 */
def recursive[E, A](parser: => Parser[E, A]): Parser[E, A] = {
  val id = nextParserId.getAndIncrement()
  Parser.Recursive(id, () => parser)
}
```

Add syntax extension in `core/src/main/scala/parser/syntax/Extensions.scala`:

```scala
extension [E, A](p: => Parser[E, A]) {
  /**
   * Marks this parser as potentially left-recursive.
   *
   * Enables memoization and handles left recursion automatically.
   */
  def recursive: Parser[E, A] =
    parser.core.recursive(p)
}
```

### Phase 5: Update Examples

#### 5.1 Update ArithmeticParser

Simplify `core/src/test/scala/parser/examples/ArithmeticParser.scala`:

Replace the awkward `Parser.Custom` version with:

```scala
// NEW: Clean, natural left-recursive grammar
enum Expr {
  case Num(value: Int)
  case Add(left: Expr, right: Expr)
  case Sub(left: Expr, right: Expr)
  case Mul(left: Expr, right: Expr)
  case Div(left: Expr, right: Expr)
}

val number: Parser[ParseError, Expr] =
  digit.many1.map(ds => Expr.Num(ds.mkString.toInt))

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

// Or even more natural (direct left recursion):
lazy val exprDirect: Parser[ParseError, Expr] = recursive {
  (exprDirect ~ char('+') ~ term).map { case ((l, _), r) => Expr.Add(l, r) } |
  (exprDirect ~ char('-') ~ term).map { case ((l, _), r) => Expr.Sub(l, r) } |
  term
}
```

#### 5.2 Create New Tests

Add to `core/src/test/scala/parser/LeftRecursionTests.scala`:

```scala
package parser

import munit.FunSuite
import parser.core._
import parser.syntax._

class LeftRecursionTests extends FunSuite {

  test("direct left recursion - list") {
    // Grammar: list = list ',' item | item
    enum ListExpr {
      case Single(value: Int)
      case Cons(list: ListExpr, value: Int)
    }

    val item = digit.map(_.toString.toInt)

    lazy val list: Parser[ParseError, ListExpr] = recursive {
      (list ~ char(',') ~ item).map { case ((l, _), i) => ListExpr.Cons(l, i) } |
      item.map(ListExpr.Single(_))
    }

    val result = list.run("1,2,3,4")
    assert(result.isSuccess)
  }

  test("indirect left recursion") {
    // Grammar: a = b 'x' | 'y'
    //          b = a 'z' | 'w'
    lazy val a: Parser[ParseError, String] = recursive {
      (b ~ char('x')).map { case (s, c) => s + c } |
      char('y').map(_.toString)
    }

    lazy val b: Parser[ParseError, String] = recursive {
      (a ~ char('z')).map { case (s, c) => s + c } |
      char('w').map(_.toString)
    }

    assertEquals(a.run("wxzx").toOption, Some("wxzx"))
  }

  test("arithmetic with direct left recursion") {
    enum Expr {
      case Num(n: Int)
      case Add(l: Expr, r: Expr)
      case Mul(l: Expr, r: Expr)
    }

    val num = digit.many1.map(ds => Expr.Num(ds.mkString.toInt))

    lazy val expr: Parser[ParseError, Expr] = recursive {
      (expr ~ char('+') ~ term).map { case ((l, _), r) => Expr.Add(l, r) } |
      term
    }

    lazy val term: Parser[ParseError, Expr] = recursive {
      (term ~ char('*') ~ factor).map { case ((l, _), r) => Expr.Mul(l, r) } |
      factor
    }

    lazy val factor: Parser[ParseError, Expr] =
      num | (char('(') *> expr <* char(')'))

    val result = expr.run("2+3*4")
    assertEquals(result.toOption.get, Expr.Add(Expr.Num(2), Expr.Mul(Expr.Num(3), Expr.Num(4))))
  }

  test("left recursion with backtracking") {
    lazy val p: Parser[ParseError, String] = recursive {
      (p ~ char('a')).map { case (s, c) => s + c } |
      (p ~ char('b')).map { case (s, c) => s + c } |
      char('c').map(_.toString)
    }

    assertEquals(p.run("caabaa").toOption, Some("caabaa"))
  }

  test("hidden left recursion") {
    // Grammar: a = (b 'x') | 'y'
    //          b = (a)
    lazy val a: Parser[ParseError, String] = recursive {
      (b ~ char('x')).map { case (s, c) => s + c } |
      char('y').map(_.toString)
    }

    lazy val b: Parser[ParseError, String] = recursive { a }

    assertEquals(a.run("yxxx").toOption, Some("yxxx"))
  }

  test("non-left-recursive parser still works") {
    val p = char('a') ~ char('b') ~ char('c')
    assertEquals(p.run("abc").toOption, Some((('a', 'b'), 'c')))
  }

  test("existing chainl1 still works") {
    val num = digit.map(_.toString.toInt)
    val add = char('+').as((a: Int, b: Int) => a + b)
    val p = num.chainl1(add)
    assertEquals(p.run("1+2+3").toOption, Some(6))
  }
}
```

### Phase 6: Documentation

#### 6.1 Update docs/structural-approach.md

Add section:

```markdown
## Left Recursion

Rumil supports left-recursive grammars natively using the Warth et al. seed-growth algorithm.

### Direct Left Recursion

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

Just wrap your recursive parser with `.recursive` (or use the `recursive { }` helper), and Rumil handles the rest automatically.

### How It Works

1. **Detection**: Rumil detects when a parser calls itself at the same position
2. **Seed**: Starts with a failure as the initial "seed" result
3. **Growth**: Re-parses repeatedly, each time accepting larger results
4. **Termination**: Stops when no more progress is made

### When to Use

Use `.recursive` when:
- Parser directly references itself: `lazy val p = recursive { p ~ ... | ... }`
- Indirect recursion: `lazy val a = recursive { b ~ ... }; lazy val b = recursive { a ~ ... }`
- Hidden recursion through epsilon productions

You don't need it for:
- Simple recursion through other combinators: `val p = many(char('a'))`
- Right recursion: `lazy val p = char('a') ~ p | succeed(())`
```

#### 6.2 Update README.md

Add to features section:

```markdown
- **Left Recursion Support** - Write natural grammars without awkward workarounds
  ```scala
  lazy val expr = recursive {
    (expr ~ char('+') ~ term).map(Add) | term
  }
  ```
```

### Phase 7: Commit and Test

1. **Run Full Test Suite**
   ```bash
   sbt clean
   sbt test
   ```
   - All existing tests must pass
   - All new left recursion tests must pass

2. **Run Formatting**
   ```bash
   sbt scalafmtAll scalafmtSbt
   ```

3. **Verify Backward Compatibility**
   - Existing parsers work unchanged
   - `chainl1`/`chainr1` still function correctly
   - Examples compile and run

4. **Create Commit**
   ```bash
   git add .
   git commit -m "$(cat <<'EOF'
   Implement left recursion support (ROADMAP 2.1)

   Adds native support for left-recursive grammars using the Warth et al.
   seed-growth algorithm. Users can now write natural, direct left-recursive
   parsers without awkward workarounds.

   Key changes:
   - Added memoization infrastructure (MemoKey, MemoEntry)
   - Extended ParserState with memo table and recursion stack
   - Implemented seed-growth algorithm in Interpreter
   - Added Parser.Recursive case and recursive() combinator
   - Simplified ArithmeticParser examples (removed Parser.Custom)
   - Added comprehensive LeftRecursionTests (15+ test cases)
   - Updated documentation with left recursion guide

   The implementation maintains full backward compatibility. Existing parsers
   and chainl1/chainr1 combinators continue to work unchanged.

   Based on: "Packrat Parsers Can Support Left Recursion"
   by Warth, Douglass, and Millstein (2008)
   EOF
   )"
   ```

## Expected Deliverables

1. ✅ Memoization infrastructure added to ParserState
2. ✅ Parser.Recursive case added to Parser enum
3. ✅ Seed-growth algorithm implemented in Interpreter
4. ✅ `recursive()` combinator and syntax extension
5. ✅ ArithmeticParser simplified (no more Parser.Custom)
6. ✅ LeftRecursionTests with 15+ comprehensive tests
7. ✅ Documentation updated with left recursion guide
8. ✅ All tests passing
9. ✅ Backward compatible (no breaking changes)

## Success Criteria

- ✅ Direct left recursion works: `lazy val p = recursive { p ~ ... | ... }`
- ✅ Indirect left recursion works: mutually recursive parsers
- ✅ Hidden left recursion works: through epsilon productions
- ✅ Existing parsers unchanged and still work
- ✅ `chainl1`/`chainr1` still work correctly
- ✅ All tests pass (existing + new)
- ✅ ArithmeticParser example simplified and cleaner
- ✅ Performance acceptable (memoization has overhead)

## Important Notes

1. **Memoization Overhead**: This adds memory overhead. For simple grammars that don't need left recursion, users can avoid using `.recursive`.

2. **Parser Identity**: The `parserId` uniquely identifies each parser instance. Each `recursive { }` call creates a new ID.

3. **State Management**: The memoization table is per-parse, not global. Each `run()` starts fresh.

4. **Testing**: Focus on:
   - Direct left recursion (expr ~ expr)
   - Indirect left recursion (a ~ b, b ~ a)
   - Hidden left recursion (through epsilon/optional)
   - Backtracking with left recursion
   - Precedence with left recursion

5. **Reference Implementation**: Check the paper for edge cases and examples.

## References

- **Paper**: "Packrat Parsers Can Support Left Recursion" by Warth et al. (2008)
- **Scala Implementation**: Consider looking at how parsley or cats-parse handle this (but maintain Rumil's architecture)
- **Tests**: Edge cases from the paper should have corresponding tests

## After Completion

Report back with:
1. Summary of implementation approach
2. Test results (all tests passing)
3. Performance impact (if measurable)
4. Any challenges or deviations from this plan
5. Examples showing the simplified API
