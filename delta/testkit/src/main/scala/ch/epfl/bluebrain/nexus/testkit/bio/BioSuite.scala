package ch.epfl.bluebrain.nexus.testkit.bio

import ch.epfl.bluebrain.nexus.testkit.IOFixedClock
import monix.bio.IO
import monix.execution.Scheduler
import munit.FunSuite

import scala.concurrent.duration.{DurationInt, FiniteDuration}

abstract class BioSuite
    extends FunSuite
    with BioFixtures
    with BioFunFixtures
    with BioAssertions
    with StreamAssertions
    with CollectionAssertions
    with EitherAssertions
    with IOFixedClock {

  implicit protected val scheduler: Scheduler     = Scheduler.global
  implicit protected val classLoader: ClassLoader = getClass.getClassLoader

  protected val ioTimeout: FiniteDuration = 45.seconds

  override def munitValueTransforms: List[ValueTransform] =
    super.munitValueTransforms ++ List(munitIOTransform)

  private val munitIOTransform: ValueTransform =
    new ValueTransform(
      "IO",
      { case io: IO[_, _] =>
        io.timeout(ioTimeout)
          .mapError {
            case t: Throwable => t
            case other        =>
              fail(
                s"""Error caught of type '${other.getClass.getName}', expected a successful response
                   |Error value: $other""".stripMargin
              )
          }
          .runToFuture
      }
    )
}
