Thanks for taking the time to submit a contribution to HybridVelocity! Your support
is greatly appreciated.

In this document, we'll give you some tips on making it more likely your
contribution will be pulled.

# Where to contribute

HybridVelocity is a fork of [Velocity](https://github.com/PaperMC/Velocity). If the change
you have in mind is not specific to this fork — a bug that also reproduces on upstream
Velocity, or a general improvement — please send it to
[PaperMC/Velocity](https://github.com/PaperMC/Velocity) instead, so everyone benefits and
this fork picks it up on the next merge. Contributions here should target the fork's own
features, which are documented in [docs/](docs/README.md).

# Setting up a development environment

This isn't as difficult as you may be led to believe. All you need to do is
clone the repository in your favorite IDE and have your backend test
servers set up to run behind the proxy.

# Actually working on the code

It is strongly recommended that you are familiar with the Minecraft protocol,
proficient with using Java, and have familiarity with the libraries used in
Velocity (particularly [Netty](https://netty.io), [Google Guava](https://github.com/google/guava),
and the [Checker Framework annotations](https://checkerframework.org/)).
While you can certainly work with the codebase without knowing any
of this, it can be risky to proceed.

The project follows the [Google Code Style](https://google.github.io/styleguide/javaguide.html)
for Java and will not build if any Checkstyle issues are found, so make
sure that you are properly adhering to the code style. Running
`./gradlew :velocity-proxy:spotlessApply` before committing takes care of the formatting.

Keep changes to code inherited from upstream as narrow as possible: the smaller the diff
against Velocity, the easier it is to merge upstream updates. Add tests for changed
behaviour, next to their package under `*/src/test/java`.

# Notes on the build

To reduce bugs and ensure code quality, we run the following tools on all commits
and pull requests:

* [SpotBugs](https://spotbugs.github.io/): ensures that common errors do not
  get into the codebase. The build will fail if SpotBugs finds an issue.
* [Checkstyle](http://checkstyle.sourceforge.net/): ensures that your code is
  correctly formatted. The build will fail if Checkstyle detects a problem.
