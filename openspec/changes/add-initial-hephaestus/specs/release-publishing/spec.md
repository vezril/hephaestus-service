# release-publishing — Spec Delta

## ADDED Requirements

### Requirement: Semver release images to Docker Hub

On a `vX.Y.Z` tag whose commit is on `main`, CI SHALL run the test suite, build the image, and push
`calvinference/hephaestus:X.Y.Z` and `:latest` to Docker Hub. The push SHALL be guarded against
overwriting an existing immutable version.

#### Scenario: Tagged release publishes versioned + latest
- **Given** `v0.1.0` is tagged on `main` and tests pass
- **When** the release workflow runs
- **Then** `calvinference/hephaestus:0.1.0` and `:latest` are pushed and share the same digest

#### Scenario: Edge case — failing tests publish nothing
- **Given** a release tag on a commit whose tests fail
- **When** the release workflow runs
- **Then** no image is pushed

#### Scenario: Edge case — re-tagging an existing version is refused
- **Given** `calvinference/hephaestus:0.1.0` already exists
- **When** a release workflow attempts to publish `0.1.0` again
- **Then** the immutability guard fails the job and no overwrite occurs

### Requirement: Development images from the integration branch

On push to `development`, CI SHALL run tests and push `calvinference/hephaestus:dev` and
`:dev-<short-sha>` for experimental homelab deploys, never touching `:latest`.

#### Scenario: Development push publishes dev tags
- **Given** a commit is pushed to `development` and tests pass
- **When** the dev workflow runs
- **Then** `:dev` and `:dev-<short-sha>` are pushed and `:latest` is unchanged

#### Scenario: Edge case — missing registry credentials fail before push
- **Given** `DOCKERHUB_TOKEN` is not configured
- **When** the workflow reaches the login/push step
- **Then** the job fails before any push attempt, with a clear error

#### Scenario: Edge case — fork PRs do not publish
- **Given** a pull request from a fork (no access to secrets)
- **When** CI runs
- **Then** the publish job is skipped (verification still runs)
