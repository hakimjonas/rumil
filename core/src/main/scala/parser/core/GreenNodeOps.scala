package parser.core

/**
 * Helper functions for building GreenNode trees.
 *
 * Provides convenient constructors for common token types and tree structures.
 * These helpers simplify the construction of lossless syntax trees.
 *
 * Example:
 * {{{
 * import GreenNodeOps.*
 *
 * val idNode = identifier("foo", span)
 * val numNode = number("42", span)
 * val expr = expression(idNode, numNode)
 * }}}
 */
object GreenNodeOps {
  import GreenNode.*

  /**
   * Creates an identifier token.
   *
   * @param text The identifier text
   * @param span The source location span
   * @return An identifier token node
   */
  def identifier(text: String, span: Span): GreenNode =
    token(TokenKind.Identifier, text, span)

  /**
   * Creates a number token.
   *
   * @param text The numeric literal text
   * @param span The source location span
   * @return A number token node
   */
  def number(text: String, span: Span): GreenNode =
    token(TokenKind.Number, text, span)

  /**
   * Creates a keyword token.
   *
   * @param text The keyword text
   * @param span The source location span
   * @return A keyword token node
   */
  def keyword(text: String, span: Span): GreenNode =
    token(TokenKind.Keyword, text, span)

  /**
   * Creates an expression tree.
   *
   * @param children The child nodes of the expression
   * @return An expression tree node
   */
  def expression(children: GreenNode*): GreenNode =
    tree(SyntaxKind.Expression, children*)

  /**
   * Creates a statement tree.
   *
   * @param children The child nodes of the statement
   * @return A statement tree node
   */
  def statement(children: GreenNode*): GreenNode =
    tree(SyntaxKind.Statement, children*)
}
