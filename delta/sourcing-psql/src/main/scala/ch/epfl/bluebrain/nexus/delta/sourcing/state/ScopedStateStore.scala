package ch.epfl.bluebrain.nexus.delta.sourcing.state

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.IriInstances
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.{RefreshStrategy, StreamingQuery}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore.StateNotFound
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore.StateNotFound.{TagNotFound, UnknownState}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.{Execute, PartitionInit, Predicate, Serializer}
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Decoder
import monix.bio.IO

/**
  * Allows to save/fetch [[ScopeState]] from the database
  */
trait ScopedStateStore[Id, S <: ScopedState] {

  /**
    * @param state
    *   The state to persist
    * @param tag
    *   The tag of the state
    * @param init
    *   The type of partition initialization to run prior to persisting
    * @return
    *   A [[ConnectionIO]] describing how to persist the event.
    */
  def save(state: S, tag: Tag, init: PartitionInit): ConnectionIO[Unit]

  /** Persist the state using the `latest` tag, and using the provided partition initialization */
  def save(state: S, init: PartitionInit): ConnectionIO[Unit] =
    save(state, Latest, init)

  /**
    * Persist the state using the `latest` tag, and with forced partition initialization. Forcing partition
    * initialization can have a negative impact on performance.
    */
  def unsafeSave(state: S): ConnectionIO[Unit] =
    unsafeSave(state, Latest)

  /**
    * Persist the state using the provided tag, and with forced partition initialization. Forcing partition
    * initialization can have a negative impact on performance.
    */
  def unsafeSave(state: S, tag: Tag): ConnectionIO[Unit] =
    save(state, tag, Execute(state.project))

  /**
    * Delete the state for the given tag
    */
  def delete(ref: ProjectRef, id: Id, tag: Tag): ConnectionIO[Unit]

  /**
    * Returns the latest state
    */
  def get(ref: ProjectRef, id: Id): IO[UnknownState, S]

  /**
    * Returns the state at the given tag
    */
  def get(ref: ProjectRef, id: Id, tag: Tag): IO[StateNotFound, S]

  /**
    * Fetches latest states from the given type from the beginning.
    *
    * The stream is completed when it reaches the end.
    * @param predicate
    *   to filter returned states
    */
  def currentStates(predicate: Predicate): EnvelopeStream[S] =
    currentStates(predicate, Offset.Start)

  /**
    * Fetches states from the given type with the given tag from the beginning.
    *
    * The stream is completed when it reaches the end.
    * @param predicate
    *   to filter returned states
    * @param tag
    *   only states with this tag will be selected
    */
  def currentStates(predicate: Predicate, tag: Tag): EnvelopeStream[S] =
    currentStates(predicate, tag, Offset.Start)

  /**
    * Fetches latest states from the given type from the provided offset.
    *
    * The stream is completed when it reaches the end.
    * @param predicate
    *   to filter returned states
    * @param offset
    *   the offset
    */
  def currentStates(predicate: Predicate, offset: Offset): EnvelopeStream[S] =
    currentStates(predicate, Latest, offset)

  /**
    * Fetches states from the given type with the given tag from the provided offset.
    *
    * The stream is completed when it reaches the end.
    * @param predicate
    *   to filter returned states
    * @param tag
    *   only states with this tag will be selected
    * @param offset
    *   the offset
    */
  def currentStates(predicate: Predicate, tag: Tag, offset: Offset): EnvelopeStream[S]

  /**
    * Fetches latest states from the given type from the beginning
    *
    * The stream is not completed when it reaches the end of the existing events, but it continues to push new events
    * when new events are persisted.
    *
    * @param predicate
    *   to filter returned states
    */
  def states(predicate: Predicate): EnvelopeStream[S] =
    states(predicate, Latest, Offset.Start)

  /**
    * Fetches states from the given type with the given tag from the beginning
    *
    * The stream is not completed when it reaches the end of the existing events, but it continues to push new events
    * when new states are persisted.
    *
    * @param predicate
    *   to filter returned states
    * @param tag
    *   only states with this tag will be selected
    */
  def states(predicate: Predicate, tag: Tag): EnvelopeStream[S] = states(predicate, tag, Offset.Start)

  /**
    * Fetches latest states from the given type from the provided offset
    *
    * The stream is not completed when it reaches the end of the existing events, but it continues to push new events
    * when new events are persisted.
    *
    * @param predicate
    *   to filter returned states
    * @param offset
    *   the offset
    */
  def states(predicate: Predicate, offset: Offset): EnvelopeStream[S] =
    states(predicate, Latest, offset)

  /**
    * Fetches states from the given type with the given tag from the provided offset
    *
    * The stream is not completed when it reaches the end of the existing events, but it continues to push new events
    * when new states are persisted.
    *
    * @param predicate
    *   to filter returned states
    * @param tag
    *   only states with this tag will be selected
    * @param offset
    *   the offset
    */
  def states(predicate: Predicate, tag: Tag, offset: Offset): EnvelopeStream[S]

}

object ScopedStateStore {

  sealed private[sourcing] trait StateNotFound extends Product with Serializable

  private[sourcing] object StateNotFound {
    sealed trait UnknownState      extends StateNotFound
    final case object UnknownState extends UnknownState
    sealed trait TagNotFound       extends StateNotFound
    final case object TagNotFound  extends TagNotFound
  }

  def apply[Id, S <: ScopedState](
      tpe: EntityType,
      serializer: Serializer[Id, S],
      config: QueryConfig,
      xas: Transactors
  ): ScopedStateStore[Id, S] = new ScopedStateStore[Id, S] {

    import IriInstances._
    implicit val putId: Put[Id]      = serializer.putId
    implicit val getValue: Get[S]    = serializer.getValue
    implicit val putValue: Put[S]    = serializer.putValue
    implicit val decoder: Decoder[S] = serializer.codec

    private def insertState(state: S, tag: Tag) =
      sql"SELECT 1 FROM scoped_states WHERE type = $tpe AND org = ${state.organization} AND project = ${state.project.project}  AND id = ${state.id} AND tag = $tag"
        .query[Int]
        .option
        .flatMap {
          _.fold(sql"""
                 | INSERT INTO scoped_states (
                 |  type,
                 |  org,
                 |  project,
                 |  id,
                 |  tag,
                 |  rev,
                 |  value,
                 |  deprecated,
                 |  instant
                 | )
                 | VALUES (
                 |  $tpe,
                 |  ${state.organization},
                 |  ${state.project.project},
                 |  ${state.id},
                 |  $tag,
                 |  ${state.rev},
                 |  $state,
                 |  ${state.deprecated},
                 |  ${state.updatedAt}
                 | )
            """.stripMargin) { _ =>
            sql"""
                 | UPDATE scoped_states SET
                 |  rev = ${state.rev},
                 |  value = $state,
                 |  deprecated = ${state.deprecated},
                 |  instant = ${state.updatedAt},
                 |  ordering = (select nextval('state_offset'))
                 | WHERE
                 |  type = $tpe AND
                 |  org = ${state.organization} AND
                 |  project = ${state.project.project} AND
                 |  id =  ${state.id} AND
                 |  tag = $tag
            """.stripMargin
          }.update.run.void
        }

    override def save(state: S, tag: Tag, init: PartitionInit): doobie.ConnectionIO[Unit] =
      init.initializePartition("scoped_states") >> insertState(state, tag)

    override def delete(ref: ProjectRef, id: Id, tag: Tag): ConnectionIO[Unit] =
      sql"""DELETE FROM scoped_states WHERE type = $tpe AND org = ${ref.organization} AND project = ${ref.project}  AND id = $id AND tag = $tag""".stripMargin.update.run.void

    private def getValue(ref: ProjectRef, id: Id, tag: Tag): ConnectionIO[Option[S]] =
      sql"""SELECT value FROM scoped_states WHERE type = $tpe AND org = ${ref.organization} AND project = ${ref.project}  AND id = $id AND tag = $tag"""
        .query[S]
        .option

    private def exists(ref: ProjectRef, id: Id): ConnectionIO[Boolean] =
      sql"""SELECT id FROM scoped_states WHERE type = $tpe AND org = ${ref.organization} AND project = ${ref.project} AND id = $id LIMIT 1"""
        .query[Iri]
        .option
        .map(_.isDefined)

    override def get(ref: ProjectRef, id: Id): IO[UnknownState, S] =
      getValue(ref, id, Latest).transact(xas.read).hideErrors.flatMap { s =>
        IO.fromOption(s, UnknownState)
      }

    override def get(ref: ProjectRef, id: Id, tag: Tag): IO[StateNotFound, S] = {
      for {
        value  <- getValue(ref, id, tag)
        exists <- value.fold(exists(ref, id))(_ => true.pure[ConnectionIO])
      } yield value -> exists
    }.transact(xas.read).hideErrors.flatMap { case (s, exists) =>
      val error = if (exists) TagNotFound else UnknownState
      IO.fromOption(s, error)
    }

    private def states(
        predicate: Predicate,
        tag: Tag,
        offset: Offset,
        strategy: RefreshStrategy
    ): EnvelopeStream[S] =
      StreamingQuery[Envelope[S]](
        offset,
        offset =>
          // format: off
          sql"""SELECT type, id, value, rev, instant, ordering FROM public.scoped_states
               |${Fragments.whereAndOpt(Some(fr"type = $tpe"), predicate.asFragment, Some(fr"tag = $tag"), offset.asFragment)}
               |ORDER BY ordering
               |LIMIT ${config.batchSize}""".stripMargin.query[Envelope[S]],
        _.offset,
        config.copy(refreshStrategy = strategy),
        xas
      )

    override def currentStates(predicate: Predicate, tag: Tag, offset: Offset): EnvelopeStream[S] =
      states(predicate, tag, offset, RefreshStrategy.Stop)

    override def states(predicate: Predicate, tag: Tag, offset: Offset): EnvelopeStream[S] =
      states(predicate, tag, offset, config.refreshStrategy)
  }

}
