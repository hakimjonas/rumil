# Unified Compilation Architecture: Eru + Rumil

**Date**: November 26, 2025
**Context**: After studying zio-parser's compilation approach and Eru's GADT Continuation architecture, we see an opportunity for a principled, shared compilation strategy.

---

## The Core Insight

Both Eru and Rumil face the same fundamental challenge:
- **Eru**: Effect ADT (`Eru[E, A]`) interpreted at runtime
- **Rumil**: Parser ADT (`Parser[E, A]`) interpreted at runtime
- **Both**: Interpretive overhead on every execution

**zio-parser showed us**: Compilation (ADT → Instructions) amortizes and enables optimization.

**The opportunity**: Design a PRINCIPLED compilation architecture that benefits BOTH libraries while maintaining elegance and understandability.

---

## Current State Analysis

### Eru's Architecture

```scala
enum Eru[+E, +A] {
  case Succeed(value: A)
  case Fail(error: E)
  case Effect(thunk: () => Either[Throwable, A])
  case Chain(source: Eru[E0, From], cont: Continuation[E0, From, To])
  case MapChain(source: Eru[E0, From], f: From => To)
  // ... ~15 more cases
}

// GADT Continuation (already zero-cast!)
enum Continuation[+E, -In, +Out] {
  case End[A]()
  case Step(f: In => Eru[E, Mid], next: Continuation[E, Mid, Out])
  case Compose(first: Continuation[E, In, Mid], g: Mid => Eru[E, Out])
}

// Interpreter
private def runFiberLoop[E, A](
  eru: Eru[E, A],
  fins: List[Finalizer],
  // ...
): TailRec[(Either[E, A], List[Finalizer])] = {
  eru match {
    case Succeed(value) => done((Right(value), fins))
    case MapChain(source, f) =>
      // Traverse source, then apply f
      tailcall(runFiberLoop(source, ...))
    case Chain(source, cont) =>
      // Traverse source, then apply continuation
      tailcall(runFiberLoop(source, ...))
    // ...
  }
}
```

**Observations**:
- ✅ Already has GADT Continuation (type-safe)
- ✅ Already has fast-path optimization (`runFast`)
- ✅ Construction-time optimizations (map fusion)
- ❌ Still interpreting ADT on every run
- ❌ Pattern matching on 15+ cases per step

### Rumil's Architecture

```scala
enum Parser[+E, +A] {
  case Succeed(value: A)
  case Fail(error: E)
  case Satisfy(predicate: Char => Boolean, expected: String)
  case FlatMap(source: Parser[E, A], f: A => Parser[E, B])
  case Map(source: Parser[E, A], f: A => B)
  // ... ~10 more cases
}

// Interpreter
def run[E, A](parser: Parser[E, A], state: ParserState): IResult[E, A] = {
  var stack: Array[Frame] = new Array[Frame](32)
  var current: Parser[Any, Any] = parser

  while (true) {
    // Phase 1: Expand parser into frames
    while (current ne NoParser) {
      current match {
        case Parser.FlatMap(source, f) =>
          stack(stackTop) = Frame.FlatMap(f, consumed)
          stackTop += 1
          current = source
        // ...
      }
    }
    // Phase 2: Apply frames to results
    // ...
  }
}
```

**Observations**:
- ❌ No Continuation GADT (uses Frame enum)
- ❌ No fast-path optimization
- ❌ Limited construction-time optimization
- ❌ Re-interprets Parser ADT on every parse
- ✅ Efficient two-phase loop (expand, apply)

---

## The Unified Vision

### Core Idea: Instruction-Based Compilation

Both Eru and Rumil can benefit from:

1. **Construction-time compilation**: ADT → Instruction[]
2. **Optimized instruction set**: Specialized, cache-friendly ops
3. **Shared compiler infrastructure**: Reusable compilation techniques
4. **Interpreted execution**: Fast instruction dispatch

### Principled Design

**Key insight**: Compilation isn't cheating—it's just MOVING work from runtime to construction time.

**Analogy**: Like compiling regex patterns
```scala
// Expensive at construction
val pattern = "a+b*c".r  // Compiles regex → instruction set

// Fast at runtime
pattern.matches(input)  // Executes pre-compiled instructions
```

---

## Proposed Architecture

### Shared Instruction Set (Abstract)

```scala
package net.ghoula.core.instruction

/** Base trait for compiled instructions.
  *
  * Instructions are the output of compiling high-level ADTs (Eru, Parser)
  * into efficient, specialized operations.
  */
sealed trait Instruction[+E, -In, +Out]

object Instruction {
  // Pure operations
  case class Succeed[A](value: A) extends Instruction[Nothing, Any, A]
  case class Fail[E](error: E) extends Instruction[E, Any, Nothing]

  // Function application
  case class MapValue[A, B](f: A => B) extends Instruction[Nothing, A, B]
  case class MapError[E1, E2](f: E1 => E2) extends Instruction[E2, Any, Nothing]

  // Continuation operations
  case class PushContinuation[E, A, B](f: A => Compiled[E, B]) extends Instruction[E, A, B]
  case class PopAndApply() extends Instruction[Nothing, Any, Any]

  // Effect operations (Eru-specific)
  case class RunEffect(thunk: () => Either[Throwable, Any]) extends Instruction[Throwable, Any, Any]
  case class Suspend[E, A](register: ...) extends Instruction[E, Any, A]

  // Parser operations (Rumil-specific)
  case class MatchChar(c: Char) extends Instruction[ParseError, Any, Char]
  case class MatchString(s: String) extends Instruction[ParseError, Any, String]
  case class Satisfy(pred: Char => Boolean, expected: String) extends Instruction[ParseError, Any, Char]
}

/** Compiled representation of a computation.
  *
  * This is the OUTPUT of compilation, the INPUT to interpretation.
  */
case class Compiled[+E, +A](
  instructions: Array[Instruction[E, ?, A]],
  metadata: CompilationMetadata
)

case class CompilationMetadata(
  maxStackDepth: Int,  // Pre-computed for stack allocation
  isPure: Boolean,     // Does it have effects?
  canFail: Boolean     // Does it have failure paths?
)
```

### Eru Compiler

```scala
package net.ghoula.eru.compiler

object EruCompiler {

  /** Compile an Eru computation into instructions.
    *
    * This is done ONCE at construction time (or lazily on first run).
    * The compiled form is cached in the Eru ADT.
    */
  def compile[E, A](eru: Eru[E, A]): Compiled[E, A] = {
    val builder = new InstructionBuilder[E, A]()

    // Walk Eru ADT, emit instructions
    compileNode(eru, builder)

    // Optimize instruction sequence
    val optimized = optimize(builder.toArray())

    Compiled(optimized, computeMetadata(optimized))
  }

  private def compileNode[E, A](
    eru: Eru[E, A],
    builder: InstructionBuilder[E, A]
  ): Unit = {
    eru match {
      case Eru.Succeed(value) =>
        builder.emit(Instruction.Succeed(value))

      case Eru.MapChain(source, f) =>
        compileNode(source, builder)
        builder.emit(Instruction.MapValue(f))

      case Eru.Chain(source, cont) =>
        compileNode(source, builder)
        compileContinuation(cont, builder)

      case Eru.Effect(thunk) =>
        builder.emit(Instruction.RunEffect(thunk))

      // ... other cases
    }
  }

  private def compileContinuation[E, In, Out](
    cont: Eru.Continuation[E, In, Out],
    builder: InstructionBuilder[E, Out]
  ): Unit = {
    cont match {
      case Eru.Continuation.End() =>
        // Nothing to emit - identity

      case Eru.Continuation.Step(f, next) =>
        builder.emit(Instruction.PushContinuation(a => compile(f(a))))
        compileContinuation(next, builder)
        builder.emit(Instruction.PopAndApply())

      case Eru.Continuation.Compose(first, g) =>
        compileContinuation(first, builder)
        builder.emit(Instruction.PushContinuation(a => compile(g(a))))
        builder.emit(Instruction.PopAndApply())
    }
  }

  /** Optimize instruction sequence.
    *
    * Examples:
    * - MapValue(f1) + MapValue(f2) → MapValue(f1.andThen(f2))
    * - Succeed(x) + MapValue(f) → Succeed(f(x))
    * - PushCont + PopAndApply + PushCont → FusedContinuation
    */
  private def optimize[E, A](
    instructions: Array[Instruction[E, ?, A]]
  ): Array[Instruction[E, ?, A]] = {
    // Peephole optimization pass
    // ...
  }
}
```

### Rumil Compiler

```scala
package parser.compiler

object ParserCompiler {

  /** Compile a Parser into instructions.
    */
  def compile[E, A](parser: Parser[E, A]): Compiled[E, A] = {
    val builder = new InstructionBuilder[E, A]()

    compileNode(parser, builder)

    val optimized = optimize(builder.toArray())

    Compiled(optimized, computeMetadata(optimized))
  }

  private def compileNode[E, A](
    parser: Parser[E, A],
    builder: InstructionBuilder[E, A]
  ): Unit = {
    parser match {
      case Parser.Succeed(value) =>
        builder.emit(Instruction.Succeed(value))

      case Parser.Char(c) =>
        builder.emit(Instruction.MatchChar(c))

      case Parser.Satisfy(pred, expected) =>
        builder.emit(Instruction.Satisfy(pred, expected))

      case Parser.FlatMap(source, f) =>
        compileNode(source, builder)
        builder.emit(Instruction.PushContinuation(a => compile(f(a))))
        builder.emit(Instruction.PopAndApply())

      case Parser.Map(source, f) =>
        compileNode(source, builder)
        builder.emit(Instruction.MapValue(f))

      // ... other cases
    }
  }
}
```

### Shared Interpreter

```scala
package net.ghoula.core.interpreter

/** Generic instruction interpreter.
  *
  * This executes pre-compiled instruction sequences efficiently.
  * Specialized for different contexts (Eru effects, Rumil parsing, etc.)
  */
object InstructionInterpreter {

  /** Execute compiled instructions with given context.
    */
  def execute[E, A, Ctx](
    compiled: Compiled[E, A],
    context: ExecutionContext[Ctx]
  ): Either[E, A] = {
    // Pre-allocate stacks based on metadata
    val continuationStack = new Array[Any => Compiled[E, Any]](compiled.metadata.maxStackDepth)
    var stackTop = 0

    val instructions = compiled.instructions
    var ip = 0  // Instruction pointer
    var value: Any = null
    var error: Any = null

    while (ip < instructions.length) {
      instructions(ip) match {
        case Instruction.Succeed(v) =>
          value = v
          error = null
          ip += 1

        case Instruction.MapValue(f) =>
          if (error == null) {
            value = f(value)
          }
          ip += 1

        case Instruction.PushContinuation(f) =>
          continuationStack(stackTop) = f
          stackTop += 1
          ip += 1

        case Instruction.PopAndApply() =>
          if (error == null) {
            stackTop -= 1
            val cont = continuationStack(stackTop)
            val nextCompiled = cont(value)
            // Tail call into next compiled segment
            return execute(nextCompiled, context)
          }
          ip += 1

        // Context-specific instructions
        case instr: Instruction.RunEffect =>
          context.executeEffect(instr.thunk) match {
            case Left(e) => error = e
            case Right(v) => value = v
          }
          ip += 1

        case instr: Instruction.MatchChar =>
          context.matchChar(instr.c) match {
            case Left(e) => error = e
            case Right(c) => value = c
          }
          ip += 1

        // ... other instructions
      }
    }

    if (error == null) Right(value.asInstanceOf[A])
    else Left(error.asInstanceOf[E])
  }
}

/** Execution context provides domain-specific operations.
  */
trait ExecutionContext[Ctx] {
  def executeEffect(thunk: () => Either[Throwable, Any]): Either[Throwable, Any]
  def matchChar(c: Char): Either[ParseError, Char]
  // ... domain-specific operations
}
```

---

## Benefits for the Ecosystem

### For Eru
1. **Performance**: 1.5-2x speedup from reduced interpretation overhead
2. **Optimization opportunities**: Instruction-level fusion, inlining
3. **Better profiling**: Clear instruction-level metrics
4. **Maintains elegance**: User-facing API unchanged

### For Rumil
1. **Performance**: Match zio-parser (1.6-2.1x faster)
2. **Shared infrastructure**: Leverage Eru's compiler work
3. **Learning from zio**: Adopt proven techniques
4. **Cross-pollination**: Improvements benefit both

### For Fungal
1. **Principled foundation**: Core compilation in ecosystem
2. **Performance**: Fast effects + fast parsing
3. **Consistency**: Similar patterns across libraries
4. **Maintainability**: Shared code, shared testing

---

## Implementation Strategy

### Phase 1: Prototype (Eru First)
- Implement instruction set for Eru
- Build compiler (Eru → Instructions)
- Build interpreter (Instructions → Result)
- Benchmark vs current TailRec approach
- **Goal**: Validate 1.5-2x improvement

### Phase 2: Extract Core
- Abstract instruction set
- Extract shared compiler utilities
- Extract shared interpreter loop
- Document compilation theory
- **Goal**: Reusable compilation infrastructure

### Phase 3: Rumil Compilation
- Implement parser-specific instructions
- Build compiler (Parser → Instructions)
- Integrate with shared interpreter
- Benchmark vs current trampoline
- **Goal**: Match zio-parser performance

### Phase 4: Optimization
- Peephole optimization passes
- Instruction fusion
- Dead code elimination
- Constant folding
- **Goal**: Beyond zio-parser performance

---

## Open Questions

### 1. When to Compile?

**Options**:
A. **Lazy (first run)**: Compile on first execution, cache result
B. **Explicit API**: `parser.compile` returns `CompiledParser[E, A]`
C. **Always**: Compile during construction

**Recommendation**: Start with explicit API for control, consider lazy later.

### 2. How to Handle Recursion?

```scala
lazy val expr: Parser[E, Int] =
  (expr ~ char('+') ~ term).map(...) | term
```

**Challenge**: `expr` references itself—can't compile fully upfront.

**Solution**: Lazy instruction (like zio's `Lazy` ParserOp)
```scala
case class LazyInstruction[E, A](thunk: () => Compiled[E, A]) extends Instruction[E, Any, A]
```

### 3. Backward Compatibility?

**For Eru**: Internal change, API unchanged ✅
**For Rumil**: Could be internal OR explicit compiled type

### 4. Complexity vs Benefit?

**Complexity added**:
- Instruction ADT (~20-30 cases)
- Compiler logic (~500 LOC)
- Interpreter (~300 LOC)
- **Total**: ~1000 LOC

**Benefits**:
- Eru: 1.5-2x faster
- Rumil: Match zio (1.6-2.1x)
- Shared infrastructure
- Optimization opportunities

**Verdict**: Worth it for ecosystem, especially if shared.

---

## Guiding Principles

### 1. Elegance
- User-facing APIs stay beautiful
- Compilation is internal optimization
- GADTs preserved where meaningful

### 2. Understanding
- Clear instruction semantics
- Well-documented compiler passes
- Traceable instruction execution

### 3. Pragmatism
- Performance matters for adoption
- Learn from proven approaches (zio)
- Don't reinvent—steal good ideas

### 4. Ecosystem Thinking
- Shared code benefits all
- Patterns consistent across libraries
- Foundational for Fungal

---

## Recommendation

**YES, pursue unified compilation architecture.**

**Rationale**:
1. Proven approach (zio-parser validates it)
2. Benefits BOTH Eru and Rumil
3. Foundational for Fungal ecosystem
4. Maintains elegance (internal optimization)
5. Enables future optimizations

**Next steps**:
1. Prototype instruction set for Eru
2. Measure performance improvement
3. If successful (>1.3x), extract core
4. Apply to Rumil
5. Iterate on shared infrastructure

**Timeline**: This is foundational work—don't rush. Get it right.

---

## Conclusion

zio-parser showed us that compilation is GOOD ENGINEERING, not a compromise.

We can build a PRINCIPLED, ELEGANT compilation architecture that:
- Maintains beautiful user-facing APIs
- Provides significant performance wins
- Benefits the entire ecosystem
- Stays true to our ideals

This is worth doing, and worth doing right.
