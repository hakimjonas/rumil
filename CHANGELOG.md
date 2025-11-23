# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - 2025-11-16

### Added
- Lossless, resilient parsing with `Result.Partial` for error recovery
- `GreenNode` syntax trees preserving all source information (whitespace, comments)
- `Decoder[From, To]` typeclass with automatic derivation for case classes
- `Parser.derived[CaseClass]` for automatic parser generation from types
- `.trace()` and `.debug()` combinators for debugging parser behavior
- Production-ready parsers for 6 formats:
  - JSON (with full spec compliance)
  - XML (with namespace support)
  - TOML (with nested tables)
  - CSV (with flexible delimiters)
  - YAML (basic support)
  - Protobuf (text format)
- Comprehensive documentation with examples
- Error recovery with multi-error accumulation
- Position tracking (line, column, offset) for all parse errors
- 100+ passing tests across 12 test suites
- CI/CD infrastructure with GitHub Actions
- Code coverage reporting with Codecov
- Maven Central publishing setup (not yet published)
- Documentation site structure with mdBook

### Changed
- N/A (initial public release)

### Deprecated
- N/A

### Removed
- N/A

### Fixed
- N/A

### Security
- N/A

## [Unreleased]

### Added
- Left recursion support via `rule` combinator using seed-growth algorithm (Warth et al.)
- `Parser.Memo` case for memoized parsing with cycle detection
- Memoization infrastructure in `ParserState` (`memo`, `lrStack`, `heads` tables)
- `LR` and `LRHead` data structures for tracking left-recursive cycles
- Direct left-recursive grammars now "just work" with natural syntax

[0.2.0]: https://github.com/hakimjonas/rumil/releases/tag/v0.2.0
