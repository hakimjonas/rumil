ThisBuild / version          := "0.2.0"
ThisBuild / scalaVersion     := "3.7.4"
ThisBuild / organization     := "net.ghoula"
ThisBuild / organizationName := "Hakim Jonas Ghoula"

ThisBuild / licenses := List(
  "MIT" -> url("https://opensource.org/licenses/MIT")
)

ThisBuild / developers := List(
  Developer(
    id = "hakimjonas",
    name = "Hakim Jonas Ghoula",
    email = "hakim@ghoula.net",
    url = url("https://hakim.ghoula.net")
  )
)

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/hakimjonas/rumil"),
    "scm:git@github.com:hakimjonas/rumil.git"
  )
)

ThisBuild / description := "A Scala 3 parser combinator library with structural-first design and idiomatic interop"
ThisBuild / homepage    := Some(url("https://github.com/hakimjonas/rumil"))

// Publishing configuration
ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
sonatypeRepository                 := "https://s01.oss.sonatype.org/service/local"
ThisBuild / sonatypeProfileName    := "net.ghoula"

ThisBuild / publishMavenStyle := true
ThisBuild / publishTo         := sonatypePublishToBundle.value
ThisBuild / versionScheme     := Some("early-semver")

// Enable scalafix semantic rules
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

// Code coverage settings
ThisBuild / coverageEnabled          := false // Only enable via `sbt coverage` command
ThisBuild / coverageMinimumStmtTotal := 50    // Realistic target given current coverage
ThisBuild / coverageFailOnMinimum    := false // Don't fail build on low coverage

// Command aliases for convenience
addCommandAlias("testAll", ";core/test;parsers/test")
addCommandAlias("prepare", ";scalafmtAll;scalafmtSbt;scalafixAll")

// Publishing settings for modules
lazy val publishSettings = Seq(
  publishMavenStyle      := true,
  Test / publishArtifact := false,
  pomIncludeRepository   := { _ => false }
)

javacOptions ++= Seq(
  "--release",
  "25"
)

// Core parser combinator library
lazy val core = (project in file("core"))
  .settings(publishSettings)
  .settings(
    name := "rumil-core",
    libraryDependencies ++= Seq(
      "org.scalacheck"        %% "scalacheck"              % "1.19.0" % Test,
      "org.scalameta"         %% "munit"                   % "1.2.1"  % Test,
      "org.typelevel"         %% "cats-parse"              % "1.1.0"  % Test, // For comparative benchmarks
      "dev.zio"               %% "zio-parser"              % "0.1.9"  % Test  // For comparative benchmarks
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings",
      "-Yexplicit-nulls",
      "-language:strictEquality",
      "-Wsafe-init",
      "-Wunused:all",
      "-Wvalue-discard",
      "-explain",
      "-no-indent", // Braces only - no significant indentation
      "-old-syntax" // Prefer braces
    ),
    javaOptions ++= Seq(
      "-XX:+UseG1GC",
      "-XX:MaxGCPauseMillis=50",
      "-XX:+UseStringDeduplication",
      "-XX:+ParallelRefProcEnabled",
      "--sun-misc-unsafe-memory-access=allow" // Suppress Java 25 Unsafe warnings (Scala 3.7.4 issue)
    ),
    Test / fork := false
  )

// Format parsers (CSV, JSON, XML, YAML, etc.)
lazy val parsers = (project in file("parsers"))
  .settings(publishSettings)
  .settings(
    name := "rumil-parsers",
    libraryDependencies ++= Seq(
      "org.scalacheck" %% "scalacheck" % "1.19.0" % Test,
      "org.scalameta"  %% "munit"      % "1.2.1"  % Test
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings",
      "-Yexplicit-nulls",
      "-language:strictEquality",
      "-Wsafe-init",
      "-Wunused:all",
      "-Wvalue-discard",
      "-explain",
      "-no-indent",
      "-old-syntax"
    ),
    javaOptions ++= Seq(
      "-XX:+UseG1GC",
      "-XX:MaxGCPauseMillis=50",
      "-XX:+UseStringDeduplication",
      "-XX:+ParallelRefProcEnabled",
      "--sun-misc-unsafe-memory-access=allow"
    ),
    Test / fork := false
  )
  .dependsOn(core % "compile->compile;test->test")

// Idiomatic Scala interop (case class derivation)
lazy val interop = (project in file("interop"))
  .settings(publishSettings)
  .settings(
    name := "rumil-interop",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.2.1" % Test
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings",
      "-Yexplicit-nulls",
      "-language:strictEquality",
      "-Wsafe-init",
      "-Wunused:all",
      "-Wvalue-discard",
      "-explain",
      "-no-indent",
      "-old-syntax"
    ),
    javaOptions ++= Seq(
      "-XX:+UseG1GC",
      "-XX:MaxGCPauseMillis=50",
      "-XX:+UseStringDeduplication",
      "-XX:+ParallelRefProcEnabled",
      "--sun-misc-unsafe-memory-access=allow"
    ),
    Test / fork := false
  )
  .dependsOn(
    core    % "compile->compile;test->test",
    parsers % "compile->compile;test->test"
  )

// JMH Benchmarks
lazy val benchmarks = (project in file("benchmarks"))
  .enablePlugins(JmhPlugin)
  .settings(
    name           := "rumil-benchmarks",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "org.typelevel"        %% "cats-parse"              % "1.1.0",  // For comparative benchmarks
      "dev.zio"              %% "zio-parser"              % "0.1.9",  // For comparative benchmarks
      "org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0"   // For comparative benchmarks
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Yexplicit-nulls",
      "-language:strictEquality",
      "-Wunused:all",
      "-no-indent",
      "-old-syntax"
    ),
    // JMH settings
    Jmh / sourceDirectory := (Compile / sourceDirectory).value,
    Jmh / classDirectory  := (Compile / classDirectory).value
  )
  .dependsOn(core, parsers, interop)

// Root aggregator project
lazy val root = (project in file("."))
  .settings(
    name           := "rumil",
    publish / skip := true
  )
  .aggregate(core, parsers, interop, benchmarks)
