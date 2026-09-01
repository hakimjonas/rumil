package parser

import munit.FunSuite

import parser.core.*
import parser.syntax.*

/** Probes for the [[Language]] / [[DefaultLanguage]] split introduced in phase 1.
  *
  * The bar this session has to clear is: mixing a foreign language's kinds into code expecting
  * [[DefaultLanguage]] is a compile error. Phase 1 proves that at the kind-value level; subsequent
  * phases extend the guarantee to greens, parsers, and reparseable bundles.
  */
class LanguageTests extends FunSuite {

  /** A second [[Language]] existing only in test scope; used to force cross-language confusion
    * rejection.
    */
  private object TestLang extends Language {
    opaque type Token = Int
    opaque type Syntax = Int

    object Tokens {
      val Foo: Token = 0
      val Bar: Token = 1
    }
    object Syntaxes {
      val Alpha: Syntax = 0
      val Beta: Syntax = 1
    }

    given CanEqual[Token, Token] = CanEqual.derived
    given CanEqual[Syntax, Syntax] = CanEqual.derived
  }

  /** TestLang sample green node for the negative assignment test. */
  private val testLangGreen: GreenNodeOf[TestLang.Token, TestLang.Syntax] =
    GreenNode.Token[TestLang.Token, TestLang.Syntax](TestLang.Tokens.Foo, "foo")

  private val testLangParser: Parser[ParseError, GreenNodeOf[TestLang.Token, TestLang.Syntax]] =
    Parser.Succeed(testLangGreen)

  private val defaultFull: Parser[ParseError, DefaultLanguage.Green] =
    Parser.Succeed(
      GreenNode.Token[DefaultLanguage.Token, DefaultLanguage.Syntax](
        DefaultLanguage.Tokens.Identifier,
        "x"
      )
    )

  private val defaultGreenVal: DefaultLanguage.Green =
    GreenNode.Token[DefaultLanguage.Token, DefaultLanguage.Syntax](
      DefaultLanguage.Tokens.Identifier,
      "x"
    )

  private val testLangBundle: IncrementalParser.ReparseableParsers[TestLang.Token, TestLang.Syntax, ParseError] =
    IncrementalParser.ReparseableParsers[TestLang.Token, TestLang.Syntax, ParseError](
      full = testLangParser,
      byKind = Map.empty,
      isSimpleToken = (_: TestLang.Token) => false,
      onParseFailure = (_: String) => testLangGreen
    )

  test("within-language token equality works") {
    assert(DefaultLanguage.Tokens.Identifier == DefaultLanguage.Tokens.Identifier)
    assert(DefaultLanguage.Tokens.Identifier != DefaultLanguage.Tokens.Number)
  }

  test("within-language syntax equality works") {
    assert(DefaultLanguage.Syntaxes.SourceFile == DefaultLanguage.Syntaxes.SourceFile)
    assert(DefaultLanguage.Syntaxes.SourceFile != DefaultLanguage.Syntaxes.Statement)
  }

  test("within-TestLang token equality works") {
    assert(TestLang.Tokens.Foo == TestLang.Tokens.Foo)
    assert(TestLang.Tokens.Foo != TestLang.Tokens.Bar)
  }

  test("cross-language token equality is a compile error under strictEquality") {
    val errs = compileErrors(
      "DefaultLanguage.Tokens.Identifier == LanguageTests.this.TestLang.Tokens.Foo"
    )
    assert(
      errs.nonEmpty,
      s"Expected a CanEqual-related compile error, got: $errs"
    )
  }

  test("cross-language syntax equality is a compile error under strictEquality") {
    val errs = compileErrors(
      "DefaultLanguage.Syntaxes.SourceFile == LanguageTests.this.TestLang.Syntaxes.Alpha"
    )
    assert(errs.nonEmpty, s"Expected compile error, got: $errs")
  }

  test("cross-language assignment is a compile error (type-level distinction)") {
    val errs = compileErrors(
      "val x: DefaultLanguage.Token = LanguageTests.this.TestLang.Tokens.Foo"
    )
    assert(errs.nonEmpty, s"Expected type-mismatch compile error, got: $errs")
  }

  test("phase 2: GreenNode[TestLang] cannot substitute for GreenNode[DefaultLanguage]") {
    val errs = compileErrors(
      "val defaultGreen: DefaultLanguage.Green = LanguageTests.this.testLangGreen"
    )
    assert(errs.nonEmpty, s"Expected type-mismatch at the green-tree level, got: $errs")
  }

  test("phase 2: Missing carries the declaring language's Token type") {
    val missing: DefaultLanguage.Green =
      GreenNode.Missing[DefaultLanguage.Token, DefaultLanguage.Syntax](
        DefaultLanguage.Tokens.RightParen
      )
    val errs = compileErrors(
      "val wrong: DefaultLanguage.Green = GreenNode.Missing[LanguageTests.this.TestLang.Token, LanguageTests.this.TestLang.Syntax](LanguageTests.this.TestLang.Tokens.Foo)"
    )
    assert(errs.nonEmpty, s"Expected type mismatch for TestLang Missing assigned to Default green, got: $errs")
    // The well-typed construction is a valid DefaultLanguage.Green.
    assert(GreenNode.textLength(missing) == 0)
  }

  test("phase 2: red-tree missingKind returns Option[Language#Token]") {
    val missing: DefaultLanguage.Green =
      GreenNode.Missing[DefaultLanguage.Token, DefaultLanguage.Syntax](
        DefaultLanguage.Tokens.RightParen
      )
    val red = RedTree(missing)
    val kind: Option[DefaultLanguage.Token] = red.missingKind
    assertEquals(kind, Some(DefaultLanguage.Tokens.RightParen))
  }

  test(
    "phase 3: Parser producing TestLang.Green cannot be assigned where DefaultLanguage.Green is expected"
  ) {
    val errs = compileErrors(
      "val p: parser.core.Parser[parser.core.ParseError, DefaultLanguage.Green] = LanguageTests.this.testLangParser"
    )
    assert(errs.nonEmpty, s"Expected A-type mismatch between languages' green parsers, got: $errs")
  }

  test(
    "phase 3: ReparseableParsers with a foreign-language byKind key is a compile error"
  ) {
    val errs = compileErrors(
      "parser.core.IncrementalParser.ReparseableParsers[DefaultLanguage.Token, DefaultLanguage.Syntax, parser.core.ParseError](full = LanguageTests.this.defaultFull, byKind = Map(LanguageTests.this.TestLang.Syntaxes.Alpha -> LanguageTests.this.defaultFull), isSimpleToken = (_: DefaultLanguage.Token) => false, onParseFailure = (_: String) => LanguageTests.this.defaultGreenVal)"
    )
    assert(errs.nonEmpty, s"Expected foreign-language byKind key to be rejected, got: $errs")
  }

  test(
    "phase 3: incrementalParse rejects a ReparseableParsers bundle of the wrong language"
  ) {
    val errs = compileErrors(
      "parser.core.IncrementalParser.incrementalParse(LanguageTests.this.defaultGreenVal, \"\", parser.core.TextEdit.insert(0, \"x\"), LanguageTests.this.testLangBundle)"
    )
    assert(errs.nonEmpty, s"Expected incrementalParse to reject cross-language bundle, got: $errs")
  }

  test(
    "phase 4: expectToken with a TestLang kind and a DefaultLanguage inner parser is a compile error"
  ) {
    // expectToken[Tok, Syn](kind: Tok, inner: Parser[ParseError, GreenNodeOf[Tok, Syn]])
    // type-unifies Tok across both arguments, so passing a TestLang.Token with a
    // DefaultLanguage.Green inner parser fails at the type level.
    val errs = compileErrors(
      "parser.core.expectToken(LanguageTests.this.TestLang.Tokens.Foo, LanguageTests.this.defaultFull)"
    )
    assert(errs.nonEmpty, s"Expected expectToken to reject mixed-language arguments, got: $errs")
  }

  test(
    "phase 4: syncUntil with a TestLang inner parser and a DefaultLanguage error token kind is a compile error"
  ) {
    val errs = compileErrors(
      "parser.core.syncUntil(LanguageTests.this.testLangParser, Set('\\n'), DefaultLanguage.Tokens.Error)"
    )
    assert(errs.nonEmpty, s"Expected syncUntil to reject mixed-language arguments, got: $errs")
  }

  test(
    "phase 4: a minimal second Language reaches a working ReparseableParsers without friction"
  ) {
    // Audience test: what does a new grammar author actually have to write? Everything below is
    // what a grammar author types to stand up their own Language — an opaque Int-backed Token/
    // Syntax, named accessors, and a ReparseableParsers bundle.
    object MiniLang extends Language {
      opaque type Token = Int
      opaque type Syntax = Int
      object Tokens {
        val Word: Token = 0
        val Space: Token = 1
        val Error: Token = 2
      }
      object Syntaxes {
        val Document: Syntax = 0
      }
      given CanEqual[Token, Token] = CanEqual.derived
      given CanEqual[Syntax, Syntax] = CanEqual.derived
    }

    // Parsers use combinators unchanged; the only thing that threads through is the Lang.Green
    // result type.
    val word: Parser[ParseError, MiniLang.Green] =
      letter.many1.map(cs => GreenNode.Token[MiniLang.Token, MiniLang.Syntax](MiniLang.Tokens.Word, cs.mkString))

    val document: Parser[ParseError, MiniLang.Green] =
      word.many.map(ws => GreenNode.treeOfVec[MiniLang.Token, MiniLang.Syntax](MiniLang.Syntaxes.Document, ws.toVector))

    val bundle: IncrementalParser.ReparseableParsers[MiniLang.Token, MiniLang.Syntax, ParseError] =
      IncrementalParser.ReparseableParsers[MiniLang.Token, MiniLang.Syntax, ParseError](
        full = document,
        byKind = Map.empty,
        isSimpleToken = (k: MiniLang.Token) => k == MiniLang.Tokens.Word,
        onParseFailure = (src: String) => GreenNode.Token(MiniLang.Tokens.Error, src)
      )

    // It works: parses, is kind-safe, is compatible with incrementalParse.
    val initial = parser.runtime.run(document, "hello")
    assert(initial.isSuccess, s"MiniLang document should parse 'hello', got: $initial")
    val edit = TextEdit.replace(0, 5, "world")
    val tree = initial.toOption.get
    val result = IncrementalParser.incrementalParse(tree, "hello", edit, bundle)
    assertEquals(GreenNode.toSource(result.tree), "world")
  }

  test(
    "phase 4: a MiniLang.Green cannot be passed to DefaultLanguage's incrementalParse"
  ) {
    // This is the cross-language-anti-test: mixing MiniLang with DefaultLanguage in the
    // incremental-parse call site is a compile error, because the Tok/Syn parameters on
    // `incrementalParse` unify across the tree and the bundle.
    val errs = compileErrors(
      "parser.core.IncrementalParser.incrementalParse(LanguageTests.this.defaultGreenVal, \"\", parser.core.TextEdit.insert(0, \"x\"), LanguageTests.this.testLangBundle)"
    )
    assert(errs.nonEmpty, s"Expected cross-language incremental parse to fail, got: $errs")
  }
}
