package ch.epfl.bluebrain.nexus.tests.admin

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import ch.epfl.bluebrain.nexus.tests.Identity.orgs.{Fry, Leela}
import ch.epfl.bluebrain.nexus.tests.Optics._
import ch.epfl.bluebrain.nexus.tests.{BaseSpec, ExpectedResponse}
import io.circe.Json
import monix.execution.Scheduler.Implicits.global

class OrgsSpec extends BaseSpec with EitherValuable {

  import ch.epfl.bluebrain.nexus.tests.iam.types.Permission._

  private val UnauthorizedAccess = ExpectedResponse(
    StatusCodes.Forbidden,
    jsonContentOf("/iam/errors/unauthorized-access.json")
  )

  private val OrganizationConflict = ExpectedResponse(
    StatusCodes.Conflict,
    jsonContentOf("/admin/errors/org-incorrect-revision.json")
  )

  "creating an organization" should {
    "fail if the permissions are missing" in {
      adminDsl.createOrganization(
        genId(),
        "Description",
        Fry,
        Some(UnauthorizedAccess)
      )
    }

    "add necessary permissions for user" in {
      aclDsl.addPermission(
        "/",
        Fry,
        Organizations.Create
      )
    }

    val id = genId()
    "succeed if payload is correct" in {
      adminDsl.createOrganization(
        id,
        "Description",
        Fry
      )
    }

    "check if permissions have been created for user" in {
      aclDsl.checkAdminAcls(s"/$id", Fry)
    }

    "fail if organization already exists" in {
      val duplicate = genId()

      for {
        _ <- adminDsl.createOrganization(
               duplicate,
               "Description",
               Fry
             )
        _ <- adminDsl.createOrganization(
               duplicate,
               "Description",
               Fry,
               Some(
                 ExpectedResponse(
                   StatusCodes.Conflict,
                   jsonContentOf("/admin/errors/org-already-exists.json", "orgId" -> duplicate)
                 )
               )
             )
      } yield succeed
    }
  }

  "fetching an organization" should {
    val id = genId()
    "fail if the permissions are missing" in {
      for {
        _ <- adminDsl.createOrganization(
               id,
               s"Description $id",
               Fry
             )
        _ <- deltaClient.get[Json](s"/orgs/$id", Leela) { (json, response) =>
               response.status shouldEqual StatusCodes.Forbidden
               json shouldEqual jsonContentOf("/iam/errors/unauthorized-access.json")
             }
      } yield succeed
    }

    "add orgs/read permissions for user" in {
      aclDsl.addPermission(
        "/",
        Leela,
        Organizations.Read
      )
    }

    "succeed if organization exists" in {
      deltaClient.get[Json](s"/orgs/$id", Leela) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        admin.validate(json, "Organization", "orgs", id, s"Description $id", 1, id)
      }
    }

    "fetch organization by UUID" in {
      deltaClient.get[Json](s"/orgs/$id", Leela) { (jsonById, _) =>
        runTask {
          val orgUuid = _uuid.getOption(jsonById).value

          deltaClient.get[Json](s"/orgs/$orgUuid", Leela) { (jsonByUuid, response) =>
            response.status shouldEqual StatusCodes.OK
            jsonByUuid shouldEqual jsonById
          }
        }
      }
    }

    "return not found when fetching a non existing revision of an organizations" in {
      deltaClient.get[Json](s"/orgs/$id?rev=3", Leela) { (_, response) =>
        response.status shouldEqual StatusCodes.NotFound
      }
    }

    val nonExistent = genId()
    "add orgs/read permissions for non-existing organization" in {
      aclDsl.addPermission(
        s"/$nonExistent",
        Leela,
        Organizations.Create
      )
    }

    "return not found when fetching a non existing organization" in {
      deltaClient.get[Json](s"/orgs/$nonExistent", Leela) { (_, response) =>
        response.status shouldEqual StatusCodes.NotFound
      }
    }
  }

  "updating an organization" should {
    val id          = genString()
    val description = s"$id organization"

    "fail if the permissions are missing" in {
      adminDsl.createOrganization(
        id,
        description,
        Leela,
        Some(UnauthorizedAccess)
      )
    }

    "add orgs/create permissions for user" in {
      aclDsl.addPermission(
        "/",
        Leela,
        Organizations.Create
      )
    }

    "create organization" in {
      adminDsl.createOrganization(
        id,
        description,
        Leela
      )
    }

    "fail when wrong revision is provided" in {
      adminDsl.updateOrganization(
        id,
        description,
        Leela,
        4,
        Some(OrganizationConflict)
      )
    }

    val nonExistent = genId()
    "add orgs/write permissions for non-existing organization" in {
      aclDsl.addPermission(
        s"/$nonExistent",
        Leela,
        Organizations.Write
      )
    }

    "fail when organization does not exist" in {
      val notFound = ExpectedResponse(
        StatusCodes.NotFound,
        jsonContentOf("/admin/errors/not-exists.json", "orgId" -> nonExistent)
      )
      adminDsl.updateOrganization(
        nonExistent,
        description,
        Leela,
        1,
        Some(notFound)
      )
    }

    "succeed and fetch revisions" in {
      val updatedName  = s"$id organization update 1"
      val updatedName2 = s"$id organization update 2"

      for {
        _ <- adminDsl.updateOrganization(
               id,
               updatedName,
               Leela,
               1
             )
        _ <- adminDsl.updateOrganization(
               id,
               updatedName2,
               Leela,
               2
             )
        _ <- deltaClient.get[Json](s"/orgs/$id", Leela) { (lastVersion, response) =>
               runTask {
                 response.status shouldEqual StatusCodes.OK
                 admin.validate(lastVersion, "Organization", "orgs", id, updatedName2, 3, id)
                 deltaClient.get[Json](s"/orgs/$id?rev=3", Leela) { (thirdVersion, response) =>
                   response.status shouldEqual StatusCodes.OK
                   thirdVersion shouldEqual lastVersion
                 }
               }
             }
        _ <- deltaClient.get[Json](s"/orgs/$id?rev=2", Leela) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               admin.validate(json, "Organization", "orgs", id, updatedName, 2, id)
             }
        _ <- deltaClient.get[Json](s"/orgs/$id?rev=1", Leela) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               admin.validate(json, "Organization", "orgs", id, s"$id organization", 1, id)
             }
      } yield succeed
    }
  }

  "deprecating an organization" should {
    val id   = genId()
    val name = genString()

    "add orgs/create permissions for user" in {
      aclDsl.addPermission(
        "/",
        Leela,
        Organizations.Create
      )
    }

    "create the organization" in {
      adminDsl.createOrganization(
        id,
        name,
        Leela
      )
    }

    "fail when wrong revision is provided" in {
      deltaClient.delete[Json](s"/orgs/$id?rev=4", Leela) { (json, response) =>
        response.status shouldEqual StatusCodes.Conflict
        json shouldEqual jsonContentOf("/admin/errors/org-incorrect-revision.json")
      }
    }

    "fail when revision is not provided" in {
      deltaClient.delete[Json](s"/orgs/$id", Leela) { (json, response) =>
        response.status shouldEqual StatusCodes.BadRequest
        json shouldEqual jsonContentOf("/admin/errors/rev-not-provided.json")
      }
    }

    "succeed if organization exists" in {
      for {
        _ <- adminDsl.deprecateOrganization(id, Leela)
        _ <- deltaClient.get[Json](s"/orgs/$id", Leela) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               admin.validate(json, "Organization", "orgs", id, name, 2, id, deprecated = true)
             }
        _ <- deltaClient.get[Json](s"/orgs/$id?rev=1", Leela) { (json, response) =>
               response.status shouldEqual StatusCodes.OK
               admin.validate(json, "Organization", "orgs", id, name, 1, id)
             }
      } yield succeed
    }
  }
}
