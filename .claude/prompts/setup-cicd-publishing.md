# Task: Set Up CI/CD and Maven Central Publishing for v0.2.0 Release

## Context

You are working on **Rumil**, a Scala 3 parser combinator library with a "two-faced" approach (structural parsing + idiomatic decoding). All Priority 1 features are complete and merged to main. The library is production-ready and needs publishing infrastructure for v0.2.0 public launch.

**Current Status:**
- ✅ All core features complete (Resilient Parsing, Decoder, Documentation, Debugging)
- ✅ 6 production parsers (JSON, XML, TOML, CSV, YAML, Protobuf)
- ✅ 100+ passing tests across 12 test suites
- 🎯 Ready for v0.2.0 public release

**Author:** Hakim Jonas Ghoula <hakim@ghoula.net>
**License:** MIT (verify in LICENSE file)

## Critical Constraints

1. **NO CLAUDE/ANTHROPIC ATTRIBUTION**
   - NEVER add "Generated with Claude Code" or similar
   - NO "Co-Authored-By: Claude" in commits
   - Author MUST be: Hakim Jonas Ghoula <hakim@ghoula.net>

2. **VERIFY BEFORE PUBLISHING**
   - All tests MUST pass before any publishing setup
   - No sensitive data (API keys, tokens) in CI config
   - Use GitHub Secrets for credentials

3. **FOLLOW SCALA ECOSYSTEM STANDARDS**
   - Use sbt-sonatype and sbt-pgp plugins (standard for Scala)
   - Follow Sonatype OSS repository conventions
   - Semantic versioning (0.2.0 for this release)

## CRITICAL: Infrastructure Only - NO PUBLIC RELEASES

**User wants to perfect everything before going public.**

This task is ONLY about setting up the infrastructure:
- ✅ Set up CI/CD workflows
- ✅ Configure publishing settings
- ✅ Test that everything works
- ❌ DON'T create release tags
- ❌ DON'T publish to Maven Central
- ❌ DON'T make anything public yet

The goal is to have everything ready and tested, so when the user decides to release, it's just a matter of pushing a tag.

## Your Tasks

### Phase 1: GitHub Actions CI/CD Setup (Required)

#### 1.1 Create CI Workflow

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  test:
    name: Test
    runs-on: ubuntu-latest
    strategy:
      matrix:
        scala: [ '3.7.4' ]
        java: [ '17', '21' ]

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup Java
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: ${{ matrix.java }}
          cache: 'sbt'

      - name: Run tests
        run: sbt ++${{ matrix.scala }} test

      - name: Check formatting
        run: sbt ++${{ matrix.scala }} scalafmtCheckAll scalafmtSbtCheck

      - name: Check compilation
        run: sbt ++${{ matrix.scala }} compile

  coverage:
    name: Code Coverage
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup Java
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'
          cache: 'sbt'

      - name: Generate coverage report
        run: sbt clean coverage test coverageReport

      - name: Upload coverage to Codecov
        uses: codecov/codecov-action@v4
        with:
          files: ./target/scala-3.7.4/scoverage-report/scoverage.xml
          fail_ci_if_error: false
```

**Verification:**
- Push to a branch and verify CI runs
- All tests must pass
- Formatting checks must pass

#### 1.2 Add Code Coverage Plugin

Add to `project/plugins.sbt`:
```scala
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.0.9")
```

Add to `build.sbt`:
```scala
ThisBuild / coverageEnabled := false // Only enable via `sbt coverage` command
ThisBuild / coverageMinimumStmtTotal := 80
ThisBuild / coverageFailOnMinimum := false
```

#### 1.3 Add Build Status Badges

Update `README.md` to add badges at the top:
```markdown
# Rumil

[![CI](https://github.com/hakimjonas/Rumil/workflows/CI/badge.svg)](https://github.com/hakimjonas/Rumil/actions)
[![codecov](https://codecov.io/gh/hakimjonas/Rumil/branch/main/graph/badge.svg)](https://codecov.io/gh/hakimjonas/Rumil)
[![Maven Central](https://img.shields.io/maven-central/v/net.ghoula/rumil-core_3.svg)](https://maven-badges.herokuapp.com/maven-central/net.ghoula/rumil-core_3)

[rest of README...]
```

### Phase 2: Maven Central Publishing Setup (Required)

#### 2.1 Add Publishing Plugins

Add to `project/plugins.sbt`:
```scala
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.11.3")
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.3.1")
```

#### 2.2 Configure Publishing Settings

Add to `build.sbt`:
```scala
// Publishing configuration
ThisBuild / organization := "net.ghoula"
ThisBuild / organizationName := "Hakim Jonas Ghoula"
ThisBuild / organizationHomepage := Some(url("https://ghoula.net"))

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/hakimjonas/Rumil"),
    "scm:git@github.com:hakimjonas/Rumil.git"
  )
)

ThisBuild / developers := List(
  Developer(
    id = "hakimjonas",
    name = "Hakim Jonas Ghoula",
    email = "hakim@ghoula.net",
    url = url("https://ghoula.net")
  )
)

ThisBuild / description := "A Scala 3 parser combinator library with structural-first design and idiomatic interop"
ThisBuild / licenses := List("MIT" -> url("https://opensource.org/licenses/MIT"))
ThisBuild / homepage := Some(url("https://github.com/hakimjonas/Rumil"))

// Sonatype publishing
ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
sonatypeRepository := "https://s01.oss.sonatype.org/service/local"
ThisBuild / sonatypeProfileName := "net.ghoula"

// Publishing settings
ThisBuild / publishMavenStyle := true
ThisBuild / publishTo := sonatypePublishToBundle.value

// Don't publish root project
publish / skip := true

// Ensure all subprojects are publishable
lazy val publishSettings = Seq(
  publishMavenStyle := true,
  Test / publishArtifact := false,
  pomIncludeRepository := { _ => false }
)

// Add publishSettings to each module
lazy val core = (project in file("core"))
  .settings(publishSettings)
  // ... rest of settings

lazy val interop = (project in file("interop"))
  .settings(publishSettings)
  // ... rest of settings

lazy val parsers = (project in file("parsers"))
  .settings(publishSettings)
  // ... rest of settings
```

#### 2.3 Create Release Workflow (DISABLED BY DEFAULT)

Create `.github/workflows/release.yml`:

**IMPORTANT:** This workflow will be set up but will NOT trigger automatically because we won't create any tags yet.

```yaml
name: Release

on:
  push:
    tags:
      - 'v*'  # Only triggers when a tag like v0.2.0 is pushed

jobs:
  publish:
    name: Publish to Maven Central
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup Java
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'
          cache: 'sbt'

      - name: Import PGP key
        run: |
          echo "${{ secrets.PGP_SECRET }}" | base64 --decode | gpg --batch --import

      - name: Publish to Sonatype
        run: sbt ci-release
        env:
          PGP_PASSPHRASE: ${{ secrets.PGP_PASSPHRASE }}
          PGP_SECRET: ${{ secrets.PGP_SECRET }}
          SONATYPE_USERNAME: ${{ secrets.SONATYPE_USERNAME }}
          SONATYPE_PASSWORD: ${{ secrets.SONATYPE_PASSWORD }}

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v1
        with:
          generate_release_notes: true
```

#### 2.4 Add sbt-ci-release Plugin

Add to `project/plugins.sbt`:
```scala
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.9.2")
```

Add to `build.sbt`:
```scala
ThisBuild / versionScheme := Some("early-semver")
```

### Phase 3: Documentation Site (Optional but Recommended)

#### 3.1 Set Up GitHub Pages with mdBook

Create `.github/workflows/docs.yml`:
```yaml
name: Documentation

on:
  push:
    branches: [ main ]

jobs:
  deploy:
    name: Deploy Documentation
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Install mdBook
        run: |
          curl -sSL https://github.com/rust-lang/mdBook/releases/download/v0.4.40/mdbook-v0.4.40-x86_64-unknown-linux-gnu.tar.gz | tar -xz
          chmod +x mdbook

      - name: Build book
        run: ./mdbook build docs

      - name: Deploy to GitHub Pages
        uses: peaceiris/actions-gh-pages@v3
        with:
          github_token: ${{ secrets.GITHUB_TOKEN }}
          publish_dir: ./docs/book
```

Create `docs/book.toml`:
```toml
[book]
title = "Rumil Documentation"
authors = ["Hakim Jonas Ghoula"]
language = "en"
multilingual = false
src = "."

[output.html]
git-repository-url = "https://github.com/hakimjonas/Rumil"
edit-url-template = "https://github.com/hakimjonas/Rumil/edit/main/docs/{path}"
```

Create `docs/SUMMARY.md`:
```markdown
# Summary

- [Getting Started](getting-started.md)
- [Structural Approach](structural-approach.md)
- [Idiomatic Approach](idiomatic-approach.md)
- [Error Handling](error-handling.md)
- [Debugging](debugging.md)
- [Performance](performance.md)
- [Migration Guide](migration-guide.md)
```

### Phase 4: Verify Infrastructure (DO NOT RELEASE YET)

**IMPORTANT:** This phase is about VERIFYING the setup works, NOT actually releasing.

1. **Test CI Workflow Locally (if possible)**
   ```bash
   # Install act (GitHub Actions local runner)
   # brew install act  # or appropriate package manager
   # act -l  # List available jobs
   # act pull_request  # Test PR workflow locally
   ```

2. **Verify Version Numbers Are Correct**
   ```bash
   grep -r "0.2.0" build.sbt
   ```
   - Ensure `ThisBuild / version := "0.2.0"`
   - Update any version references in docs

3. **Run Full Test Suite**
   ```bash
   sbt clean
   sbt test
   ```
   - All tests must pass

4. **Test Publishing Locally (WITHOUT actually publishing)**
   ```bash
   sbt publishLocal  # This publishes to ~/.ivy2/local only, NOT Maven Central
   ```
   - Verify artifacts are generated correctly
   - Check POM files have correct metadata

5. **Verify PGP Signing Setup (if user has keys)**
   ```bash
   sbt publishLocalSigned  # Test signing works locally
   ```

6. **Create CHANGELOG.md (Draft for v0.2.0)**
   ```markdown
   # Changelog

   ## [0.2.0] - 2025-11-16

   ### Added
   - Lossless, resilient parsing with `Result.Partial`
   - `GreenNode` syntax trees preserving all source information
   - `Decoder[From, To]` typeclass with automatic derivation
   - `Parser.derived[CaseClass]` for automatic parser generation
   - `.trace()` and `.debug()` combinators for debugging
   - Production parsers: JSON, XML, TOML, CSV, YAML, Protobuf
   - Comprehensive documentation and examples
   - Error recovery with multi-error accumulation

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
   ```

6. **DON'T Create Release Tag Yet**

   ❌ **DO NOT RUN:**
   ```bash
   # git tag -a v0.2.0 -m "Release v0.2.0: Public Launch"
   # git push origin v0.2.0
   ```

   The user will create the tag manually when ready to release.

## GitHub Secrets Setup

**IMPORTANT:** Before running release workflow, set up these secrets in GitHub:
1. Go to repository Settings → Secrets and variables → Actions
2. Add the following secrets:
   - `SONATYPE_USERNAME` - Your Sonatype account username
   - `SONATYPE_PASSWORD` - Your Sonatype account password
   - `PGP_SECRET` - Your PGP private key (base64 encoded)
   - `PGP_PASSPHRASE` - Your PGP key passphrase

**PGP Key Generation:**
```bash
# Generate key
gpg --gen-key

# Export private key (base64 encoded)
gpg --armor --export-secret-keys YOUR_EMAIL | base64 -w0

# Get key ID
gpg --list-secret-keys
```

## Expected Deliverables

1. ✅ GitHub Actions CI configured and running on PRs/pushes to main
2. ✅ Code coverage reporting configured with Codecov
3. ✅ Build status badges added to README
4. ✅ Maven Central publishing settings configured in build.sbt
5. ✅ Release workflow file created (will trigger only when tag is pushed)
6. ✅ Documentation site setup (optional)
7. ✅ CHANGELOG.md created (draft)
8. ✅ Local publishing tested (`sbt publishLocal`)
9. ❌ NO tags created
10. ❌ NO public releases made
11. ❌ NO Maven Central publishing triggered

## Success Criteria

- ✅ CI passes on main branch
- ✅ Coverage report shows >80% coverage
- ✅ Publishing settings are configured in build.sbt
- ✅ `sbt publishLocal` works and generates artifacts correctly
- ✅ Release workflow exists but is NOT triggered (no tags pushed)
- ✅ Documentation site setup exists but deployment is optional
- ❌ NOTHING has been published to Maven Central
- ❌ NO release tags exist
- ❌ Repository is NOT publicly advertised yet

**The infrastructure is ready, but the trigger (git tag) has not been pulled.**

## Important Notes

1. **DO NOT PUBLISH ANYTHING**: This is CRITICAL. You are ONLY setting up infrastructure:
   - ✅ Create workflow files
   - ✅ Update build.sbt with publishing config
   - ✅ Test locally with `publishLocal`
   - ❌ DON'T create any git tags (no v0.2.0, v0.1.0, etc.)
   - ❌ DON'T run `sbt publishSigned`
   - ❌ DON'T run `sbt sonatypeBundleRelease`
   - ❌ DON'T push to Maven Central

2. **Test the Workflows**:
   - Push your changes to a feature branch
   - Create a test PR to verify CI runs correctly
   - Verify all checks pass
   - DO NOT merge to main until user approves

3. **Sonatype Account Setup (User Action Required)**:
   - User will need to create Sonatype account at https://s01.oss.sonatype.org
   - File JIRA ticket to claim `net.ghoula` groupId
   - This can take 1-2 business days
   - **Document this in your report** - user needs to do this before release

4. **PGP Key Setup (User Action Required)**:
   - User needs to generate PGP key for signing artifacts
   - Add key to GitHub secrets
   - **Document the exact steps needed** in your report

5. **Verify License**: Ensure LICENSE file exists and is correct (should be MIT)

6. **When Ready to Release (User Decision)**:
   The user will later run:
   ```bash
   git tag -a v0.2.0 -m "Release v0.2.0"
   git push origin v0.2.0
   ```
   This will trigger the release workflow automatically.

## After Completion

Report back with:

### 1. Infrastructure Summary
- List all files created/modified
- Confirm CI is running and passing
- Show coverage percentage

### 2. What Works (Verified)
- ✅ CI tests pass
- ✅ Formatting checks pass
- ✅ `sbt publishLocal` generates artifacts
- ✅ Build configuration is valid

### 3. What's NOT Done (By Design)
- ❌ No tags created
- ❌ No Maven Central publishing
- ❌ No public releases
- ❌ Repository not advertised

### 4. User Action Items (Before Release)
Document exactly what the user needs to do:

**A. Sonatype OSS Account**
- Sign up at https://s01.oss.sonatype.org
- Create JIRA ticket to claim `net.ghoula` groupId
- Estimated time: 1-2 business days for approval

**B. PGP Key Generation and Setup**
- Generate PGP key: `gpg --gen-key`
- Export for GitHub: `gpg --armor --export-secret-keys EMAIL | base64 -w0`
- Add these GitHub secrets:
  - `PGP_SECRET` (base64 encoded private key)
  - `PGP_PASSPHRASE` (key passphrase)
  - `SONATYPE_USERNAME` (from step A)
  - `SONATYPE_PASSWORD` (from step A)

**C. Final Pre-Release Checklist**
- Review all documentation
- Run full test suite one more time
- Update CHANGELOG.md with final notes
- Make any last-minute fixes

**D. When Ready to Release**
```bash
git tag -a v0.2.0 -m "Release v0.2.0: Public Launch"
git push origin v0.2.0
# This automatically triggers Maven Central publishing via GitHub Actions
```

### 5. Verification Steps User Should Run
Provide commands user can run to verify setup:
```bash
# Verify CI config is valid
cat .github/workflows/ci.yml

# Test local publishing
sbt clean publishLocal

# Check generated artifacts
ls -la ~/.ivy2/local/net/ghoula/

# Verify POM metadata
cat ~/.ivy2/local/net/ghoula/rumil-core_3/0.2.0/poms/rumil-core_3.pom
```

### 6. Next Steps Recommendation
- Suggest creating a private test repository to test the release workflow end-to-end
- Recommend timeline: "Infrastructure ready now, but allow 1-2 days for Sonatype account approval"
- Note that user can perfect the code/docs while waiting for Sonatype approval
