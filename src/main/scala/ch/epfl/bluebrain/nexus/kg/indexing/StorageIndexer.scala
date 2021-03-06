package ch.epfl.bluebrain.nexus.kg.indexing

import java.time.Instant

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Source}
import akka.util.Timeout
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.AdminClient
import ch.epfl.bluebrain.nexus.kg.cache.{ProjectCache, StorageCache}
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.storage.Storage
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressFlow.{PairMsg, ProgressFlowElem}
import ch.epfl.bluebrain.nexus.sourcing.projections._
import com.typesafe.scalalogging.Logger

import scala.concurrent.ExecutionContext

// $COVERAGE-OFF$
object StorageIndexer {

  private implicit val log = Logger[StorageIndexer.type]

  def start[F[_]: Timer](storages: Storages[F], storageCache: StorageCache[F])(
      implicit projectCache: ProjectCache[F],
      F: Effect[F],
      as: ActorSystem,
      projectInitializer: ProjectInitializer[F],
      adminClient: AdminClient[F],
      config: AppConfig
  ): StreamSupervisor[F, Unit] = {

    implicit val authToken                = config.iam.serviceAccountToken
    implicit val indexing: IndexingConfig = config.keyValueStore.indexing
    implicit val ec: ExecutionContext     = as.dispatcher
    implicit val tm: Timeout              = Timeout(config.keyValueStore.askTimeout)
    val name                              = "storage-indexer"

    def toStorage(event: Event): F[Option[(Storage, Instant)]] =
      fetchProject(event.organization, event.id.parent, event.subject).flatMap { implicit project =>
        storages.fetchStorage(event.id).value.map {
          case Left(err) =>
            log.error(s"Error on event '${event.id.show} (rev = ${event.rev})', cause: '${err.msg}'")
            None
          case Right(timedStorage) => Some(timedStorage)
        }
      }

    val source: Source[PairMsg[Any], _] = cassandraSource(s"type=${nxv.Storage.value.show}", name)
    val flow: Flow[PairMsg[Any], Unit, _] = ProgressFlowElem[F, Any]
      .collectCast[Event]
      .groupedWithin(indexing.batch, indexing.batchTimeout)
      .distinct()
      .mergeEmit()
      .mapAsync(toStorage)
      .collectSome[(Storage, Instant)]
      .runAsync { case (storage, instant) => storageCache.put(storage)(instant) }()
      .flow
      .map(_ => ())

    StreamSupervisor.startSingleton(F.delay(source.via(flow)), name)
  }
}
// $COVERAGE-ON$
