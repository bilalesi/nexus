package ch.epfl.bluebrain.nexus.delta.sourcing.syntax

import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Message
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionStream.{ChunkStreamOps, SimpleStreamOps}
import fs2.{Chunk, Stream}
import monix.bio.Task
import monix.execution.Scheduler

trait ProjectionStreamSyntax {
  implicit final def simpleStreamSyntax[A](
      stream: Stream[Task, Message[A]]
  )(implicit scheduler: Scheduler): SimpleStreamOps[A] =
    new SimpleStreamOps(stream)

  implicit final def chunkStreamSyntax[A](stream: Stream[Task, Chunk[Message[A]]]): ChunkStreamOps[A] =
    new ChunkStreamOps(stream)
}
