# SBT Commands Reference

## Testing

### Run all tests (core + parsers)
```bash
sbt testAll
```

This runs:
1. Core parser combinator library tests (48 tests)
2. Parsers library tests (CSV, JSON, XML, YAML, TOML, Protobuf)

### Run core tests only
```bash
sbt test
```

### Run parsers tests only
```bash
sbt parsers/test
```

## Code Formatting & Linting

### Prepare code for commit (format + lint)
```bash
sbt prepare
```

This runs:
1. `scalafmtAll` - Format all code with Scalafmt
2. `scalafixAll` - Apply Scalafix rules

### Format code only
```bash
sbt scalafmtAll
```

### Check formatting (CI mode)
```bash
sbt scalafmtCheckAll
```

### Apply scalafix rules
```bash
sbt scalafixAll
```

### Check scalafix rules (CI mode)
```bash
sbt "scalafixAll --check"
```

## Compilation

### Compile all projects
```bash
sbt compile
sbt Test/compile
```

### Clean build artifacts
```bash
sbt clean
```

## Complete Workflow

### Before committing changes:
```bash
sbt prepare    # Format and lint
sbt testAll    # Run all tests
```

### CI/CD Pipeline:
```bash
sbt clean
sbt scalafmtCheckAll
sbt "scalafixAll --check"
sbt testAll
```

## Project Structure

```
Rumil/
├── core/                  # Parser combinator library
│   ├── src/main/scala/   # Core parsers (Parser, Result, combinators)
│   └── src/test/scala/   # Core tests (48 tests)
└── parsers/               # Format parsers (CSV, JSON, XML, YAML, TOML, Protobuf)
    ├── src/main/scala/   # Format parsers implementation
    └── src/test/scala/   # Format parser tests (~150+ tests)
```

### SBT Projects

- **`core`** - Core parser combinator library
- **`parsers`** - Format parsers (depends on core)
- **`root`** - Aggregator (runs commands across both projects)

## Configuration Files

- `.scalafmt.conf` - Scalafmt configuration (braces-only syntax)
- `.scalafix.conf` - Scalafix rules
- `build.sbt` - Root project configuration
- `parsers/build.sbt` - Parsers subproject configuration
- `project/plugins.sbt` - SBT plugins

## Braces-Only Syntax Enforcement

Rumil enforces **braces-only syntax** (no significant indentation) through:

### Compiler Flags (build.sbt)
```scala
"-no-indent"     // Reject significant indentation
"-old-syntax"    // Require braces
```

### Scalafmt Configuration (.scalafmt.conf)
```hocon
rewrite.scala3.convertToNewSyntax = false      // Don't convert to new syntax
rewrite.scala3.removeOptionalBraces = no       // Keep all braces
```

**Result:** Code using significant indentation will fail compilation and formatting.
