package parser.core

import scala.collection.immutable.Vector

// ============================================================================
// NAMED TUPLES - Product Types
// ============================================================================

/**
 * Represents a position in the input stream.
 *
 * Fields:
 * - line: 1-indexed line number
 * - column: 1-indexed column number
 * - offset: 0-indexed character offset from start
 *
 * Example:
 * {{{
 * val loc: Location = (line = 1, column = 5, offset = 4)
 * }}}
 */
type Location = (line: Int, column: Int, offset: Int)

/**
 * Represents a span of input between two locations.
 *
 * Fields:
 * - start: Starting location (inclusive)
 * - end: Ending location (exclusive)
 */
type Span = (start: Location, end: Location)

/**
 * Key for memoization table.
 *
 * Identifies a parser at a specific input position.
 * Uses unique parser ID for identity.
 *
 * Fields:
 * - parserId: Unique ID for each parser instance
 * - position: Input offset (0-indexed)
 */
type MemoKey = (parserId: Int, position: Int)

given CanEqual[MemoKey, MemoKey] = CanEqual.derived

/**
 * Entry in the memoization table.
 *
 * Tracks parse results and left-recursion detection state.
 *
 * Cases:
 * - InProgress: Parse is currently in progress (for cycle detection)
 * - Completed: Parse completed with this result
 * - Growing: Left-recursive parse being grown from seed
 */
enum MemoEntry[+E, +A] {
  case InProgress                                           extends MemoEntry[Nothing, Nothing]
  case Completed[E, A](result: Result[E, A], consumed: Int) extends MemoEntry[E, A]
  case Growing[E, A](seed: Result[E, A], consumed: Int)     extends MemoEntry[E, A]
}

given CanEqual[MemoEntry[?, ?], MemoEntry[?, ?]] = CanEqual.derived

// ============================================================================
// ENUMS - Sum Types
// ============================================================================

/**
 * A parser that consumes input of type String and produces a result of type A,
 * or fails with an error of type E.
 *
 * Parsers are pure, immutable descriptions of parsing operations.
 * They do not perform any computation until executed with the `run` method.
 *
 * Type parameters:
 * @tparam E The error type (covariant)
 * @tparam A The result type (covariant)
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
enum Parser[+E, +A] {
  case Succeed[A](value: A)                                          extends Parser[Nothing, A]
  case Fail[E](error: E)                                             extends Parser[E, Nothing]
  case Satisfy(pred: Char => Boolean, expected: String)              extends Parser[ParseError, Char]
  case Map[E, A, B](source: Parser[E, A], f: A => B)                 extends Parser[E, B]
  case FlatMap[E, A, B](source: Parser[E, A], f: A => Parser[E, B])  extends Parser[E, B]
  case Or[E, A](left: Parser[E, A], right: Parser[E, A])             extends Parser[E, A]
  case Many[E, A](parser: Parser[E, A])                              extends Parser[E, List[A]]
  case Many1[E, A](parser: Parser[E, A])                             extends Parser[E, List[A]]
  case Optional[E, A](parser: Parser[E, A])                          extends Parser[E, Option[A]]
  case Attempt[E, A](parser: Parser[E, A])                           extends Parser[Nothing, Result[E, A]]
  case LookAhead[E, A](parser: Parser[E, A])                         extends Parser[E, A]
  case NotFollowedBy[A](parser: Parser[ParseError, A])               extends Parser[ParseError, Unit]
  case Named[A](parser: Parser[ParseError, A], name: String)         extends Parser[ParseError, A]
  case Trace[E, A](parser: Parser[E, A], label: String)              extends Parser[E, A]
  case Debug[E, A](parser: Parser[E, A], label: String)              extends Parser[E, A]
  case Custom[E, A](run: parser.runtime.ParserState => Result[E, A]) extends Parser[E, A]

  /**
   * A parser that may be left-recursive.
   *
   * Wraps a parser to enable memoization and left-recursion handling
   * using the Warth et al. seed-growth algorithm.
   *
   * @param id Unique identifier for this parser instance
   * @param parser The underlying parser (lazy to allow recursion)
   */
  case Recursive[E, A](id: Int, parser: () => Parser[E, A]) extends Parser[E, A]
}

/**
 * Standard error type for parsing failures.
 *
 * Cases:
 * - Unexpected: Found one thing, expected another
 * - EndOfInput: Reached end of input unexpectedly
 * - Custom: User-defined error message
 */
enum ParseError {
  case Unexpected(found: String, expected: Set[String], location: Location)
  case EndOfInput(expected: String, location: Location)
  case Custom(message: String, location: Location)
}

/**
 * The result of running a parser on input.
 *
 * Type parameters:
 * @tparam E The error type (covariant)
 * @tparam A The success value type (covariant)
 *
 * Cases:
 * - Success: Parser succeeded, contains value and characters consumed
 * - Partial: Parser partially succeeded with errors (resilient parsing)
 * - Failure: Parser failed, contains errors and furthest location reached
 *
 * The Partial case enables error recovery - the parser produces a result
 * (often a GreenNode tree with error markers) while collecting errors.
 * This allows IDE tooling to work with partially-valid code.
 *
 * The furthest location is used for error reporting to show the most
 * specific parse failure point.
 */
enum Result[+E, +A] {
  case Success(value: A, consumed: Int)
  case Partial(value: A, errors: List[E], consumed: Int)
  case Failure(errors: List[E], furthest: Location)
}

/**
 * Token classification for lexical analysis.
 *
 * Used in lossless syntax tree construction.
 */
enum TokenKind {
  case Identifier, Number, String, Keyword, Operator
  case LeftParen, RightParen, LeftBrace, RightBrace
  case Comma, Semicolon, Colon, Arrow
  case Whitespace, Comment, EOF
  case Error // Marks error regions during resilient parsing
}

given CanEqual[TokenKind, TokenKind] = CanEqual.derived

/**
 * Syntax node classification for abstract syntax trees.
 *
 * Used in lossless syntax tree construction.
 */
enum SyntaxKind {
  case SourceFile, Function, TypeDef, Expression
  case Block, Statement, Pattern, Literal
}

given CanEqual[SyntaxKind, SyntaxKind] = CanEqual.derived

/**
 * Immutable syntax tree node for lossless parsing.
 *
 * Preserves all source information including whitespace and comments,
 * enabling perfect source reconstruction.
 *
 * Cases:
 * - Token: Leaf node containing raw text
 * - Tree: Interior node with children
 */
enum GreenNode {
  case Token(kind: TokenKind, text: String, span: Span)
  case Tree(kind: SyntaxKind, children: Vector[GreenNode])
}

object GreenNode {

  /**
   * Creates a token node.
   *
   * @param kind The token classification
   * @param text The raw source text
   * @param span The source location span
   * @return A token node
   */
  def token(kind: TokenKind, text: String, span: Span): GreenNode =
    Token(kind, text, span)

  /**
   * Creates a tree node from children.
   *
   * @param kind The syntax node classification
   * @param children The child nodes
   * @return A tree node containing the children
   */
  def tree(kind: SyntaxKind, children: GreenNode*): GreenNode =
    Tree(kind, children.toVector)

  /**
   * Gets the text span covered by this node.
   *
   * For tokens, returns the token's span directly.
   * For trees, computes the span from first child's start to last child's end.
   * Empty trees return a zero-width span at position (1, 1, 0).
   *
   * @param node The node to get the span from
   * @return The span covered by the node
   */
  def span(node: GreenNode): Span = node match {
    case Token(_, _, span) => span
    case Tree(_, children) =>
      if (children.isEmpty) {
        (start = (line = 1, column = 1, offset = 0), end = (line = 1, column = 1, offset = 0))
      } else {
        val first = span(children.head)
        val last  = span(children.last)
        (start = first.start, end = last.end)
      }
  }

  /**
   * Reconstructs source text from the tree.
   *
   * This is the lossless property: toSource preserves exact input.
   * Tokens return their text, trees concatenate their children.
   *
   * @param node The node to reconstruct source from
   * @return The reconstructed source text
   */
  def toSource(node: GreenNode): String = node match {
    case Token(_, text, _) => text
    case Tree(_, children) => children.map(toSource).mkString
  }

  /**
   * Traverses the tree in pre-order.
   *
   * Applies the given function to each node, visiting parents before children.
   *
   * @param node The root node to traverse from
   * @param f The function to apply to each node
   */
  def traverse(node: GreenNode)(f: GreenNode => Unit): Unit = {
    f(node)
    node match {
      case Token(_, _, _)    => ()
      case Tree(_, children) => children.foreach(traverse(_)(f))
    }
  }
}
