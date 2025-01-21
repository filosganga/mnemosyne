val catsVersion = "2.12.0"
val catsEffectVersion = "3.5.7"
val slf4jVersion = "2.0.16"
val awsSdkVersion = "2.29.52"
val log4CatsVersion = "2.7.0"
val munitVersion = "1.0.3"
val munitScalacheckVersion = "1.0.0"
val munitCatsEffectVersion = "2.0.0"
val logBackVersion = "1.5.15"
val log4j2Version = "2.24.3"
val lettuceVersion = "6.5.1.RELEASE"

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
ThisBuild / scalaVersion := "3.6.2"
ThisBuild / crossScalaVersions ++= Seq("3.3.4", "2.13.15")

ThisBuild / versionScheme := Some("early-semver")
ThisBuild / startYear := Some(2020)
ThisBuild / licenses += License.Apache2
ThisBuild / organization := "com.filippodeluca"
ThisBuild / organizationHomepage := Some(url("http://filippodeluca.com"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/filosganga/mnemosyne"),
    "git@github.com:filosganga/mnemosyne.git"
  )
)
ThisBuild / developers := List(
  Developer(
    "filosganga",
    "Filippo De Luca",
    "me@filippodeluca.com",
    url("https://github.com/filosganga")
  ),
  Developer(
    "SystemFw",
    "Fabio Labella",
    "fabio.labella@xyz.com",
    url("https://github.com/SystemFw")
  )
)
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishMavenStyle := true
ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
ThisBuild / publishTo := sonatypePublishToBundle.value
ThisBuild / credentials ++= {
  for {
    usr <- sys.env.get("SONATYPE_USER")
    password <- sys.env.get("SONATYPE_PASS")
  } yield Credentials(
    "Sonatype Nexus Repository Manager",
    "s01.oss.sonatype.org",
    usr,
    password
  )
}.toList

ThisBuild / excludeDependencies ++= Seq(
  ExclusionRule("commons-logging", "commons-logging"),
  ExclusionRule("org.slf4j", "slf4j-log4j12"),
  ExclusionRule("log4j", "log4j"),
  ExclusionRule("org.apache.logging.log4j", "log4j-core"),
  ExclusionRule("org.apache.logging.log4j", "log4j-slf4j-impl")
)

val scalacOptionsSettings = List(
  scalacOptions ++= {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, _)) => List("-Xsource:3")
      case _ => List.empty
    }
  },
  scalacOptions := {
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((3, 6)) =>
        scalacOptions.value.map {
          case "-Ykind-projector" => "-Xkind-projector"
          case other => other
        }
      case _ => scalacOptions.value
    }
  }
)

val sonatypeSettings = List(
  // Setting it on ThisBuild does not have any effect
  sonatypePublishToBundle := {
    if (isSnapshot.value) {
      Some(sonatypeSnapshotResolver.value)
    } else {
      Some(Resolver.file("sonatype-local-bundle", sonatypeBundleDirectory.value))
    }
  }
)

val loggingOverrideDependencies = List(
  "org.slf4j" % "log4j-over-slf4j" % slf4jVersion,
  "org.slf4j" % "jcl-over-slf4j" % slf4jVersion,
  "org.slf4j" % "jul-to-slf4j" % slf4jVersion,
  "org.apache.logging.log4j" % "log4j-to-slf4j" % log4j2Version
)

lazy val it = project
  .in(file("modules/it"))
  .dependsOn(core, dynamodb)
  .settings(
    name := "mnemosyne-it",
    publish / skip := true,
    Test / fork := true,
    Test / javaOptions ++= Seq(
      "-Dlogback.configurationFile=logback-integration-test.xml",
      "-Dsoftware.amazon.awssdk.http.async.service.impl=software.amazon.awssdk.http.nio.netty.NettySdkAsyncHttpService"
    ),
    scalacOptionsSettings,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % munitVersion % Test,
      "org.scalameta" %% "munit-scalacheck" % munitScalacheckVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test,
      "ch.qos.logback" % "logback-classic" % logBackVersion % Test
    ),
    libraryDependencies ++= loggingOverrideDependencies.map(_ % Test)
  )

lazy val core = project
  .in(file("modules/core"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "mnemosyne-core",
    buildInfoPackage := "com.filippodeluca.mnemosyne",
    scalacOptionsSettings,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.typelevel" %% "log4cats-core" % log4CatsVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4CatsVersion, // TODO We should not depend directly on it
      "ch.qos.logback" % "logback-classic" % logBackVersion % Test,
      "org.typelevel" %% "cats-effect-laws" % catsEffectVersion % Test,
      "org.scalameta" %% "munit" % munitVersion % Test,
      "org.scalameta" %% "munit-scalacheck" % munitScalacheckVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test
    ),
    libraryDependencies ++= loggingOverrideDependencies.map(_ % Test)
  )

lazy val dynamodb = project
  .in(file("modules/dynamodb"))
  .dependsOn(core % "compile->compile;test->test")
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "mnemosyne-dynamodb",
    buildInfoPackage := "com.filippodeluca.mnemosyne",
    scalacOptionsSettings,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "software.amazon.awssdk" % "dynamodb" % awsSdkVersion,
      "org.typelevel" %% "log4cats-core" % log4CatsVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4CatsVersion, // TODO We should not depend directly on it
      "ch.qos.logback" % "logback-classic" % logBackVersion % Test,
      "org.typelevel" %% "cats-effect-laws" % catsEffectVersion % Test,
      "org.scalameta" %% "munit" % munitVersion % Test,
      "org.scalameta" %% "munit-scalacheck" % munitScalacheckVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test
    ),
    libraryDependencies ++= loggingOverrideDependencies.map(_ % Test)
  )

lazy val redis = project
  .in(file("modules/redis"))
  .dependsOn(core % "compile->compile;test->test")
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "mnemosyne-redis",
    buildInfoPackage := "com.filippodeluca.mnemosyne",
    scalacOptionsSettings,
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "io.lettuce" % "lettuce-core" % lettuceVersion,
      "org.typelevel" %% "log4cats-core" % log4CatsVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4CatsVersion, // TODO We should not depend directly on it
      "ch.qos.logback" % "logback-classic" % logBackVersion % Test,
      "org.typelevel" %% "cats-effect-laws" % catsEffectVersion % Test,
      "org.scalameta" %% "munit" % munitVersion % Test,
      "org.scalameta" %% "munit-scalacheck" % munitScalacheckVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % munitCatsEffectVersion % Test
    ),
    libraryDependencies ++= loggingOverrideDependencies.map(_ % Test)
  )

lazy val mnemosyne = project
  .in(file("."))
  .aggregate(core, dynamodb, redis, it)
  .settings(
    name := "mnemosyne",
    publishArtifact := false,
    publish / skip := true
  )
