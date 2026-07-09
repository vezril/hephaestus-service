# documentation Specification

## Purpose

Define the repository's front-page documentation: a README covering what Hephaestus is, its CI/CD badges, an honest AI usage disclaimer, deployment and configuration examples, run/test instructions, and an MIT license.

## Requirements

### Requirement: README with quickstart, config, and AI usage disclaimer

The repository SHALL include a `README.md` covering: what Hephaestus is (the media-worker forge of
the constellation), CI/CD status badges, an **AI Usage Disclaimer** naming the SDLC agent team and
affirming human review, a docker compose deployment example, a configuration table mapping HOCON
keys to environment variables, and run/test instructions.

#### Scenario: Quickstart runs on a fresh clone
- **Given** a fresh clone of the repository
- **When** the documented build/test commands are run verbatim
- **Then** they succeed as written

#### Scenario: Configuration table matches the code
- **Given** the configuration table in the README
- **When** it is compared against the service's config keys
- **Then** every operational key (Hermes/Apollo endpoints, lanes, derivative dimensions, spec
  version, thresholds) appears with its env-var override and default

#### Scenario: Edge case — compose example is self-contained
- **Given** the docker compose snippet copied to an empty directory
- **When** `docker compose up` runs
- **Then** the service reaches `healthy` with no additional files

### Requirement: MIT license

The repository SHALL carry an MIT `LICENSE` with the correct year and holder, referenced from the
sbt `licenses` setting and the README.

#### Scenario: License is present and detected
- **Given** the repository on GitHub
- **When** GitHub scans the repository
- **Then** it auto-detects the MIT license and the sbt build reports the same license
