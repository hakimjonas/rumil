package parsers.xml

import munit.FunSuite
import net.ghoula.sarati.ast.xml.*

import parser.core.*
import parser.syntax.*

class XmlDtdTests extends FunSuite {
  import XmlNode.*

  private def root(result: Result[ParseError, XmlDocument]): XmlNode =
    result.toOption.get.root

  test("DOCTYPE internal subset expands entity in content") {
    val xml = "<!DOCTYPE r [<!ENTITY w \"hello\">]><r><m>&w;</m></r>"
    val result = parseXml(xml)
    assert(result.isSuccess)
    root(result) match {
      case Element(name, _, List(Element(mName, _, List(Text("hello"))))) =>
        assertEquals(name.localName, "r")
        assertEquals(mName.localName, "m")
      case other => fail(s"Expected <r><m>hello</m></r>, got $other")
    }
  }

  test("entity expands in attribute value") {
    val xml = "<!DOCTYPE r [<!ENTITY a \"v1\">]><r x=\"&a;\"/>"
    val result = parseXml(xml)
    assert(result.isSuccess)
    root(result) match {
      case Element(_, attributes, _) => assertEquals(attributes.head.value, "v1")
      case other => fail(s"Expected element, got $other")
    }
  }

  test("entity referenced in entity replacement text (nested)") {
    val xml = "<!DOCTYPE r [<!ENTITY a \"&b;\"><!ENTITY b \"hello\">]><r>&a;</r>"
    val result = parseXml(xml)
    assert(result.isSuccess)
    root(result) match {
      case Element(_, _, Text("hello") :: _) => ()
      case other => fail(s"Expected expanded text 'hello', got $other")
    }
  }

  test("circular entity reference rejected") {
    val xml = "<!DOCTYPE r [<!ENTITY a \"&b;\"><!ENTITY b \"&a;\">]><r/>"
    val result = parseXml(xml)
    assert(result.isFailure, s"expected failure, got $result")
    val messages = result.errors.collect { case ParseError.Custom(m, _) => m }
    assert(
      messages.exists(_.contains("circular entity reference")),
      s"unexpected errors: ${result.errors}"
    )
  }

  test("replacement text containing < rejected in attribute") {
    val xml = "<!DOCTYPE r [<!ENTITY e \"<tag>\">]><r x=\"&e;\"/>"
    val result = parseXml(xml)
    assert(result.isFailure, s"expected failure, got $result")
  }

  test("indirect < in replacement text rejected in attribute") {
    val xml = "<!DOCTYPE r [<!ENTITY a \"&b;\"><!ENTITY b \"<tag>\">]><r x=\"&a;\"/>"
    val result = parseXml(xml)
    assert(result.isFailure, s"expected failure, got $result")
  }

  test("indirect external entity ref rejected in attribute") {
    val xml = "<!DOCTYPE r [<!ENTITY a \"&b;\"><!ENTITY b SYSTEM \"e.xml\">]><r x=\"&a;\"/>"
    val result = parseXml(xml)
    assert(result.isFailure, s"expected failure, got $result")
  }

  test("character reference in entity value is expanded") {
    val xml = "<!DOCTYPE r [<!ENTITY e \"&#65;BC\">]><r>&e;</r>"
    val result = parseXml(xml)
    assert(result.isSuccess)
    root(result) match {
      case Element(_, _, Text("ABC") :: _) => ()
      case other => fail(s"Expected 'ABC', got $other")
    }
  }

  test("undeclared entity rejected with internal DTD") {
    val xml = "<!DOCTYPE r [<!ENTITY e \"value\">]><r>&f;</r>"
    val result = parseXml(xml)
    assert(result.isFailure, s"expected failure, got $result")
  }

  test("undeclared entity rejected without DTD") {
    val result = parseXml("<?xml version=\"1.0\"?><r>&foo;</r>")
    assert(result.isFailure, s"expected failure, got $result")
  }

  test("DOCTYPE without internal subset") {
    val result = parseXml("<!DOCTYPE r><r/>")
    assert(result.isSuccess)
  }

  test("DOCTYPE SYSTEM external ID parsed, not resolved") {
    val result = parseXml("<!DOCTYPE r SYSTEM \"r.dtd\"><r/>")
    assert(result.isSuccess)
  }

  test("DOCTYPE PUBLIC external ID parsed, not resolved") {
    val result = parseXml("<!DOCTYPE r PUBLIC \"-//X//EN\" \"r.dtd\"><r/>")
    assert(result.isSuccess)
  }

  test("undeclared entity allowed with external subset") {
    val xml = "<!DOCTYPE r SYSTEM \"r.dtd\"><r>&maybe;</r>"
    val result = parseXml(xml)
    assert(result.isSuccess)
    root(result) match {
      case Element(_, _, Text(content) :: _) => assertEquals(content, "&maybe;")
      case other => fail(s"Expected literal entity reference text, got $other")
    }
  }

  test("unparsed entity reference rejected") {
    val xml = "<!DOCTYPE r [<!NOTATION n SYSTEM \"n\"><!ENTITY e SYSTEM \"e\" NDATA n>]><r>&e;</r>"
    val result = parseXml(xml)
    assert(result.isFailure, s"expected failure, got $result")
  }

  test("external entity in attribute rejected") {
    val xml = "<!DOCTYPE r [<!ENTITY e SYSTEM \"e.xml\">]><r a=\"&e;\"/>"
    val result = parseXml(xml)
    assert(result.isFailure, s"expected failure, got $result")
  }

  test("ELEMENT/ATTLIST/NOTATION declarations are skipped") {
    val xml =
      "<!DOCTYPE r [\n" +
        "<!ELEMENT r (#PCDATA)>\n" +
        "<!ATTLIST r id CDATA #IMPLIED>\n" +
        "<!NOTATION n SYSTEM \"n\">\n" +
        "]>\n" +
        "<r id=\"1\">hello</r>"
    val result = parseXml(xml)
    assert(result.isSuccess)
  }

  test("ELEMENT content model with choice and quantifier is skipped") {
    val xml = "<!DOCTYPE r [<!ELEMENT r (a|b|c)*>]><r><a/><b/></r>"
    val result = parseXml(xml)
    assert(result.isSuccess)
  }

  test("ATTLIST enumeration and tokenized type are skipped") {
    val xml = "<!DOCTYPE r [<!ATTLIST r id IDREF #IMPLIED kind (x|y) #FIXED \"x\">]><r/>"
    val result = parseXml(xml)
    assert(result.isSuccess)
  }

  test("NOTATION with PUBLIC identifier is skipped") {
    val xml = "<!DOCTYPE r [<!NOTATION n PUBLIC \"-//X//EN\">]><r/>"
    val result = parseXml(xml)
    assert(result.isSuccess)
  }

  test("comment between DOCTYPE and root element is allowed") {
    val xml = "<!DOCTYPE r><!-- note --><r/>"
    val result = parseXml(xml)
    assert(result.isSuccess)
  }

  test("parameter entity declaration and reference relax strict checking") {
    val xml = "<!DOCTYPE r [<!ENTITY % pe \"v\"> %pe;]><r>&maybe;</r>"
    val result = parseXml(xml)
    assert(result.isSuccess)
    root(result) match {
      case Element(_, _, Text(content) :: _) => assertEquals(content, "&maybe;")
      case other => fail(s"Expected literal entity reference text, got $other")
    }
  }

  test("strictXmlConfig does not resolve DTD") {
    val result = parseXml("<!DOCTYPE r><r/>", strictXmlConfig)
    assert(result.isFailure, s"expected failure, got $result")
  }

  test("parity: strongbow DTD divergence document expands under xpath config") {
    val xml = "<!DOCTYPE r [<!ENTITY w \"hello\">]><r><m>&w;</m></r>"
    val result = parseXml(xml, xpathXmlConfig)
    assert(result.isSuccess)
    root(result) match {
      case Element(_, _, List(Element(_, _, List(Text("hello"))))) => ()
      case other => fail(s"Expected <r><m>hello</m></r>, got $other")
    }
  }
}
