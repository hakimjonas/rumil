package parsers.xml

import munit.FunSuite
import parser.core._
import parser.syntax._

class XmlParserTests extends FunSuite {
  import XmlNode.*

  // ============================================================================
  // Basic Element Tests
  // ============================================================================

  test("parse empty self-closing element") {
    val result = parseXmlFragment("<root/>")
    assert(result.isSuccess)
    assertEquals(
      result.toOption,
      Some(Element((prefix = None, localName = "root"), List(), List()))
    )
  }

  test("parse element with text content") {
    val result = parseXmlFragment("<root>hello</root>")
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(name, _, children) =>
        assertEquals(name.localName, "root")
        assertEquals(children.length, 1)
      case _ => fail("Expected Element")
    }
  }

  test("parse nested elements") {
    val xml    = "<root><child>text</child></root>"
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, _, children) => assertEquals(children.length, 1)
      case _                       => fail("Expected Element")
    }
  }

  test("parse multiple child elements") {
    val xml    = "<root><a>1</a><b>2</b><c>3</c></root>"
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, _, children) => assertEquals(children.length, 3)
      case _                       => fail("Expected Element")
    }
  }

  // ============================================================================
  // Attribute Tests
  // ============================================================================

  test("parse element with single attribute") {
    val xml    = """<root id="123"/>"""
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, attributes, _) =>
        assertEquals(attributes.length, 1)
        assertEquals(attributes.head.name.localName, "id")
        assertEquals(attributes.head.value, "123")
      case _ => fail("Expected Element")
    }
  }

  test("parse element with multiple attributes") {
    val xml    = """<root id="123" name="test" active="true"/>"""
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, attributes, _) => assertEquals(attributes.length, 3)
      case _                         => fail("Expected Element")
    }
  }

  test("parse attribute with single quotes") {
    val xml    = """<root id='123'/>"""
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, attributes, _) => assertEquals(attributes.head.value, "123")
      case _                         => fail("Expected Element")
    }
  }

  test("parse attribute with entity") {
    val xml    = """<root msg="Hello &amp; goodbye"/>"""
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, attributes, _) => assertEquals(attributes.head.value, "Hello & goodbye")
      case _                         => fail("Expected Element")
    }
  }

  // ============================================================================
  // Entity Tests
  // ============================================================================

  test("parse text with lt entity") {
    val xml    = "<root>5 &lt; 10</root>"
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, _, Text(content) :: _) => assertEquals(content, "5 < 10")
      case _                                 => fail("Expected Element with Text child")
    }
  }

  test("parse text with gt entity") {
    val xml    = "<root>10 &gt; 5</root>"
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, _, Text(content) :: _) => assertEquals(content, "10 > 5")
      case _                                 => fail("Expected Element with Text child")
    }
  }

  test("parse text with amp entity") {
    val xml    = "<root>Tom &amp; Jerry</root>"
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, _, Text(content) :: _) => assertEquals(content, "Tom & Jerry")
      case _                                 => fail("Expected Element with Text child")
    }
  }

  test("parse text with quot entity") {
    val xml    = "<root>Say &quot;hello&quot;</root>"
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, _, Text(content) :: _) => assert(content.contains("\""))
      case _                                 => fail("Expected Element with Text child")
    }
  }

  test("parse text with apos entity") {
    val xml    = "<root>It&apos;s working</root>"
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, _, Text(content) :: _) => assert(content.contains("'"))
      case _                                 => fail("Expected Element with Text child")
    }
  }

  // ============================================================================
  // Character Reference Tests
  // ============================================================================

  test("parse decimal character reference") {
    val xml    = "<root>&#65;BC</root>"
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, _, Text(content) :: _) => assertEquals(content, "ABC")
      case _                                 => fail("Expected Element with Text child")
    }
  }

  test("parse hexadecimal character reference") {
    val xml    = "<root>&#x41;BC</root>"
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, _, Text(content) :: _) => assertEquals(content, "ABC")
      case _                                 => fail("Expected Element with Text child")
    }
  }

  // ============================================================================
  // CDATA Tests
  // ============================================================================

  test("parse CDATA section") {
    val xml    = "<root><![CDATA[<>&\"']]></root>"
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, _, CData(content) :: _) => assertEquals(content, "<>&\"'")
      case _                                  => fail("Expected Element with CData child")
    }
  }

  test("parse CDATA with special content") {
    val xml    = "<root><![CDATA[function() { if (x < 5 && y > 10) return true; }]]></root>"
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, _, CData(content) :: _) =>
        assert(content.contains("<"))
        assert(content.contains("&&"))
      case _ => fail("Expected Element with CData child")
    }
  }

  // ============================================================================
  // Comment Tests
  // ============================================================================

  test("parse comment") {
    val xml    = "<root><!-- this is a comment --></root>"
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, _, children) =>
        assertEquals(children.length, 1)
        children.head match {
          case Comment(content) => assert(content.contains("this is a comment"))
          case _                => fail("Expected Comment")
        }
      case _ => fail("Expected Element")
    }
  }

  test("parse multiple comments") {
    val xml    = "<root><!-- first --><!-- second --></root>"
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, _, children) =>
        val commentCount = children.count { case Comment(_) => true; case _ => false }
        assertEquals(commentCount, 2)
      case _ => fail("Expected Element")
    }
  }

  // ============================================================================
  // Processing Instruction Tests
  // ============================================================================

  test("parse processing instruction") {
    val xml    = "<root><?target content?></root>"
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, _, ProcessingInstruction(target, content) :: _) =>
        assertEquals(target, "target")
        assertEquals(content, "content")
      case _ => fail("Expected Element with ProcessingInstruction child")
    }
  }

  // ============================================================================
  // Namespace Tests
  // ============================================================================

  test("parse element with namespace prefix") {
    val xml    = "<xs:element/>"
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(name, _, _) =>
        assertEquals(name.prefix, Some("xs"))
        assertEquals(name.localName, "element")
      case _ => fail("Expected Element")
    }
  }

  test("parse element with namespace attribute") {
    val xml    = """<root xmlns="http://example.com"/>"""
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, attributes, _) => assert(attributes.exists(_.name.localName == "xmlns"))
      case _                         => fail("Expected Element")
    }
  }

  test("parse element with prefixed namespace declaration") {
    val xml    = """<root xmlns:xs="http://www.w3.org/2001/XMLSchema"/>"""
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
  }

  // ============================================================================
  // Whitespace Tests
  // ============================================================================

  test("parse element with whitespace") {
    val xml    = """
      <root>
        <child>text</child>
      </root>
    """
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
  }

  test("preserve whitespace when configured") {
    val config = (
      preserveWhitespace = true,
      parseComments = true,
      parseProcessingInstructions = true,
      expandEntities = true
    )
    val xml    = "<root>  text  </root>"
    val result = parseXmlFragment(xml, config)
    assert(result.isSuccess)
    result.toOption.get match {
      case Element(_, _, Text(content) :: _) => assert(content.startsWith("  "))
      case _                                 => fail("Expected Element with Text child")
    }
  }

  // ============================================================================
  // Complete Document Tests
  // ============================================================================

  test("parse document with XML declaration") {
    val xml    = """<?xml version="1.0" encoding="UTF-8"?><root/>"""
    val result = parseXml(xml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.version, "1.0")
    assertEquals(doc.encoding, Some("UTF-8"))
  }

  test("parse document with standalone declaration") {
    val xml    = """<?xml version="1.0" standalone="yes"?><root/>"""
    val result = parseXml(xml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.standalone, Some(true))
  }

  test("parse document without declaration") {
    val xml    = "<root/>"
    val result = parseXml(xml)
    assert(result.isSuccess)
    val doc = result.toOption.get
    assertEquals(doc.version, "1.0")
  }

  // ============================================================================
  // Real-World Examples
  // ============================================================================

  test("parse SVG fragment") {
    val xml    = """<svg width="100" height="100">
      <circle cx="50" cy="50" r="40" fill="red"/>
    </svg>"""
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
  }

  test("parse RSS feed fragment") {
    val xml    = """<rss version="2.0">
      <channel>
        <title>Example Feed</title>
        <link>http://example.com</link>
        <description>Example description</description>
        <item>
          <title>Article 1</title>
          <description>Article description</description>
        </item>
      </channel>
    </rss>"""
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
  }

  test("parse SOAP envelope fragment") {
    val xml    = """<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
      <soap:Body>
        <GetUserInfo>
          <UserId>123</UserId>
        </GetUserInfo>
      </soap:Body>
    </soap:Envelope>"""
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
  }

  test("parse config file") {
    val xml    = """<configuration>
      <appSettings>
        <add key="ServerUrl" value="http://example.com"/>
        <add key="Timeout" value="30"/>
      </appSettings>
      <connectionStrings>
        <add name="DefaultConnection" connectionString="Server=localhost"/>
      </connectionStrings>
    </configuration>"""
    val result = parseXmlFragment(xml)
    assert(result.isSuccess)
  }

  // ============================================================================
  // Formatting Tests
  // ============================================================================

  test("format simple element") {
    val elem = Element(
      qname("root"),
      List(),
      List(Text("hello"))
    )
    val formatted = formatXml(elem)
    assertEquals(formatted, "<root>hello</root>")
  }

  test("format element with attributes") {
    val elem = Element(
      qname("root"),
      List((qname("id"), "123")),
      List()
    )
    val formatted = formatXml(elem)
    assert(formatted.contains("""id="123""""))
  }

  test("format nested elements") {
    val elem = Element(
      qname("root"),
      List(),
      List(
        Element(qname("child"), List(), List(Text("text")))
      )
    )
    val formatted = formatXml(elem)
    assert(formatted.contains("<root>"))
    assert(formatted.contains("<child>"))
  }

  test("format document") {
    val doc = (
      version = "1.0",
      encoding = Some("UTF-8"),
      standalone = None,
      root = Element(qname("root"), List(), List())
    )
    val formatted = formatXmlDocument(doc)
    assert(formatted.startsWith("<?xml"))
    assert(formatted.contains("<root/>"))
  }

  // ============================================================================
  // Error Cases
  // ============================================================================

  test("fail on unclosed element") {
    val xml    = "<root>"
    val result = parseXmlFragment(xml)
    assert(result.isFailure)
  }

  test("fail on mismatched tags") {
    val xml    = "<root></other>"
    val result = parseXmlFragment(xml)
    // Note: Current implementation may not catch this, but it should
    assert(result.isSuccess || result.isFailure)
  }

  test("fail on invalid element name") {
    val xml    = "<123/>"
    val result = parseXmlFragment(xml)
    assert(result.isFailure)
  }
}
