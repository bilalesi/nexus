package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.BootstrapSetup
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.{ExceptionHandler, RejectionHandler, Route}
import akka.stream.{Materializer, SystemMaterializer}
import cats.data.NonEmptyList
import cats.effect.{Clock, Resource, Sync}
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.database.{DatabaseConfig, Transactors}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ErrorRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction.AggregateIndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.StrictEntity
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{RdfExceptionHandler, RdfRejectionHandler}
import ch.epfl.bluebrain.nexus.delta.sdk.migration.{MigrationLog, MigrationState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.PluginDef
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{OwnerPermissionsScopeInitialization, ProjectsConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{ProjectionConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Supervisor
import ch.epfl.bluebrain.nexus.migration.Migration
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.typesafe.config.Config
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.{Task, UIO}
import monix.execution.Scheduler
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration.DurationInt

/**
  * Complete service wiring definitions.
  *
  * @param appCfg
  *   the application configuration
  * @param config
  *   the raw merged and resolved configuration
  */
class DeltaModule(appCfg: AppConfig, config: Config)(implicit classLoader: ClassLoader) extends ModuleDef {
  addImplicit[Sync[Task]]

  make[AppConfig].from(appCfg)
  make[Config].from(config)
  make[DatabaseConfig].from(appCfg.database)
  make[FusionConfig].from { appCfg.fusion }
  make[ProjectsConfig].from { appCfg.projects }
  make[ProjectionConfig].from { appCfg.projections }
  make[QueryConfig].from { appCfg.projections.query }
  make[BaseUri].from { appCfg.http.baseUri }
  make[StrictEntity].from { appCfg.http.strictEntityTimeout }
  make[ServiceAccount].from { appCfg.serviceAccount.value }
  make[Crypto].from { appCfg.encryption.crypto }

  make[Transactors].fromResource {
    Transactors.init(appCfg.database)
  }

  make[List[PluginDescription]].from { (pluginsDef: List[PluginDef]) => pluginsDef.map(_.info) }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/metadata.json"))

  make[IndexingAction].named("aggregate").from { (internal: Set[IndexingAction]) =>
    AggregateIndexingAction(NonEmptyList.fromListUnsafe(internal.toList))
  }

  make[RemoteContextResolution].named("aggregate").fromEffect { (otherCtxResolutions: Set[RemoteContextResolution]) =>
    for {
      errorCtx      <- ContextValue.fromFile("contexts/error.json")
      metadataCtx   <- ContextValue.fromFile("contexts/metadata.json")
      searchCtx     <- ContextValue.fromFile("contexts/search.json")
      pipelineCtx   <- ContextValue.fromFile("contexts/pipeline.json")
      tagsCtx       <- ContextValue.fromFile("contexts/tags.json")
      versionCtx    <- ContextValue.fromFile("contexts/version.json")
      validationCtx <- ContextValue.fromFile("contexts/validation.json")
    } yield RemoteContextResolution
      .fixed(
        contexts.error      -> errorCtx,
        contexts.metadata   -> metadataCtx,
        contexts.search     -> searchCtx,
        contexts.pipeline   -> pipelineCtx,
        contexts.tags       -> tagsCtx,
        contexts.version    -> versionCtx,
        contexts.validation -> validationCtx
      )
      .merge(otherCtxResolutions.toSeq: _*)
  }

  make[JsonLdApi].fromValue(new JsonLdJavaApi(appCfg.jsonLdApi))

  make[Clock[UIO]].from(Clock[UIO])
  make[UUIDF].from(UUIDF.random)
  make[Scheduler].from(Scheduler.global)
  make[JsonKeyOrdering].from(
    JsonKeyOrdering.default(topKeys =
      List("@context", "@id", "@type", "reason", "details", "sourceId", "projectionId", "_total", "_results")
    )
  )
  make[ActorSystem[Nothing]].fromResource {
    val make    = Task.delay(
      ActorSystem[Nothing](
        Behaviors.empty,
        appCfg.description.fullName,
        BootstrapSetup().withConfig(config).withClassloader(classLoader)
      )
    )
    val release = (as: ActorSystem[Nothing]) => {
      import akka.actor.typed.scaladsl.adapter._
      Task.deferFuture(as.toClassic.terminate()).timeout(15.seconds).void
    }
    Resource.make(make)(release)
  }

  make[Materializer].from((as: ActorSystem[Nothing]) => SystemMaterializer(as).materializer)
  make[Logger].from { LoggerFactory.getLogger("delta") }
  make[RejectionHandler].from {
    (s: Scheduler, cr: RemoteContextResolution @Id("aggregate"), ordering: JsonKeyOrdering) =>
      RdfRejectionHandler(s, cr, ordering)
  }
  make[ExceptionHandler].from {
    (s: Scheduler, cr: RemoteContextResolution @Id("aggregate"), ordering: JsonKeyOrdering, base: BaseUri) =>
      RdfExceptionHandler(s, cr, ordering, base)
  }
  make[CorsSettings].from(
    CorsSettings.defaultSettings
      .withAllowedMethods(List(GET, PUT, POST, PATCH, DELETE, OPTIONS, HEAD))
      .withExposedHeaders(List(Location.name))
  )

  many[ScopeInitialization].add { (acls: Acls, serviceAccount: ServiceAccount) =>
    OwnerPermissionsScopeInitialization(acls, appCfg.permissions.ownerPermissions, serviceAccount)
  }

  many[PriorityRoute].add {
    (cfg: AppConfig, s: Scheduler, cr: RemoteContextResolution @Id("aggregate"), ordering: JsonKeyOrdering) =>
      val route = new ErrorRoutes()(cfg.http.baseUri, s, cr, ordering)
      PriorityRoute(pluginsMaxPriority + 999, route.routes, requiresStrictEntity = true)
  }

  make[Vector[Route]].from { (pluginsRoutes: Set[PriorityRoute]) =>
    pluginsRoutes.toVector.sorted.map(_.route)
  }

  make[ResourceShifts].from {
    (shifts: Set[ResourceShift[_, _, _]], xas: Transactors, rcr: RemoteContextResolution @Id("aggregate")) =>
      ResourceShifts(shifts, xas)(rcr)
  }

  include(PermissionsModule)
  include(AclsModule)
  include(RealmsModule)
  include(OrganizationsModule)
  include(ProjectsModule)
  include(ResolversModule)
  include(SchemasModule)
  include(ResourcesModule)
  include(IdentitiesModule)
  include(VersionModule)
  include(QuotasModule)
  include(EventsModule)
  include(StreamModule)
  include(SupervisionModule)

  if (MigrationState.isRunning) {
    include(MigrationModule)
    make[Migration].fromEffect {
      (logs: Set[MigrationLog], xas: Transactors, supervisor: Supervisor, as: ActorSystem[Nothing]) =>
        Migration(logs, xas, supervisor, as.classicSystem)
    }
  }
}

object DeltaModule {

  /**
    * Complete service wiring definitions.
    *
    * @param appCfg
    *   the application configuration
    * @param config
    *   the raw merged and resolved configuration
    * @param classLoader
    *   the aggregated class loader
    */
  final def apply(
      appCfg: AppConfig,
      config: Config,
      classLoader: ClassLoader
  ): DeltaModule =
    new DeltaModule(appCfg, config)(classLoader)
}
