package parsers.json

import parser.core.*

/** JSON's own [[Language]] instance (the finding from `ecosystem-findings-from-melian.md`,
  * "Adjacent finding: Rumil").
  *
  * The lossless machinery in `rumil-core` ([[GreenNodeOf]], [[RedTree]], [[IncrementalParser]]) is
  * grammar-generic, but `rumil-parsers`' JSON parser exposed only the semantic entry points
  * (`parseJson` / `parseJsonAs`, producing Sarati `JsonValue`s). This instance gives JSON the two
  * missing pieces:
  *
  *   1. a [[Language]] — its own token and syntax alphabets, so green trees, red trees, and
  *      reparseable bundles for JSON cannot mix with another grammar's,
  *   2. a batch lossless parse ([[parseJsonLossless]]) whose green tree preserves the source
  *      byte-for-byte: `GreenNodeOf.toSource(tree) == input`, whitespace included.
  *
  * See [[JsonLossless]] for the parser and the [[IncrementalParser.ReparseableParsers]] bundle.
  */
object JsonLanguage extends Language {

  /** JSON token classifications. */
  enum JsonTokenKind derives CanEqual {
    case LBrace
    case RBrace
    case LBracket
    case RBracket
    case Colon
    case Comma
    case Null
    case True
    case False
    case Number
    case Str
    case Whitespace
    case Error
  }

  /** JSON syntax-node classifications. */
  enum JsonSyntaxKind derives CanEqual {
    case Document
    case Object
    case Member
    case Array
  }

  type Token = JsonTokenKind
  type Syntax = JsonSyntaxKind

  /** Named accessors for the token alphabet. */
  object Tokens {
    val LBrace: Token = JsonTokenKind.LBrace
    val RBrace: Token = JsonTokenKind.RBrace
    val LBracket: Token = JsonTokenKind.LBracket
    val RBracket: Token = JsonTokenKind.RBracket
    val Colon: Token = JsonTokenKind.Colon
    val Comma: Token = JsonTokenKind.Comma
    val Null: Token = JsonTokenKind.Null
    val True: Token = JsonTokenKind.True
    val False: Token = JsonTokenKind.False
    val Number: Token = JsonTokenKind.Number
    val Str: Token = JsonTokenKind.Str
    val Whitespace: Token = JsonTokenKind.Whitespace
    val Error: Token = JsonTokenKind.Error
  }

  /** Named accessors for the syntax alphabet. */
  object Syntaxes {
    val Document: Syntax = JsonSyntaxKind.Document
    val Object: Syntax = JsonSyntaxKind.Object
    val Member: Syntax = JsonSyntaxKind.Member
    val Array: Syntax = JsonSyntaxKind.Array
  }

  /** Validates a red tree with JSON's error-token predicate. */
  extension (tree: Red) {
    def validateJson: List[ParseError] = tree.validateWith(_ == Tokens.Error)
  }
}
