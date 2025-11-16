# Remaining Work for v0.2.0 Public Launch

## Critical Path to Release

### 1. Documentation PR Merge
**Status:** Ready for review
**Branch:** `claude/complete-documentation-01D2tLLuDCR96Fy5t4VFAhCU`
**Effort:** Review and merge (~30 min)

This PR contains:
- Updated README with accurate API examples
- 7 comprehensive tutorial documents
- 5 runnable example programs
- "Structural-First Design" branding

**Action:** Create and merge PR #10

---

### 2. Publishing & CI/CD Setup
**Status:** Not started
**Priority:** HIGH (required for public release)
**Estimated Effort:** 4-6 hours

#### Tasks:

**2.1 GitHub Actions CI/CD**
- [ ] Create `.github/workflows/ci.yml`
  - Run tests on push/PR
  - Check formatting (`sbt scalafmtCheckAll scalafmtSbtCheck`)
  - Run scalafix checks
  - Test on multiple Scala versions (3.7.4+)
- [ ] Add test coverage reporting
  - Use scoverage plugin
  - Upload to Codecov
- [ ] Add build status badge to README

**2.2 Maven Central Publishing**
- [ ] Set up Sonatype OSS account
- [ ] Configure `build.sbt` for publishing:
  ```scala
  ThisBuild / sonatypeProfileName := "net.ghoula"
  publishMavenStyle := true
  publishTo := sonatypePublishToBundle.value
  ```
- [ ] Add PGP signing for artifacts
- [ ] Create release workflow
  - Tag-based automated publishing
  - Generate release notes from commits
- [ ] Publish v0.2.0 to Maven Central

**2.3 Documentation Site (Optional but Recommended)**
- [ ] Set up GitHub Pages with mdBook or Docusaurus
- [ ] Auto-deploy docs from `docs/` directory
- [ ] Add API docs generated from scaladoc

---

### 3. Comprehensive Benchmarks
**Status:** Basic benchmarks exist
**Priority:** MEDIUM (nice to have for v0.2.0)
**Estimated Effort:** 3-4 hours

Currently: Basic performance tests in test suite
Needed: JMH benchmarks comparing against competitors

**Tasks:**
- [ ] Add JMH plugin to `build.sbt`
- [ ] Create `benchmarks/` module
- [ ] Implement benchmarks:
  - JSON parsing (vs fastparse, circe)
  - Expression evaluation (vs fastparse, cats-parse)
  - Error recovery overhead
  - GreenNode vs direct parsing
- [ ] Run benchmarks and document results
- [ ] Add performance comparison table to README

---

## Optional Enhancements for v0.2.0

### 4. Extended Decoder Features
**Priority:** LOW
**Effort:** 2-3 hours each

**4.1 Custom Field Annotations**
```scala
case class User(
  @JsonKey("user_name") name: String,
  @JsonIgnore internal: String = ""
)
```

**4.2 Circe Compatibility Layer**
```scala
import io.circe.Decoder as CirceDecoder
given [A](using CirceDecoder[A]): Decoder[JsonValue, A] = ...
```

**4.3 XML and TOML Decoders**
- Decoder instances for XmlNode → case classes
- Decoder instances for TomlValue → case classes

---

## Post-v0.2.0 Roadmap

### Priority 2: Performance & Power (v0.3.0)

**2.1 Left Recursion Support**
- Implement seed-growth algorithm (Warth et al.)
- Remove need for `chainl1`/`chainr1` workarounds
- Effort: 8-10 hours

**2.2 Memoization/Packrat Parsing**
- Add `.memoize` combinator
- Implement result caching with LRU eviction
- Effort: 6-8 hours

**2.3 Comprehensive Benchmarks** (if not done in v0.2.0)
- See section 3 above

### Priority 3: Ecosystem Expansion (v0.4.0)

**3.1 Streaming/Incremental Parsing**
- Parser.parseStream for Iterator-based input
- Chunked parsing for large files
- Effort: 10-12 hours

**3.2 Platform Expansion**
- Cross-compile to Scala.js
- Cross-compile to Scala Native
- Effort: 4-6 hours (mostly build configuration)

---

## Summary

**For v0.2.0 Public Launch:**

**Critical (Must Have):**
1. ✅ All Priority 1 features - COMPLETE
2. 📝 Merge documentation PR - Ready
3. 🚀 Publishing & CI/CD - 4-6 hours

**Recommended (Should Have):**
4. 📊 Comprehensive benchmarks - 3-4 hours

**Optional (Nice to Have):**
5. Extended Decoder features - 6-9 hours total

**Total Critical Path:** ~5-7 hours of work
**Total for Full v0.2.0:** ~14-20 hours

**Status:** Rumil is **95% ready** for v0.2.0 public launch. The core library is feature-complete and production-ready. Only publishing infrastructure remains.
