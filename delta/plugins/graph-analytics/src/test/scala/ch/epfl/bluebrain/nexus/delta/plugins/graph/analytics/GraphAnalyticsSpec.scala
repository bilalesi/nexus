package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import akka.actor.ActorSystem
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, ElasticSearchClient}
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.config.GraphAnalyticsConfig.TermAggregationsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.AnalyticsGraph.{Edge, EdgePath, Node}
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.GraphAnalyticsRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.PropertiesStatistics.Metadata
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.{AnalyticsGraph, GraphAnalyticsRejection, PropertiesStatistics}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientWorthRetry}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchContainer._
import ch.epfl.bluebrain.nexus.testkit.elasticsearch.ElasticSearchDocker
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.execution.Scheduler
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Span}
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.UUID
import scala.concurrent.duration._

@DoNotDiscover
class GraphAnalyticsSpec(docker: ElasticSearchDocker)
    extends TestKit(ActorSystem("GraphAnalyticsSpec"))
    with AnyWordSpecLike
    with Matchers
    with TestHelpers
    with IOValues
    with IOFixedClock
    with ConfigFixtures
    with Eventually {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(10.seconds, Span(10, Millis))

  implicit val sc: Scheduler         = Scheduler.global
  implicit val cfg: HttpClientConfig =
    HttpClientConfig(RetryStrategyConfig.AlwaysGiveUp, HttpClientWorthRetry.never, true)

  private val project      = ProjectGen.project("org", "project", uuid = UUID.randomUUID(), orgUuid = UUID.randomUUID())
  private val fetchContext = FetchContextDummy[GraphAnalyticsRejection](
    List(project),
    ProjectContextRejection
  )

  private lazy val endpoint                       = docker.esHostConfig.endpoint
  private lazy val client                         = new ElasticSearchClient(HttpClient(), endpoint, 2000)
  private val prefix                              = "test"
  private lazy val graphAnalytics: GraphAnalytics =
    GraphAnalytics(client, fetchContext, "test", TermAggregationsConfig(100, 300))

  "GraphAnalytics" should {

    "initialize" in {
      val idx    = GraphAnalytics.index(prefix, project.ref)
      client.createIndex(idx, Some(jsonObjectContentOf("elasticsearch/mappings.json")), None).accepted
      val robert = iri"http://localhost/Robert"
      val sam    = iri"http://localhost/Sam"
      val fred   = iri"http://localhost/fred"
      val anna   = iri"http://localhost/Anna"
      client
        .bulk(
          List(
            ElasticSearchBulk.Index(idx, "1", jsonContentOf("document-source.json", "id" -> sam, "brother" -> sam)),
            ElasticSearchBulk.Index(idx, "2", jsonContentOf("document-source.json", "id" -> anna, "brother" -> robert)),
            ElasticSearchBulk.Index(idx, "3", jsonContentOf("document-source.json", "id" -> sam, "brother" -> fred))
          )
        )
        .accepted
    }

    "fetch relationships" in eventually {
      graphAnalytics.relationships(project.ref).accepted shouldEqual
        AnalyticsGraph(
          List(Node(schema.Person, "Person", 3)),
          List(Edge(schema.Person, schema.Person, 3, Vector(EdgePath(schema + "brother", "brother"))))
        )
    }

    "fetch properties" in {
      graphAnalytics.properties(project.ref, schema.Person).accepted shouldEqual
        PropertiesStatistics(
          Metadata(schema.Person, "Person", 3),
          List(
            PropertiesStatistics(Metadata(schema + "givenName", "givenName", 3), List.empty),
            PropertiesStatistics(Metadata(schema + "brother", "brother", 3), List.empty),
            PropertiesStatistics(
              Metadata(schema + "address", "address", 3),
              List(PropertiesStatistics(Metadata(schema + "street", "street", 3), List.empty))
            )
          )
        )

    }
  }
}
