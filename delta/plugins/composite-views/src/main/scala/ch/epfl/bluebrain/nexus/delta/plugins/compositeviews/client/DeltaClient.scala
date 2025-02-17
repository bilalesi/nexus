package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.client.RequestBuilding.{Get, Head}
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, Accept}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.stream.alpakka.sse.scaladsl.EventSource
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.RemoteProjectSource
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NQuads
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient.HttpResult
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.AuthToken
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectStatistics
import ch.epfl.bluebrain.nexus.delta.sdk.stream.StreamConverter
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset.Start
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, RemainingElems}
import com.typesafe.scalalogging.Logger
import io.circe.Json
import io.circe.parser.decode
import fs2._
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/**
  * Collection of functions for interacting with a remote delta instance.
  */
trait DeltaClient {

  /**
    * Fetches the [[ProjectStatistics]] for the remote source
    */
  def projectStatistics(source: RemoteProjectSource): HttpResult[ProjectStatistics]

  /**
    * Fetches the [[RemainingElems]] for the remote source
    */
  def remaining(source: RemoteProjectSource, offset: Offset): HttpResult[RemainingElems]

  /**
    * Checks whether the events endpoint and token provided by the source are correct
    *
    * @param source
    *   the source
    */
  def checkElems(source: RemoteProjectSource): HttpResult[Unit]

  /**
    * Produces a stream of elems with their offset for the provided ''source''.
    *
    * @param source
    *   the remote source that is used to collect the server sent events
    * @param run
    *   the branch run we want to stream for
    * @param offset
    *   the initial offset
    */
  def elems(source: RemoteProjectSource, run: CompositeBranch.Run, offset: Offset): ElemStream[Unit]

  /**
    * Fetches a resource with a given id in n-quads format.
    */
  def resourceAsNQuads(source: RemoteProjectSource, id: Iri): HttpResult[Option[NQuads]]

  /**
    * Fetches a resource with a given id in n-quads format.
    */
  def resourceAsJson(source: RemoteProjectSource, id: Iri): HttpResult[Option[Json]]

}

object DeltaClient {

  private val logger: Logger = Logger[DeltaClient.type]

  private val accept = Accept(`application/json`.mediaType, RdfMediaTypes.`application/ld+json`)

  final private class DeltaClientImpl(client: HttpClient, retryDelay: FiniteDuration)(implicit
      as: ActorSystem[Nothing],
      scheduler: Scheduler
  ) extends DeltaClient {

    override def projectStatistics(source: RemoteProjectSource): HttpResult[ProjectStatistics] = {
      implicit val cred: Option[AuthToken] = token(source)
      val statisticsEndpoint: HttpRequest  =
        Get(
          source.endpoint / "projects" / source.project.organization.value / source.project.project.value / "statistics"
        ).addHeader(accept).withCredentials
      client.fromJsonTo[ProjectStatistics](statisticsEndpoint)
    }

    override def remaining(source: RemoteProjectSource, offset: Offset): HttpResult[RemainingElems] = {
      implicit val cred: Option[AuthToken] = token(source)
      val remainingEndpoint: HttpRequest   =
        Get(elemAddress(source) / "remaining")
          .addHeader(accept)
          .addHeader(`Last-Event-ID`(offset.value.toString))
          .withCredentials
      client.fromJsonTo[RemainingElems](remainingEndpoint)
    }

    override def checkElems(source: RemoteProjectSource): HttpResult[Unit] = {
      implicit val cred: Option[AuthToken] = token(source)
      client(Head(elemAddress(source)).withCredentials) {
        case resp if resp.status.isSuccess() => UIO.delay(resp.discardEntityBytes()) >> IO.unit
      }
    }

    override def elems(source: RemoteProjectSource, run: CompositeBranch.Run, offset: Offset): ElemStream[Unit] = {
      val initialLastEventId = offset match {
        case Start            => None
        case Offset.At(value) => Some(value.toString)
      }

      implicit val cred: Option[AuthToken] = token(source)

      def send(request: HttpRequest): Future[HttpResponse] = {
        client[HttpResponse](request.withCredentials)(IO.pure(_)).runToFuture
      }

      val suffix = run match {
        case CompositeBranch.Run.Main    => "continuous"
        case CompositeBranch.Run.Rebuild => "currents"
      }
      val uri    = elemAddress(source) / suffix
      StreamConverter(EventSource(uri, send, initialLastEventId, retryDelay))
        .flatMap { sse =>
          decode[Elem[Unit]](sse.data) match {
            case Right(elem) => Stream.emit(elem)
            case Left(err)   =>
              logger.error(s"Failed to decode sse event '$sse'", err)
              Stream.empty
          }
        }
    }

    private def elemAddress(source: RemoteProjectSource) =
      source.endpoint / "elems" / source.project.organization.value / source.project.project.value

    override def resourceAsNQuads(source: RemoteProjectSource, id: Iri): HttpResult[Option[NQuads]] = {
      implicit val cred: Option[AuthToken] = token(source)
      val resourceUrl: Uri                 =
        source.endpoint / "resources" / source.project.organization.value / source.project.project.value / "_" / id.toString
      val req                              = Get(
        source.resourceTag.fold(resourceUrl)(t => resourceUrl.withQuery(Query("tag" -> t.value)))
      ).addHeader(Accept(RdfMediaTypes.`application/n-quads`)).withCredentials
      client.fromEntityTo[String](req).map(nq => Some(NQuads(nq, id))).onErrorRecover {
        case HttpClientStatusError(_, StatusCodes.NotFound, _) => None
      }
    }

    override def resourceAsJson(source: RemoteProjectSource, id: Iri): HttpResult[Option[Json]] = {
      implicit val cred: Option[AuthToken] = token(source)
      val req                              = Get(
        source.endpoint / "resources" / source.project.organization.value / source.project.project.value / "_" / id.toString
      ).addHeader(accept).withCredentials
      client.toJson(req).map(Some(_)).onErrorRecover { case HttpClientStatusError(_, StatusCodes.NotFound, _) =>
        None
      }
    }

    private def token(source: RemoteProjectSource) =
      source.token.map { token => AuthToken(token.value.value) }
  }

  /**
    * Factory method for delta clients.
    */
  def apply(client: HttpClient, retryDelay: FiniteDuration)(implicit
      as: ActorSystem[Nothing],
      sc: Scheduler
  ): DeltaClient =
    new DeltaClientImpl(client, retryDelay)
}
