package ch.epfl.bluebrain.nexus.delta.sdk.deletion

import cats.effect.Clock
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.model.ProjectDeletionReport
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectsConfig.DeletionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectState
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{Projects, ProjectsConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{Task, UIO}
import retry.syntax.all._

/**
  * Stream to delete project from the system after those are marked as deleted
  */
sealed trait ProjectDeletionCoordinator

object ProjectDeletionCoordinator {

  private val logger: Logger = Logger[ProjectDeletionCoordinator]

  /**
    * If deletion is disabled, we do nothing
    */
  final private[deletion] case object Noop extends ProjectDeletionCoordinator

  /**
    * If deletion is enabled, we go through the project state log, looking for those marked for deletion
    */
  final private[deletion] class Active(
      fetchProjects: Offset => ElemStream[ProjectState],
      deletionTasks: List[ProjectDeletionTask],
      deletionConfig: DeletionConfig,
      serviceAccount: ServiceAccount,
      deletionStore: ProjectDeletionStore
  )(implicit clock: Clock[UIO])
      extends ProjectDeletionCoordinator {

    implicit private val serviceAccountSubject: Subject = serviceAccount.subject

    def run(offset: Offset): Stream[Task, Elem[Unit]] =
      fetchProjects(offset).evalMap {
        _.traverse {
          case project if project.markedForDeletion =>
            // If it fails, we try again after a backoff
            val retryStrategy = RetryStrategy.retryOnNonFatal(
              deletionConfig.retryStrategy,
              logger,
              s"attempting to delete project ${project.project}"
            )
            delete(project).retryingOnSomeErrors(retryStrategy.retryWhen, retryStrategy.policy, retryStrategy.onError)
          case _                                    => Task.unit
        }
      }

    private[deletion] def delete(project: ProjectState) =
      for {
        _         <- Task.delay(logger.warn(s"Starting deletion of project ${project.project}"))
        now       <- IOUtils.instant
        // Running preliminary tasks before deletion like deprecating and stopping views,
        // removing acl related to the project, etc...
        initReport = ProjectDeletionReport(project.project, project.updatedAt, now, project.updatedBy)
        report    <- deletionTasks
                       .foldLeftM(initReport) { case (report, task) =>
                         task(project.project).map(report ++ _)
                       }
        // Waiting for events issued by deletion tasks to be taken into account
        _         <- Task.sleep(deletionConfig.propagationDelay)
        // Delete the events and states and save the deletion report
        _         <- deletionStore.deleteAndSaveReport(report)
        _         <- Task.delay(logger.info(s"Project ${project.project} has been successfully deleted."))
      } yield ()

    private[deletion] def list(project: ProjectRef): Task[List[ProjectDeletionReport]] =
      deletionStore.list(project)
  }

  /**
    * Build the project deletion stream according to the configuration
    */
  def apply(
      projects: Projects,
      deletionTasks: Set[ProjectDeletionTask],
      deletionConfig: ProjectsConfig.DeletionConfig,
      serviceAccount: ServiceAccount,
      xas: Transactors
  )(implicit clock: Clock[UIO]): ProjectDeletionCoordinator =
    if (deletionConfig.enabled) {
      new Active(
        projects.states,
        deletionTasks.toList,
        deletionConfig,
        serviceAccount,
        new ProjectDeletionStore(xas)
      )
    } else
      Noop

  /**
    * Build and run the project deletion stream in the supervisor
    */
  // $COVERAGE-OFF$
  def apply(
      projects: Projects,
      deletionTasks: Set[ProjectDeletionTask],
      deletionConfig: ProjectsConfig.DeletionConfig,
      serviceAccount: ServiceAccount,
      supervisor: Supervisor,
      xas: Transactors
  )(implicit clock: Clock[UIO]): Task[ProjectDeletionCoordinator] = {
    val stream = apply(projects, deletionTasks, deletionConfig, serviceAccount, xas)
    stream match {
      case Noop           => Task.delay(logger.info("Projection deletion is disabled.")).as(Noop)
      case active: Active =>
        val metadata: ProjectionMetadata = ProjectionMetadata("system", "project-deletion", None, None)
        supervisor
          .run(
            CompiledProjection.fromStream(
              metadata,
              ExecutionStrategy.PersistentSingleNode,
              active.run
            )
          )
          .as(active)
    }
  }
  // $COVERAGE-ON$
}
