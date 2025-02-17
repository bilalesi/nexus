package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, Route}
import cats.data.NonEmptySet
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.RealmsRoutes.RealmInput
import ch.epfl.bluebrain.nexus.delta.routes.RealmsRoutes.RealmInput._
import ch.epfl.bluebrain.nexus.delta.sdk.RealmResource
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.RealmSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Name}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{realms => realmsPermissions}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.{Realm, RealmRejection}
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

import scala.annotation.nowarn

class RealmsRoutes(identities: Identities, realms: Realms, aclCheck: AclCheck)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling {

  import baseUri.prefixSegment

  private def realmsSearchParams: Directive1[RealmSearchParams] =
    searchParams.tmap { case (deprecated, rev, createdBy, updatedBy) =>
      RealmSearchParams(None, deprecated, rev, createdBy, updatedBy)
    }

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      pathPrefix("realms") {
        extractCaller { implicit caller =>
          concat(
            // List realms
            (get & extractUri & fromPaginated & realmsSearchParams & sort[Realm] & pathEndOrSingleSlash) {
              (uri, pagination, params, order) =>
                operationName(s"$prefixSegment/realms") {
                  authorizeFor(AclAddress.Root, realmsPermissions.read).apply {
                    implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[RealmResource]] =
                      searchResultsJsonLdEncoder(Realm.context, pagination, uri)

                    emit(realms.list(pagination, params, order).widen[SearchResults[RealmResource]])
                  }
                }
            },
            (label & pathEndOrSingleSlash) { id =>
              operationName(s"$prefixSegment/realms/{label}") {
                concat(
                  // Create or update a realm
                  put {
                    authorizeFor(AclAddress.Root, realmsPermissions.write).apply {
                      parameter("rev".as[Int].?) {
                        case Some(rev) =>
                          // Update a realm
                          entity(as[RealmInput]) { case RealmInput(name, openIdConfig, logo, acceptedAudiences) =>
                            emit(
                              realms.update(id, rev, name, openIdConfig, logo, acceptedAudiences).mapValue(_.metadata)
                            )
                          }
                        case None      =>
                          // Create a realm
                          entity(as[RealmInput]) { case RealmInput(name, openIdConfig, logo, acceptedAudiences) =>
                            emit(
                              StatusCodes.Created,
                              realms.create(id, name, openIdConfig, logo, acceptedAudiences).mapValue(_.metadata)
                            )
                          }
                      }
                    }
                  },
                  // Fetch a realm
                  get {
                    authorizeFor(AclAddress.Root, realmsPermissions.read).apply {
                      parameter("rev".as[Int].?) {
                        case Some(rev) => // Fetch realm at specific revision
                          emit(realms.fetchAt(id, rev).leftWiden[RealmRejection])
                        case None      => // Fetch realm
                          emit(realms.fetch(id).leftWiden[RealmRejection])
                      }
                    }
                  },
                  // Deprecate realm
                  delete {
                    authorizeFor(AclAddress.Root, realmsPermissions.write).apply {
                      parameter("rev".as[Int]) { rev => emit(realms.deprecate(id, rev).mapValue(_.metadata)) }
                    }
                  }
                )
              }
            }
          )
        }
      }
    }
}

object RealmsRoutes {

  @nowarn("cat=unused")
  implicit final private val configuration: Configuration = Configuration.default.withStrictDecoding

  final private[routes] case class RealmInput(
      name: Name,
      openIdConfig: Uri,
      logo: Option[Uri],
      acceptedAudiences: Option[NonEmptySet[String]]
  )
  private[routes] object RealmInput {
    implicit val realmDecoder: Decoder[RealmInput] = deriveConfiguredDecoder[RealmInput]
  }

  /**
    * @return
    *   the [[Route]] for realms
    */
  def apply(identities: Identities, realms: Realms, aclCheck: AclCheck)(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    new RealmsRoutes(identities, realms, aclCheck).routes

}
