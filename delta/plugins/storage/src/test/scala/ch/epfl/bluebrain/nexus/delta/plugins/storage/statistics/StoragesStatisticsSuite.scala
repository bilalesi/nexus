package ch.epfl.bluebrain.nexus.delta.plugins.storage.statistics

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.EventMetricsProjection.{eventMetricsIndex, initMetricsIndex}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchSink
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics.MetricsStream.{metricsStream, projectRef1, projectRef2}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchClientSetup, EventMetricsProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesStatistics
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatEntry
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.SupervisorSetup
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, PatienceConfig}
import monix.bio.IO
import munit.AnyFixture

import scala.concurrent.duration.DurationInt

class StoragesStatisticsSuite
    extends BioSuite
    with ElasticSearchClientSetup.Fixture
    with SupervisorSetup.Fixture
    with TestHelpers
    with IOValues {

  override def munitFixtures: Seq[AnyFixture[_]] = List(esClient, supervisor)

  implicit private val patience: PatienceConfig = PatienceConfig(2.seconds, 10.milliseconds)

  private lazy val client  = esClient()
  private lazy val (sv, _) = supervisor()

  private lazy val sink   = ElasticSearchSink.events(client, 2, 50.millis, index, Refresh.False)
  private val indexPrefix = "delta"
  private val index       = eventMetricsIndex(indexPrefix)

  private val stats = (client: ElasticSearchClient) =>
    StoragesStatistics.apply(client, (storage, _) => IO.pure(Iri.unsafe(storage.toString)), indexPrefix)

  test("Run the event metrics projection") {
    val metricsProjection =
      EventMetricsProjection(sink, sv, _ => metricsStream, initMetricsIndex(client, index))
    metricsProjection.accepted
  }

  test("Correct statistics for storage in project 1") {
    stats(client).get("storageId", projectRef1).eventually(StorageStatEntry(2L, 30L))
  }

  test("Correct statistics for storage in project 2") {
    stats(client).get("storageId", projectRef2).eventually(StorageStatEntry(1L, 20L))
  }

  test("Zero stats for non-existing storage") {
    stats(client).get("none", projectRef1).eventually(StorageStatEntry(0L, 0L))
  }

}
