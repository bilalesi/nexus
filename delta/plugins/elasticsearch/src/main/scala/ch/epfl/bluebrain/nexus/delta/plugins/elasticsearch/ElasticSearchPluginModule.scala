package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchPluginModule.injectElasticViewDefaults
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.deletion.{ElasticSearchDeletionTask, EventMetricsDeletionTask}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{contexts, defaultElasticsearchMapping, defaultElasticsearchSettings, schema => viewsSchemaId, ElasticSearchView, ElasticSearchViewCommand, ElasticSearchViewEvent, ElasticSearchViewRejection, ElasticSearchViewState, ElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsRoutes
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.migration.{MigrationLog, MigrationState}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projections
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{PipeChain, ReferenceRegistry, Supervisor}
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

/**
  * ElasticSearch plugin wiring.
  */
class ElasticSearchPluginModule(priority: Int) extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[ElasticSearchViewsConfig].from { ElasticSearchViewsConfig.load(_) }

  make[HttpClient].named("elasticsearch-client").from {
    (cfg: ElasticSearchViewsConfig, as: ActorSystem[Nothing], sc: Scheduler) =>
      HttpClient()(cfg.client, as.classicSystem, sc)
  }

  make[ElasticSearchClient].from {
    (cfg: ElasticSearchViewsConfig, client: HttpClient @Id("elasticsearch-client"), as: ActorSystem[Nothing]) =>
      new ElasticSearchClient(client, cfg.base, cfg.maxIndexPathLength)(cfg.credentials, as.classicSystem)
  }

  make[ValidateElasticSearchView].from {
    (
        registry: ReferenceRegistry,
        permissions: Permissions,
        client: ElasticSearchClient,
        config: ElasticSearchViewsConfig,
        xas: Transactors
    ) =>
      ValidateElasticSearchView(
        PipeChain.validate(_, registry),
        permissions,
        client: ElasticSearchClient,
        config.prefix,
        config.maxViewRefs,
        xas
      )
  }

  make[ElasticSearchViews].fromEffect {
    (
        fetchContext: FetchContext[ContextRejection],
        contextResolution: ResolverContextResolution,
        validateElasticSearchView: ValidateElasticSearchView,
        config: ElasticSearchViewsConfig,
        xas: Transactors,
        api: JsonLdApi,
        clock: Clock[UIO],
        uuidF: UUIDF
    ) =>
      ElasticSearchViews(
        fetchContext.mapRejection(ProjectContextRejection),
        contextResolution,
        validateElasticSearchView,
        config.eventLog,
        config.prefix,
        xas
      )(api, clock, uuidF)
  }

  if (!MigrationState.isEsIndexingDisabled) {
    make[ElasticSearchCoordinator].fromEffect {
      (
          views: ElasticSearchViews,
          graphStream: GraphResourceStream,
          registry: ReferenceRegistry,
          supervisor: Supervisor,
          client: ElasticSearchClient,
          config: ElasticSearchViewsConfig,
          cr: RemoteContextResolution @Id("aggregate")
      ) =>
        ElasticSearchCoordinator(
          views,
          graphStream,
          registry,
          supervisor,
          client,
          config.batch
        )(cr)
    }
  }

  make[EventMetricsProjection].fromEffect {
    (
        metricEncoders: Set[ScopedEventMetricEncoder[_]],
        xas: Transactors,
        supervisor: Supervisor,
        client: ElasticSearchClient,
        config: ElasticSearchViewsConfig
    ) =>
      EventMetricsProjection(
        metricEncoders,
        supervisor,
        client,
        xas,
        config.batch,
        config.metricsQuery,
        config.prefix
      )
  }

  make[ElasticSearchViewsQuery].from {
    (
        aclCheck: AclCheck,
        fetchContext: FetchContext[ContextRejection],
        views: ElasticSearchViews,
        client: ElasticSearchClient,
        xas: Transactors,
        baseUri: BaseUri,
        cfg: ElasticSearchViewsConfig
    ) =>
      ElasticSearchViewsQuery(
        aclCheck,
        fetchContext.mapRejection(ProjectContextRejection),
        views,
        client,
        cfg.prefix,
        xas
      )(baseUri)
  }

  make[ElasticSearchViewsRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        views: ElasticSearchViews,
        projections: Projections,
        schemeDirectives: DeltaSchemeDirectives,
        indexingAction: IndexingAction @Id("aggregate"),
        viewsQuery: ElasticSearchViewsQuery,
        shift: ElasticSearchView.Shift,
        baseUri: BaseUri,
        cfg: ElasticSearchViewsConfig,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        resourcesToSchemaSet: Set[ResourceToSchemaMappings],
        fusionConfig: FusionConfig
    ) =>
      val resourceToSchema = resourcesToSchemaSet.foldLeft(ResourceToSchemaMappings.empty)(_ + _)
      new ElasticSearchViewsRoutes(
        identities,
        aclCheck,
        views,
        viewsQuery,
        projections,
        resourceToSchema,
        schemeDirectives,
        indexingAction(_, _, _)(shift, cr)
      )(
        baseUri,
        cfg.pagination,
        s,
        cr,
        ordering,
        fusionConfig
      )
  }

  make[ElasticSearchScopeInitialization]
    .from { (views: ElasticSearchViews, serviceAccount: ServiceAccount, config: ElasticSearchViewsConfig) =>
      new ElasticSearchScopeInitialization(views, serviceAccount, config.defaults)
    }

  many[ScopeInitialization].ref[ElasticSearchScopeInitialization]

  many[ProjectDeletionTask].add { (views: ElasticSearchViews) => ElasticSearchDeletionTask(views) }

  many[ProjectDeletionTask].add { (client: ElasticSearchClient, config: ElasticSearchViewsConfig) =>
    new EventMetricsDeletionTask(client, config.prefix)
  }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/elasticsearch-metadata.json"))

  make[MetadataContextValue]
    .named("search-metadata")
    .from((agg: Set[MetadataContextValue]) => agg.foldLeft(MetadataContextValue.empty)(_ merge _))

  make[MetadataContextValue]
    .named("indexing-metadata")
    .from { (listingsMetadataCtx: MetadataContextValue @Id("search-metadata")) =>
      MetadataContextValue(listingsMetadataCtx.value.visit(obj = { case ContextObject(obj) =>
        ContextObject(obj.filterKeys(_.startsWith("_")))
      }))
    }

  many[SseEncoder[_]].add { base: BaseUri => ElasticSearchViewEvent.sseEncoder(base) }

  many[ScopedEventMetricEncoder[_]].add { ElasticSearchViewEvent.esViewMetricEncoder }

  many[RemoteContextResolution].addEffect {
    (
        searchMetadataCtx: MetadataContextValue @Id("search-metadata"),
        indexingMetadataCtx: MetadataContextValue @Id("indexing-metadata")
    ) =>
      for {
        elasticsearchCtx     <- ContextValue.fromFile("contexts/elasticsearch.json")
        elasticsearchMetaCtx <- ContextValue.fromFile("contexts/elasticsearch-metadata.json")
        elasticsearchIdxCtx  <- ContextValue.fromFile("contexts/elasticsearch-indexing.json")
        offsetCtx            <- ContextValue.fromFile("contexts/offset.json")
        statisticsCtx        <- ContextValue.fromFile("contexts/statistics.json")
      } yield RemoteContextResolution.fixed(
        contexts.elasticsearch         -> elasticsearchCtx,
        contexts.elasticsearchMetadata -> elasticsearchMetaCtx,
        contexts.elasticsearchIndexing -> elasticsearchIdxCtx,
        contexts.indexingMetadata      -> indexingMetadataCtx.value,
        contexts.searchMetadata        -> searchMetadataCtx.value,
        Vocabulary.contexts.offset     -> offsetCtx,
        Vocabulary.contexts.statistics -> statisticsCtx
      )
  }

  many[ResourceToSchemaMappings].add(
    ResourceToSchemaMappings(Label.unsafe("views") -> viewsSchemaId.iri)
  )

  many[ApiMappings].add(ElasticSearchViews.mappings)

  many[PriorityRoute].add { (route: ElasticSearchViewsRoutes) =>
    PriorityRoute(priority, route.routes, requiresStrictEntity = true)
  }

  many[ServiceDependency].add { new ElasticSearchServiceDependency(_) }

  many[IndexingAction].add {
    (
        views: ElasticSearchViews,
        registry: ReferenceRegistry,
        client: ElasticSearchClient,
        config: ElasticSearchViewsConfig
    ) =>
      ElasticSearchIndexingAction(views, registry, client, config.syncIndexingTimeout, config.syncIndexingRefresh)
  }

  make[ElasticSearchView.Shift].fromEffect { (views: ElasticSearchViews, base: BaseUri) =>
    for {
      defaultMapping  <- defaultElasticsearchMapping
      defaultSettings <- defaultElasticsearchSettings
    } yield ElasticSearchView.shift(views, defaultMapping, defaultSettings)(base)
  }

  many[ResourceShift[_, _, _]].ref[ElasticSearchView.Shift]

  if (MigrationState.isRunning) {
    many[MigrationLog].add { (cfg: ElasticSearchViewsConfig, xas: Transactors, clock: Clock[UIO], uuidF: UUIDF) =>
      MigrationLog.scoped[
        Iri,
        ElasticSearchViewState,
        ElasticSearchViewCommand,
        ElasticSearchViewEvent,
        ElasticSearchViewRejection
      ](
        ElasticSearchViews.definition((_, _, _) =>
          IO.terminate(new IllegalStateException("ElasticSearchView command evaluation should not happen"))
        )(clock, uuidF),
        e => e.id,
        identity,
        (e, _) => injectElasticViewDefaults(cfg.defaults)(e),
        cfg.eventLog,
        xas
      )
    }
  }

}

// TODO: This object contains migration helpers, and should be deleted when the migration module is removed
object ElasticSearchPluginModule {

  import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.defaultViewId

  private def setViewDefaults(
      name: Option[String],
      description: Option[String]
  ): ElasticSearchViewValue => ElasticSearchViewValue = {
    case iv: IndexingElasticSearchViewValue  => iv.copy(name = name, description = description)
    case av: AggregateElasticSearchViewValue => av.copy(name = name, description = description)
  }

  def injectElasticViewDefaults(defaults: Defaults): ElasticSearchViewEvent => ElasticSearchViewEvent = {
    case e @ ElasticSearchViewCreated(id, _, _, value, _, _, _, _) if id == defaultViewId =>
      e.copy(value = setViewDefaults(Some(defaults.name), Some(defaults.description))(value))
    case e @ ElasticSearchViewUpdated(id, _, _, value, _, _, _, _) if id == defaultViewId =>
      e.copy(value = setViewDefaults(Some(defaults.name), Some(defaults.description))(value))
    case event                                                                            => event
  }

}
