# Rumil Parsers

A collection of production-ready parsers for common data formats, built using the Rumil parser combinator library.

## Implemented Formats

- **CSV** - RFC 4180 compliant CSV parser
- **TSV** - Tab-Separated Values (CSV variant)
- **JSON** - RFC 8259 compliant JSON parser
- **TOML** - TOML v1.0.0 parser
- **XML** - Well-formed XML with namespace support
- **YAML** - YAML 1.2 parser
- **Protocol Buffers** - Proto3 syntax parser for .proto files

## Design Philosophy

All parsers follow Rumil's manifesto:

- ✅ **Enums** for AST nodes (not case classes)
- ✅ **Named tuples** for simple data structures
- ✅ **Top-level functions** for parsers
- ✅ **No mutation** except in controlled Ref cells
- ✅ **Full spec compliance** where applicable

## Usage

```scala
import parsers.csv.*
import parsers.json.*
import parser.syntax.*

// Parse CSV
val csvResult = CsvParser.parse("name,age\nAlice,30\nBob,25")

// Parse JSON
val jsonResult = JsonParser.parse("""{"name": "Alice", "age": 30}""")
```

## Testing

Each parser includes:
- Property-based tests using ScalaCheck
- Real-world example files
- Compliance test suites

Run tests:
```bash
cd parsers
sbt test
```

## License

MIT
