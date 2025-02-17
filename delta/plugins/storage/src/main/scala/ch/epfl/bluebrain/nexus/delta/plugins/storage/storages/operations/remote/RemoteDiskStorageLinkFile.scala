package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.LinkFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.MoveFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskStorageFileAttributes
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.AuthToken
import monix.bio.IO

class RemoteDiskStorageLinkFile(storage: RemoteDiskStorage)(implicit
    config: StorageTypeConfig,
    httpClient: HttpClient,
    as: ActorSystem
) extends LinkFile {
  implicit private val cred: Option[AuthToken] = storage.value.authToken(config)
  private val client: RemoteDiskStorageClient  = new RemoteDiskStorageClient(storage.value.endpoint)

  def apply(sourcePath: Uri.Path, description: FileDescription): IO[MoveFileRejection, FileAttributes] = {
    val destinationPath = intermediateFolders(storage.project, description.uuid, description.filename)
    client.moveFile(storage.value.folder, sourcePath, destinationPath).map {
      case RemoteDiskStorageFileAttributes(location, bytes, digest, _) =>
        FileAttributes(
          uuid = description.uuid,
          location = location,
          path = destinationPath,
          filename = description.filename,
          mediaType = description.mediaType,
          bytes = bytes,
          digest = digest,
          origin = Storage
        )
    }

  }
}
