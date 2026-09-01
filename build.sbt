ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "net.ghoula"
ThisBuild / organizationName := "Hakim Jonas Ghoula"
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

ThisBuild / licenses := Seq("GPL-3.0-or-later" -> uri("https://www.gnu.org/licenses/gpl-3.0.txt"))
ThisBuild / homepage := Some(uri("https://github.com/hakimjonas/rumil"))
ThisBuild / description := "A Scala 3 parser combinator library with structural-first design and idiomatic interop"
ThisBuild / developers := List(
  Developer(
    id = "hakimjonas",
    name = "Hakim Jonas Ghoula",
    email = "hakim@ghoula.net",
    url = uri("https://github.com/hakimjonas")
  )
)
ThisBuild / scmInfo := Some(
  ScmInfo(
    uri("https://github.com/hakimjonas/rumil"),
    "scm:git@github.com:hakimjonas/rumil.git"
  )
)

// ===== Publishing Settings =====
//
// Maven Central (Central Portal) is the single publication target. Releases are staged locally
// and uploaded with `sonaRelease` (sbt 2.x built-in Central Portal support); artifacts are signed
// by sbt-pgp (`publishSigned`). Credentials are read automatically from SONATYPE_USERNAME /
// SONATYPE_PASSWORD. Dependencies (sarati) resolve from Maven Central, so no extra resolvers.
ThisBuild / publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}
ThisBuild / publishMavenStyle := true
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / Test / publishArtifact := false

// Code coverage settings
ThisBuild / coverageEnabled := false
ThisBuild / coverageMinimumStmtTotal := 50
ThisBuild / coverageFailOnMinimum := false

// Command aliases
addCommandAlias("testAll", "core/Test/testFull; parsers/Test/testFull; interop/Test/testFull")
addCommandAlias("prepare", ";scalafmtAll;scalafmtSbt;scalafixAll")
addCommandAlias("check", "scalafixAll --check; scalafmtCheckAll; scalafmtSbtCheck")

javacOptions ++= Seq("--release", "25")

val saratiVersion = "1.0.0-alpha.2"

val sharedScalacOptions = Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Werror",
  "-Yexplicit-nulls",
  "-language:strictEquality",
  "-Wsafe-init",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wconf:id=E198:s",
  "-explain",
  "-no-indent",
  "-new-syntax",
  "-source:future"
)

// Core parser combinator library
lazy val core = (project in file("core"))
  .settings(
    name := "rumil-core",
    libraryDependencies ++= Seq(
      "org.scalacheck" %% "scalacheck" % "1.19.0" % Test,
      "org.scalameta" %% "munit" % "1.3.5" % Test,
      "org.typelevel" %% "cats-parse" % "1.1.0" % Test,
      "dev.zio" %% "zio-parser" % "0.1.11" % Test
    ),
    scalacOptions ++= sharedScalacOptions,
    Test / testOptions += Tests.Filter { name =>
      if (sys.env.contains("CI"))
        !name.contains("Benchmark") && !name.contains("Comparison")
      else
        true
    },
    javaOptions ++= Seq("-XX:+UseZGC"),
    Test / fork := true
  )

// Format parsers (CSV, JSON, XML, YAML, etc.)
lazy val parsers = (project in file("parsers"))
  .settings(
    name := "rumil-parsers",
    libraryDependencies ++= Seq(
      "net.ghoula" %% "sarati" % saratiVersion,
      "org.scalacheck" %% "scalacheck" % "1.19.0" % Test,
      "org.scalameta" %% "munit" % "1.3.5" % Test
    ),
    scalacOptions ++= sharedScalacOptions,
    javaOptions ++= Seq("-XX:+UseZGC"),
    Test / fork := true
  )
  .dependsOn(core % "compile->compile;test->test")

// Idiomatic Scala interop (case class derivation)
lazy val interop = (project in file("interop"))
  .settings(
    Test / scalacOptions += "-Wconf:cat=deprecation:s", // interop tests exercise the deprecated interop API by design
    name := "rumil-interop",
    libraryDependencies ++= Seq(
      "net.ghoula" %% "sarati" % saratiVersion,
      "org.scalameta" %% "munit" % "1.3.5" % Test
    ),
    scalacOptions ++= sharedScalacOptions,
    javaOptions ++= Seq("-XX:+UseZGC"),
    Test / fork := true
  )
  .dependsOn(
    core % "compile->compile;test->test",
    parsers % "compile->compile;test->test"
  )

// JMH Benchmarks
lazy val benchmarks = (project in file("benchmarks"))
  .enablePlugins(JmhPlugin)
  .settings(
    name := "rumil-benchmarks",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-parse" % "1.1.0",
      "dev.zio" %% "zio-parser" % "0.1.11",
      "org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0"
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Yexplicit-nulls",
      "-language:strictEquality",
      "-Wunused:all",
      "-no-indent",
      "-new-syntax",
      "-source:future"
    ),
    Jmh / sourceDirectory := (Compile / sourceDirectory).value,
    // NOTE: do NOT alias Jmh/classDirectory to Compile's and do NOT add it to products:
    // sbt 2 materializes the Compile class directory wholesale (snapshot restore), which
    // silently wipes foreign files (jmh_generated classes, BenchmarkList) at assembly time,
    // and adding the Jmh dir to products duplicates the benchmark classes inside packageBin.
    // The Jmh config therefore compiles to its OWN directory; the CI smoke step overlays
    // those outputs onto the assembled fat jar with `jar --update`.
    assembly / mainClass := Some("org.openjdk.jmh.Main"),
    assembly / assemblyJarName := "rumil-bench.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
      case PathList("META-INF", "versions", _*) => MergeStrategy.first
      case PathList("META-INF", _*) => MergeStrategy.first
      case "module-info.class" => MergeStrategy.discard
      case x if x.endsWith(".class") => MergeStrategy.first
      case _ => MergeStrategy.first
    }
  )
  .dependsOn(core, parsers, interop)

// Root aggregator project
lazy val root = (project in file("."))
  .settings(
    name := "rumil",
    publish / skip := true
  )
  .aggregate(core, parsers, interop, benchmarks)
