# Why zio-parser is Faster Than Rumil

**Date**: November 26, 2025
**Context**: Fair benchmarks show zio 1.6-2.1x faster than Rumil

---

## Fair Benchmark Results

| Benchmark | Rumil | zio-parser | zio advantage |
|-----------|-------|------------|---------------|
| choice (backtracking) | 6,904 ops/ms | 10,951 ops/ms | **1.59x faster** |
| many (10K repetitions) | 10.1 ops/ms | 21.6 ops/ms | **2.13x faster** |

Both use trampolining. Both are stack-safe. Why is zio faster?

---

## Key Architectural Differences

### 1. Compilation Step (Parser → ParserOp)

**zio-parser**:
```scala
// Parser construction happens ONCE
val parser = Syntax.char('1').repeat

// This compiles Parser AST → ParserOp instructions
// Expensive upfront, but cached

// Parsing is just executing pre-compiled ops
parser.parseString(input) // Fast - no compilation
```

**Rumil**:
```scala
// Parser is ADT
val parser = many(char('1'))

// Every parse traverses the Parser ADT
run(parser, input) // Interprets AST on every call
```

**Impact**: zio's compilation amortizes. Rumil re-interprets the same AST structure repeatedly.

### 2. Result Stack Architecture

**zio-parser**: Dual-stack + inline top 2 values
```scala
// Top 2 results inline (hot variables)
var lastSuccess1: AnyRef = null
var lastFailure1: ParserError[Any] = null
var lastSuccess2: AnyRef = null
var lastFailure2: ParserError[Any] = null

// Overflow goes to stacks
val successResultStack: Stack[AnyRef] = Stack()
val failedResultStack: Stack[ParserError[Any]] = Stack()
```

**Rumil**: Single result variable
```scala
var result: IResult[Any, Any] = NoResult

// Result is Success | Partial | LazyFailure
// Each operation allocates new IResult
```

**Impact**: zio keeps hot values in registers. Rumil allocates IResult wrappers.

### 3. Collection Building (Chunk vs List)

**zio-parser**: ChunkBuilder (mutable)
```scala
val builder: ChunkBuilder[A] = ChunkBuilder.make(sizeHint)
while (parsing) {
  builder += parseElement()
}
builder.result() // O(1) finalization
```

**Rumil**: List prepend + reverse
```scala
var acc: List[A] = Nil
while (parsing) {
  acc = parseElement() :: acc
}
acc.reverse // O(n) allocation
```

**Impact**: For 10K elements, List needs O(n) reverse. Chunk amortizes with size hint.

### 4. Operation Dispatch

**zio-parser**: ~30 operation types, highly specialized
```scala
case MatchSeq(sequence, as, createFailure) =>
  // Optimized string matching inline
  var pos = 0
  while (pos < sequence.length && failure == null) {
    if (source(pos0 + pos) != sequence(pos)) {
      failure = createFailure(pos, item)
    }
    pos += 1
  }
```

**Rumil**: Generic Frame + pattern matching
```scala
case Frame.FlatMap(fn, consumed) =>
  // Apply continuation generically
  result match {
    case Success(value, cons) =>
      current = fn(value)
      // ...
  }
```

**Impact**: zio's specialized operations avoid generic overhead.

### 5. Position Tracking

**zio-parser**: Separate position array
```scala
val storedPositions: Array[Int] = parser.initialPositions.clone()
var storedPositionIndex: Int = parser.initialPositionIndex
```

**Rumil**: Position in state + result
```scala
case Result.Success(value, consumed) =>
  // consumed is part of result
```

**Impact**: zio's Array[Int] is cache-friendly. Rumil allocates Result with consumed.

---

## What Can Rumil Learn?

### 1. Compilation Step (HIGH IMPACT)

**Consider**: Pre-compile Parser → optimized instruction sequence

```scala
// Compile once
sealed trait Instruction
case class MatchChar(c: Char) extends Instruction
case class PushContinuation(f: Any => Parser[E, A]) extends Instruction
case class PopAndApply() extends Instruction

object Compiler {
  def compile[E, A](parser: Parser[E, A]): Array[Instruction] = {
    // Walk Parser AST, emit instructions
    // Optimize: fuse maps, eliminate unnecessary frames, etc.
  }
}

// Store compiled form
class CompiledParser[E, A](instructions: Array[Instruction])

// Interpreter executes instructions
def run(compiled: CompiledParser[E, A], input: String): Result[E, A]
```

**Benefits**:
- Amortizes construction overhead
- Enables optimizations (fusion, inlining)
- Instructions can be more specialized

**Costs**:
- Complexity (two representations)
- Memory (store compiled form)
- API: when to compile?

### 2. Inline Top Result (MEDIUM IMPACT)

**Consider**: Keep hot result inline, overflow to stack

```scala
def run[E, A](parser: Parser[E, A], state: ParserState): IResult[E, A] = {
  var stack: Array[Frame] = ...
  var stackTop = 0

  // Top result inline (avoid allocation)
  var topSuccess: AnyRef = null
  var topFailure: LazyFailure[Any] = null

  // Overflow stack
  val resultStack: Stack[IResult[Any, Any]] = Stack()

  // When result needed on stack
  if (needSpace) {
    if (topSuccess != null) {
      resultStack.push(Success(topSuccess, consumed))
    } else {
      resultStack.push(topFailure)
    }
  }
}
```

**Benefits**:
- Reduces IResult allocations
- Keeps hot values in registers

**Costs**:
- More complex result management
- Loses clean IResult abstraction

### 3. Use ChunkBuilder for many/rep (LOW IMPACT)

**Consider**: Replace List with builder pattern

```scala
def many[E, A](p: Parser[E, A]): Parser[E, Chunk[A]] = {
  // Use mutable builder during parsing
  val builder = ChunkBuilder.make[A]()
  while (parseSucceeds) {
    builder += parseResult
  }
  builder.result() // Immutable Chunk
}
```

**Benefits**:
- O(1) append vs O(n) reverse
- Better for large collections

**Costs**:
- Adds dependency (need Chunk or similar)
- List is "good enough" for most parsing

### 4. Specialize Hot Operations (MEDIUM IMPACT)

**Consider**: Add specialized Frame types

```scala
enum Frame {
  case FlatMap(fn: Any => Parser[Any, Any], consumed: Int)
  case FlatMapPartial(errors: List[Any], consumed: Int)

  // NEW: specialized for common patterns
  case MatchStringCont(remaining: String, consumed: Int)
  case RepeatCont(builder: Builder[Any], count: Int, consumed: Int)
}
```

**Benefits**:
- Avoid generic overhead for hot paths
- Enable inline optimizations

**Costs**:
- More Frame variants (complexity)
- Harder to maintain

---

## Recommended Approach

### Phase 1: Measurement (Do First)

Before optimizing, understand WHERE the time is spent:

1. **Profile with JMH `-prof stack`**
   ```bash
   sbt "benchmarks/Jmh/run -prof stack MinimalFairComparison"
   ```
   - Find hottest methods
   - Understand call stacks

2. **Profile with JMH `-prof gc`**
   ```bash
   sbt "benchmarks/Jmh/run -prof gc MinimalFairComparison"
   ```
   - Measure allocation rate
   - Find allocation hotspots

3. **Micro-benchmarks for hypotheses**
   - Is it IResult allocation?
   - Is it List.reverse?
   - Is it Frame dispatch?

### Phase 2: Low-Hanging Fruit (Quick Wins)

Based on profiling, pick 1-2 high-impact, low-complexity improvements:

**Option A: Inline top result**
- Medium impact, medium complexity
- No API changes
- Reduces allocations

**Option B: ChunkBuilder for `many`**
- Low-medium impact
- Requires collection library (Chunk or custom)
- Clean API boundary

### Phase 3: Consider Compilation (Big Decision)

**Only if**:
- Phase 1-2 gains aren't enough
- We want to match zio's performance
- We're willing to increase complexity

**Design questions**:
- When to compile? (explicit API? lazy? always?)
- How to handle recursive parsers?
- Backward compatibility?

---

## Open Questions

### 1. Is 1.6-2.1x Good Enough?

**Context**:
- Rumil is 2-3x slower than cats-parse (direct recursion)
- Rumil is 1.6-2.1x slower than zio-parser (compiled trampoline)
- Rumil is stack-safe, zero-dependency, left-recursive

**Question**: Is matching zio's performance worth the complexity?

### 2. What Are We Optimizing For?

**Possible goals**:
- Match cats-parse (requires giving up guarantees)
- Match zio-parser (requires compilation step)
- Stay simple (accept 2-3x cost for guarantees)

**Need**: Clarify project priorities before optimizing.

### 3. What Is zio-parser's Compilation Overhead?

**Hypothesis**: Compilation is expensive, but amortizes.

**Test**:
```scala
// Measure compilation time
@Benchmark
def zio_compile(): Any = {
  buildComplexParser() // Just construction
}

// Measure amortized benefit
@Benchmark
def zio_parseMany(): Any = {
  for (_ <- 1 to 1000) {
    prebuiltParser.parse(input)
  }
}
```

If compilation is 100ms but amortizes over 1000 parses, that's only 0.1ms/parse overhead.

---

## Tentative Recommendation

### Short Term (v0.2.0)

1. ✅ Profile to understand bottlenecks
2. ✅ Implement 1-2 low-complexity optimizations
3. ✅ Document performance characteristics honestly
4. ❌ Don't add compilation (too complex for this release)

### Long Term (v1.0+)

**IF** we decide matching zio's performance is critical:
1. Design compilation API carefully
2. Implement instruction-based interpreter
3. Benchmark to verify gains
4. Consider it a major feature (not just optimization)

### Alternative: Accept The Cost

**Position**: "Rumil trades 2x performance for simplicity + guarantees"

**Value proposition**:
- Zero dependencies (vs zio's heavy deps)
- Simple architecture (vs 30-operation dispatch)
- Predictable behavior (no hidden compilation)
- Still much faster than naive approaches

**Analogy**: Like Rust's borrow checker—pays compile-time cost for runtime guarantees. Rumil pays runtime cost for simplicity guarantees.

---

## Conclusion

zio-parser is faster because:
1. **Compilation amortizes** (biggest factor)
2. **Inline result handling** (reduces allocation)
3. **Specialized operations** (avoids generic overhead)
4. **ChunkBuilder** (better for large collections)

Rumil CAN adopt these techniques, but should we?

**Next step**: Profile to confirm hypotheses, then decide based on project goals.
