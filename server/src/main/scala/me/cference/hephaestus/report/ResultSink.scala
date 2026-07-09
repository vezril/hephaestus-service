package me.cference.hephaestus.report

import scala.concurrent.Future

/**
 * The publish capability behind the §4 publisher — a topic + canonical-JSON payload + attributes,
 * out to HermesMQ. Injected as a seam so [[HermesResultPublisher]] is unit-testable with an
 * in-memory fake (capturing the published `(topic, json, attributes)`), and the real HermesMQ glue
 * ([[HermesResultSink]]) stays a thin adapter. A publish failure surfaces as a failed `Future` —
 * the consumer treats that as transient (no ack, redelivery).
 */
trait ResultSink:
  def publish(topic: String, payload: String, attributes: Map[String, String]): Future[Unit]
