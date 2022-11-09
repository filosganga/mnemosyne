val catsVersion = "2.6.1"
val catsEffectVersion = "3.1.1"
val slf4jVersion = "1.7.30"
val scalaJava8CompatVersion = "0.9.1"
val awsSdkVersion = "2.16.75"
val log4CatsVersion = "2.1.1"
val munitVersion = "0.7.26"
val logBackVersion = "1.2.3"

ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.6.0"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

lazy val deduplication = (project in file("."))
  .enablePlugins(BuildInfoPlugin)
  .configs(IntegrationTest)
  .settings(inConfig(IntegrationTest)(Defaults.testSettings))
  .settings(
    IntegrationTest / fork := true,
    IntegrationTest / javaOptions ++= Seq(
      "-Dlogback.configurationFile=logback-it.xml",
      "-Dsoftware.amazon.awssdk.http.async.service.impl=software.amazon.awssdk.http.nio.netty.NettySdkAsyncHttpService"
    )
  )
  .settings(
    organization := "com.kaluza.mnemosyne",
    organizationHomepage := Some(url("http://www.kaluza.com")),
    licenses := Seq(("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))),
    scalaVersion := "2.13.10",
    crossScalaVersions += "2.12.12",
    scalafmtOnCompile := true,
    scalacOptions -= "-Xfatal-warnings", // enable all options from sbt-tpolecat except fatal warnings
    initialCommands := s"import com.kaluza.mnemosyne._",
    javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation"),
    addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.13.0" cross CrossVersion.full),
    publishTo := Some("Artifactory Realm" at "https://kaluza.jfrog.io/artifactory/maven-private/"),
    credentials += {
      for {
        usr <- sys.env.get("ARTIFACTORY_USER")
        password <- sys.env.get("ARTIFACTORY_PASS")
      } yield Credentials("Artifactory Realm", "kaluza.jfrog.io", usr, password)
    }.getOrElse(Credentials(Path.userHome / ".ivy2" / ".credentials")),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/ovotech/comms-deduplication"),
        "git@github.com:ovotech/comms-deduplication.git"
      )
    ),
    developers := List(
      Developer(
        "filosganga",
        "Filippo De Luca",
        "filippo.deluca@ovoenergy.com",
        url("https://github.com/filosganga")
      ),
      Developer(
        "SystemFw",
        "Fabio Labella",
        "fabio.labella@ovoenergy.com",
        url("https://github.com/SystemFw")
      )
    ),
    excludeDependencies ++= Seq(
      ExclusionRule("commons-logging", "commons-logging")
    ),
    name := "mnemosyne",
    buildInfoPackage := "com.kaluza.mnemosyne",
    version ~= (_.replace('+', '-')),
    dynver ~= (_.replace('+', '-')),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.scala-lang.modules" %% "scala-java8-compat" % scalaJava8CompatVersion,
      "software.amazon.awssdk" % "dynamodb" % awsSdkVersion,
      "org.typelevel" %% "log4cats-core" % log4CatsVersion,
      "org.typelevel" %% "log4cats-slf4j" % log4CatsVersion,
      "org.slf4j" % "slf4j-api" % slf4jVersion,
      "org.typelevel" %% "cats-effect-laws" % catsEffectVersion % Test,
      "org.slf4j" % "jcl-over-slf4j" % slf4jVersion % IntegrationTest,
      "org.scalameta" %% "munit" % munitVersion % s"${Test};${IntegrationTest}",
      "org.scalameta" %% "munit-scalacheck" % munitVersion % s"${Test};${IntegrationTest}",
      "ch.qos.logback" % "logback-classic" % logBackVersion % s"${Test};${IntegrationTest}"
    )
  )
