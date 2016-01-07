# Coursier

*Pure Scala Artifact Fetching*

A Scala library to fetch dependencies from Maven / Ivy repositories

[![Build Status](https://travis-ci.org/alexarchambault/coursier.svg?branch=master)](https://travis-ci.org/alexarchambault/coursier)
[![Build status (Windows)](https://ci.appveyor.com/api/projects/status/trtum5b7washfbj9?svg=true)](https://ci.appveyor.com/project/alexarchambault/coursier)
[![Join the chat at https://gitter.im/alexarchambault/coursier](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/alexarchambault/coursier?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.alexarchambault/coursier_2.11.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.alexarchambault/coursier_2.11)

*coursier* is a dependency resolver / fetcher *à la* Maven / Ivy, entirely
rewritten from scratch in Scala. It aims at being fast and easy to embed
in other contexts. Its very core (`core` module) aims at being
extremely pure, and only requires to be fed external data (Ivy / Maven metadata) via a monad.

The `cache` module handles caching of the metadata and artifacts themselves,
and is less so pure than the `core` module, in the sense that it happily
does IO as a side-effect (always wrapped in `Task`, and naturally favoring immutability for all
that's kept in memory).

It handles fancy Maven features like
* [POM inheritance](http://books.sonatype.com/mvnref-book/reference/pom-relationships-sect-project-relationships.html#pom-relationships-sect-project-inheritance),
* [dependency management](http://books.sonatype.com/mvnex-book/reference/optimizing-sect-dependencies.html),
* [import scope](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Importing_Dependencies),
* [properties](http://books.sonatype.com/mvnref-book/reference/resource-filtering-sect-properties.html),
* etc.

and is able to fetch metadata and artifacts from both Maven and Ivy repositories.

Compared to the default dependency resolution of SBT, it adds:
* downloading of artifacts in parallel,
* better offline mode - one can safely work with snapshot dependencies if these are in cache (SBT tends to try to fail if it cannot check for updates),
* non obfuscated cache (cache structure just mimicks the URL it caches).

From the command-line, it also has:
* a [launcher](#launch), able to launch apps distributed via Maven / Ivy repositories,
* a [bootstrap](#bootstrap) generator, able to generate stripped launchers of these apps.

Lastly, it can be used programmatically via its [API](#api) and has a Scala JS [demo](#scala-js-demo).

## Table of content

1. [Quick start](#quick-start)
2. [Why](#why)
3. [Usage](#usage)
  1. [SBT plugin](#sbt-plugin)
  2. [Command-line](#command-line)
  3. [API](#api)
  4. [Scala JS demo](#scala-js-demo)
4. [Contributors](#contributors)
5. [Projects using coursier](#projects-using-coursier)

## Quick start

The default global cache used by coursier is `~/.coursier/cache/v1`. E.g. the artifact at
`https://repo1.maven.org/maven2/org/scala-lang/scala-library/2.11.7/scala-library-2.11.7.jar`
will land in `~/.coursier/cache/v1/https/repo1.maven.org/maven2/org/scala-lang/scala-library/2.11.7/scala-library-2.11.7.jar`.

From the SBT plugin, the default repositories are the ones provided by SBT (typically Central or JFrog, and `~/.ivy2/local`).
From the CLI tools, these are Central (`https://repo1.maven.org/maven2`) and `~/.ivy2/local`.
From the API, these are specified manually - you are encouraged to use those too.

* SBT plugin

Enable the SBT plugin by adding
```scala
addSbtPlugin("com.github.alexarchambault" % "coursier-sbt-plugin" % "1.0.0-M2")
```
to `~/.sbt/0.13/plugins/build.sbt` (enables it globally), or to the `project/plugins.sbt` file
of a SBT project. Tested with SBT 0.13.8 / 0.13.9.


* CLI

Download and run its laucher with
```
$ curl -L -o coursier https://git.io/vEpQR && chmod +x coursier && ./coursier --help
```

Run an application distributed via artifacts with
```
$ ./coursier launch com.lihaoyi:ammonite-repl_2.11.7:0.5.2
```

Download and list the classpath of one or several dependencies with
```
$ ./coursier fetch org.apache.spark:spark-sql_2.11:1.5.2 com.twitter:algebird-spark_2.11:0.11.0
Dependencies:
  org.apache.spark:spark-sql_2.11:1.5.2
  com.twitter:algebird-spark_2.11:0.11.0
Fetching artifacts
/path/to/.coursier/cache/v1/https/repo1.maven.org/maven2/com/sun/jersey/jersey-client/1.9/jersey-client-1.9.jar
/path/to/.coursier/cache/v1/https/repo1.maven.org/maven2/net/jpountz/lz4/lz4/1.3.0/lz4-1.3.0.jar
/path/to/.coursier/cache/v1/https/repo1.maven.org/maven2/com/clearspring/analytics/stream/2.7.0/stream-2.7.0.jar
/path/to/.coursier/cache/v1/https/repo1.maven.org/maven2/com/typesafe/config/1.2.1/config-1.2.1.jar
...
```

* API

Add to your `build.sbt`
```scala
libraryDependencies ++= Seq(
  "com.github.alexarchambault" %% "coursier" % "1.0.0-M2",
  "com.github.alexarchambault" %% "coursier-cache" % "1.0.0-M2"
)
```

Add an import for coursier,
```scala
import coursier._
```

To resolve dependencies, first create a `Resolution` case class with your dependencies in it,
```scala
val start = Resolution(
  Set(
    Dependency(
      Module("org.scalaz", "scalaz-core_2.11"), "7.2.0"
    ),
    Dependency(
      Module("org.spire-math", "cats-core_2.11"), "0.3.0"
    )
  )
)
```

Create a fetch function able to get things from a few repositories via a local cache,
```scala
val repositories = Seq(
  Cache.ivy2Local,
  MavenRepository("https://repo1.maven.org/maven2")
)

val fetch = Fetch.from(repositories, Cache.fetch())
```

Then run the resolution per-se,
```scala
val resolution = start.process.run(fetch).run
```
That will fetch and use metadata.

Check for errors in
```scala
val errors: Seq[(Dependency, Seq[String])] = resolution.errors
```
These would mean that the resolution wasn't able to get metadata about some dependencies.

Then fetch and get local copies of the artifacts themselves (the JARs) with
```scala
import java.io.File
import scalaz.\/
import scalaz.concurrent.Task

val localArtifacts: Seq[FileError \/ File] = Task.gatherUnordered(
  resolution.artifacts.map(Cache.file(_).run)
).run
```


## Why

The current state of dependency management in Scala suffers several flaws, that prevent applications to fully
profit from and rely on dependency management. Coursier aims at addressing these by making it easy to:
- resolve / download dependencies programmatically,
- launch applications distributed via Maven / Ivy artifacts from the command-line,
- work offline with artifacts,
- sandbox dependency management between projects.

As its [API](#api) illustrates, getting artifacts of dependencies is just a matter of specifying these along
with a few repositories. You can then straightforwardly get the corresponding artifacts, easily getting
precise feedback about what goes on during the resolution.

Launching an application distributed via Maven artifacts is just a command away with the [launcher](#command-line) of coursier.
In most cases, just specifying the corresponding main dependency is enough to launch the corresponding application.

If all your dependencies are in cache, chances are coursier will not even try to connect to remote repositories. This
also applies to snapshot dependencies of course - these are only updated on demand, not getting constantly in your way
like is currently the case by default with SBT.

When using coursier from the command-line or via its SBT plugin, sandboxing is just one command away. Just do
`export COURSIER_CACHE="$(pwd)/.coursier-cache"`, and the cache will become `.coursier-cache` from the current
directory instead of the default global `~/.coursier/cache/v1`. This allows for example to quickly inspect the content
of the cache used by a particular project, in case you have any doubt about what's in it.

## Usage

### SBT plugin

Enable the SBT plugin globally by adding
```scala
addSbtPlugin("com.github.alexarchambault" % "coursier-sbt-plugin" % "1.0.0-M2")
```
to `~/.sbt/0.13/plugins/build.sbt`

To enable it on a per-project basis, add it only to the `project/plugins.sbt` of a SBT project.
The SBT plugin has been tested only with SBT 0.13.8 / 0.13.9.

Once enabled, the `update`, `updateClassifiers`, and `updateSbtClassifiers` commands are taken care of by coursier. These
provide more output about what's going on than their default implementations do.





### Command-line

Download and run its laucher with
```
$ curl -L -o coursier https://git.io/vEpQR && chmod +x coursier && ./coursier --help
```

The launcher itself weights only 8 kB and can be easily embedded as is in other projects.
It downloads the artifacts required to launch coursier on the first run.

```
$ ./coursier --help
```
lists the available coursier commands. The most notable ones are `launch`, and `fetch`. Type
```
$ ./coursier command --help
```
to get a description of the various options the command `command` (replace with one
of the above command) accepts.

Both command belows can be given repositories with the `-r` or `--repository` option, like
```
-r central
-r https://oss.sonatype.org/content/repositories/snapshots
-r "ivy:https://repo.typesafe.com/typesafe/ivy-releases/[organisation]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]"
```

`central` and `ivy2local` correspond to Maven Central and `~/.ivy2/local`. These are used by default
if no `-r` or `--repository` option is specified.
As soon as a `-r` or `--repository` option is specified, these default are not used any more - only the
specified repositories are used.

Repositories starting with `ivy:` are assumed to be Ivy repositories, specified with an Ivy pattern. Else,
a Maven repository is assumed.

#### launch

The `launch` command fetches a set of Maven coordinates it is given, along
with their transitive dependencies, then launches the "main `main` class" from
it if it can find one (typically from the manifest of the first coordinates).
The main class to launch can also be manually specified with the `-M` option.

For example, it can launch:

* [Ammonite](https://github.com/lihaoyi/Ammonite) (enhanced Scala REPL),
```
$ ./coursier launch com.lihaoyi:ammonite-repl_2.11.7:0.5.2
```

along with the REPLs of various JVM languages like

* Frege,
```
$ ./coursier launch -r central -r https://oss.sonatype.org/content/groups/public \
    org.frege-lang:frege-repl-core:1.3 -M frege.repl.FregeRepl
```

* clojure,
```
$ ./coursier launch org.clojure:clojure:1.7.0 -M clojure.main
```

* jruby,
```
$ wget https://raw.githubusercontent.com/jruby/jruby/master/bin/jirb && \
  ./coursier launch org.jruby:jruby:9.0.4.0 -M org.jruby.Main -- -- jirb
```

* jython,
```
$ ./coursier launch org.python:jython-standalone:2.7.0 -M org.python.util.jython
```

* Groovy,
```
$ ./coursier launch org.codehaus.groovy:groovy-groovysh:2.4.5 -M org.codehaus.groovy.tools.shell.Main \
    commons-cli:commons-cli:1.3.1
```

etc.

and various programs, like

* Proguard and its utility Retrace,
```
$ ./coursier launch net.sf.proguard:proguard-base:5.2.1 -M proguard.ProGuard
$ ./coursier launch net.sf.proguard:proguard-retrace:5.2.1 -M proguard.retrace.ReTrace
```

#### fetch

The `fetch` command simply fetches a set of dependencies, along with their
transitive dependencies, then prints the local paths of all their artifacts.

Example
```
$ ./coursier fetch org.apache.spark:spark-sql_2.11:1.5.2
...
/path/to/.coursier/cache/0.1.0-SNAPSHOT-2f5e731/files/central/io/dropwizard/metrics/metrics-jvm/3.1.2/metrics-jvm-3.1.2.jar
/path/to/.coursier/cache/0.1.0-SNAPSHOT-2f5e731/files/central/javax/servlet/javax.servlet-api/3.0.1/javax.servlet-api-3.0.1.jar
/path/to/.coursier/cache/0.1.0-SNAPSHOT-2f5e731/files/central/javax/inject/javax.inject/1/javax.inject-1.jar
...
```

By adding the `-p` option, these paths can be handed over directly to
`java -cp`, like
```
$ java -cp "$(./coursier fetch -p com.lihaoyi:ammonite-repl_2.11.7:0.5.2)" ammonite.repl.Main
Loading...
Welcome to the Ammonite Repl 0.5.2
(Scala 2.11.7 Java 1.8.0_51)
@
```





### bootstrap

The `bootstrap` generates tiny bootstrap launchers, able to pull their dependencies from
repositories on first launch. For example, the launcher of coursier is [generated](https://github.com/alexarchambault/coursier/blob/master/project/generate-launcher.sh) with a command like
```
$ ./coursier bootstrap \
    com.github.alexarchambault:coursier-cli_2.11:1.0.0-M2 \
    -b -f -o coursier \
    -M coursier.cli.Coursier
```

See `./coursier bootstrap --help` for a list of the available options.

### API

Add to your `build.sbt`
```scala
libraryDependencies ++= Seq(
  "com.github.alexarchambault" %% "coursier" % "1.0.0-M2",
  "com.github.alexarchambault" %% "coursier-cache" % "1.0.0-M2"
)
```

The first module, `"com.github.alexarchambault" %% "coursier" % "1.0.0-M2"`, mainly depends on
`scalaz-core` (and only it, *not* `scalaz-concurrent` for example). It contains among others,
definitions,
mainly in [`Definitions.scala`](https://github.com/alexarchambault/coursier/blob/master/core/shared/src/main/scala/coursier/core/Definitions.scala),
[`Resolution`](https://github.com/alexarchambault/coursier/blob/master/core/shared/src/main/scala/coursier/core/Resolution.scala), representing a particular state of the resolution,
and [`ResolutionProcess`](https://github.com/alexarchambault/coursier/blob/master/core/shared/src/main/scala/coursier/core/ResolutionProcess.scala),
that expects to be given metadata, wrapped in any `Monad`, then feeds these to `Resolution`, and at the end gives
you the final `Resolution`, wrapped in the same `Monad` it was given input. This final `Resolution` has all the dependencies,
including the transitive ones.

The second module, `"com.github.alexarchambault" %% "coursier-cache" % "1.0.0-M2"`, is precisely in charge of fetching
these input metadata. It uses `scalaz.concurrent.Task` as a `Monad` to wrap them. It also fetches artifacts (JARs, etc.).
It caches all of these (metadata and artifacts) on disk, and validates checksums too.

In the code below, we'll assume some imports are around,
```scala
import coursier._
```

Resolving dependencies involves create an initial resolution state, with all the initial dependencies in it, like
```scala
val start = Resolution(
  Set(
    Dependency(
      Module("org.spire-math", "cats-core_2.11"), "0.3.0"
    ),
    Dependency(
      Module("org.scalaz", "scalaz-core_2.11"), "7.2.0"
    )
  )
)
```

It goes without saying that a `Resolution` is immutable, as are all the classes defined in the core module.
The resolution process will go on by giving successive `Resolution`s, until the final one.

`start` above is only the initial state - it is far from over, as the `isDone` method on it tells,
```scala
scala> start.isDone
res4: Boolean = false
```




In order for the resolution to go on, we'll need things from a few repositories,
```scala
scala> val repositories = Seq(
     |   Cache.ivy2Local,
     |   MavenRepository("https://repo1.maven.org/maven2")
     | )
repositories: Seq[coursier.core.Repository] = List(IvyRepository(file:/Users/alexandre/.ivy2/local/[organisation]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext],None,Map(),true,true,true), MavenRepository(https://repo1.maven.org/maven2,None,false))
```
The first one, `Cache.ivy2Local`, is defined in `coursier.Cache`, itself from the `coursier-cache` module that
we added above. As we can see, it is an `IvyRepository`, picking things under `~/.ivy2/local`. An `IvyRepository`
is related to the [Ivy](http://ant.apache.org/ivy/) build tool. This kind of repository involves a so-called [pattern](http://ant.apache.org/ivy/history/2.4.0/concept.html#patterns), with
various properties. These are not of very common use in Scala, although SBT uses them a bit.

The second repository in a `MavenRepository`. These are simpler than the Ivy repositories. They're the ones
we're the most used to in Scala. Common ones like [Central](https://repo1.maven.org/maven2) like here, or the repositories
from [Sonatype](https://oss.sonatype.org/content/repositories/), are Maven repositories. These originate
from the [Maven](https://maven.apache.org/) build tool. Unlike the Ivy repositories which involve customisable patterns to point
to the underlying metadata and artifacts, the paths of these for Maven repositories all look alike,
like for any particular version of the standard library, under paths like
[this one](http://repo1.maven.org/maven2/org/scala-lang/scala-library/2.11.7/).

Both `IvyRepository` and `MavenRepository` are case classes, so that it's straightforward to specify one's own
repositories.

Now that we have repositories, we're going to mix these with things from the `coursier-cache` module,
for resolution to happen via the cache. We'll create a function
of type `Seq[(Module, String)] => F[Seq[((Module, String), Seq[String] \/ (Artifact.Source, Project))]]`.
Given a sequence of dependencies, designated by their `Module` (organisation and name in most cases)
and version (just a `String`), it gives either errors (`Seq[String]`) or metadata (`(Artifact.Source, Project)`),
wrapping the whole in a monad `F`.
```scala
val fetch = Fetch.from(repositories, Cache.fetch())
```

The monad used by `Fetch.from` is `scalaz.concurrent.Task`, but the resolution process is not tied to a particular
monad - any stack-safe monad would do.

With this `fetch` method, we can now go on with the resolution. Calling `process` on `start` above gives a
[`ResolutionProcess`](https://github.com/alexarchambault/coursier/blob/master/core/shared/src/main/scala/coursier/core/ResolutionProcess.scala),
that drives the resolution. It is loosely inspired by the `Process` of scalaz-stream.
It is an immutable structure, that represents the various states the resolution process can be in.

Its method `current` gives the current `Resolution`. Calling `isDone` on the latter says whether the
resolution is done or not.

The `next` method, that expects a `fetch` method like the one above, gives
the "next" state of the resolution process, wrapped in the monad of the `fetch` method. It allows to do
one resolution step.

Lastly, the `run` method runs the whole resolution until its end. It expects a `fetch` method too,
and will make at most `maxIterations` steps (50 by default), and return the "final" resolution state,
wrapped in the monad of `fetch`. One should check that the `Resolution` it returns is done (`isDone`) -
the contrary means that `maxIterations` were reached, likely signaling an issue, unless the underlying
resolution is particularly complex, in which case `maxIterations` could be increased.

Let's run the whole resolution,
```scala
val resolution = start.process.run(fetch).run
```

To get additional feedback during the resolution, we can give the `Cache.default` method above
a [`Cache.Logger`](https://github.com/alexarchambault/coursier/blob/cf269c6895e19f2d590f08811406724304332950/cache/src/main/scala/coursier/Cache.scala#L484-L490).

By default, downloads happen in a global fixed thread pool (with 6 threads, allowing for 6 parallel downloads), but
you can supply your own thread pool to `Cache.default`.

Now that the resolution is done, we can check for errors in
```scala
val errors: Seq[(Dependency, Seq[String])] = resolution.errors
```
These would mean that the resolution wasn't able to get metadata about some dependencies.

We can also check for version conflicts, in
```scala
val conflicts: Set[Dependency] = resolution.conflicts
```
which are dependencies whose versions could not be unified.

Then, if all went well, we can fetch and get local copies of the artifacts themselves (the JARs) with
```scala
import java.io.File
import scalaz.\/
import scalaz.concurrent.Task

val localArtifacts: Seq[FileError \/ File] = Task.gatherUnordered(
  resolution.artifacts.map(Cache.file(_).run)
).run
```

We're using the `Cache.file` method, that can also be given a `Logger` (for more feedback) and a custom thread pool.


### Scala JS demo

*coursier* is also compiled to Scala JS, and can be tested in the browser via its
[demo](http://alexarchambault.github.io/coursier/#demo).

## Contributors

- Your name here :-)

Don't hesitate to pick an issue to contribute, and / or ask for help for how to proceed
on the [Gitter channel](https://gitter.im/alexarchambault/coursier).

## Projects using coursier

- [Lars Hupel](https://github.com/larsrh/)'s [libisabelle](https://github.com/larsrh/libisabelle) fetches
some of its requirements via coursier,
- [jupyter-scala](https://github.com/alexarchambault/jupyter-scala) should soon allow
to add dependencies in its sessions with coursier (initial motivation for writing coursier),
- Your project here :-)


Released under the Apache license, v2.