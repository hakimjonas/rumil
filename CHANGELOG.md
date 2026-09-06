# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **License** — moved from GPL-3.0-or-later to LGPL-3.0-or-later across the LICENSE file,
  build metadata, and documentation. LGPL keeps the copyleft protection of the source while
  permitting applications to link and use the libraries without license obligations on
  application code.
- **Dependency** — `sarati` moved to 1.0.0-alpha.3 (license-metadata-only upstream release;
  no API change).
- **Dependency** — `sarati` moved to 1.0.0-alpha.4 (toolchain-only upstream release:
  Scala 3.9.0 LTS + sbt 2.0.8; no API change).

### Removed

- **Sarati re-exports** — `parsers.{json,xml,toml,yaml,xpath}` no longer re-export Sarati's
  formatters and XPath AST types (`formatJson`, `formatXml`, `formatToml*`, `formatYaml*`,
  `compactFormat`, `prettyFormat`, `JsonFormatConfig`, `toInlineValue`, `XPathExpr`, `Step`,
  `Axis`, `NodeTest`, `NameTest`, `BinaryOp`, `ArithOp`). Import them from Sarati directly
  (`net.ghoula.sarati.ast.json.*`, `net.ghoula.sarati.ast.xml.*`, `net.ghoula.sarati.ast.toml.*`,
  `net.ghoula.sarati.ast.yaml.*`, `net.ghoula.sarati.xpath.*`): every consumer already
  declares `sarati` (the AST types appear throughout Rumil's public signatures), and each
  library's compatibility surface should be exactly its own. Rumil's own API — the parsers
  and combinators — is unchanged.

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
