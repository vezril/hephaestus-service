package me.cference.hephaestus.job

/**
 * The two consumption lanes. New uploads (`Ingest`) beat backfills (`Reprocess`): the consumer
 * drains `Ingest` fully before it pulls `Reprocess` (design-artemis-reprocessing).
 */
enum Lane:
  case Ingest, Reprocess

/**
 * The pure lane-priority rule. `Ingest` is always preferred; `Reprocess` is only pulled once the
 * ingest lane has drained (its most recent pull came back empty). After any pull the consumer
 * returns to trying `Ingest` first, so a burst of new uploads immediately preempts a backfill.
 */
object LanePriority:

  /** Which lane to pull next, given whether the ingest lane appeared drained on its last pull. */
  def next(ingestDrained: Boolean): Lane =
    if ingestDrained then Lane.Reprocess else Lane.Ingest
