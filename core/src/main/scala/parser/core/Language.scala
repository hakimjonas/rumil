package parser.core

/** A language is a bundle of two kind alphabets: a set of token classifications and a set of
  * syntax-tree-node classifications. Each grammar that coexists in the JVM with other grammars can
  * declare its own `Language` instance so the type system prevents accidental cross-grammar reuse
  * of tokens, missing-markers, or reparseable-subtree keys.
  *
  * A `Language` exposes its alphabets as abstract type members so concrete instances can back them
  * with opaque primitive aliases (see [[DefaultLanguage]]).
  */
trait Language {
  type Token
  type Syntax

  /** Canonical green-tree type for this language. Grammar authors reference `Lang.Green` rather
    * than spelling `GreenNodeOf[Lang.Token, Lang.Syntax]` at every call site.
    */
  type Green = GreenNodeOf[Token, Syntax]

  /** Canonical red-tree view for this language. */
  type Red = RedTree[Token, Syntax]
}

/** The language backing the current fixed [[TokenKind]] / [[SyntaxKind]] enums.
  *
  * Every case carried by those enums has a corresponding named value here. Code that has been
  * parameterized over a [[Language]] uses `DefaultLanguage.Tokens.Identifier` rather than
  * `TokenKind.Identifier` directly.
  *
  * Implementation note — transparent-alias scaffolding. `Token` and `Syntax` are transparent
  * aliases (`type Token = TokenKind`, `type Syntax = SyntaxKind`), not opaques. This is deliberate
  * migration scaffolding carried over from Session C phase 4: existing code that still writes
  * `TokenKind.Identifier` directly compiles because `TokenKind.Identifier` is literally a
  * `DefaultLanguage.Token`. Cross-grammar safety is intact: any foreign [[Language]] uses its own
  * opaque [[Language.Token]] whose representation is unrelated to `TokenKind`, so passing a foreign
  * language's green/token into a `DefaultLanguage`-parameterized signature is a compile error. A
  * future session can opacify by (a) flipping both to `opaque type … = Int`, (b) mechanically
  * rewriting every remaining `TokenKind.X` → `DefaultLanguage.Tokens.X` and `SyntaxKind.X` →
  * `DefaultLanguage.Syntaxes.X` across the codebase, and (c) deleting the legacy [[TokenKind]] /
  * [[SyntaxKind]] enums. The refactor is mechanical; it wasn't performed during Session C because
  * the blast radius was ~80 test call sites for no additional cross-grammar guarantee.
  */
object DefaultLanguage extends Language {

  /** The default language's token alphabet is the existing [[TokenKind]] enum. See the object
    * docstring for why this is a transparent alias rather than an opaque type.
    */
  type Token = TokenKind

  /** The default language's syntax alphabet is the existing [[SyntaxKind]] enum. */
  type Syntax = SyntaxKind

  /** Named accessors for the default language's token kinds, each mapping to its underlying enum
    * case. Code that has been parameterized over a [[Language]] uses these rather than
    * `TokenKind.Identifier` directly.
    */
  object Tokens {
    val Identifier: Token = TokenKind.Identifier
    val Number: Token = TokenKind.Number
    val String: Token = TokenKind.String
    val Keyword: Token = TokenKind.Keyword
    val Operator: Token = TokenKind.Operator
    val LeftParen: Token = TokenKind.LeftParen
    val RightParen: Token = TokenKind.RightParen
    val LeftBrace: Token = TokenKind.LeftBrace
    val RightBrace: Token = TokenKind.RightBrace
    val Comma: Token = TokenKind.Comma
    val Semicolon: Token = TokenKind.Semicolon
    val Colon: Token = TokenKind.Colon
    val Arrow: Token = TokenKind.Arrow
    val Whitespace: Token = TokenKind.Whitespace
    val Comment: Token = TokenKind.Comment
    val EOF: Token = TokenKind.EOF
    val Error: Token = TokenKind.Error
  }

  /** Named accessors for the default language's syntax kinds. */
  object Syntaxes {
    val SourceFile: Syntax = SyntaxKind.SourceFile
    val Function: Syntax = SyntaxKind.Function
    val TypeDef: Syntax = SyntaxKind.TypeDef
    val Expression: Syntax = SyntaxKind.Expression
    val Block: Syntax = SyntaxKind.Block
    val Statement: Syntax = SyntaxKind.Statement
    val Pattern: Syntax = SyntaxKind.Pattern
    val Literal: Syntax = SyntaxKind.Literal
  }

  // CanEqual[Token, Token] and CanEqual[Syntax, Syntax] come from the underlying
  // TokenKind/SyntaxKind enums' own `derives CanEqual` clauses.

  /** Default-language specialization of [[RedTree.validateWith]] that supplies the [[Tokens.Error]]
    * predicate automatically. This extension lives inside the companion so `import parser.core.*`
    * brings it into implicit scope alongside the other core primitives.
    */
  extension (tree: Red) {
    def validate: List[ParseError] =
      tree.validateWith(_ == Tokens.Error)
  }
}
