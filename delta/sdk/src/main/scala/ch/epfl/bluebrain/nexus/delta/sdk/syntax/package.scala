package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.kernel.syntax.{ClassTagSyntax, InstantSyntax, KamonSyntax, TaskSyntax}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.{IriSyntax, IterableSyntax, JsonLdEncoderSyntax, JsonSyntax, PathSyntax, UriSyntax}

/**
  * Aggregate syntax from rdf plus the current sdk syntax to avoid importing multiple syntax
  */
package object syntax
    extends JsonSyntax
    with IriSyntax
    with IriEncodingSyntax
    with JsonLdEncoderSyntax
    with UriSyntax
    with PathSyntax
    with IterableSyntax
    with KamonSyntax
    with IOSyntax
    with HttpRequestSyntax
    with HttpResponseFieldsSyntax
    with ClassTagSyntax
    with TaskSyntax
    with InstantSyntax
    with NonEmptySetSyntax
