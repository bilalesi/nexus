package ch.epfl.bluebrain.nexus.delta.sdk.identities.model

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.TokenRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.Fixtures
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class TokenRejectionSpec
    extends AnyWordSpecLike
    with Matchers
    with Inspectors
    with CirceLiteral
    with TestHelpers
    with IOValues
    with Fixtures {

  "A TokenRejection" should {

    val invalidFormat = InvalidAccessTokenFormat
    val noIssuer      = AccessTokenDoesNotContainSubject

    "be converted to compacted JSON-LD" in {
      val list = List(
        noIssuer      -> json"""{"@type": "AccessTokenDoesNotContainSubject", "reason": "${noIssuer.reason}"}""",
        invalidFormat -> json"""{"@type": "InvalidAccessTokenFormat", "reason": "${invalidFormat.reason}"}"""
      )
      forAll(list) { case (rejection, json) =>
        rejection.toCompactedJsonLd.accepted.json shouldEqual json.addContext(contexts.error)
      }
    }

    "be converted to expanded JSON-LD" in {
      val list = List(
        noIssuer      -> json"""[{"@type": ["${nxv + "AccessTokenDoesNotContainSubject"}"], "${nxv + "reason"}": [{"@value": "${noIssuer.reason}"} ] } ]""",
        invalidFormat -> json"""[{"@type": ["${nxv + "InvalidAccessTokenFormat"}"], "${nxv + "reason"}": [{"@value": "${invalidFormat.reason}"} ] } ]"""
      )
      forAll(list) { case (rejection, json) =>
        rejection.toExpandedJsonLd.accepted.json shouldEqual json
      }
    }
  }

}
