package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.data.NonEmptyChain
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchSink
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{metricsMapping, metricsSettings}
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.event.EventStreaming
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EnvelopeStream
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.AsJson
import ch.epfl.bluebrain.nexus.delta.sourcing.{MultiDecoder, Predicate}
import monix.bio.Task

trait EventMetricsProjection

object EventMetricsProjection {
  val projectionMetadata: ProjectionMetadata  = ProjectionMetadata("system", "event-metrics", None, None)
  val eventMetricsIndex: String => IndexLabel = prefix => IndexLabel.unsafe(s"${prefix}_project_metrics")

  /**
    * @param client
    *   the elastic search client
    * @param index
    *   the index to be created at initialization
    * @return
    *   creates the metrics index using the client
    */
  def initMetricsIndex(client: ElasticSearchClient, index: IndexLabel): Task[Unit] =
    for {
      mappings <- metricsMapping
      settings <- metricsSettings
      _        <- client.createIndex(index, Some(mappings), Some(settings))
    } yield ()

  /**
    * @param metricEncoders
    *   a set of encoders for all entity
    * @param supervisor
    *   the supervisor which will supervise the projection
    * @param client
    *   the elasticsearch client
    * @param xas
    *   doobie transactors
    * @param batchConfig
    *   Elasticsearch batch config
    * @param queryConfig
    *   query config for fetching scoped events
    * @param indexPrefix
    *   the prefix to use for the index name
    * @return
    *   a Task that registers a projection with the supervisor which reads all scoped events and pushes their metrics to
    *   Elasticsearch. Events of implementations of ScopedEvents that do not have an instance of
    *   ScopedEventMetricEncoder are silently ignored.
    */
  def apply(
      metricEncoders: Set[ScopedEventMetricEncoder[_]],
      supervisor: Supervisor,
      client: ElasticSearchClient,
      xas: Transactors,
      batchConfig: BatchConfig,
      queryConfig: QueryConfig,
      indexPrefix: String
  ): Task[EventMetricsProjection] = {
    val allEntityTypes = metricEncoders.map(_.entityType).toList

    implicit val multiDecoder: MultiDecoder[ProjectScopedMetric] =
      MultiDecoder(metricEncoders.map { encoder => encoder.entityType -> encoder.toMetric }.toMap)

    // define how to get metrics from a given offset
    val metrics                                                  = (offset: Offset) =>
      EventStreaming.fetchScoped(Predicate.root, allEntityTypes, offset, queryConfig, xas)

    val index = eventMetricsIndex(indexPrefix)

    val sink =
      ElasticSearchSink.events(client, batchConfig.maxElements, batchConfig.maxInterval, index, Refresh.False)

    apply(sink, supervisor, metrics, initMetricsIndex(client, index))
  }

  /**
    * Test friendly apply method
    */
  def apply(
      sink: Sink,
      supervisor: Supervisor,
      metrics: Offset => EnvelopeStream[ProjectScopedMetric],
      init: Task[Unit]
  ): Task[EventMetricsProjection] = {

    val source = Source { (offset: Offset) =>
      metrics(offset).map { e => e.toElem { m => Some(m.project) } }
    }

    val compiledProjection =
      CompiledProjection.compile(
        projectionMetadata,
        ExecutionStrategy.PersistentSingleNode,
        source,
        NonEmptyChain(AsJson.pipe[ProjectScopedMetric]),
        sink
      )

    for {
      projection <- Task.fromEither(compiledProjection)
      _          <- supervisor.run(projection, init)
    } yield new EventMetricsProjection {}
  }

}
