package parser.runtime

import scala.collection.mutable

import parser.core.{MemoKey, _}

// ============================================================================
// Ref - Controlled Mutation (Eru Pattern)
// ============================================================================

/**
 * Mutable reference cell for controlled mutation.
 *
 * Encapsulates mutation behind a pure interface, allowing functional-style
 * updates while maintaining efficiency.
 *
 * @tparam A The type of value stored
 */
final private class Ref[A](private var value: A) {
  def get: A                  = value
  def set(newValue: A): Unit  = value = newValue
  def update(f: A => A): Unit = value = f(value)
}

// ============================================================================
// Left Recursion Support - Seed-Growth Algorithm (Warth et al.)
// ============================================================================

// Note: MemoKey is defined in parser.core.MemoKey to avoid coupling Parser to runtime

/**
 * Type-safe memoization table for left recursion support.
 *
 * Stores parse results keyed by (MemoKey, position). The MemoKey carries
 * type information ensuring that retrieval returns the correct type.
 *
 * Implementation note: Internally uses type erasure (Any) but the API
 * is type-safe because MemoKey[E, A] can only be used with Result[E, A].
 */
final private[runtime] class MemoTable private () {
  private val table: mutable.Map[(AnyRef, Int), Either[LR, MemoEntry]] = mutable.Map.empty

  /**
   * Store a result with type-safe key.
   *
   * Type safety: key's [E, A] matches result's [E, A] at compile time.
   * Internally erases to Any for heterogeneous storage.
   */
  def put[E, A](key: MemoKey[E, A], pos: Int, result: Result[E, A], endPos: Int): Unit = {
    val _ = table.put((key, pos), Right(MemoEntry(Some(eraseResult(result)), endPos)))
  }

  /** Store an LR marker with type-safe key. */
  def putLR[E, A](key: MemoKey[E, A], pos: Int, lr: LR): Unit = {
    val _ = table.put((key, pos), Left(lr))
  }

  /**
   * Retrieve and cast a cached result with type-safe key.
   *
   * SAFETY PROOF for the cast:
   * 1. MemoKey[E, A] instances are unique (created once per `rule` call)
   * 2. The same key is used for both put() and getResult()
   * 3. put() stores Result[E, A] (erased to Any)
   * 4. Therefore getResult() returns the same Result[E, A]
   *
   * This is the ONLY cast point for typed retrieval.
   */
  def getResult[E, A](key: MemoKey[E, A], pos: Int): Option[Result[E, A]] =
    table.get((key, pos)).flatMap {
      case Right(entry) => entry.result.map(castResult[E, A])
      case Left(_)      => None
    }

  /**
   * Retrieve LR marker if present.
   */
  def getLR[E, A](key: MemoKey[E, A], pos: Int): Option[LR] =
    table.get((key, pos)).flatMap {
      case Left(lr) => Some(lr)
      case Right(_) => None
    }

  /**
   * Get the end position from a cached entry.
   */
  def getEndPos[E, A](key: MemoKey[E, A], pos: Int): Option[Int] =
    table.get((key, pos)).flatMap {
      case Right(entry) => Some(entry.pos)
      case Left(_)      => None
    }

  /**
   * Check if entry exists (either LR or cached result).
   */
  def contains[E, A](key: MemoKey[E, A], pos: Int): Boolean =
    table.contains((key, pos))

  /**
   * Get raw entry for LR algorithm internals.
   * Returns Left(LR) if in left-recursive cycle, Right(MemoEntry) if cached.
   */
  def getRaw[E, A](key: MemoKey[E, A], pos: Int): Option[Either[LR, MemoEntry]] =
    table.get((key, pos))

  /**
   * Store raw entry for seed growth algorithm.
   * Used internally when growing seeds - maintains type safety through
   * the algorithm's invariant that the same key is always used.
   */
  def putRaw[E, A](key: MemoKey[E, A], pos: Int, entry: Either[LR, MemoEntry]): Unit = {
    val _ = table.put((key, pos), entry)
  }

  // ===========================================================================
  // Type Erasure Helpers - The ONLY place where casts occur
  // ===========================================================================

  /**
   * Erase result type for heterogeneous storage.
   * Safe because we track type through MemoKey.
   */
  private def eraseResult[E, A](result: Result[E, A]): Result[Any, Any] =
    result.asInstanceOf[Result[Any, Any]]

  /**
   * Cast erased result back to typed result.
   *
   * SAFETY: This cast is safe when called through getResult() because
   * the MemoKey[E, A] used for retrieval is the same instance used for storage.
   */
  private def castResult[E, A](result: Result[Any, Any]): Result[E, A] =
    result.asInstanceOf[Result[E, A]]
}

private[runtime] object MemoTable {
  def apply(): MemoTable = new MemoTable()
}

/**
 * Entry in the memoization table for left recursion handling.
 *
 * Stores type-erased result because the table is heterogeneous.
 * Type safety is maintained by MemoKey at the API boundary.
 *
 * @param result The cached parse result (None if currently being evaluated)
 * @param pos The position after parsing (for detecting progress)
 */
final private[runtime] case class MemoEntry(
  result: Option[Result[Any, Any]],
  pos: Int
)

/**
 * Tracks the "head" of a left-recursive rule.
 * Used to detect when we're in a left-recursive cycle.
 *
 * @param rule The parser identity (MemoKey) that started the left recursion
 * @param involvedSet Parsers involved in this left-recursive cycle
 * @param evalSet Parsers that need re-evaluation during seed growth
 */
final private[runtime] class LRHead(
  val rule: AnyRef,
  val involvedSet: mutable.Set[AnyRef],
  var evalSet: mutable.Set[AnyRef]
)

/**
 * Left recursion marker used during seed detection.
 *
 * @param seed The current seed result (type-erased for heterogeneous storage)
 * @param rule The parser identity (MemoKey)
 * @param head The head of the left-recursive cycle (if known)
 */
final private[runtime] case class LR(
  var seed: Result[Any, Any],
  rule: AnyRef,
  var head: Option[LRHead]
)

// ============================================================================
// ParserState - Controlled Mutation via Refs
// ============================================================================

/**
 * Mutable parser state tracking position in input.
 *
 * Encapsulates all mutation needed for parsing while keeping the parser
 * descriptions themselves pure and immutable.
 *
 * State tracked:
 * - offset: Character position (0-indexed)
 * - line: Line number (1-indexed)
 * - column: Column number (1-indexed)
 *
 * Left recursion support:
 * - memo: Memoization table keyed by (parser-identity, position)
 * - lrStack: Stack tracking left-recursive rule invocations
 * - heads: Map from position to the head of left-recursive cycles
 *
 * Use the `parserState(input)` function to create instances.
 *
 * @param input The input string being parsed
 */
final class ParserState private[runtime] (
  val input: String,
  private val offsetRef: Ref[Int],
  private val lineRef: Ref[Int],
  private val columnRef: Ref[Int],
  // Left recursion support
  private[runtime] val memo: MemoTable = MemoTable(),
  private[runtime] val lrStack: mutable.ArrayBuffer[LR] = mutable.ArrayBuffer.empty,
  private[runtime] val heads: mutable.Map[Int, LRHead] = mutable.Map.empty
) {

  def offset: Int = offsetRef.get
  def line: Int   = lineRef.get
  def column: Int = columnRef.get

  def location: Location =
    (line = lineRef.get, column = columnRef.get, offset = offsetRef.get)

  def atEnd: Boolean = offsetRef.get >= input.length

  def current: Option[Char] =
    if (atEnd) {
      None
    } else {
      Some(input(offsetRef.get))
    }

  def peek(n: Int): Option[Char] = {
    val idx = offsetRef.get + n
    if (idx < 0 || idx >= input.length) {
      None
    } else {
      Some(input(idx))
    }
  }

  def advance(): Unit =
    if (!atEnd) {
      if (input(offsetRef.get) == '\n') {
        lineRef.update(_ + 1)
        columnRef.set(1)
      } else {
        columnRef.update(_ + 1)
      }
      offsetRef.update(_ + 1)
    }

  def advanceN(n: Int): Unit = {
    var i = 0
    while (i < n) {
      advance()
      i += 1
    }
  }

  def save: StateSnapshot =
    (offset = offsetRef.get, line = lineRef.get, column = columnRef.get)

  def restore(snapshot: StateSnapshot): Unit = {
    offsetRef.set(snapshot.offset)
    lineRef.set(snapshot.line)
    columnRef.set(snapshot.column)
  }

  def remaining: String = input.substring(offsetRef.get)

  def slice(start: Int, end: Int): String = input.substring(start, end)
}

/**
 * Snapshot of parser state for backtracking.
 *
 * Used by save/restore methods for implementing choice and lookahead.
 *
 * Fields:
 * - offset: Character offset (0-indexed)
 * - line: Line number (1-indexed)
 * - column: Column number (1-indexed)
 */
type StateSnapshot = (offset: Int, line: Int, column: Int)

/**
 * Creates a new parser state for the given input.
 *
 * Initial state:
 * - offset = 0
 * - line = 1
 * - column = 1
 *
 * @param input The string to parse
 * @return A fresh parser state ready to parse input
 *
 * Example:
 * {{{
 * val state = parserState("hello")
 * state.current  // Some('h')
 * }}}
 */
def parserState(input: String): ParserState =
  new ParserState(input, Ref(0), Ref(1), Ref(1))
