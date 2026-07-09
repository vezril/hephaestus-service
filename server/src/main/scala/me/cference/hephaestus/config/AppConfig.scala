package me.cference.hephaestus.config

import com.typesafe.config.{Config, ConfigException}

import scala.util.Try
import scala.util.control.NonFatal

/** A configuration failure carrying a message that names the offending key. */
final case class ConfigError(message: String)

/** HTTP bind settings. */
final case class HttpConfig(host: String, port: Int)

/** HermesMQ endpoint + the two media lane names Hephaestus consumes. */
final case class HermesConfig(endpoint: String, ingestLane: String, reprocessLane: String)

/** Apollo object-store endpoint. */
final case class ApolloConfig(endpoint: String)

/** Derivative output dimensions + the stamped spec version. */
final case class DerivativeConfig(thumbnailPx: Int, samplePx: Int, specVersion: String)

/** Processing thresholds. */
final case class ThresholdConfig(sampleMinLongEdgePx: Int)

/** The fully-resolved, validated application configuration. */
final case class AppConfig(
    http: HttpConfig,
    hermes: HermesConfig,
    apollo: ApolloConfig,
    derivatives: DerivativeConfig,
    thresholds: ThresholdConfig
)

/**
 * Pure config loader: `Config => Either[ConfigError, AppConfig]`. Validation is total — a missing
 * or blank required key yields `Left(ConfigError(..))` naming the key rather than throwing, so it
 * is unit-testable and Main can fail fast with a clear message. Env overrides are expressed as
 * `${?ENV_VAR}` substitutions in application.conf; this loader only reads the resolved values.
 */
object AppConfig:

  private val Root = "hephaestus"

  def load(config: Config): Either[ConfigError, AppConfig] =
    try
      for
        http <- httpConfig(config)
        hermes <- hermesConfig(config)
        apollo <- apolloConfig(config)
        derived <- derivativeConfig(config)
        thresh <- thresholdConfig(config)
      yield AppConfig(http, hermes, apollo, derived, thresh)
    catch
      case e: ConfigException => Left(ConfigError(e.getMessage))
      case NonFatal(e) => Left(ConfigError(e.getMessage))

  private def httpConfig(c: Config): Either[ConfigError, HttpConfig] =
    for
      host <- requiredString(c, s"$Root.http.host")
      port <- requiredInt(c, s"$Root.http.port")
    yield HttpConfig(host, port)

  private def hermesConfig(c: Config): Either[ConfigError, HermesConfig] =
    for
      endpoint <- requiredString(c, s"$Root.hermes.endpoint")
      ingest <- requiredString(c, s"$Root.hermes.ingest-lane")
      reprocess <- requiredString(c, s"$Root.hermes.reprocess-lane")
    yield HermesConfig(endpoint, ingest, reprocess)

  private def apolloConfig(c: Config): Either[ConfigError, ApolloConfig] =
    requiredString(c, s"$Root.apollo.endpoint").map(ApolloConfig.apply)

  private def derivativeConfig(c: Config): Either[ConfigError, DerivativeConfig] =
    for
      thumb <- requiredInt(c, s"$Root.derivatives.thumbnail-px")
      sample <- requiredInt(c, s"$Root.derivatives.sample-px")
      version <- requiredString(c, s"$Root.derivatives.spec-version")
    yield DerivativeConfig(thumb, sample, version)

  private def thresholdConfig(c: Config): Either[ConfigError, ThresholdConfig] =
    requiredInt(c, s"$Root.thresholds.sample-min-long-edge-px").map(ThresholdConfig.apply)

  /** Read a required string; a missing, null, or blank value fails naming the key. */
  private def requiredString(c: Config, key: String): Either[ConfigError, String] =
    if !c.hasPath(key) then Left(ConfigError(s"missing required config key: $key"))
    else
      val value = c.getString(key)
      if value.trim.isEmpty then Left(ConfigError(s"required config key is blank: $key"))
      else Right(value)

  /**
   * Read a required int; a missing OR malformed value fails naming the key (symmetric with
   * requiredString — the parse error is surfaced, not swallowed by the outer catch).
   */
  private def requiredInt(c: Config, key: String): Either[ConfigError, Int] =
    if !c.hasPath(key) then Left(ConfigError(s"missing required config key: $key"))
    else
      Try(c.getInt(key)).toEither.left.map(e =>
        ConfigError(s"config key $key is not an int: ${e.getMessage}")
      )
