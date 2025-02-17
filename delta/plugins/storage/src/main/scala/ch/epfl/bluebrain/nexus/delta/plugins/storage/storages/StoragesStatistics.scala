package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import akka.http.scaladsl.model.Uri.Query
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.EventMetricsProjection.eventMetricsIndex
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageFetchRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageStatEntry
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.literal._
import io.circe.{DecodingFailure, JsonObject}
import monix.bio.{IO, UIO}

trait StoragesStatistics {

  /**
    * Retrieve the current statistics for a given storage in the given project
    */
  def get(idSegment: IdSegment, project: ProjectRef): IO[StorageFetchRejection, StorageStatEntry]

}

object StoragesStatistics {

  /**
    * @param client
    *   the Elasticsearch client
    * @param fetchStorageId
    *   the function to fetch the storage ID
    * @param indexPrefix
    *   the index prefix
    * @return
    *   StorageStatistics instance
    */
  def apply(
      client: ElasticSearchClient,
      fetchStorageId: (IdSegment, ProjectRef) => IO[StorageFetchRejection, Iri],
      indexPrefix: String
  ): StoragesStatistics = {
    val search = (jsonObject: JsonObject) =>
      client.search(jsonObject, Set(eventMetricsIndex(indexPrefix).value), Query.Empty)()

    (idSegment: IdSegment, project: ProjectRef) => {
      for {
        storageId <- fetchStorageId(idSegment, project)
        query     <- storageStatisticsQuery(project, storageId)
        result    <- search(query).hideErrors
        stats     <- IO.fromEither(result.as[StorageStatEntry]).hideErrors
      } yield stats
    }
  }

  /**
    * @param projectRef
    *   the project on which the statistics should be computed
    * @param storageId
    *   the ID of the storage on which the statistics should be computed
    * @return
    *   a query for the total number of files and the total size of a storage in a given project
    */
  private def storageStatisticsQuery(projectRef: ProjectRef, storageId: Iri): UIO[JsonObject] =
    IO.fromOption(
      json"""
         {
          "query": {
            "bool": {
              "filter": [
                { "term": { "@type.short": "File" } },
                { "term": { "project": $projectRef } },
                { "term": { "storage": $storageId } }
              ]
            }
          },
          "aggs": {
            "storageSize": { "sum": { "field": "bytes" } },
            "filesCount": { "sum": { "field": "newFileWritten" } }
          },
          "size": 0
        }
        """.asObject,
      DecodingFailure("Failed to decode ES statistics query.", List.empty)
    ).hideErrors

}
