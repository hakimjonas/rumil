package parser.runtime

import scala.collection.mutable

import parser.core.{MemoKey, *}

/** Mutable reference cell for controlled mutation.
  *
  * Encapsulates mutation behind a pure interface, allowing functional-style updates while
  * maintaining efficiency.
  *
  * @tparam A
  *   The type of value stored
  */
final private class Ref[A](private var value: A) {
  def get: A = value
  def set(newValue: A): Unit = value = newValue
  def update(f: A => A): Unit = value = f(value)
}

/** Type-safe memoization table for left recursion support.
  *
  * Stores parse results keyed by (MemoKey, position). The MemoKey carries type information ensuring
  * that retrieval returns the correct type.
  *
  * Implementation note: Internally uses type erasure (Any) but the API is type-safe because
  * MemoKey[E, A] can only be used with Result[E, A].
  */
final private[runtime] class MemoTable private () {
  private val table: mutable.Map[(AnyRef, Int), Either[LR, MemoEntry]] = mutable.Map.empty

  /** Store a result with type-safe key.
    *
    * Type safety: key's [E, A] matches result's [E, A] at compile time. Internally erases to Any
    * for heterogeneous storage.
    */
  def put[E, A](key: MemoKey[E, A], pos: Int, result: Result[E, A], endPos: Int): Unit = {
    val _ = table.put((key, pos), Right(MemoEntry(Some(eraseResult(result)), endPos)))
  }

  /** Store an LR marker with type-safe key. */
  def putLR[E, A](key: MemoKey[E, A], pos: Int, lr: LR): Unit = {
    val _ = table.put((key, pos), Left(lr))
  }

  /** Retrieve and cast a cached result with type-safe key.
    *
    * SAFETY PROOF for the cast:
    *   1. MemoKey[E, A] instances are unique (created once per `rule` call)
    *   2. The same key is used for both put() and getResult()
    *   3. put() stores Result[E, A] (erased to Any)
    *   4. Therefore getResult() returns the same Result[E, A]
    *
    * This is the ONLY cast point for typed retrieval.
    */
  def getResult[E, A](key: MemoKey[E, A], pos: Int): Option[Result[E, A]] =
    table.get((key, pos)).flatMap {
      case Right(entry) => entry.result.map(castResult[E, A])
      case Left(_) => None
    }

  /** Retrieve LR marker if present.
    */
  def getLR[E, A](key: MemoKey[E, A], pos: Int): Option[LR] =
    table.get((key, pos)).flatMap {
      case Left(lr) => Some(lr)
      case Right(_) => None
    }

  /** Get the end position from a cached entry.
    */
  def getEndPos[E, A](key: MemoKey[E, A], pos: Int): Option[Int] =
    table.get((key, pos)).flatMap {
      case Right(entry) => Some(entry.pos)
      case Left(_) => None
    }

  /** Check if entry exists (either LR or cached result).
    */
  def contains[E, A](key: MemoKey[E, A], pos: Int): Boolean =
    table.contains((key, pos))

  /** Get raw entry for LR algorithm internals. Returns Left(LR) if in left-recursive cycle,
    * Right(MemoEntry) if cached.
    */
  def getRaw[E, A](key: MemoKey[E, A], pos: Int): Option[Either[LR, MemoEntry]] =
    table.get((key, pos))

  /** Store raw entry for seed growth algorithm. Used internally when growing seeds - maintains type
    * safety through the algorithm's invariant that the same key is always used.
    */
  def putRaw[E, A](key: MemoKey[E, A], pos: Int, entry: Either[LR, MemoEntry]): Unit = {
    val _ = table.put((key, pos), entry)
  }

  /** Erase result type for heterogeneous storage. Safe because we track type through MemoKey.
    */
  private def eraseResult[E, A](result: Result[E, A]): Result[Any, Any] =
    result.asInstanceOf[Result[Any, Any]] // scalafix:ok DisableSyntax.asInstanceOf

  /** Cast erased result back to typed result.
    *
    * SAFETY: This cast is safe when called through getResult() because the MemoKey[E, A] used for
    * retrieval is the same instance used for storage.
    */
  private def castResult[E, A](result: Result[Any, Any]): Result[E, A] =
    result.asInstanceOf[Result[E, A]] // scalafix:ok DisableSyntax.asInstanceOf
}

private[runtime] object MemoTable {
  def apply(): MemoTable = new MemoTable()
}

/** Simple memoization table for non-left-recursive parsers.
  *
  * Optimized for pure caching without LR overhead:
  *   - No Either[LR, Entry] wrapping (direct result storage)
  *   - No Option wrapping (always has a result when cached)
  *   - Separate from LR infrastructure for better cache locality
  *
  * Performance: ~50% faster cache hits than full LR path.
  */
final private[runtime] class SimpleMemoTable private () {
  private val table: mutable.Map[(AnyRef, Int), SimpleCacheEntry] = mutable.Map.empty

  /** Store a cached result with type-safe key.
    *
    * Type safety: key's [E, A] matches result's [E, A] at compile time.
    */
  def put[E, A](key: MemoKey[E, A], pos: Int, result: Result[E, A], endPos: Int): Unit = {
    val _ = table.put((key, pos), SimpleCacheEntry(eraseResult(result), endPos))
  }

  /** Retrieve cached result with type-safe key.
    *
    * SAFETY PROOF for the cast:
    *   1. MemoKey[E, A] instances are unique
    *   2. The same key is used for both put() and get()
    *   3. put() stores Result[E, A] (erased to Any)
    *   4. Therefore get() returns the same Result[E, A]
    */
  def get[E, A](key: MemoKey[E, A], pos: Int): Option[SimpleCacheEntry] =
    table.get((key, pos))

  /** Retrieve and cast the cached result.
    */
  def getResult[E, A](key: MemoKey[E, A], pos: Int): Option[Result[E, A]] =
    table.get((key, pos)).map(entry => castResult[E, A](entry.result))

  private def eraseResult[E, A](result: Result[E, A]): Result[Any, Any] =
    result.asInstanceOf[Result[Any, Any]] // scalafix:ok DisableSyntax.asInstanceOf

  private def castResult[E, A](result: Result[Any, Any]): Result[E, A] =
    result.asInstanceOf[Result[E, A]] // scalafix:ok DisableSyntax.asInstanceOf
}

private[runtime] object SimpleMemoTable {
  def apply(): SimpleMemoTable = new SimpleMemoTable()
}

/** Entry in the simple memoization table.
  *
  * @param result
  *   The cached parse result (type-erased for heterogeneous storage)
  * @param pos
  *   The position after parsing
  */
final private[runtime] case class SimpleCacheEntry(
  result: Result[Any, Any],
  pos: Int
)

/** Entry in the memoization table for left recursion handling.
  *
  * Stores type-erased result because the table is heterogeneous. Type safety is maintained by
  * MemoKey at the API boundary.
  *
  * @param result
  *   The cached parse result (None if currently being evaluated)
  * @param pos
  *   The position after parsing (for detecting progress)
  */
final private[runtime] case class MemoEntry(
  result: Option[Result[Any, Any]],
  pos: Int
)

/** Tracks the "head" of a left-recursive rule. Used to detect when we're in a left-recursive cycle.
  *
  * @param rule
  *   The parser identity (MemoKey) that started the left recursion
  * @param involvedSet
  *   Parsers involved in this left-recursive cycle
  * @param evalSet
  *   Parsers that need re-evaluation during seed growth
  */
final private[runtime] class LRHead(
  val rule: AnyRef,
  val involvedSet: mutable.Set[AnyRef],
  var evalSet: mutable.Set[AnyRef]
)

/** Left recursion marker used during seed detection.
  *
  * @param seed
  *   The current seed result (type-erased for heterogeneous storage)
  * @param rule
  *   The parser identity (MemoKey)
  * @param head
  *   The head of the left-recursive cycle (if known)
  */
final private[runtime] case class LR(
  var seed: Result[Any, Any],
  rule: AnyRef,
  var head: Option[LRHead]
)

/** Element-access cursor over an input source — the seam that lets the interpreter read input
  * elements without hard-coding `Char`/`String`.
  *
  * `Input` abstracts ONLY element access (length, bounds, element-at-offset). The position
  * machinery (offset/line/column, save/restore) stays on [[ParserState]] and is element-blind, per
  * the increment-1 design — so `Input` carries no line/column logic.
  *
  * The string path uses [[StringInput]]. A future token-stream grammar provides a parallel
  * `Input[Tok]` backed by an indexed token sequence; nothing else in the cursor contract changes.
  *
  * Route-A note (increment 1): [[ParserState]] still holds its `input: String` field directly and
  * keeps every char hot-path accessor (`currentChar`, `hasChar`, `advanceByString`, `slice`)
  * byte-identical — so the string path pays nothing. `Input`/[[StringInput]] is introduced as the
  * cursor *interface* and backs only the cold, element-generic accessors
  * (`hasElement`/`currentElement`/`peekElement`); swapping the field's backing to `Input[Elem]` is
  * deferred to the first real token grammar (post-increment-3), where it plugs in without touching
  * the char path.
  *
  * @tparam Elem
  *   The input element type (e.g. `Char` for the string path).
  */
trait Input[Elem] {

  /** Total number of elements in the source. */
  def length: Int

  /** True iff `offset` indexes a valid element (`0 <= offset < length`). */
  def hasElementAt(offset: Int): Boolean

  /** The element at `offset`. Precondition: `hasElementAt(offset)` — callers check first. */
  def elementAt(offset: Int): Elem
}

/** [[Input]] backed by a `String`; its element type is `Char`. Thin wrapper over `String` accessors
  * (`length`/`charAt`), so element reads are direct `String` operations.
  */
final class StringInput(val string: String) extends Input[Char] {
  def length: Int = string.length
  def hasElementAt(offset: Int): Boolean = offset >= 0 && offset < string.length
  def elementAt(offset: Int): Char = string.charAt(offset)
}

/** Mutable parser state tracking position in input.
  *
  * Encapsulates all mutation needed for parsing while keeping the parser descriptions themselves
  * pure and immutable.
  *
  * State tracked:
  *   - offset: Character position (0-indexed)
  *   - line: Line number (1-indexed)
  *   - column: Column number (1-indexed)
  *
  * Left recursion support:
  *   - memo: Memoization table keyed by (parser-identity, position)
  *   - lrStack: Stack tracking left-recursive rule invocations
  *   - heads: Map from position to the head of left-recursive cycles
  *
  * Use the `parserState(input)` function to create instances.
  *
  * @param input
  *   The input string being parsed
  */
final class ParserState private[runtime] (
  val input: String,
  private var _offset: Int,
  private var _line: Int,
  private var _column: Int
) {
  private[runtime] lazy val memo: MemoTable = MemoTable()
  private[runtime] lazy val lrStack: mutable.ArrayBuffer[LR] = mutable.ArrayBuffer.empty
  private[runtime] lazy val heads: mutable.Map[Int, LRHead] = mutable.Map.empty
  private[runtime] lazy val simpleCache: SimpleMemoTable = SimpleMemoTable()

  private var _errorsDiscarded: Boolean = false

  /** True when the enclosing interpreter frame has declared it will discard any inner failure (e.g.
    * the terminating iteration of `Many`/`SkipMany`, the no-match branch of `Optional`). Terminals
    * short-circuit to a shared sentinel `LazyFailure` when this is set, avoiding ParseError + Set +
    * Location + thunk allocation whose payload nobody reads. Combinators that DO observe inner
    * failures (`Attempt` wraps them into a Success; `RecoverWith` folds them into user-visible
    * Partial/Failure) must toggle the flag off before their inner call.
    */
  private[runtime] def errorsDiscarded: Boolean = _errorsDiscarded
  private[runtime] def setErrorsDiscarded(value: Boolean): Unit = _errorsDiscarded = value

  /** Parse-scoped hash-cons cache for [[Parser.InternedGreen]]. Stored at the monomorphic
    * `GreenCache[Any, Any]` so `ParserState` doesn't need to be parameterised on `(Tok, Syn)`,
    * which would cascade through every interpreter signature.
    *
    * Scala 3 won't unreduce a higher-kinded opaque type against wildcard arguments (`GreenCache[?,
    * ?]` is a hard compile error), so the cache is stored at the concrete-but- erased
    * `GreenCache[Any, Any]` and the interpreter casts to `GreenCache[Tok, Syn]` at the single
    * `Parser.InternedGreen` handler site. Type-safe under the invariant "one language per parse": a
    * single [[parser.runtime.run]] invocation runs one grammar whose green-producers share one
    * `(Tok, Syn)` pair, so structural equality on the cache's keys is well-defined. Same erasure
    * discipline as [[MemoTable]] / [[SimpleMemoTable]].
    *
    * Starts empty on every fresh `ParserState`, so no green leaks between parses.
    */
  private var _greenCache: GreenCache[Any, Any] = GreenCache.empty[Any, Any]

  private[runtime] def greenCache: GreenCache[Any, Any] = _greenCache
  private[runtime] def setGreenCache(cache: GreenCache[Any, Any]): Unit = _greenCache = cache

  def offset: Int = _offset
  def line: Int = _line
  def column: Int = _column

  def location: Location =
    (line = _line, column = _column, offset = _offset)

  def atEnd: Boolean = _offset >= input.length

  def current: Option[Char] =
    if atEnd then {
      None
    } else {
      Some(input(_offset))
    }

  /** Inline check for character availability - avoids Option boxing */
  inline def hasChar: Boolean = _offset < input.length

  /** Inline character access - only call when hasChar is true */
  inline def currentChar: Char = input.charAt(_offset)

  def peek(n: Int): Option[Char] = {
    val idx = _offset + n
    if idx < 0 || idx >= input.length then {
      None
    } else {
      Some(input(idx))
    }
  }

  /** Element-generic cursor view of this state's input, for the element-abstract parse path.
    *
    * Route-A seam (increment 1): the only input source today is the string path, so the cursor is a
    * [[StringInput]] over the existing `input` field — `currentElement` reads the same `charAt` the
    * char accessors do, just typed as the abstract `Elem` (here `Char`). The hot char path
    * (`currentChar`/`hasChar`/char-scan loops) does NOT go through this view; it stays on the
    * direct `String` accessors above, so string-path performance is unchanged. When a token grammar
    * lands, this becomes the `Input[Tok]` the state was constructed with.
    */
  private[runtime] val inputCursor: Input[Char] = StringInput(input)

  /** True when the input source is the Char/string backing — the precondition for the zero-alloc
    * char-scan fast paths (`interpretManySatisfy`, `tryMatchSimpleI`, …), which read `currentChar`
    * unboxed and treat a matched `Satisfy`'s element as `Char`.
    *
    * Under route A every `ParserState` is `StringInput`-backed, so this is always true and the fast
    * paths always engage — string-path behaviour and performance are unchanged. The gate exists so
    * that when a token-stream backing lands, a `Satisfy[Tok]` is NOT misrouted through a Char loop:
    * the fast-path guards become false and execution falls through to the element-generic branch.
    */
  private[runtime] def isCharBacked: Boolean = true

  /** True iff an element is available at the current offset (element-generic mirror of
    * [[hasChar]]).
    */
  def hasElement: Boolean = inputCursor.hasElementAt(_offset)

  /** The element at the current offset. Precondition: [[hasElement]] is true (mirror of
    * [[currentChar]]).
    */
  def currentElement: Char = inputCursor.elementAt(_offset)

  /** The element `n` positions ahead, or `None` if out of bounds (element-generic mirror of
    * [[peek]]).
    */
  def peekElement(n: Int): Option[Char] = {
    val idx = _offset + n
    if inputCursor.hasElementAt(idx) then Some(inputCursor.elementAt(idx)) else None
  }

  def advance(): Unit =
    if !atEnd then {
      if input(_offset) == '\n' then {
        _line += 1
        _column = 1
      } else {
        _column += 1
      }
      _offset += 1
    }

  def advanceN(n: Int): Unit = {
    var i = 0
    while i < n do {
      advance()
      i += 1
    }
  }

  /** Advances position by a known string in O(1) for newline-free strings.
    *
    * Optimized for string matching where we know the exact content being consumed. Avoids
    * per-character iteration when there are no newlines.
    *
    * @param s
    *   The string being consumed (must match input at current position)
    */
  def advanceByString(s: String): Unit = {
    val len = s.length
    _offset += len
    val nlIdx = s.indexOf('\n')
    if nlIdx < 0 then {
      _column += len
    } else {
      val newlines = s.count(_ == '\n')
      _line += newlines
      _column = len - s.lastIndexOf('\n')
    }
  }

  def save: StateSnapshot =
    StateSnapshot.of(_offset, _line, _column)

  def restore(snapshot: StateSnapshot): Unit = {
    _offset = snapshot.offset
    _line = snapshot.line
    _column = snapshot.column
  }

  def remaining: String = input.substring(_offset)

  def slice(start: Int, end: Int): String = input.substring(start, end)
}

/** Snapshot of parser state for backtracking.
  *
  * Used by save/restore methods for implementing choice, Attempt, Many, etc. Encoded as an opaque
  * Long to avoid Int boxing on the parser's hottest allocation path — JFR profiling showed the
  * previous named-tuple encoding was responsible for ~40% of sampled bytes on JSON parsing, all
  * from `java.lang.Integer` boxing inside the `save`/`restore` pair.
  *
  * Encoding: `(offset << 32) | ((line & 0xFFFF) << 16) | (column & 0xFFFF)`. Limits: 4 GB input, 65
  * 535 lines, 65 535 columns per line. These bounds are generous for source-code-sized inputs; for
  * any larger or wider input the snapshot silently truncates line/column, which affects only
  * error-reporting line/column numbers after a backtrack, not parse correctness.
  *
  * Fields:
  *   - offset: Character offset (0-indexed)
  *   - line: Line number (1-indexed)
  *   - column: Column number (1-indexed)
  */
opaque type StateSnapshot = Long

object StateSnapshot {
  inline def of(offset: Int, line: Int, column: Int): StateSnapshot =
    (offset.toLong << 32) | ((line & 0xffff).toLong << 16) | (column & 0xffff).toLong

  extension (s: StateSnapshot) {
    inline def offset: Int = (s >>> 32).toInt
    inline def line: Int = ((s >>> 16) & 0xffff).toInt
    inline def column: Int = (s & 0xffff).toInt
  }
}

/** Creates a new parser state for the given input.
  *
  * Initial state:
  *   - offset = 0
  *   - line = 1
  *   - column = 1
  *
  * @param input
  *   The string to parse
  * @return
  *   A fresh parser state ready to parse input
  *
  * Example:
  * {{{
  * val state = parserState("hello")
  * state.current  // Some('h')
  * }}}
  */
def parserState(input: String): ParserState =
  new ParserState(input, 0, 1, 1)
