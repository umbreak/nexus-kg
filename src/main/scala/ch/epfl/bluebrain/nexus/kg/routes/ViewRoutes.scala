package ch.epfl.bluebrain.nexus.kg.routes

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.test.Resources.jsonContentOf
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.kg.async.Caches
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.AppConfig.tracing._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.directives.AuthDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.PathDirectives._
import ch.epfl.bluebrain.nexus.kg.indexing.View
import ch.epfl.bluebrain.nexus.kg.indexing.View._
import ch.epfl.bluebrain.nexus.kg.indexing.ViewEncoder._
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.NotFound
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import io.circe.Json
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

class ViewRoutes private[routes] (resources: Resources[Task], acls: AccessControlLists, caller: Caller)(
    implicit project: Project,
    cache: Caches[Task],
    indexers: Clients[Task],
    config: AppConfig,
    um: FromEntityUnmarshaller[String])
    extends CommonRoutes(resources, "views", acls, caller, cache.view) {

  private val emptyEsList: Json                          = jsonContentOf("/elastic/empty-list.json")
  private val transformation: Transformation[Task, View] = Transformation.view

  private implicit val projectCache = cache.project
  private implicit val viewCache    = cache.view
  private implicit val esClient     = indexers.elastic
  private implicit val ujClient     = indexers.uclJson

  def routes: Route = {
    val viewRefOpt = Some(viewRef)
    create(viewRef) ~ list(viewRefOpt) ~ sparql ~ elasticSearch ~
      pathPrefix(IdSegment) { id =>
        concat(
          update(id, viewRefOpt),
          create(id, viewRef),
          tag(id, viewRefOpt),
          deprecate(id, viewRefOpt),
          fetch(id, viewRefOpt)
        )
      }
  }

  override implicit def additional: AdditionalValidation[Task] = AdditionalValidation.view[Task](caller, acls)

  override def transform(r: ResourceV): Task[ResourceV] = transformation(r)

  private def sparql: Route =
    pathPrefix(IdSegment / "sparql") { id =>
      (post & entity(as[String]) & hasPermission(queryPermission) & pathEndOrSingleSlash) { query =>
        val result: Task[Either[Rejection, Json]] = viewCache.getBy[SparqlView](project.ref, id).flatMap {
          case Some(v) => indexers.sparql.copy(namespace = v.name).queryRaw(query).map(Right.apply)
          case _       => Task.pure(Left(NotFound(id.ref)))
        }
        trace("searchSparql")(complete(result.runToFuture))
      }
    }

  private def elasticSearch: Route =
    pathPrefix(IdSegment / "_search") { id =>
      (post & entity(as[Json]) & extract(_.request.uri.query()) & hasPermission(queryPermission) & pathEndOrSingleSlash) {
        (query, params) =>
          val result: Task[Either[Rejection, Json]] = viewCache.getBy[View](project.ref, id).flatMap {
            case Some(v: ElasticView) => indexers.elastic.searchRaw(query, Set(v.index), params).map(Right.apply)
            case Some(AggregateElasticViewRefs(v)) =>
              allowedIndices(v).flatMap {
                case indices if indices.isEmpty => Task.pure[Either[Rejection, Json]](Right(emptyEsList))
                case indices                    => indexers.elastic.searchRaw(query, indices.toSet, params).map(Right.apply)
              }
            case _ => Task.pure(Left(NotFound(id.ref)))
          }
          trace("searchElastic")(complete(result.runToFuture))
      }
    }

  private def allowedIndices(v: AggregateElasticViewRefs): Task[List[String]] =
    v.value.toList.foldM(List.empty[String]) {
      case (acc, ViewRef(ref, id)) =>
        (cache.view.getBy[ElasticView](ref, id) -> cache.project.getLabel(ref)).mapN {
          case (Some(view), Some(label)) if !view.deprecated && caller.hasPermission(acls, label, queryPermission) =>
            view.index :: acc
          case _ =>
            acc
        }
    }
}
