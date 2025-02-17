package ch.epfl.bluebrain.nexus.delta.plugins.archive

import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.contexts
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{contexts => fileContexts}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{contexts => storageContexts}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.testkit.IOValues

trait RemoteContextResolutionFixture extends IOValues {
  implicit private val cl: ClassLoader = getClass.getClassLoader

  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixed(
    storageContexts.storages         -> ContextValue.fromFile("contexts/storages.json").accepted,
    storageContexts.storagesMetadata -> ContextValue.fromFile("contexts/storages-metadata.json").accepted,
    fileContexts.files               -> ContextValue.fromFile("contexts/files.json").accepted,
    contexts.archives                -> ContextValue.fromFile("contexts/archives.json").accepted,
    contexts.archivesMetadata        -> ContextValue.fromFile("contexts/archives-metadata.json").accepted,
    Vocabulary.contexts.metadata     -> ContextValue.fromFile("contexts/metadata.json").accepted,
    Vocabulary.contexts.error        -> ContextValue.fromFile("contexts/error.json").accepted,
    Vocabulary.contexts.tags         -> ContextValue.fromFile("contexts/tags.json").accepted
  )
}

object RemoteContextResolutionFixture extends RemoteContextResolutionFixture
