package me.cference.hephaestus.e2e

import org.scalatest.Tag

/**
 * ScalaTest tag marking the opt-in end-to-end tier (add-e2e-integration). Suites/tests carrying
 * this tag boot the published Apollo + Hermes images via testcontainers, so they are heavy and
 * require a Docker daemon. `build.sbt` EXCLUDES this tag from the default `sbt test` (`-l
 * ...e2e.E2E`) unless `-De2e=true` is set — so the fast PR CI never runs them; the dedicated
 * `e2e.yml` workflow does.
 *
 * The tag NAME is the fully-qualified string `me.cference.hephaestus.e2e.E2E`, which is exactly the
 * argument `build.sbt` passes to ScalaTest's `-l` / `-n` filters.
 */
object E2E extends Tag("me.cference.hephaestus.e2e.E2E")
