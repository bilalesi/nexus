package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.config.GraphAnalyticsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing.GraphAnalyticsStream
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.GraphAnalyticsRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.routes.GraphAnalyticsRoutes
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.migration.MigrationState
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, Projects}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projections
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Supervisor
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.execution.Scheduler

/**
  * Graph analytics plugin wiring.
  */
class GraphAnalyticsPluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[GraphAnalyticsConfig].from { GraphAnalyticsConfig.load _ }

  make[GraphAnalytics]
    .from { (client: ElasticSearchClient, fetchContext: FetchContext[ContextRejection], config: GraphAnalyticsConfig) =>
      GraphAnalytics(client, fetchContext.mapRejection(ProjectContextRejection), config.prefix, config.termAggregations)
    }

  if (!MigrationState.isEsIndexingDisabled) {
    make[GraphAnalyticsStream].from { (qc: QueryConfig, xas: Transactors) =>
      GraphAnalyticsStream(qc, xas)
    }

    make[GraphAnalyticsCoordinator].fromEffect {
      (
          projects: Projects,
          analyticsStream: GraphAnalyticsStream,
          supervisor: Supervisor,
          client: ElasticSearchClient,
          config: GraphAnalyticsConfig
      ) => GraphAnalyticsCoordinator(projects, analyticsStream, supervisor, client, config)
    }
  }

  make[GraphAnalyticsRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        graphAnalytics: GraphAnalytics,
        projections: Projections,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new GraphAnalyticsRoutes(
        identities,
        aclCheck,
        graphAnalytics,
        project => projections.statistics(project, None, GraphAnalytics.projectionName(project)),
        schemeDirectives
      )(
        baseUri,
        s,
        cr,
        ordering
      )
  }

  many[RemoteContextResolution].addEffect {
    for {
      relationshipsCtx <- ContextValue.fromFile("contexts/relationships.json")
      propertiesCtx    <- ContextValue.fromFile("contexts/properties.json")
    } yield RemoteContextResolution.fixed(
      contexts.relationships -> relationshipsCtx,
      contexts.properties    -> propertiesCtx
    )
  }

  many[PriorityRoute].add { (route: GraphAnalyticsRoutes) =>
    PriorityRoute(priority, route.routes, requiresStrictEntity = true)
  }
}
