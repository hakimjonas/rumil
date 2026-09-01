package parser.core

/** Helper constructors for [[DefaultLanguage]] green-tree nodes.
  *
  * Convenience shortcuts for the most common token and tree kinds of the default language.
  * Positions are not an argument — greens are position-independent (see [[GreenNode]]).
  *
  * Example:
  * {{{
  * import GreenNodeOps.*
  *
  * val idNode = identifier("foo")
  * val numNode = number("42")
  * val expr = expression(idNode, numNode)
  * }}}
  */
object GreenNodeOps {
  import GreenNode.*

  private type Tok = DefaultLanguage.Token
  private type Syn = DefaultLanguage.Syntax
  private type G = DefaultLanguage.Green

  /** Creates an identifier token. */
  def identifier(text: String): G =
    token[Tok, Syn](DefaultLanguage.Tokens.Identifier, text)

  /** Creates a number token. */
  def number(text: String): G =
    token[Tok, Syn](DefaultLanguage.Tokens.Number, text)

  /** Creates a keyword token. */
  def keyword(text: String): G =
    token[Tok, Syn](DefaultLanguage.Tokens.Keyword, text)

  /** Creates an expression tree. */
  def expression(children: G*): G =
    tree[Tok, Syn](DefaultLanguage.Syntaxes.Expression, children*)

  /** Creates a statement tree. */
  def statement(children: G*): G =
    tree[Tok, Syn](DefaultLanguage.Syntaxes.Statement, children*)
}
