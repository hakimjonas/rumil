# Rumil Parsers

A collection of parsers for common data formats built using the Rumil parser combinator library.

## Formats

- **CSV/TSV** - CSV parser with quoted fields (embedded delimiters, quotes, newlines) and TSV support
- **JSON** - RFC 8259 compliant JSON parser with formatting
- **TOML** - TOML v1.0.0 parser (tables and array tables)
- **XML** - XML parser with namespace-prefixed names, rejecting mismatched end tags
- **YAML** - YAML 1.2 subset: block and flow sequences/mappings, scalars, comments; no anchors, aliases, tags, or multi-document streams
- **Protocol Buffers** - Proto3 syntax parser for .proto files
- **XPath 1.0** - expression parser producing sarati's `XPathExpr` AST, plus a canonical printer

## Usage

```scala
import parsers.csv.*
import parsers.json.*
import parser.syntax.*

// Parse CSV
val csvResult = parseCsv("name,age\nAlice,30\nBob,25")

// Parse CSV with headers
val headersResult = parseCsvWithHeaders("name,age\nAlice,30")

// Parse TSV
val tsvResult = parseTsv("name\tage\nAlice\t30")

// Parse JSON
val jsonResult = parseJson("""{"name": "Alice", "age": 30}""")

// Format JSON
val formatted = formatJson(jsonValue, prettyFormat)

// Parse XML
val xmlResult = parseXml("""<?xml version="1.0"?><root>content</root>""")

// Parse TOML
val tomlResult = parseToml("key = 'value'\nage = 30")

// Parse YAML
val yamlResult = parseYaml("name: Alice\nage: 30")

// Parse Protocol Buffers
val protoResult = parseProto("syntax = \"proto3\";\nmessage User {}")
```

## Testing

Each parser includes property-based tests using ScalaCheck and compliance test suites against real-world example files.

Run tests:
```bash
cd parsers
sbt test
```

## License

GPL-3.0-or-later
