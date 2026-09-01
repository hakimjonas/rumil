package parser.benchmarks

import parser.core.*
import parser.runtime.run
import parser.syntax.*

/** Minimal statement-structured grammar that produces `GreenNode` trees.
  *
  * The file is a sequence of newline-separated statements; each statement is a Pratt-parsed
  * arithmetic expression over single-digit numerals and the operators `+ - * /`. Trailing
  * whitespace is preserved as `TokenKind.Whitespace` so `GreenNode.toSource` reconstructs input
  * losslessly.
  *
  * Purpose: a workload for the incremental-parser bench harness. The statement layer gives a
  * natural `SyntaxKind.Statement` reparse boundary, so edits touching one line resolve there
  * instead of escalating to `SyntaxKind.SourceFile`.
  *
  * No span plumbing: greens carry only kind + text (position-independent). Offsets are
  * reconstructed on demand at the red-tree layer.
  */
object IncrementalGrammar {

  private def numberToken(c: Char): GreenNode =
    GreenNode.Token(TokenKind.Number, c.toString)

  private def opToken(c: Char): GreenNode =
    GreenNode.Token(TokenKind.Operator, c.toString)

  private def whitespaceToken(s: String): GreenNode =
    GreenNode.Token(TokenKind.Whitespace, s)

  private def binOp(left: GreenNode, op: Char, right: GreenNode): GreenNode =
    GreenNode.treeOfVec(SyntaxKind.Expression, Vector(left, opToken(op), right))

  private val atomDigit: Parser[ParseError, GreenNode] =
    digit.map(numberToken)

  /** Pratt expression over single-digit atoms with standard arithmetic precedence. */
  private val expression: Parser[ParseError, GreenNode] = {
    lazy val atom: Parser[ParseError, GreenNode] =
      atomDigit | (char('(') *> defer(exprP) <* char(')'))
    lazy val exprP: Parser[ParseError, GreenNode] =
      pratt(
        defer(atom),
        List(
          Operator.InfixLeft(char('+'), 10, (a: GreenNode, b: GreenNode) => binOp(a, '+', b)),
          Operator.InfixLeft(char('-'), 10, (a: GreenNode, b: GreenNode) => binOp(a, '-', b)),
          Operator.InfixLeft(char('*'), 20, (a: GreenNode, b: GreenNode) => binOp(a, '*', b)),
          Operator.InfixLeft(char('/'), 20, (a: GreenNode, b: GreenNode) => binOp(a, '/', b))
        )
      )
    exprP
  }

  /** One statement: an expression wrapped in a `SyntaxKind.Statement` tree. Wrapping lets the
    * incremental parser's `findReparseRegion` stop at the statement boundary instead of always
    * escalating to `SyntaxKind.Block`.
    */
  private val statement: Parser[ParseError, GreenNode] =
    expression.map(e => GreenNode.treeOfVec(SyntaxKind.Statement, Vector(e)))

  /** Zero-or-more whitespace characters preserved as a single `TokenKind.Whitespace` token. */
  private val ws: Parser[ParseError, GreenNode] =
    satisfy(c => c == ' ' || c == '\t' || c == '\n', "whitespace").many.map { chars =>
      whitespaceToken(chars.mkString)
    }

  /** A source file: `ws (stmt ws)*` wrapped in a `SyntaxKind.Block` (children) inside a
    * `SyntaxKind.SourceFile`. Every token that appears in the source — including whitespace — is
    * preserved as a green child, so `GreenNode.toSource(tree) == input`.
    */
  val sourceFile: Parser[ParseError, GreenNode] = {
    val stmtWithTrailing: Parser[ParseError, Vector[GreenNode]] =
      (statement ~ ws).map { case (s, w) => Vector(s, w) }
    (ws ~ stmtWithTrailing.many).map { case (leading, stmts) =>
      val blockChildren = stmts.flatten.toVector
      val block = GreenNode.treeOfVec(SyntaxKind.Block, blockChildren)
      GreenNode.treeOfVec(SyntaxKind.SourceFile, Vector(leading, block))
    }
  }

  /** Reparse parser for a single [[SyntaxKind.Statement]] subtree — exactly the text that
    * `GreenNode.toSource` produces for a Statement node (the wrapped expression, no trailing
    * whitespace; trailing whitespace is a Block child, not a Statement child).
    */
  val statementOnly: Parser[ParseError, GreenNode] = statement

  /** Resilient source-file parser: uses [[syncUntil]] at the statement boundary so a malformed
    * statement is wrapped in [[GreenNode.Unexpected]] and parsing continues at the next newline.
    * Demonstrates panic-mode recovery alongside [[sourceFile]] (which has no recovery).
    *
    * Guard: `notFollowedBy(eof)` prevents the `many` loop from livelocking on the zero-width
    * Partial that `syncUntil` returns at end-of-input.
    */
  val resilientSourceFile: Parser[ParseError, GreenNode] = {
    val resilientStatement: Parser[ParseError, GreenNode] =
      syncUntil(statement, Set('\n'), TokenKind.Error)
    val notAtEof: Parser[ParseError, Unit] =
      parser.core.notFollowedBy(eof)
    val stmtWithTrailing: Parser[ParseError, Vector[GreenNode]] =
      ((notAtEof *> resilientStatement) ~ ws).map { case (s, w) => Vector(s, w) }
    (ws ~ stmtWithTrailing.many).map { case (leading, stmts) =>
      val blockChildren = stmts.flatten.toVector
      val block = GreenNode.treeOfVec(SyntaxKind.Block, blockChildren)
      GreenNode.treeOfVec(SyntaxKind.SourceFile, Vector(leading, block))
    }
  }

  /** Reparse parser for a [[SyntaxKind.Block]] subtree: zero-or-more `(stmt ws)` groups. */
  val blockOnly: Parser[ParseError, GreenNode] = {
    val stmtWithTrailing: Parser[ParseError, Vector[GreenNode]] =
      (statement ~ ws).map { case (s, w) => Vector(s, w) }
    stmtWithTrailing.many.map { stmts =>
      GreenNode.treeOfVec(SyntaxKind.Block, stmts.flatten.toVector)
    }
  }

  /** Reparse-parser bundle: Statement and Block are reparseable; SourceFile is only used for full
    * reparse via [[IncrementalParser.ReparseableParsers.full]].
    */
  val parsers: IncrementalParser.ReparseableParsers[TokenKind, SyntaxKind, ParseError] =
    IncrementalParser.ReparseableParsers[TokenKind, SyntaxKind, ParseError](
      full = sourceFile,
      byKind = Map(
        SyntaxKind.Statement -> statementOnly,
        SyntaxKind.Block -> blockOnly
      ),
      isSimpleToken = (k: TokenKind) =>
        k == TokenKind.Identifier ||
          k == TokenKind.Number ||
          k == TokenKind.String ||
          k == TokenKind.Whitespace ||
          k == TokenKind.Comment,
      // On outright parse failure, wrap the unparseable source in an Error token so the lossless
      // invariant `GreenNode.toSource(result.tree) == newSource` holds on every result path.
      onParseFailure = (src: String) => GreenNode.Token(TokenKind.Error, src)
    )

  /** Generate a synthetic source file of [[statementCount]] newline-separated expressions, each
    * with [[opsPerStatement]] operators over single-digit operands. Returns `(source, greenTree)`
    * where `greenTree` is the parsed result, verified to round-trip losslessly.
    */
  def synthesize(statementCount: Int, opsPerStatement: Int): (String, GreenNode) = {
    val opSymbols = Array('+', '-', '*', '/')
    val sb = new StringBuilder
    var i = 0
    while i < statementCount do {
      sb.append(((i + 1) % 10).toString)
      var j = 0
      while j < opsPerStatement do {
        sb.append(opSymbols((i + j) % opSymbols.length))
        sb.append(((i + j + 1) % 10).toString)
        j += 1
      }
      sb.append('\n')
      i += 1
    }
    val source = sb.result()
    val tree = run(sourceFile, source) match {
      case Result.Success(t, _) => t
      case Result.Partial(_, errors, _) =>
        throw new AssertionError(s"grammar.synthesize produced Partial with ${errors.length} errors — grammar bug")
      case Result.Failure(errors, furthest) =>
        throw new AssertionError(
          s"grammar.synthesize could not parse ${statementCount}×${opsPerStatement}: furthest=$furthest, errors=${errors.length}"
        )
    }
    val reconstructed = GreenNode.toSource(tree)
    if reconstructed != source then {
      throw new AssertionError(
        s"grammar.synthesize lossy: input≠reconstructed (${source.length} vs ${reconstructed.length} bytes)"
      )
    }
    (source, tree)
  }
}
