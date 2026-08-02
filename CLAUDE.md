# Repository Guidelines

## Project Structure & Module Organization

Velocity is a multi-module Gradle project targeting Java 25. `api/` contains the public API, `proxy/` contains the proxy implementation and protocol handling, and `native/` holds native integrations. Shared Gradle convention plugins live in `build-logic/`; Checkstyle configuration is in `config/checkstyle/`. Production and test code follow the conventional layout: `*/src/main/java` and `*/src/test/java`. The distributable shaded JAR is produced under `proxy/build/libs/`.

## Build, Test, and Development Commands

Use the Gradle wrapper; CI uses it as well.

- `./gradlew build` (Windows: `./gradlew.bat build`) compiles every module, runs formatting/style checks, tests, and creates distributions.
- `./gradlew :velocity-proxy:test` runs proxy tests only. Target one test with `--tests com.velocitypowered.proxy.connection.client.ExampleTest`.
- `./gradlew :velocity-proxy:spotlessApply` applies the repository's Java formatting before committing.
- `./gradlew :velocity-proxy:runShadow` runs the assembled proxy locally; configure its generated runtime files in `proxy/run/`.

## Coding Style & Naming Conventions

Use Java with the Google Java Style conventions enforced by Checkstyle and Spotless. Use two-space indentation, braces on the same line, and keep imports sorted/unused imports removed. Name classes in `UpperCamelCase`, methods and fields in `lowerCamelCase`, and constants in `UPPER_SNAKE_CASE`. Keep protocol, connection, and API changes narrowly scoped; preserve nullness annotations and asynchronous event-loop behavior.

## Testing Guidelines

Tests use JUnit Jupiter; Mockito is available in the proxy module. Put tests next to their package, such as `proxy/src/test/java/com/velocitypowered/proxy/connection/client/`. Name tests `*Test` and test methods by behavior (for example, `hybridOfflineProfileUsesDottedNameForUuid`). Add focused tests for changed behavior, then run the relevant module test task and the full `build` before opening a PR.

## Commit & Pull Request Guidelines

Recent history uses concise imperative subjects, optionally with a scope or issue reference: `Add provides API (#1853)` or `feat: Make version clickable...`. Keep each commit cohesive. PRs should explain the behavioral change, note configuration or protocol impact, link the issue when applicable, and include tests performed. Provide screenshots only for user-facing visual changes.
