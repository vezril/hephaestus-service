package me.cference.hephaestus.config

import com.typesafe.config.{Config, ConfigException}

import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.util.control.NonFatal

/** A configuration failure carrying a message that names the offending key. */
final case class ConfigError(message: String)

/** HTTP bind settings. */
final case class HttpConfig(host: String, port: Int)

/** HermesMQ endpoint + the two media lane names Hephaestus consumes. */
final case class HermesConfig(endpoint: String, ingestLane: String, reprocessLane: String)

/**
 * Apollo object-store gRPC endpoint (`host:port`), the media bucket originals live in / derivatives
 * are written to, and the per-call gRPC deadline. `host`/`port` are parsed from `endpoint` so the
 * client can build `GrpcClientSettings` directly.
 */
final case class ApolloConfig(
    endpoint: String,
    mediaBucket: String,
    deadline: FiniteDuration
):
  /** Host portion of `endpoint` (everything before the last colon). */
  def host: String = ApolloConfig.splitHostPort(endpoint)._1

  /** Port portion of `endpoint` (after the last colon); 0 if `endpoint` carries no valid port. */
  def port: Int = ApolloConfig.splitHostPort(endpoint)._2

object ApolloConfig:
  /** Split `host:port` on the LAST colon (IPv6-tolerant-ish); a missing/blank port yields 0. */
  private[config] def splitHostPort(endpoint: String): (String, Int) =
    endpoint.lastIndexOf(':') match
      case -1 => (endpoint, 0)
      case i =>
        val host = endpoint.substring(0, i)
        val port = endpoint.substring(i + 1).toIntOption.getOrElse(0)
        (host, port)

/** Derivative output dimensions + the stamped spec version. */
final case class DerivativeConfig(thumbnailPx: Int, samplePx: Int, specVersion: String)

/** Processing thresholds. */
final case class ThresholdConfig(sampleMinLongEdgePx: Int)

/**
 * Consumer-loop tuning: messages pulled per batch (small + backpressured), bounded in-flight
 * processing concurrency (CPU-heavy transcodes), and the idle back-off between empty pulls.
 */
final case class ConsumerConfig(
    batchSize: Int,
    concurrency: Int,
    pollInterval: FiniteDuration
)

/** The fully-resolved, validated application configuration. */
final case class AppConfig(
    http: HttpConfig,
    hermes: HermesConfig,
    apollo: ApolloConfig,
    derivatives: DerivativeConfig,
    thresholds: ThresholdConfig,
    consumer: ConsumerConfig
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
        consumer <- consumerConfig(config)
      yield AppConfig(http, hermes, apollo, derived, thresh, consumer)
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
    for
      endpoint <- requiredString(c, s"$Root.apollo.endpoint")
      bucket <- requiredString(c, s"$Root.apollo.media-bucket")
      deadline <- requiredDuration(c, s"$Root.apollo.deadline")
    yield ApolloConfig(endpoint, bucket, deadline)

  private def derivativeConfig(c: Config): Either[ConfigError, DerivativeConfig] =
    for
      thumb <- requiredInt(c, s"$Root.derivatives.thumbnail-px")
      sample <- requiredInt(c, s"$Root.derivatives.sample-px")
      version <- requiredString(c, s"$Root.derivatives.spec-version")
    yield DerivativeConfig(thumb, sample, version)

  private def thresholdConfig(c: Config): Either[ConfigError, ThresholdConfig] =
    requiredInt(c, s"$Root.thresholds.sample-min-long-edge-px").map(ThresholdConfig.apply)

  private def consumerConfig(c: Config): Either[ConfigError, ConsumerConfig] =
    for
      batch <- requiredInt(c, s"$Root.consumer.batch-size")
      concurrency <- requiredInt(c, s"$Root.consumer.concurrency")
      poll <- requiredDuration(c, s"$Root.consumer.poll-interval")
    yield ConsumerConfig(batch, concurrency, poll)

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

  /**
   * Read a required HOCON duration (e.g. `30s`); a missing or malformed value fails naming the key.
   */
  private def requiredDuration(c: Config, key: String): Either[ConfigError, FiniteDuration] =
    if !c.hasPath(key) then Left(ConfigError(s"missing required config key: $key"))
    else
      Try(
        FiniteDuration(c.getDuration(key).toNanos, java.util.concurrent.TimeUnit.NANOSECONDS)
      ).toEither.left
        .map(e => ConfigError(s"config key $key is not a duration: ${e.getMessage}"))
