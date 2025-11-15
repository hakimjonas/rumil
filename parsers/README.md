# Rumil Parsers

A collection of parsers for common data formats built using the Rumil parser combinator library.

## Formats

- **CSV/TSV** - RFC 4180 compliant CSV parser with TSV support
- **JSON** - RFC 8259 compliant JSON parser with formatting
- **TOML** - TOML v1.0.0 parser
- **XML** - Well-formed XML parser with namespace support
- **YAML** - YAML 1.2 parser (simplified subset)
- **Protocol Buffers** - Proto3 syntax parser for .proto files

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

MIT
