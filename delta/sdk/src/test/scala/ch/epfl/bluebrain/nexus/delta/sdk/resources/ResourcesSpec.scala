package ch.epfl.bluebrain.nexus.delta.sdk.resources

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen, ResourceResolutionGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.FetchResource
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport.ResolverReport
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{ResolverResolutionRejection, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{IncorrectRev, InvalidJsonLdFormat, InvalidResource, InvalidSchemaRejection, ReservedResourceId, ResourceIsDeprecated, ResourceNotFound, RevisionNotFound, SchemaIsDeprecated, UnexpectedResourceSchema}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{ResourceCommand, ResourceEvent, ResourceRejection, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.Fixtures
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.{Latest, Revision}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit._
import monix.bio.{IO, UIO}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

import java.time.Instant

class ResourcesSpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValuable
    with Inspectors
    with IOFixedClock
    with IOValues
    with TestHelpers
    with CirceLiteral
    with OptionValues
    with Fixtures {

  "The Resources state machine" when {
    implicit val sc: Scheduler = Scheduler.global
    val epoch                  = Instant.EPOCH
    val time2                  = Instant.ofEpochMilli(10L)
    val subject                = User("myuser", Label.unsafe("myrealm"))
    val caller                 = Caller(subject, Set.empty)

    val project    = ProjectGen.resourceFor(ProjectGen.project("myorg", "myproject", base = nxv.base))
    val projectRef = project.value.ref

    val schemaSource = jsonContentOf("resources/schema.json").addContext(contexts.shacl, contexts.schemasMetadata)
    val schema1      = SchemaGen.schema(nxv + "myschema", projectRef, schemaSource)
    val schema2      = SchemaGen.schema(nxv + "myschema2", projectRef, schemaSource)

    val fetchSchema: (ResourceRef, ProjectRef) => FetchResource[Schema] = {
      case (ref, _) if ref.iri == schema2.id => UIO.some(SchemaGen.resourceFor(schema2, deprecated = true))
      case (ref, _) if ref.iri == schema1.id => UIO.some(SchemaGen.resourceFor(schema1))
      case _                                 => UIO.none
    }

    val resourceValidator = new ValidateResourceImpl(ResourceResolutionGen.singleInProject(projectRef, fetchSchema))

    val eval: (Option[ResourceState], ResourceCommand) => IO[ResourceRejection, ResourceEvent] =
      evaluate(resourceValidator)

    val myId   = nxv + "myid"
    val types  = Set(nxv + "Custom")
    val source = jsonContentOf("resources/resource.json", "id" -> myId)

    "evaluating an incoming command" should {
      "create a new event from a CreateResource command" in {
        forAll(List(Latest(schemas.resources), Latest(schema1.id))) { schemaRef =>
          val schemaRev    = Revision(schemaRef.iri, 1)
          val myIdResource = ResourceGen.resource(myId, projectRef, source, schemaRef)
          val comp         = myIdResource.compacted
          val exp          = myIdResource.expanded
          eval(
            None,
            CreateResource(myId, projectRef, schemaRef, source, comp, exp, caller)
          ).accepted shouldEqual
            ResourceCreated(myId, projectRef, schemaRev, projectRef, types, source, comp, exp, 1, epoch, subject)
        }
      }

      "create a new event from a UpdateResource command" in {
        forAll(List(None -> Latest(schemas.resources), Some(Latest(schema1.id)) -> Latest(schema1.id))) {
          case (schemaOptCmd, schemaEvent) =>
            val current   = ResourceGen.currentState(myId, projectRef, source, schemaEvent, types)
            val schemaRev = Revision(schemaEvent.iri, 1)

            val newSource = source.deepMerge(json"""{"@type": ["Custom", "Person"]}""")
            val newTpe    = Set(nxv + "Custom", nxv + "Person")
            val updated   = ResourceGen.resource(myId, projectRef, newSource, schemaEvent)
            val comp      = updated.compacted
            val exp       = updated.expanded
            eval(
              Some(current),
              UpdateResource(myId, projectRef, schemaOptCmd, newSource, comp, exp, 1, caller)
            ).accepted shouldEqual
              ResourceUpdated(myId, projectRef, schemaRev, projectRef, newTpe, newSource, comp, exp, 2, epoch, subject)
        }
      }

      "create a new event from a TagResource command" in {
        val list = List(
          (None, Latest(schemas.resources), false),
          (None, Latest(schema1.id), false),
          (Some(Latest(schema1.id)), Latest(schema1.id), true)
        )
        forAll(list) { case (schemaOptCmd, schemaEvent, deprecated) =>
          val current =
            ResourceGen.currentState(myId, projectRef, source, schemaEvent, types, rev = 2, deprecated = deprecated)

          eval(
            Some(current),
            TagResource(myId, projectRef, schemaOptCmd, 1, UserTag.unsafe("myTag"), 2, subject)
          ).accepted shouldEqual
            ResourceTagAdded(myId, projectRef, types, 1, UserTag.unsafe("myTag"), 3, epoch, subject)
        }
      }

      "create a new event from a DeleteResourceTag command" in {
        val list = List(
          (None, Latest(schemas.resources), false),
          (None, Latest(schema1.id), false),
          (Some(Latest(schema1.id)), Latest(schema1.id), true)
        )
        val tag  = UserTag.unsafe("myTag")
        forAll(list) { case (schemaOptCmd, schemaEvent, deprecated) =>
          val current =
            ResourceGen.currentState(
              myId,
              projectRef,
              source,
              schemaEvent,
              types,
              rev = 2,
              deprecated = deprecated,
              tags = Tags(tag -> 1)
            )

          eval(
            Some(current),
            DeleteResourceTag(myId, projectRef, schemaOptCmd, tag, 2, subject)
          ).accepted shouldEqual
            ResourceTagDeleted(myId, projectRef, types, UserTag.unsafe("myTag"), 3, epoch, subject)
        }
      }

      "create a new event from a DeprecateResource command" in {
        val list = List(
          None                     -> Latest(schemas.resources),
          None                     -> Latest(schema1.id),
          Some(Latest(schema1.id)) -> Latest(schema1.id)
        )
        forAll(list) { case (schemaOptCmd, schemaEvent) =>
          val current =
            ResourceGen.currentState(myId, projectRef, source, schemaEvent, types, rev = 2)

          eval(Some(current), DeprecateResource(myId, projectRef, schemaOptCmd, 2, subject)).accepted shouldEqual
            ResourceDeprecated(myId, projectRef, types, 3, epoch, subject)
        }
      }

      "reject with ReservedResourceId" in {
        forAll(List(Latest(schemas.resources), Latest(schema1.id))) { schemaRef =>
          val myId         = contexts + "some.json"
          val myIdResource = ResourceGen.resource(myId, projectRef, source, schemaRef)
          val comp         = myIdResource.compacted
          val exp          = myIdResource.expanded
          eval(
            None,
            CreateResource(myId, projectRef, schemaRef, source, comp, exp, caller)
          ).rejectedWith[ReservedResourceId]
        }
      }

      "reject with IncorrectRev" in {
        val current   = ResourceGen.currentState(myId, projectRef, source, Latest(schemas.resources), types)
        val compacted = current.compacted
        val expanded  = current.expanded
        val list      = List(
          current -> UpdateResource(myId, projectRef, None, source, compacted, expanded, 2, caller),
          current -> TagResource(myId, projectRef, None, 1, UserTag.unsafe("tag"), 2, subject),
          current -> DeleteResourceTag(myId, projectRef, None, UserTag.unsafe("tag"), 2, subject),
          current -> DeprecateResource(myId, projectRef, None, 2, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(Some(state), cmd).rejected shouldEqual IncorrectRev(provided = 2, expected = 1)
        }
      }

      "reject with InvalidResource" in {
        val current       = ResourceGen.currentState(myId, projectRef, source, Latest(schema1.id), types)
        val wrongSource   = source deepMerge json"""{"number": "unexpected"}"""
        val wrongResource = ResourceGen.resource(myId, projectRef, wrongSource)
        val compacted     = wrongResource.compacted
        val expanded      = wrongResource.expanded
        val schema        = Latest(schema1.id)
        val list          = List(
          None          -> CreateResource(myId, projectRef, schema, wrongSource, compacted, expanded, caller),
          Some(current) -> UpdateResource(myId, projectRef, None, wrongSource, compacted, expanded, 1, caller)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[InvalidResource]
        }
      }

      "reject with InvalidJsonLdFormat" in {
        val current       = ResourceGen.currentState(myId, projectRef, source, Latest(schema1.id), types)
        val wrongSource   = source deepMerge json"""{"other": {"@id": " http://nexus.example.com/myid"}}"""
        val wrongResource = ResourceGen.resource(myId, projectRef, wrongSource)
        val compacted     = wrongResource.compacted
        val expanded      = wrongResource.expanded
        val schema        = Latest(schema1.id)
        val list          = List(
          None          -> CreateResource(myId, projectRef, schema, wrongSource, compacted, expanded, caller),
          Some(current) -> UpdateResource(myId, projectRef, None, wrongSource, compacted, expanded, 1, caller)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[InvalidJsonLdFormat]
        }
      }

      "reject with SchemaIsDeprecated" in {
        val schema    = Latest(schema2.id)
        val current   = ResourceGen.currentState(myId, projectRef, source, schema, types)
        val compacted = current.compacted
        val expanded  = current.expanded
        val list      = List(
          None          -> CreateResource(myId, projectRef, schema, source, compacted, expanded, caller),
          Some(current) -> UpdateResource(myId, projectRef, None, source, compacted, expanded, 1, caller),
          Some(current) -> UpdateResource(myId, projectRef, Some(schema), source, compacted, expanded, 1, caller)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejected shouldEqual SchemaIsDeprecated(schema2.id)
        }
      }

      "reject with InvalidSchemaRejection" in {
        val notFoundSchema = Latest(nxv + "notFound")
        val current        = ResourceGen.currentState(myId, projectRef, source, Latest(schema1.id), types)
        val compacted      = current.compacted
        val expanded       = current.expanded
        eval(
          None,
          CreateResource(myId, projectRef, notFoundSchema, source, compacted, expanded, caller)
        ).rejected shouldEqual InvalidSchemaRejection(
          notFoundSchema,
          projectRef,
          ResourceResolutionReport(
            ResolverReport.failed(
              nxv + "in-project",
              projectRef -> ResolverResolutionRejection.ResourceNotFound(notFoundSchema.iri, projectRef)
            )
          )
        )
      }

      "reject with ResourceSchemaUnexpected" in {
        val current     = ResourceGen.currentState(myId, projectRef, source, Latest(schema1.id), types)
        val otherSchema = Some(Latest(schema2.id))
        val compacted   = current.compacted
        val expanded    = current.expanded
        eval(
          Some(current),
          UpdateResource(myId, projectRef, otherSchema, source, compacted, expanded, 1, caller)
        ).rejected shouldEqual
          UnexpectedResourceSchema(myId, provided = otherSchema.value, expected = Latest(schema1.id))
      }

      "reject with ResourceNotFound" in {
        val current   = ResourceGen.currentState(myId, projectRef, source, Latest(schemas.resources), types)
        val compacted = current.compacted
        val expanded  = current.expanded
        val list      = List(
          None -> UpdateResource(myId, projectRef, None, source, compacted, expanded, 1, caller),
          None -> TagResource(myId, projectRef, None, 1, UserTag.unsafe("myTag"), 1, subject),
          None -> DeprecateResource(myId, projectRef, None, 1, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[ResourceNotFound]
        }
      }

      "reject with ResourceIsDeprecated" in {
        val current   =
          ResourceGen.currentState(myId, projectRef, source, Latest(schemas.resources), types, deprecated = true)
        val compacted = current.compacted
        val expanded  = current.expanded
        val list      = List(
          current -> UpdateResource(myId, projectRef, None, source, compacted, expanded, 1, caller),
          current -> DeprecateResource(myId, projectRef, None, 1, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(Some(state), cmd).rejectedWith[ResourceIsDeprecated]
        }
      }

      "reject with RevisionNotFound" in {
        val current = ResourceGen.currentState(myId, projectRef, source, Latest(schemas.resources), types)
        eval(
          Some(current),
          TagResource(myId, projectRef, None, 3, UserTag.unsafe("myTag"), 1, subject)
        ).rejected shouldEqual
          RevisionNotFound(provided = 3, current = 1)
      }
    }

    "producing next state" should {
      val schema    = Latest(schemas.resources)
      val schemaRev = Revision(schemas.resources, 1)
      val tags      = Tags(UserTag.unsafe("a") -> 1)
      val current   = ResourceGen.currentState(myId, projectRef, source, schema, types, tags)
      val comp      = current.compacted
      val exp       = current.expanded

      "create a new ResourceCreated state" in {
        next(
          None,
          ResourceCreated(myId, projectRef, schemaRev, projectRef, types, source, comp, exp, 1, epoch, subject)
        ).value shouldEqual
          current.copy(
            createdAt = epoch,
            schema = schemaRev,
            createdBy = subject,
            updatedAt = epoch,
            updatedBy = subject,
            tags = Tags.empty
          )

        next(
          Some(current),
          ResourceCreated(myId, projectRef, schemaRev, projectRef, types, source, comp, exp, 1, time2, subject)
        ) shouldEqual None
      }

      "create a new ResourceUpdated state" in {
        val newTypes  = types + (nxv + "Other")
        val newSource = source deepMerge json"""{"key": "value"}"""
        next(
          None,
          ResourceUpdated(myId, projectRef, schemaRev, projectRef, newTypes, source, comp, exp, 1, time2, subject)
        ) shouldEqual None

        next(
          Some(current),
          ResourceUpdated(myId, projectRef, schemaRev, projectRef, newTypes, newSource, comp, exp, 2, time2, subject)
        ).value shouldEqual
          current.copy(
            rev = 2,
            source = newSource,
            updatedAt = time2,
            updatedBy = subject,
            types = newTypes
          )
      }

      "create new ResourceTagAdded state" in {
        val tag = UserTag.unsafe("tag")
        next(
          None,
          ResourceTagAdded(myId, projectRef, types, 1, tag, 2, time2, subject)
        ) shouldEqual None

        next(Some(current), ResourceTagAdded(myId, projectRef, types, 1, tag, 2, time2, subject)).value shouldEqual
          current.copy(rev = 2, updatedAt = time2, updatedBy = subject, tags = tags + (tag -> 1))
      }

      "create new ResourceDeprecated state" in {
        next(None, ResourceDeprecated(myId, projectRef, types, 1, time2, subject)) shouldEqual None

        next(Some(current), ResourceDeprecated(myId, projectRef, types, 2, time2, subject)).value shouldEqual
          current.copy(rev = 2, deprecated = true, updatedAt = time2, updatedBy = subject)
      }
    }
  }
}
