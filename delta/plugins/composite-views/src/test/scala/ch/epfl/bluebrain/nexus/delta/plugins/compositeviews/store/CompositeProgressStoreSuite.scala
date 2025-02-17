package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart.{FullRebuild, FullRestart, PartialRebuild}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch.Run
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionProgress
import ch.epfl.bluebrain.nexus.testkit.IOFixedClock
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import munit.AnyFixture

import java.time.Instant

class CompositeProgressStoreSuite extends BioSuite with IOFixedClock with Doobie.Fixture with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = new CompositeProgressStore(xas)

  private val project = ProjectRef.unsafe("org", "proj")
  private val view    = ViewRef(project, nxv + "id")
  private val rev     = 2
  private val source  = nxv + "source"
  private val target1 = nxv + "target1"
  private val target2 = nxv + "target2"

  private val mainBranch1      = CompositeBranch(source, target1, Run.Main)
  private val rebuildBranch1   = CompositeBranch(source, target1, Run.Rebuild)
  private val mainProgress1    = ProjectionProgress(Offset.At(42L), Instant.EPOCH, 5, 2, 1)
  private val rebuildProgress1 = ProjectionProgress(Offset.At(21L), Instant.EPOCH, 4, 1, 0)

  private val mainBranch2      = CompositeBranch(source, target2, Run.Main)
  private val rebuildBranch2   = CompositeBranch(source, target2, Run.Rebuild)
  private val mainProgress2    = ProjectionProgress(Offset.At(22L), Instant.EPOCH, 2, 1, 0)
  private val rebuildProgress2 = ProjectionProgress(Offset.At(33L), Instant.EPOCH, 4, 2, 0)

  private val view2         = ViewRef(project, nxv + "id2")
  private val view2Progress = ProjectionProgress(Offset.At(999L), Instant.EPOCH, 514, 140, 0)

  // Check that view 2 is not affected by changes on view 1
  private def assertView2 = {
    val expected = Map(mainBranch1 -> view2Progress, rebuildBranch1 -> view2Progress)
    store.progress(view2, rev).assert(expected)
  }

  // Save progress for view 1
  private def saveView1 =
    for {
      _ <- store.save(view, rev, mainBranch1, mainProgress1)
      _ <- store.save(view, rev, rebuildBranch1, rebuildProgress1)
      _ <- store.save(view, rev, mainBranch2, mainProgress2)
      _ <- store.save(view, rev, rebuildBranch2, rebuildProgress2)
    } yield ()

  test("Return no progress") {
    store.progress(view, rev).assert(Map.empty)
  }

  test("Save progress for all branches and views") {
    for {
      _ <- saveView1
      _ <- store.save(view2, rev, mainBranch1, view2Progress)
      _ <- store.save(view2, rev, rebuildBranch1, view2Progress)
    } yield ()
  }

  test("Return new progress") {
    val expected = Map(
      mainBranch1    -> mainProgress1,
      rebuildBranch1 -> rebuildProgress1,
      mainBranch2    -> mainProgress2,
      rebuildBranch2 -> rebuildProgress2
    )
    for {
      _ <- store.progress(view, rev).assert(expected)
      _ <- assertView2
    } yield ()
  }

  test("Update progress for main branch") {
    val newProgress = mainProgress1.copy(processed = 100L)
    val expected    = Map(
      mainBranch1    -> newProgress,
      rebuildBranch1 -> rebuildProgress1,
      mainBranch2    -> mainProgress2,
      rebuildBranch2 -> rebuildProgress2
    )
    for {
      _ <- store.save(view, rev, mainBranch1, newProgress)
      _ <- store.save(view, rev, rebuildBranch1, rebuildProgress1)
      _ <- store.progress(view, rev).assert(expected)
      _ <- assertView2
    } yield ()
  }

  test("Delete progresses for the view") {
    for {
      _ <- store.deleteAll(view, rev)
      _ <- store.progress(view, rev).assert(Map.empty)
      _ <- assertView2
    } yield ()
  }

  test("Apply a full restart on view 1") {
    val fr       = FullRestart(project, view.viewId, Instant.EPOCH, Anonymous)
    val expected = Map(
      mainBranch1    -> ProjectionProgress.NoProgress,
      rebuildBranch1 -> ProjectionProgress.NoProgress,
      mainBranch2    -> ProjectionProgress.NoProgress,
      rebuildBranch2 -> ProjectionProgress.NoProgress
    )
    for {
      _ <- saveView1
      _ <- store.restart(fr)
      _ <- store.progress(view, rev).assert(expected)
      _ <- assertView2
    } yield ()
  }

  test("Apply a full rebuild on view 1") {
    val fr       = FullRebuild(project, view.viewId, Instant.EPOCH, Anonymous)
    val expected = Map(
      mainBranch1    -> mainProgress1,
      rebuildBranch1 -> ProjectionProgress.NoProgress,
      mainBranch2    -> mainProgress2,
      rebuildBranch2 -> ProjectionProgress.NoProgress
    )
    for {
      _ <- saveView1
      _ <- store.restart(fr)
      _ <- store.progress(view, rev).assert(expected)
      _ <- assertView2
    } yield ()
  }

  test("Apply a partial rebuild on view 1") {
    val pr       = PartialRebuild(project, view.viewId, target1, Instant.EPOCH, Anonymous)
    val expected = Map(
      mainBranch1    -> mainProgress1,
      rebuildBranch1 -> ProjectionProgress.NoProgress,
      mainBranch2    -> mainProgress2,
      rebuildBranch2 -> rebuildProgress2
    )
    for {
      _ <- saveView1
      _ <- store.restart(pr)
      _ <- store.progress(view, rev).assert(expected)
      _ <- assertView2
    } yield ()
  }

}
