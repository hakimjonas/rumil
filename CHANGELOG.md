# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0-alpha] - 2026-09

Initial public release.

### Added

- Core parser combinators with a structural-first design: lossless, resilient parsing with `Result.Partial` for error recovery, `GreenNode` syntax trees preserving all source information, and position tracking for every failure.
- Format parsers for JSON, XML, TOML, CSV, YAML, Protobuf, and XPath 1.0 (producing sarati's `XPathExpr` AST, with `printXPath`).
- Left recursion via the `rule` combinator (seed-growth algorithm) and memoization with cycle detection.
- Fast alternation: `orElse`/`|` flatten nested chains into a single `Choice` (and a radix `StringChoice` for pure-string alternatives); skip fusion for `<*`/`*>`; `firstCharChoice`; and a `cFamilyPrecedence` pratt preset.
- `recover(p, fallback)` for parser-level error recovery with error tracking.

### Deprecated

- The `rumil-interop` module (`parser.interop`) — a pre-Sarati twin of the codec layer — is deprecated and scheduled for removal in 1.0. Use `net.ghoula:sarati` codec derivation instead.

### Changed

- Toolchain: sbt 2.0.7, Scala 3.8.4, sarati 1.0.0-alpha, munit 1.3.5.
