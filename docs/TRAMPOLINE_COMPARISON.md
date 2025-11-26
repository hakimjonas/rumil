# Trampoline Implementation Comparison: Rumil vs zio-parser

**Date**: November 26, 2025
**Purpose**: Technical comparison of stack-safe interpreter implementations

---

## Executive Summary

Both Rumil and zio-parser achieve stack safety through manual trampolining with while-loops and operation stacks. Key differences:

1. **Dependency footprint**: Rumil = 0 deps, zio-parser = ZIO core (~500KB+ JARs)
2. **Architecture**: Rumil = Frame-based, zio-parser = Operation-based
3. **Compilation**: Rumil = direct interpretation, zio-parser = Parser → ParserOp compilation step
4. **Performance**: Unknown (needs benchmarking)

---

## Dependency Analysis

### zio-parser Dependencies

**Direct dependencies**:
```scala
libraryDependencies ++= Seq(
  "dev.zio" %%% "zio"         % "2.0.x",  // Full ZIO core
  "dev.zio" %%% "zio-streams" % "2.0.x"   // ZIO streams
)
```

**What's actually used from ZIO**:
```scala
import zio.{Chunk, ChunkBuilder}  // In stacksafe interpreter
import zio.{Chunk, =!=}           // In Printer
```

**Chunk API usage** (from code analysis):
- `Chunk.empty` - Create empty chunk
- `Chunk.fromArray(arr)` - Wrap array as chunk
- `Chunk.fromIterable(it)` - Convert iterable to chunk
- `ChunkBuilder.make(sizeHint)` - Create mutable builder

**Question**: Could zio-parser use stdlib `List`/`Array`/`ArrayBuffer` instead?
- `Chunk.empty` → `List.empty` or `Array.empty`
- `Chunk.fromArray` → identity (already an array)
- `Chunk.fromIterable` → `it.toList` or `it.toArray`
- `ChunkBuilder.make` → `ArrayBuffer[A](sizeHint)`

**Hypothesis**: zio-parser could be zero-dependency if it replaced `Chunk` with stdlib collections.

### Rumil Dependencies

**Direct dependencies**: None (Scala 3 stdlib only)

**Collections used**:
- `Array[Frame]` - Manual continuation stack
- `List[E]` - Error lists (from stdlib)
- `List[A]` - Result lists for `many` combinator

**Stack management**:
```scala
var stack = new Array[Frame](32)
var stackTop = 0

// Manual growth
if (stackTop >= stack.length) {
  val newStack = new Array[Frame](stack.length * 2)
  System.arraycopy(stack, 0, newStack, 0, stackTop)
  stack = newStack
}
```

---

## Architecture Comparison

### Rumil: Direct Frame-Based Trampoline

**Data structure**:
```scala
enum Frame {
  case FlatMap(fn: Any => Parser[Any, Any], consumed: Int)
  case FlatMapPartial(errors: List[Any], consumed: Int)
}
```

**Interpreter loop**:
```scala
def run[E, A](parser: Parser[E, A], state: ParserState): IResult[E, A] = {
  var stack: Array[Frame] = new Array[Frame](32)
  var stackTop = 0
  var current: Parser[Any, Any] = parser
  var result: IResult[Any, Any] = NoResult

  while (true) {
    // Phase 1: Expand parser into continuations
    while (current ne NoParser) {
      current match {
        case Parser.FlatMap(source, f) =>
          stack(stackTop) = Frame.FlatMap(f, consumedAcc)
          stackTop += 1
          current = source
        // ...
      }
    }

    // Phase 2: Apply continuations to results
    while (result ne NoResult) {
      if (stackTop == 0) return result
      val frame = stack(stackTop - 1)
      stackTop -= 1
      // Apply frame to result...
    }
  }
}
```

**Key characteristics**:
- **Two-phase**: Expand parsers → Apply continuations
- **Direct interpretation**: Parser ADT → Frame stack
- **Minimal allocations**: Reuses Frame array, sentinel values for Option
- **Inline optimizations**: Map fusion, consumed tracking

---

### zio-parser: Compiled Operation-Based Trampoline

**Data structures**:
```scala
sealed trait ParserOp
object ParserOp {
  case class PushOp2(a: ParserOp, b: ParserOp, pushBranchPosition: Boolean)
  case class PushOp3(a: ParserOp, b: ParserOp, c: ParserOp)
  case class PushOp4(a: ParserOp, b: ParserOp, c: ParserOp, d: ParserOp, ...)
  case class Lazy(op: () => ParserOp)
  case class PushResult(success: AnyRef, failure: ParserError[Any], popFirst: Boolean)
  case class TransformResultEither(f: Any => Either[Any, Any])
  case class MatchSeq(sequence: Chunk[Any], as: AnyRef, ...)
  // ... ~30 more operation types
}
```

**Interpreter loop**:
```scala
def run(): Either[ParserError[Err], Result] = {
  val opStack: Stack[ParserOp] = parser.initialStack.clone()
  var op: ParserOp = parser.op

  var lastSuccess1: AnyRef = null
  var lastFailure1: ParserError[Any] = null
  var lastSuccess2: AnyRef = null
  var lastFailure2: ParserError[Any] = null
  val successResultStack: Stack[AnyRef] = Stack()
  val failedResultStack: Stack[ParserError[Any]] = Stack()

  while (op != null) {
    op = op match {
      case PushOp2(a, b, pushBranchPosition) =>
        if (pushBranchPosition) storedPositions(storedPositionIndex) = position
        opStack.push(a)
        b
      case PushResult(success, failure, popFirst) =>
        // Complex result stack management...
      case TransformResultEither(f) =>
        // Pop result, transform, push back...
      // ... ~30 more cases
    }
  }
}
```

**Key characteristics**:
- **Compilation step**: Parser ADT → ParserOp instructions (before run)
- **Instruction-based**: VM-like with ~30 operation types
- **Explicit result stacks**: Separate success/failure stacks + top 2 values inline
- **Branch tracking**: Separate position stack for backtracking

---

## Architectural Tradeoffs

| Aspect | Rumil | zio-parser |
|--------|-------|-----------|
| **Phases** | Two-phase (expand, apply) | Single-phase (execute ops) |
| **Compilation** | None (direct interpretation) | Parser → ParserOp (upfront cost) |
| **Operation types** | 2 Frame variants | ~30 ParserOp variants |
| **Result tracking** | Single IResult in var | Dual stacks + 2 inline values |
| **Stack types** | 1 stack (Frame) | 4 stacks (op, success, failure, position) |
| **Lazy evaluation** | Lazy Parser combinator | Lazy ParserOp case |
| **Collections** | Array + List (stdlib) | Chunk + ChunkBuilder (ZIO) |

---

## Open Questions (Need Benchmarking)

### Performance Questions

1. **Compilation overhead**: Does zio-parser's Parser → ParserOp compilation amortize over single parse?
2. **Result stack overhead**: Does dual result stack (success/failure) cost more than single IResult?
3. **Chunk overhead**: Is ZIO's Chunk faster/slower than stdlib List for repeated results?
4. **Operation dispatch**: Does 30-case match cost more than 2-case match?
5. **Memory footprint**: Does 4-stack approach use more heap than single stack?

### Design Questions

1. **Why compilation?**: What optimization opportunities does ParserOp enable?
2. **Why dual result stacks?**: What's gained by separating success/failure?
3. **Why Chunk?**: Why not use stdlib collections?
4. **Why 30 operations?**: Could this be simplified like Rumil's 2 frames?

---

## Benchmark Plan

To answer these questions, we need comparative benchmarks:

### Setup
1. Add zio-parser as benchmark dependency
2. Implement same parser patterns in both libraries
3. Use JMH for measurement

### Benchmark Categories

#### 1. Simple Sequential Parsing
```scala
// Pattern: many small sequential operations
val digits = char('0') | char('1') | ... | char('9')
val number = digits.rep
```
**Measures**: Basic operation overhead

#### 2. Deep Nesting
```scala
// Pattern: deeply nested flatMap
var p = char('a')
for (_ <- 1 to 10000) {
  p = p.flatMap(_ => char('a'))
}
```
**Measures**: Stack management overhead

#### 3. Repetition with Collection
```scala
// Pattern: many/rep collecting results
val items = item.rep(1000)
```
**Measures**: Collection building (Chunk vs List)

#### 4. Backtracking-Heavy
```scala
// Pattern: choice with many failed branches
val p = attempt(longParser1) | attempt(longParser2) | ... | success
```
**Measures**: Backtracking + error handling

#### 5. Regex Matching
```scala
// Pattern: regex-based token matching
val identifier = regex("[a-zA-Z][a-zA-Z0-9]*")
```
**Measures**: String operations

### Metrics to Collect
- **Throughput**: ops/ms (JMH)
- **Latency**: p50, p99, p999 (JMH)
- **Allocation**: bytes allocated per op (JMH profiler)
- **Compilation overhead**: Parser → Op transformation time (zio-parser only)

---

## Hypothesis: Why Each Might Win

### Rumil Advantages
- ✅ **Simpler dispatch**: 2 Frame cases vs 30 ParserOp cases (better branch prediction?)
- ✅ **Single result**: One IResult var vs dual stacks (less memory traffic?)
- ✅ **No compilation**: Direct interpretation (saves upfront cost?)
- ✅ **Stdlib collections**: List is JVM-optimized (might be faster than Chunk?)

### zio-parser Advantages
- ✅ **Compilation**: Optimization pass before execution (amortizes over long inputs?)
- ✅ **Explicit operations**: More opportunities for instruction fusion?
- ✅ **Chunk**: Persistent data structure with structural sharing (better for large results?)
- ✅ **Mature codebase**: More optimization iterations over time?

---

## Next Steps

1. ☐ Set up zio-parser in benchmarks/build.sbt
2. ☐ Implement equivalent parsers in both libraries
3. ☐ Run comparative JMH benchmarks
4. ☐ Profile allocation rates with JMH
5. ☐ Analyze results and understand tradeoffs
6. ☐ Document findings

---

## Notes for Implementation

### Adding zio-parser to benchmarks
```scala
// benchmarks/build.sbt
libraryDependencies ++= Seq(
  "dev.zio" %% "zio-parser" % "0.1.9"
)
```

### Equivalent Parser Examples
```scala
// Rumil
import parser.core._
import parser.syntax._
val p = char('a') ~ char('b')

// zio-parser
import zio.parser._
val p = Syntax.char('a') ~ Syntax.char('b')
```

Both use similar combinator APIs, should be straightforward to create equivalent tests.
