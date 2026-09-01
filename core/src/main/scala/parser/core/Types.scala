package parser.core

import scala.collection.immutable.Vector
import scala.collection.mutable

/** Represents a position in the input stream.
  *
  * Fields:
  *   - line: 1-indexed line number
  *   - column: 1-indexed column number
  *   - offset: 0-indexed character offset from start
  *
  * Example:
  * {{{
  * val loc: Location = (line = 1, column = 5, offset = 4)
  * }}}
  */
type Location = (line: Int, column: Int, offset: Int)

/** Represents a span of input between two locations.
  *
  * Fields:
  *   - start: Starting location (inclusive)
  *   - end: Ending location (exclusive)
  */
type Span = (start: Location, end: Location)

/** A parser over an input **element** type `Elem` (characters, tokens, …) that produces a result of
  * type `A`, or fails with an error of type `E`.
  *
  * Parsers are pure, immutable descriptions of parsing operations. They do not perform any
  * computation until executed with the `run` method.
  *
  * ==Element type parameter (`Elem`)==
  *
  * `Elem` is the type of a single input element. For the string path it is [[Char]]; a token-stream
  * grammar would instantiate it at a token type. `Elem` is **invariant** (unlike `E`/`A`, which
  * stay covariant): a `ParserK[E, Char, A]` and a `ParserK[E, Token, A]` are unrelated types, so
  * combining mismatched-element parsers (e.g. `zip(charParser, tokenParser)`) is a **compile
  * error** rather than silently unifying their elements to a least-upper-bound. That is the whole
  * point of carrying the element type — element mismatches are caught by the type system.
  *
  * Element-agnostic cases (`Succeed`, `Fail`, `Eof`, and every value-only combinator) each carry a
  * *free* `Elem` type parameter so they compose into a grammar of any single element type; within
  * one grammar every node shares one `Elem`, so invariant unification just works.
  *
  * Most code never writes `ParserK` directly — see the [[Parser]] type alias below, which fixes
  * `Elem = Char` so existing string grammars and all their call sites are unchanged.
  *
  * Type parameters:
  * @tparam E
  *   The error type (covariant)
  * @tparam Elem
  *   The input element type (invariant — see above)
  * @tparam A
  *   The result type (covariant)
  *
  * Example:
  * {{{
  * import parser.syntax.*
  *
  * val parser = char('a') ~ char('b')
  * val result = parser.run("ab")
  * // Success(('a', 'b'), 2)
  * }}}
  */
enum ParserK[+E, Elem, +A] {
  case Succeed[Elem, A](value: A) extends ParserK[Nothing, Elem, A]
  case Fail[E, Elem](error: E) extends ParserK[E, Elem, Nothing]

  /** Fails with a [[ParseError.Custom]] carrying the position where the failure happened — unlike
    * [[Fail]], whose error is built before any input is seen and cannot know it.
    */
  case FailWith[Elem](message: String) extends ParserK[ParseError, Elem, Nothing]

  /** Produces the current input offset without consuming anything — for attaching source positions
    * to values (e.g. validation errors computed after a structure is built).
    */
  case GetOffset[Elem]() extends ParserK[Nothing, Elem, Int]
  // The one generalized element-consuming primitive: a predicate over `Elem`. The Char-specialized
  // constructors (`char`, `satisfy`, `digit`, …) build `Satisfy[Char]`, so the string path is
  // unchanged; a future token grammar builds `Satisfy[Tok]` over a token-kind predicate.
  case Satisfy[Elem](pred: Elem => Boolean, expected: String) extends ParserK[ParseError, Elem, Elem]
  // StringMatch / StringChoice / Capture are intrinsically Char operations (radix matching,
  // regionMatches, slicing input to a String) — kept Char-pinned, layered over the generic
  // element primitive only where it makes sense. A token grammar matches token kind via Satisfy.
  case StringMatch(target: String) extends ParserK[ParseError, Char, String]
  case StringChoice(radix: RadixNode, targets: Array[String]) extends ParserK[ParseError, Char, String]

  /** First-character dispatch: peek the next input char and run the parser mapped to it directly,
    * skipping the linear backtracking scan of `Or`/`Choice`. The `table` maps each possible leading
    * character to its parser; `expected` is the concatenation of those characters (in declaration
    * order) for error messages; `fallback` runs when the next char is not in the table.
    *
    * Char-pinned and `ParseError`-fixed (like [[ParserK.StringMatch]] / [[ParserK.Satisfy]]): a
    * dispatch miss with no fallback synthesizes a `ParseError.Unexpected` /
    * `ParseError.EndOfInput`. Built by [[parser.core.firstCharChoice]]; the interpreter handler is
    * a pure "pick the next parser" decision, so it carries no continuation frame in the trampoline.
    */
  case FirstCharChoice[A](
    table: scala.collection.immutable.Map[Char, Parser[ParseError, A]],
    expected: String,
    fallback: Option[Parser[ParseError, A]]
  ) extends ParserK[ParseError, Char, A]

  case Map[E, Elem, A, B](source: ParserK[E, Elem, A], f: A => B) extends ParserK[E, Elem, B]
  case FlatMap[E, Elem, A, B](source: ParserK[E, Elem, A], f: A => ParserK[E, Elem, B]) extends ParserK[E, Elem, B]
  case Zip[E, Elem, A, B](left: ParserK[E, Elem, A], right: ParserK[E, Elem, B]) extends ParserK[E, Elem, (A, B)]

  /** Fused sequence that runs `left` then `right` and keeps only **right**'s value.
    *
    * The LEFT operand is *skipped* (discarded). This is the fused form of the prior
    * `FlatMap(left, a => Map(right, _ => a))`: the interpreter runs both legs and returns `right`'s
    * value directly, never allocating the `(A, B)` pair nor a discarding `Map`/closure. Constructed
    * by [[parser.core.zipRight]] (and the `*>` syntax).
    *
    * This is the dominant token shape in real grammars (every `lexeme`-wrapped atom, every
    * structural separator), so eliminating the per-token pair + map frame is the highest-frequency
    * dispatch/alloc win — the same fusion rumil-dart ships as `SkipLeft`/`skipThen`.
    */
  case SkipLeft[E, Elem, A, B](left: ParserK[E, Elem, A], right: ParserK[E, Elem, B]) extends ParserK[E, Elem, B]

  /** Fused sequence that runs `left` then `right` and keeps only **left**'s value.
    *
    * The RIGHT operand is *skipped* (discarded). Mirror of [[SkipLeft]]: no pair, no discarding
    * closure. Constructed by [[parser.core.zipLeft]] (and the `<*` syntax).
    */
  case SkipRight[E, Elem, A, B](left: ParserK[E, Elem, A], right: ParserK[E, Elem, B]) extends ParserK[E, Elem, A]

  case Or[E, Elem, A](left: ParserK[E, Elem, A], right: ParserK[E, Elem, A]) extends ParserK[E, Elem, A]
  case Choice[E, Elem, A](alternatives: List[ParserK[E, Elem, A]]) extends ParserK[E, Elem, A]
  case Many[E, Elem, A](parser: ParserK[E, Elem, A]) extends ParserK[E, Elem, List[A]]
  case Many1[E, Elem, A](parser: ParserK[E, Elem, A]) extends ParserK[E, Elem, List[A]]
  case SkipMany[E, Elem, A](parser: ParserK[E, Elem, A]) extends ParserK[E, Elem, Unit]
  case Capture[E, A](parser: ParserK[E, Char, A]) extends ParserK[E, Char, String]
  case Optional[E, Elem, A](parser: ParserK[E, Elem, A]) extends ParserK[E, Elem, Option[A]]
  case Attempt[E, Elem, A](parser: ParserK[E, Elem, A]) extends ParserK[Nothing, Elem, Result[E, A]]
  case LookAhead[E, Elem, A](parser: ParserK[E, Elem, A]) extends ParserK[E, Elem, A]
  case NotFollowedBy[Elem, A](parser: ParserK[ParseError, Elem, A]) extends ParserK[ParseError, Elem, Unit]
  case Named[Elem, A](parser: ParserK[ParseError, Elem, A], name: String) extends ParserK[ParseError, Elem, A]
  case Trace[E, Elem, A](parser: ParserK[E, Elem, A], label: String) extends ParserK[E, Elem, A]
  case Debug[E, Elem, A](parser: ParserK[E, Elem, A], label: String) extends ParserK[E, Elem, A]
  case Defer[E, Elem, A](thunk: () => ParserK[E, Elem, A]) extends ParserK[E, Elem, A]
  case Eof[Elem]() extends ParserK[ParseError, Elem, Unit]
  case RecoverWith[E, Elem, A](parser: ParserK[E, Elem, A], recovery: ParserK[E, Elem, A]) extends ParserK[E, Elem, A]
  case Expect[Elem, A](parser: ParserK[ParseError, Elem, A], message: String) extends ParserK[ParseError, Elem, A]
  case Memo[E, Elem, A](inner: ParserK[E, Elem, A], key: MemoKey[E, A], enableLR: Boolean) extends ParserK[E, Elem, A]
  case Pratt[E, Elem, A](
    nud: ParserK[E, Elem, A],
    getOp: ParserK[E, Elem, PrattOp[A]],
    minBp: Int,
    opTable: PrattOpTable[A] | Null
  ) extends ParserK[E, Elem, A]

  /** Runs [[inner]], then looks up the produced green in the parse-scoped [[GreenCache]] and
    * returns the canonical instance. Structurally-equal greens (e.g. every `Token(Number, "5")`
    * across a parse, or every identical `Tree(Expression, ...)` subtree) collapse to one heap
    * instance.
    *
    * Opt-in combinator entry points — grammars wrap token-producing parsers with
    * [[parser.core.internToken]] or tree-producing parsers with [[parser.core.internTree]]. The
    * distinction is purely grammar-author-facing naming; both combinators produce this one ADT
    * case. The interpreter reads the cache through [[parser.runtime.ParserState]]; no other parser
    * case carries or threads the cache.
    *
    * Same ADT-integration precedent as [[ParserK.Memo]]: one case, one handler, no pervasive
    * threading through other combinators.
    */
  case InternedGreen[E, Elem, Tok, Syn](inner: ParserK[E, Elem, GreenNodeOf[Tok, Syn]])
      extends ParserK[E, Elem, GreenNodeOf[Tok, Syn]]
}

/** Backwards-compatible alias fixing the input element type to [[Char]] — the string path.
  *
  * Every existing string grammar, combinator signature, and call site writes `Parser[E, A]` (and
  * `Parser.Succeed(...)`, `case Parser.Satisfy(...)`, …); they resolve through this alias and the
  * companion `val` below to [[ParserK]] at `Elem = Char`, so the element abstraction is invisible
  * to char-parsing code. New element-generic code (e.g. token grammars) uses [[ParserK]] directly.
  *
  * Same dual `type` + `val` aliasing mechanism as [[GreenNode]] / [[GreenNodeOf]]: the `type` alias
  * forwards type references; the `val Parser: ParserK.type` forwards companion-member access
  * (constructors and pattern-match extractors), which a bare type alias does not.
  *
  * @tparam E
  *   The error type (covariant)
  * @tparam A
  *   The result type (covariant)
  */
type Parser[+E, +A] = ParserK[E, Char, A]
val Parser: ParserK.type = ParserK

/** Pre-compiled character-indexed operator dispatch table for Pratt parsing.
  *
  * When all operators in a Pratt grammar have single-character symbols, the public builder compiles
  * them into this table so the loop can dispatch by peeking the next input character instead of
  * running the `getOp` parser. This avoids per-operator allocation of `LazyFailure`, `Location`,
  * and `PrattOp.Infix`/`PrattOp.Postfix` instances — the table holds pre-built `Op` instances
  * reused across every parse.
  *
  * The table is indexed by `Char.toInt`; a `null` slot means the character is not an operator.
  * Lookups at non-operator positions cost one array access and one null check. Callers must only
  * consume the input character when they've confirmed a match and want to apply the operator.
  */
final class PrattOpTable[A](private val slots: Array[PrattOp[A] | Null]) {
  inline def opAt(c: Char): PrattOp[A] | Null =
    if c.toInt >= slots.length then null else slots(c.toInt) // scalafix:ok DisableSyntax.null
}

object PrattOpTable {

  /** Constructs a table from (char, op) pairs. Last wins on duplicate keys. Returns the table, and
    * the maximum codepoint covered (so the caller can size the slot array tightly).
    */
  def fromPairs[A](pairs: List[(Char, PrattOp[A])]): PrattOpTable[A] = {
    val max = pairs.map(_._1.toInt).maxOption.getOrElse(-1)
    val slots = new Array[PrattOp[A] | Null](max + 1)
    pairs.foreach { case (ch, op) => slots(ch.toInt) = op }
    PrattOpTable(slots)
  }
}

/** Operator description for Pratt (Top-Down Operator Precedence) parsing.
  *
  * Produced by a `getOp` parser, consumed by the Pratt evaluation loop. Binding powers (`lbp`,
  * `rbp`, `bp`) drive precedence and associativity:
  *
  *   - Left-associative infix at precedence `n`: `lbp = n, rbp = n` (RHS requires strictly greater
  *     bp to continue)
  *   - Right-associative infix at precedence `n`: `lbp = n, rbp = n - 1` (RHS accepts equal bp,
  *     enabling right-nesting)
  *   - Postfix: single `bp`; applies in-place to the accumulated LHS
  *
  * Prefix operators are not an `Op` variant — they are compiled into the `nud` parser directly.
  */
enum PrattOp[A] {
  case Infix(lbp: Int, rbp: Int, combine: (A, A) => A) extends PrattOp[A]
  case Postfix(bp: Int, apply: A => A) extends PrattOp[A]
}

/** Standard error type for parsing failures.
  *
  * Cases:
  *   - Unexpected: Found one thing, expected another
  *   - EndOfInput: Reached end of input unexpectedly
  *   - Custom: User-defined error message
  */
enum ParseError {
  case Unexpected(found: String, expected: Set[String], location: Location)
  case EndOfInput(expected: String, location: Location)
  case Custom(message: String, location: Location)
}

/** The result of running a parser on input.
  *
  * Type parameters:
  * @tparam E
  *   The error type (covariant)
  * @tparam A
  *   The success value type (covariant)
  *
  * Cases:
  *   - Success: Parser succeeded, contains value and characters consumed
  *   - Partial: Parser partially succeeded with errors (resilient parsing)
  *   - Failure: Parser failed, contains errors and furthest location reached
  *
  * The Partial case enables error recovery - the parser produces a result (often a GreenNode tree
  * with error markers) while collecting errors. This allows IDE tooling to work with
  * partially-valid code.
  *
  * The furthest location is used for error reporting to show the most specific parse failure point.
  */
enum Result[+E, +A] {
  case Success(value: A, consumed: Int)
  case Partial(value: A, errors: List[E], consumed: Int)
  case Failure(errors: List[E], furthest: Location)
}

/** Token classification for lexical analysis.
  *
  * Used in lossless syntax tree construction.
  */
enum TokenKind derives CanEqual {
  case Identifier, Number, String, Keyword, Operator
  case LeftParen, RightParen, LeftBrace, RightBrace
  case Comma, Semicolon, Colon, Arrow
  case Whitespace, Comment, EOF
  case Error // Marks error regions during resilient parsing
}

/** Syntax node classification for abstract syntax trees.
  *
  * Used in lossless syntax tree construction.
  */
enum SyntaxKind derives CanEqual {
  case SourceFile, Function, TypeDef, Expression
  case Block, Statement, Pattern, Literal
}

/** Classification of a red-tree node covering all four [[GreenNode]] cases.
  *
  * Parameterized over a language's `Tok` / `Syn` alphabets. Pattern-match refines the payload:
  * matching [[TokenK]] gives the language's token type; matching [[MissingK]] gives the expected
  * token type; matching [[TreeK]] gives the language's syntax type; [[UnexpectedK]] carries no
  * payload (the wrapped children are reachable via `RedTree.children`).
  */
enum NodeKind[Tok, Syn] {
  case TokenK[Tok, Syn](kind: Tok) extends NodeKind[Tok, Syn]
  case TreeK[Tok, Syn](kind: Syn) extends NodeKind[Tok, Syn]
  case MissingK[Tok, Syn](expected: Tok) extends NodeKind[Tok, Syn]
  case UnexpectedK[Tok, Syn]() extends NodeKind[Tok, Syn]
}

/** Immutable, position-independent syntax tree node.
  *
  * Greens are pure lexical payload: a token carries its kind and text; a tree carries its kind and
  * children. Absolute offsets, line numbers, and spans are computed at the [[RedTree]] layer from
  * the offset at which a green is viewed plus the textual lengths of its preceding siblings.
  *
  * Keeping position data off the green has three consequences:
  *   1. Green subtrees are shareable across edits (the same `1+2` subtree can appear at any offset
  *      in any file without allocation or rewriting).
  *   2. Splicing a subtree into a tree is a pure vector update — no `adjustSpans` pass.
  *   3. Parsers don't need to thread absolute offsets through combinators; Pratt, chainl1, etc.
  *      produce correct trees without any lexer-level offset plumbing.
  *
  * Cases:
  *   - Token: leaf carrying kind + raw text
  *   - Tree: interior node carrying kind + children
  *   - Missing: zero-width placeholder synthesised when the parser expected a token that wasn't
  *     there; carries the kind that was expected so quick-fixes and diagnostics can name it exactly
  *     ("expected `)`"). `textLength(Missing(_)) == 0`, so `toSource` is lossless.
  *   - Unexpected: wraps one or more tokens that the parser skipped during recovery; carries the
  *     skipped tokens as children so `toSource` still reconstructs the original input verbatim.
  *
  * The Missing/Unexpected pair is SwiftSyntax's resilient-tree model: the tree *itself* records
  * what went wrong at the structural position it went wrong, instead of emitting a flat list of
  * errors alongside a tree that looks as if it had parsed. This makes `RedTree.nodeAt(offset)` on
  * an error position return the error node directly, which is what a language server needs to
  * surface a diagnostic at that point.
  */
enum GreenNodeOf[Tok, Syn] {
  case Token[Tok, Syn](kind: Tok, text: String) extends GreenNodeOf[Tok, Syn]
  // `width` is the cached source-char width of the whole subtree (Roslyn / rust-analyzer "green
  // node stores full width"). It is a STRICT field populated at construction from the children's
  // already-cached widths — O(children) per level, never a recursive descent (greens build
  // bottom-up, so every child already carries its width). This makes [[GreenNodeOf.textLength]] an
  // O(1) read, which is what flattens a keystroke from O(file) to O(depth). Build `Tree`/
  // `Unexpected` through [[GreenNodeOf.tree]] / [[GreenNodeOf.unexpected]] (and the `*Vec`
  // variants), which compute `width` correctly; passing a wrong `width` silently breaks every
  // offset, so the smart constructors are the only intended construction path.
  case Tree[Tok, Syn](kind: Syn, children: Vector[GreenNodeOf[Tok, Syn]], width: Int) extends GreenNodeOf[Tok, Syn]
  case Missing[Tok, Syn](expected: Tok) extends GreenNodeOf[Tok, Syn]
  case Unexpected[Tok, Syn](children: Vector[GreenNodeOf[Tok, Syn]], width: Int) extends GreenNodeOf[Tok, Syn]
}

object GreenNodeOf {

  /** Creates a token node. */
  def token[Tok, Syn](kind: Tok, text: String): GreenNodeOf[Tok, Syn] =
    Token[Tok, Syn](kind, text)

  /** Creates a tree node from children (varargs). Width is summed from the children's cached widths
    * — O(children), never a recursive descent (see [[treeOfVec]]).
    */
  def tree[Tok, Syn](kind: Syn, children: GreenNodeOf[Tok, Syn]*): GreenNodeOf[Tok, Syn] =
    treeOfVec(kind, children.toVector)

  /** Creates a tree node from an already-built children vector. The single intended construction
    * path for `Tree`: it computes the cached `width` as `sum(child.width)` over DIRECT children —
    * O(children), reading each child's already-cached O(1) width. Because greens are built
    * bottom-up (every child is fully constructed before its parent), this never recurses into
    * descendants, so building an arbitrarily deep tree stays O(total nodes) overall and O(children)
    * per level — it cannot reintroduce the depth-recursion stack overflow.
    */
  def treeOfVec[Tok, Syn](kind: Syn, children: Vector[GreenNodeOf[Tok, Syn]]): GreenNodeOf[Tok, Syn] =
    Tree[Tok, Syn](kind, children, sumWidths(children))

  /** Creates a tree node from an already-built children vector AND a precomputed `width`, bypassing
    * the O(children) [[sumWidths]] pass. The single intended use is a splice that already knows the
    * new width by an O(1) delta — e.g. [[TreeSplicing.replaceAt]] rebuilding a path level computes
    * `oldParentWidth - oldChildWidth + newChildWidth` rather than re-summing all N siblings, taking
    * the splice from O(depth·width) to O(depth).
    *
    * `width` MUST equal `sum(child.width)` for these `children` — exactly what [[treeOfVec]] would
    * compute. A wrong width silently corrupts every descendant offset, so this is a caller
    * obligation; prefer [[treeOfVec]] anywhere the O(children) sum is not a measured bottleneck.
    * The [[GreenTreeStackSafety]] value-equivalence corpus + the splice tests guard that the delta
    * agrees with the naive sum.
    */
  def treeWithWidth[Tok, Syn](
    kind: Syn,
    children: Vector[GreenNodeOf[Tok, Syn]],
    width: Int
  ): GreenNodeOf[Tok, Syn] =
    Tree[Tok, Syn](kind, children, width)

  /** Creates a zero-width Missing placeholder for a token the parser expected but didn't find. */
  def missing[Tok, Syn](expected: Tok): GreenNodeOf[Tok, Syn] =
    Missing[Tok, Syn](expected)

  /** Creates an Unexpected wrapper over skipped tokens (varargs). Width summed O(children). */
  def unexpected[Tok, Syn](children: GreenNodeOf[Tok, Syn]*): GreenNodeOf[Tok, Syn] =
    unexpectedOfVec(children.toVector)

  /** Creates an Unexpected wrapper from an already-built children vector; computes `width`
    * O(children) (see [[treeOfVec]] for why this stays shallow).
    */
  def unexpectedOfVec[Tok, Syn](children: Vector[GreenNodeOf[Tok, Syn]]): GreenNodeOf[Tok, Syn] =
    Unexpected[Tok, Syn](children, sumWidths(children))

  /** Sum of the children's cached widths — O(children), reads each child's O(1) `width`, never
    * recurses into grandchildren. A plain indexed `var` loop (no `foldLeft` `Integer` boxing).
    */
  private def sumWidths[Tok, Syn](children: Vector[GreenNodeOf[Tok, Syn]]): Int = {
    var total = 0
    var i = 0
    while i < children.length do {
      total += textLength(children(i))
      i += 1
    }
    total
  }

  /** Length in source characters of [[node]] and everything under it — an O(1) field read.
    *
    * `Token` is `text.length`, `Missing` is `0`, and `Tree`/`Unexpected` return their cached
    * `width` (populated O(children) at construction; see [[treeOfVec]]). This is the read whose
    * O(1)-ness flattens a keystroke from O(file) to O(depth): the prior recursive char-sum was ~88%
    * of keystroke CPU.
    */
  def textLength[Tok, Syn](node: GreenNodeOf[Tok, Syn]): Int = node match {
    case Token(_, text) => text.length
    case Missing(_) => 0
    case Tree(_, _, width) => width
    case Unexpected(_, width) => width
  }

  /** Remove and return the last element of `buf` (LIFO pop). O(1) — trims the tail slot, no shift.
    * Used by the iterative [[toSource]] / [[traverse]] walks (kept stack-safe-by-iteration).
    */
  private def popLast[Tok, Syn](buf: mutable.ArrayBuffer[GreenNodeOf[Tok, Syn]]): GreenNodeOf[Tok, Syn] = {
    val last = buf(buf.length - 1)
    buf.remove(buf.length - 1)
    last
  }

  /** Reconstructs the source text covered by this node.
    *
    * Lossless for Token, Tree, and Unexpected (whose skipped children still carry their original
    * text). Missing contributes the empty string — which is exactly right: the user didn't type
    * what the parser expected, so reconstructing "what was in the buffer" means no text at that
    * position.
    */
  def toSource[Tok, Syn](node: GreenNodeOf[Tok, Syn]): String = {
    // Explicit work-stack (recursion over tree DEPTH overflows on deep trees — see `textLength`).
    // `pushReversed` orders the stack so `popLast` visits leaves left-to-right (document order), so
    // appending token text in pop order reconstructs the source exactly. Not on the keystroke hot
    // path, so a single iterative form (no depth-threshold hybrid) is simplest.
    val sb = new StringBuilder
    val stack = mutable.ArrayBuffer.empty[GreenNodeOf[Tok, Syn]]
    stack += node
    while stack.nonEmpty do {
      popLast(stack) match {
        case Token(_, text) => sb.append(text)
        case Missing(_) => ()
        case Tree(_, children, _) => pushReversed(stack, children)
        case Unexpected(children, _) => pushReversed(stack, children)
      }
    }
    sb.result()
  }

  /** Push `children` in REVERSE onto `stack`, so a subsequent LIFO `popLast` pops them
    * left-to-right (child 0 first) — preserving document order for `toSource`/`traverse`.
    */
  private def pushReversed[Tok, Syn](
    stack: mutable.ArrayBuffer[GreenNodeOf[Tok, Syn]],
    children: Vector[GreenNodeOf[Tok, Syn]]
  ): Unit = children.reverseIterator.foreach(stack += _)

  /** Pre-order traversal; applies [[f]] to each node, parents before children. */
  def traverse[Tok, Syn](node: GreenNodeOf[Tok, Syn])(f: GreenNodeOf[Tok, Syn] => Unit): Unit = {
    // Explicit work-stack (recursion over tree DEPTH overflows on deep trees — see `textLength`).
    // `f` is applied on pop, before children are pushed; `pushReversed` makes the leftmost child
    // pop first — the same parents-before-children, left-to-right pre-order as the recursive form.
    val stack = mutable.ArrayBuffer.empty[GreenNodeOf[Tok, Syn]]
    stack += node
    while stack.nonEmpty do {
      val cur = popLast(stack)
      f(cur)
      cur match {
        case Token(_, _) => ()
        case Missing(_) => ()
        case Tree(_, children, _) => pushReversed(stack, children)
        case Unexpected(children, _) => pushReversed(stack, children)
      }
    }
  }
}

/** Backwards-compat alias so existing code that writes `GreenNode` (as a type) and
  * `GreenNode.Token(...)` (as a value) continues to compile while the codebase is migrated to the
  * parameterized shape. Prefer [[DefaultLanguage.Green]] in new code.
  */
type GreenNode = GreenNodeOf[DefaultLanguage.Token, DefaultLanguage.Syntax]
val GreenNode: GreenNodeOf.type = GreenNodeOf
