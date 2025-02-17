package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMinPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ResourcesRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.{ResolverContextResolution, Resolvers, ResourceResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{Resource, ResourceEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.{Resources, ResourcesImpl, ValidateResource, ValidateResourceImpl}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Resources wiring
  */
object ResourcesModule extends ModuleDef {

  make[Resources].from {
    (
        validator: ValidateResource,
        fetchContext: FetchContext[ContextRejection],
        config: AppConfig,
        resolverContextResolution: ResolverContextResolution,
        api: JsonLdApi,
        xas: Transactors,
        clock: Clock[UIO],
        uuidF: UUIDF
    ) =>
      ResourcesImpl(
        validator,
        fetchContext.mapRejection(ProjectContextRejection),
        resolverContextResolution,
        config.resources,
        xas
      )(
        api,
        clock,
        uuidF
      )
  }

  make[ResolverContextResolution].from {
    (aclCheck: AclCheck, resolvers: Resolvers, resources: Resources, rcr: RemoteContextResolution @Id("aggregate")) =>
      ResolverContextResolution(aclCheck, resolvers, resources, rcr)
  }

  make[ResourceResolution[Schema]].from { (aclCheck: AclCheck, resolvers: Resolvers, schemas: Schemas) =>
    ResourceResolution.schemaResource(aclCheck, resolvers, schemas)
  }

  make[ResourcesRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        resources: Resources,
        schemeDirectives: DeltaSchemeDirectives,
        indexingAction: IndexingAction @Id("aggregate"),
        shift: Resource.Shift,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      new ResourcesRoutes(identities, aclCheck, resources, schemeDirectives, indexingAction(_, _, _)(shift, cr))(
        baseUri,
        s,
        cr,
        ordering,
        fusionConfig
      )
  }

  make[ValidateResource].from((resourceResolution: ResourceResolution[Schema], api: JsonLdApi) =>
    new ValidateResourceImpl(resourceResolution)(api)
  )

  many[SseEncoder[_]].add { base: BaseUri => ResourceEvent.sseEncoder(base) }

  many[ScopedEventMetricEncoder[_]].add { ResourceEvent.resourceEventMetricEncoder }

  many[ApiMappings].add(Resources.mappings)

  many[PriorityRoute].add { (route: ResourcesRoutes) =>
    PriorityRoute(pluginsMinPriority - 1, route.routes, requiresStrictEntity = true)
  }

  make[Resource.Shift].from { (resources: Resources, base: BaseUri) =>
    Resource.shift(resources)(base)
  }

  many[ResourceShift[_, _, _]].ref[Resource.Shift]

}
