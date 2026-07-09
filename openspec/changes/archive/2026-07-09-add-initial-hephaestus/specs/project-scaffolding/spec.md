# project-scaffolding — Spec Delta

## ADDED Requirements

### Requirement: Two-module sbt build (pure core, Pekko server)

The project SHALL be an sbt multi-module build with `core` (pure media/domain logic, **zero Pekko
dependencies**, exhaustively unit-tested) and `server` (Pekko runtime + Main + Docker image),
Scala 3.3 LTS, with `-Werror`/`-Wunused` strictness and scalafmt + scalafix configured.

#### Scenario: Modules compile independently
- **Given** a fresh checkout
- **When** `sbt core/compile server/compile` runs
- **Then** both modules compile and `core` has no Pekko artifacts on its classpath

#### Scenario: Edge case — strict compiler rejects unused code
- **Given** an unused import or binding is introduced
- **When** the build compiles under `-Werror -Wunused:all`
- **Then** compilation fails (warnings are errors)

### Requirement: Semantic versioning via git tags

The project SHALL derive its version exclusively from git tags following `vMAJOR.MINOR.PATCH`
(sbt-dynver); no version literal is committed to source, and the dynver separator SHALL be
Docker-tag-safe (`-`, not `+`).

#### Scenario: Version derived from a release tag
- **Given** the repository HEAD is tagged `v1.2.3`
- **When** the build computes the project version
- **Then** the version is `1.2.3`

#### Scenario: Untagged commit yields a distinguishable snapshot version
- **Given** HEAD is 2 commits after tag `v1.2.3`
- **When** the build computes the project version
- **Then** the version contains `1.2.3`, the commit distance/sha, and is marked as a snapshot

#### Scenario: Edge case — malformed tag is ignored by release tooling
- **Given** a tag `release-1.2` (non-semver) is pushed
- **When** the release workflow's tag filter evaluates it
- **Then** no release build is triggered and no artifact is published

#### Scenario: Edge case — tag on a non-main commit does not release
- **Given** a tag `v9.9.9` is pushed on a commit that is not on `main`
- **When** the release workflow validates the tag's branch ancestry
- **Then** the workflow fails with an explicit error and publishes nothing

### Requirement: Two-branch strategy with protected main

The repository SHALL maintain `main` (latest stable release, protected, PR-only) and `development`
(integration branch); feature branches SHALL target `development`, and `main` SHALL only receive
merges from `development`.

#### Scenario: Feature flow
- **Given** a feature branch `feature/health-endpoint`
- **When** the work is complete
- **Then** it is merged into `development` via a pull request with passing checks

#### Scenario: Release flow
- **Given** `development` contains changes ready for release
- **When** a release PR from `development` to `main` is merged and `vX.Y.Z` is tagged
- **Then** the release workflow builds and publishes that version

### Requirement: CI verifies formatting, compilation, and tests

A GitHub Actions PR-verification workflow SHALL run scalafmt check, compile, and the test suite on
pull requests targeting `development` and `main`, and SHALL block merge on any failure.

#### Scenario: Failing test blocks merge
- **Given** a pull request whose branch has a failing test
- **When** the PR-verification workflow runs
- **Then** the check is red and the PR cannot be merged

#### Scenario: Edge case — formatting violation fails independently
- **Given** a branch whose tests pass but contains a scalafmt violation
- **When** the workflow runs the format check
- **Then** the format check fails on its own (independent of the test result)
