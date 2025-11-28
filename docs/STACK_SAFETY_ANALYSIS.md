# Stack Safety Analysis: The Full Picture

**Date**: November 27, 2025
**Status**: Critical analysis - we've been here before and need clarity

---

## Executive Summary

We have made **contradictory claims** about stack safety:
- README: "7,000,000+ nested recursive rules"
- Reality: True nested recursion hits StackOverflow at ~500K-1M

**This document provides the complete analysis and paths forward.**

---

## What We Actually Have

### Parser ADT (23 cases)

```scala
enum Parser[+E, +A] {
  case Succeed, Fail                    // Terminals (no recursion needed)
  case Satisfy, StringMatch, StringChoice, Eof  // Terminals
  case Map, FlatMap                     // Structural recursion
  case Or, Choice                       // Alternation (backtracking)
  case Many, Many1, Optional            // Repetition
  case Attempt, LookAhead, NotFollowedBy // Control flow
  case Named, Trace, Debug, Expect      // Metadata
  case Defer                            // Lazy evaluation
  case RecoverWith                      // Error recovery
  case Memo                             // Memoization + left recursion
}
```

### Current Interpreter Architecture

```
User calls: run(parser, input)
            │
            ▼
    ┌───────────────────┐
    │  TrampolineOpt    │  ← Stack-safe for FlatMap/Map chains
    │  (while loop)     │
    └───────────────────┘
            │
            │  For non-FlatMap/Map cases:
            ▼
    ┌───────────────────┐
    │   interpretI      │  ← RECURSIVE! Uses JVM call stack
    │   (recursive)     │
    └───────────────────┘
```

### What TrampolineOpt Handles (Stack-Safe)

```scala
// TrampolineOpt.run - lines 50-84
while (current ne NoParser)
  current match {
    case Parser.FlatMap(source, f) =>  // ✅ Pushed to Frame stack
      stack(stackTop) = Frame.FlatMap(fn, consumedAcc)
      current = source

    case Parser.Map(source, f) =>      // ✅ Fused with mapFn accumulator
      mapFn = compose(mapFn, f)
      current = source

    case _ =>                           // ❌ Falls through to interpretI
      result = interpretI(current, state)
}
```

### What interpretI Handles (RECURSIVE - Not Stack-Safe)

| Parser Case | How Handled | Stack Usage |
|-------------|-------------|-------------|
| `Succeed`, `Fail` | Terminal | O(1) |
| `Satisfy`, `StringMatch` | Terminal | O(1) |
| `Map(source, f)` | `interpretI(source, state)` | **O(depth)** |
| `FlatMap(source, f)` | `interpretI(source, state)` then `interpretI(f(v), state)` | **O(depth)** |
| `Or(left, right)` | `interpretI(left, state)` then maybe `interpretI(right, state)` | **O(depth)** |
| `Choice(alts)` | Tail-recursive helper, but calls `interpretI(head, state)` | **O(depth)** |
| `Many(p)` | While loop calling `interpretI(p, state)` | O(1) per item |
| `Optional(p)` | `interpretI(p, state)` | **O(depth)** |
| `Defer(thunk)` | `interpretI(thunk(), state)` | **O(depth)** |
| `Memo(inner, ...)` | `interpretI(inner, state)` via memo machinery | **O(depth)** |

---

## The Problem with True Recursion

### What Happens with `rule { nested | base }`

```scala
lazy val nested: Parser[ParseError, Int] = rule {
  (char('[') *> defer(nested) <* char(']')).map(_ + 1) | succeed(0)
}
```

Call stack for input `"[[[...]]]"` (depth N):

```
run(nested, input)
  └─ TrampolineOpt.run
      └─ interpretI(Memo(...))           // Frame 1
          └─ interpretMemoI
              └─ interpretI(Or(...))     // Frame 2
                  └─ interpretI(FlatMap(...))  // Frame 3
                      └─ interpretI(Defer(...))  // Frame 4
                          └─ interpretI(nested)  // RECURSION!
                              └─ interpretI(Memo(...))  // Frame 5
                                  └─ ... N more frames ...
```

**Each level of recursion adds ~4-5 JVM stack frames.**

With default 1MB stack (~8K-10K frames available):
- 500K recursion depth → ~2M frames → StackOverflow
- Actual observed limit: ~500K-1M depending on JVM

---

## Why 7M "Works" for Sequential Parsers

The 7M claim came from testing **sequential FlatMap chains**:

```scala
var p: Parser[ParseError, Any] = char('1')
for (_ <- 1 until 7_000_000) {
  p = p ~ char('1')  // This creates FlatMap(p, ...)
}
```

This works because `TrampolineOpt` handles FlatMap with an explicit stack:
- Each `~` creates a `FlatMap`
- TrampolineOpt pushes `Frame.FlatMap` to heap-allocated array
- No JVM recursion!

**But this is NOT "nested recursive rules"** - it's sequential composition.

---

## The Terminology Confusion

| Term | What We Said | What It Actually Means |
|------|--------------|------------------------|
| "Nested recursive rules" | 7M sequential FlatMaps | Grammar rules calling themselves |
| "Stack-safe" | True for FlatMap chains | NOT true for Or/Defer/Memo |
| "Trampolined" | FlatMap/Map only | Everything else recursive |

---

## Current Limits

| Operation | Actual Limit | Why |
|-----------|--------------|-----|
| Sequential `~` / `flatMap` | ~7-8 million | TrampolineOpt handles |
| `many`/`many1` repetition | ~8 million items | While loop, O(1) stack per item |
| True recursion (`rule`, `defer`) | ~500K-1M | JVM call stack limit |
| Left-recursive growth | ~50K iterations | LR algorithm + recursion |

---

## Options Going Forward

### Option 1: Fix the README (Honest Marketing)

**Effort**: Low (documentation only)
**Risk**: Low
**Impact**: Honest communication with users

Change claims from:
- "7,000,000+ nested recursive rules"

To:
- "Stack-safe for sequential composition (7M+ FlatMap chains)"
- "Supports recursive grammars up to ~500K nesting depth"
- "True left-recursion support via seed-growth algorithm"

**Pros**:
- Honest
- No code changes
- Accurate expectations

**Cons**:
- Less impressive marketing
- Still have a limitation

---

### Option 2: Full Trampolining (Make It Actually Stack-Safe)

**Effort**: High (significant refactor)
**Risk**: Medium (performance regression possible)
**Impact**: True stack safety for all operations

Extend `TrampolineOpt` to handle ALL Parser cases:

```scala
enum Frame {
  case FlatMap(fn, consumed)
  case FlatMapPartial(mkErrors, consumed)
  // NEW:
  case OrRight(right: Parser, snapshot: StateSnapshot, leftMkErrors: () => List, leftFurthest: Location)
  case DeferCont                        // No-op, just marks defer was evaluated
  case MemoCont(key: MemoKey, pos: Int) // Handle memo continuation
  case OptionalCont(snapshot: StateSnapshot)
  case ManyCont(acc: ArrayBuffer, errThunks: ArrayBuffer, consumed: Int)
  // ... etc for all 23 cases
}
```

**Approach**:
1. Every `interpretI` call becomes either:
   - A terminal (return result immediately)
   - Push frame + set `current = subparser`
2. No recursive calls to `interpretI`

**Pros**:
- True stack safety (provable, not just practical)
- 7M claim would be accurate for ALL operations

**Cons**:
- Significant complexity increase
- Performance may degrade (more frame types, more allocation)
- 2-4 weeks of work
- Need comprehensive benchmarking

---

### Option 3: CPS Transformation (Academic Approach)

**Effort**: Very High
**Risk**: High
**Impact**: Elegant but potentially slow

Transform Parser ADT to continuation-passing style:

```scala
type Cont[E, A, R] = (A, Int) => R

def interpret[E, A, R](
  parser: Parser[E, A],
  state: ParserState,
  k: Cont[E, A, IResult[E, R]]
): IResult[E, R]
```

**Pros**:
- Theoretically elegant
- Proven technique

**Cons**:
- Massive refactor
- Scala's CPS support is limited
- Performance overhead from closures
- Not idiomatic Scala 3

---

### Option 4: Hybrid Approach (Selective Trampolining)

**Effort**: Medium
**Risk**: Low-Medium
**Impact**: Stack safety for the most common recursive patterns

Only extend trampolining for the cases that cause recursion in practice:
- `Defer` - the main culprit
- `Or` - alternation
- `Memo` - rules

Keep simple cases recursive (they're terminal or shallow):
- `Satisfy`, `StringMatch`, etc. - terminals
- `Many`, `Many1` - already use while loops

**Approach**:
```scala
enum Frame {
  case FlatMap(...)
  case FlatMapPartial(...)
  case OrRight(...)     // NEW
  case DeferPending     // NEW - marker that we're evaluating a defer
}
```

**Pros**:
- Addresses 90% of real-world recursion
- Smaller change than full trampolining
- Lower performance risk

**Cons**:
- Not provably complete
- Edge cases might still overflow

---

### Option 5: Tagless Final / Free Monad

**Effort**: Very High (architectural rewrite)
**Risk**: Very High
**Impact**: Complete redesign

Rewrite parser as tagless final:

```scala
trait ParserAlg[F[_]] {
  def succeed[A](a: A): F[A]
  def flatMap[A, B](fa: F[A])(f: A => F[B]): F[B]
  def or[A](left: F[A], right: F[A]): F[A]
  // ...
}

// Then provide stack-safe interpreter
given stackSafeInterpreter: ParserAlg[Trampoline] = ...
```

**Pros**:
- Clean separation of description and execution
- Can swap interpreters

**Cons**:
- Complete rewrite
- Different API
- Months of work
- Not the Rumil way (we chose concrete AST deliberately)

---

## Recommendation

### Phase 1: Immediate (DONE)

**Option 1**: Update README with honest claims.

- ✅ Changed "7,000,000+ nested recursive rules" to accurate description
- ✅ Documented what IS stack-safe (FlatMap/Map chains)
- ✅ Documented what has limits (recursive grammars ~500K)
- ✅ Updated benchmark numbers with fresh JMH results

### Phase 2: The Real Fix (PLANNED)

**Option 2**: Full Trampolining.

We are **80% of the way there**. TrampolineOpt already has:
- Custom heap-allocated stack
- Frame enum for continuations
- While-loop execution

What's needed:
- Extend Frame enum to handle all Parser cases
- Move logic from `interpretI` into frame handling
- Example: Instead of `interpretI(left)` for Or, push `Frame.Or(right)` and set `current = left`

This is NOT a massive rewrite - it's extending existing infrastructure.

---

## Appendix: What Other Libraries Do

### cats-parse
- **NOT stack-safe** for deep recursion
- Uses `Defer0` for lazy evaluation but recursion is JVM-based
- Practical limit similar to Rumil

### zio-parser
- **Fully trampolined** via ParserOp compilation
- ~30 operation types in the stack
- Slower compilation, but true stack safety
- 101 type casts (vs our 33)

### fastparse (Scala 2)
- NOT stack-safe
- Relies on JVM inlining for performance
- Deep recursion will overflow

---

## Test Evidence

```
TrueRecursionTest results (November 27, 2025):

Depth 50,000:   SUCCESS (18ms)
Depth 100,000:  SUCCESS (95ms)
Depth 200,000:  SUCCESS (266ms)
Depth 500,000:  SUCCESS (935ms)
Depth 1,000,000: STACK OVERFLOW
```

This proves the ~500K-1M limit for true nested recursion.

---

## Conclusion

We have been conflating two different things:
1. **Sequential FlatMap chains** (7M+ works, trampolined)
2. **True recursive grammars** (500K limit, JVM stack)

The README claim "7,000,000+ nested recursive rules" is **inaccurate** for true recursion.

**Immediate action**: Update README with honest, accurate claims.

**Future consideration**: Extend trampolining if users need deeper recursion.
