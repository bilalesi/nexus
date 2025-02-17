package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.GraphAnalytics.{index, projectionName}
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.GraphAnalyticsCoordinator.{analyticsMetadata, logger, ProjectDef}
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.config.GraphAnalyticsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing.{graphAnalyticsMappings, scriptContent, updateRelationshipsScriptId, GraphAnalyticsSink, GraphAnalyticsStream}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.Task

/**
  * Coordinates the lifecycle of Graph analytics as projections
  * @param fetchProjects
  *   stream of projects
  * @param analyticsStream
  *   the stream of analytics
  * @param supervisor
  *   the general supervisor
  * @param sink
  *   the sink to push the results
  * @param createIndex
  *   how to create an index
  * @param deleteIndex
  *   how to delete an index
  */
final class GraphAnalyticsCoordinator private (
    fetchProjects: Offset => ElemStream[ProjectDef],
    analyticsStream: GraphAnalyticsStream,
    supervisor: Supervisor,
    sink: ProjectRef => Sink,
    createIndex: ProjectRef => Task[Unit],
    deleteIndex: ProjectRef => Task[Unit]
) {

  def run(offset: Offset): Stream[Task, Elem[Unit]] =
    fetchProjects(offset).evalMap {
      _.traverse {
        case p if p.markedForDeletion => destroy(p.ref)
        case p                        => start(p.ref)
      }
    }

  private def compile(project: ProjectRef): Task[CompiledProjection] =
    Task.fromEither(
      CompiledProjection.compile(
        analyticsMetadata(project),
        ExecutionStrategy.PersistentSingleNode,
        Source(analyticsStream(project, _)),
        sink(project)
      )
    )

  // Start the analysis projection for the given project
  private def start(project: ProjectRef): Task[Unit] =
    for {
      compiled <- compile(project)
      status   <- supervisor.describe(compiled.metadata.name)
      _        <- status match {
                    case Some(value) if value.status == ExecutionStatus.Running =>
                      Task.delay(logger.info(s"Graph analysis of '$project' is already running."))
                    case _                                                      =>
                      Task.delay(logger.info(s"Starting graph analysis of '$project'...")) >>
                        supervisor.run(
                          compiled,
                          createIndex(project)
                        )
                  }
    } yield ()

  // Destroy the analysis for the given project and deletes the related Elasticsearch index
  private def destroy(project: ProjectRef): Task[Unit] = {
    Task.delay(logger.info(s"Project '$project' has been marked as deleted, stopping the graph analysis...")) >>
      supervisor
        .destroy(
          projectionName(project),
          deleteIndex(project)
        )
        .void
  }

}

object GraphAnalyticsCoordinator {

  final val id                                        = nxv + "graph-analytics"
  private val logger: Logger                          = Logger[GraphAnalyticsCoordinator]
  private[analytics] val metadata: ProjectionMetadata = ProjectionMetadata("system", "ga-coordinator", None, None)

  private def analyticsMetadata(project: ProjectRef) = ProjectionMetadata(
    "ga",
    projectionName(project),
    Some(project),
    Some(id)
  )

  private[analytics] case class ProjectDef(ref: ProjectRef, markedForDeletion: Boolean)

  /**
    * Creates the [[GraphAnalyticsCoordinator]] and register it into the supervisor
    * @param projects
    *   the projects module
    * @param analyticsStream
    *   how to generate the stream of analytics from a project
    * @param supervisor
    *   the general supervisor
    * @param client
    *   the elasticsearch client
    * @param config
    *   the graph analytics config
    */
  def apply(
      projects: Projects,
      analyticsStream: GraphAnalyticsStream,
      supervisor: Supervisor,
      client: ElasticSearchClient,
      config: GraphAnalyticsConfig
  ): Task[GraphAnalyticsCoordinator] = {
    val coordinator = apply(
      projects.states(_).map(_.map { p => ProjectDef(p.project, p.markedForDeletion) }),
      analyticsStream,
      supervisor,
      ref =>
        new GraphAnalyticsSink(
          client,
          config.batch.maxElements,
          config.batch.maxInterval,
          index(config.prefix, ref)
        ),
      ref =>
        graphAnalyticsMappings.flatMap { mappings =>
          client.createIndex(index(config.prefix, ref), Some(mappings), None)
        }.void,
      ref => client.deleteIndex(index(config.prefix, ref)).void
    )

    for {
      script <- scriptContent
      _      <- client.createScript(updateRelationshipsScriptId, script)
      c      <- coordinator
    } yield c
  }

  private[analytics] def apply(
      fetchProjects: Offset => ElemStream[ProjectDef],
      analyticsStream: GraphAnalyticsStream,
      supervisor: Supervisor,
      sink: ProjectRef => Sink,
      createIndex: ProjectRef => Task[Unit],
      deleteIndex: ProjectRef => Task[Unit]
  ): Task[GraphAnalyticsCoordinator] = {
    val coordinator =
      new GraphAnalyticsCoordinator(fetchProjects, analyticsStream, supervisor, sink, createIndex, deleteIndex)
    supervisor
      .run(
        CompiledProjection.fromStream(
          metadata,
          ExecutionStrategy.EveryNode,
          coordinator.run
        )
      )
      .as(coordinator)
  }

}
