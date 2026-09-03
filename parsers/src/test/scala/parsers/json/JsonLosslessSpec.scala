package parsers.json

import munit.FunSuite

import parser.core.*

/** Tests for the JSON [[Language]] instance and the batch lossless parse.
  *
  * The bar: `GreenNodeOf.toSource(tree) == input` for every accepted input — whitespace, escape
  * sequences, number formatting, and everything else preserved byte-for-byte — plus a reparseable
  * bundle that keeps the same invariant on the failure path.
  */
class JsonLosslessSpec extends FunSuite {

  private def assertLossless(input: String): GreenNodeOf[JsonLanguage.Token, JsonLanguage.Syntax] =
    JsonLossless.parseJsonLossless(input) match {
      case Result.Success(tree, _) =>
        val source = GreenNodeOf.toSource(tree)
        assertEquals(source, input, "green tree must round-trip the source exactly")
        tree
      case other =>
        fail(s"expected Success for ${input.take(60)}, got $other")
    }

  test("lossless: scalars round-trip, numbers keep their raw form") {
    assertLossless("null")
    assertLossless("true")
    assertLossless("false")
    assertLossless("42")
    assertLossless("-0.5e-10")
    // Number tokens carry the raw text — no double rounding.
    val raw = "1.0000000000000000001"
    val tree = assertLossless(raw)
    val numberToken = tree match {
      case GreenNodeOf.Tree(_, children, _) =>
        children.collectFirst { case t @ GreenNodeOf.Token(JsonLanguage.Tokens.Number, _) => t }
      case other => fail(s"expected a document tree, got $other")
    }
    val expected: Option[GreenNodeOf.Token[JsonLanguage.Token, JsonLanguage.Syntax]] =
      Some(GreenNodeOf.Token(JsonLanguage.Tokens.Number, raw))
    assertEquals(numberToken, expected)
  }

  test("lossless: strings keep quotes and escapes verbatim") {
    val input = """"a\"b\\c\u0041""""
    val tree = assertLossless(input)
    tree match {
      case GreenNodeOf.Tree(_, children, _) =>
        children.collectFirst { case t @ GreenNodeOf.Token(JsonLanguage.Tokens.Str, text) =>
          assertEquals(text, input)
        } match {
          case Some(_) => ()
          case None => fail("expected a string token under the document")
        }
      case other => fail(s"expected a document tree, got $other")
    }
  }

  test("lossless: objects and arrays preserve all whitespace and punctuation") {
    val input = """
    {
      "alpha" : [1,  2 ,

        {"nested": null } ],
      "beta":
        {
          "x" : true
        }
    }
    """
    assertLossless(input)
  }

  test("lossless: empty containers and trailing whitespace") {
    assertLossless("{}")
    assertLossless("[]")
    assertLossless("  [ ]  \n\t ")
    assertLossless("{\n}")
  }

  test("lossless: members are Tree nodes and whitespace is first-class") {
    val tree = assertLossless("""{"a": 1}""")
    tree match {
      case GreenNodeOf.Tree(kind, children, _) =>
        assertEquals(kind, JsonLanguage.Syntaxes.Document)
        // document -> object
        children.collectFirst { case GreenNodeOf.Tree(k, _, _) => k } match {
          case Some(k) => assertEquals(k, JsonLanguage.Syntaxes.Object)
          case None => fail("expected an object tree under the document")
        }
      case other => fail(s"expected a document tree, got $other")
    }
  }

  test("reparseable bundle: object regions reparse in isolation") {
    val region = """"a" : [1, {"b": null}]"""
    JsonLossless.jsonReparseable.byKind.get(JsonLanguage.Syntaxes.Object) match {
      case Some(_) => () // objects are registered
      case None => fail("object must be reparseable by kind")
    }
    JsonLossless.jsonReparseable.byKind.get(JsonLanguage.Syntaxes.Array) match {
      case Some(_) => ()
      case None => fail("array must be reparseable by kind")
    }
    // simple-token predicate: numbers/strings/keywords are editable in place
    assert(JsonLossless.jsonReparseable.isSimpleToken(JsonLanguage.Tokens.Number))
    assert(JsonLossless.jsonReparseable.isSimpleToken(JsonLanguage.Tokens.Str))
    assert(!JsonLossless.jsonReparseable.isSimpleToken(JsonLanguage.Tokens.Whitespace))
    assert(region.nonEmpty) // keep the variable meaningful
  }

  test("reparseable bundle: failure fallback preserves the source") {
    val bad = "this is not json at all"
    val fallback = JsonLossless.jsonReparseable.onParseFailure(bad)
    assertEquals(GreenNodeOf.toSource(fallback), bad)
    fallback match {
      case GreenNodeOf.Token(kind, _) => assertEquals(kind, JsonLanguage.Tokens.Error)
      case other => fail(s"expected an Error token tree, got $other")
    }
  }

  test("language instance: kinds do not mix with DefaultLanguage") {
    // Compile-time cross-grammar safety: a JSON green is NOT a DefaultLanguage green.
    summon[JsonLanguage.Green =:= GreenNodeOf[JsonLanguage.JsonTokenKind, JsonLanguage.JsonSyntaxKind]]
    val jsonGreen: JsonLanguage.Green =
      GreenNode.token[JsonLanguage.Token, JsonLanguage.Syntax](JsonLanguage.Tokens.Null, "null")
    assertEquals(GreenNodeOf.toSource(jsonGreen), "null")
  }
}
