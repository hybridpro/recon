package recon

import com.twitter._
import util.{Future, FuturePool, Return, TimeoutException, Timer, JavaTimer}
import conversions.time._
import com.redis._
import serialization._
import java.util.concurrent.Executors

import scalaz._
import Scalaz._

trait ReconEngine { 
  type ReconId 

  implicit val timer = new JavaTimer

  // set up Executors
  val futures = FuturePool(Executors.newFixedThreadPool(8))

  def loadOneReconSet[T, K, V](defn: ReconDef[ReconId, T])(implicit clients: RedisClientPool, format: Format, parse: Parse[V], m: Monoid[V], p: ReconProtocol[T, K, V], mv: Manifest[V]) = clients.withClient {client =>
    import client._

    /**
     * Using 2 levels of hash. The first level stores the recon id as the key, the group key as the
     * field and key for the second level hash as the value. e.g.
     *
     *   +-----------------------------+           +---------------------------------------+
     *   | r1 | account120111212 |  x--|---------> | r1:account120111212 | quantity | 200  |
     *   +------------+----------------+           +---------------------+----------+------+
     *                |                                                 | amount    | 2000 |
     *                |                                                 +-----------+------+
     *        +-------+----------------+           +---------------------------------------+
     *        | account220111212 |  x--|---------> | r1:account220111212 | quantity | 100  |
     *        +------------------------+           +---------------------+----------+------+
     *                                                                  | amount    | 2500 |
     *                                                                  +-----------+------+
     *
     */                                                                  
    def load(value: T) {
      val gk = p.groupKey(value)
      val mvs = p.matchValues(value)
      val id = defn.id

      // group key + id = key to the second level hash
      val mapId = gk + ":" + id.toString

      // level 1 hash population
      hsetnx(id, gk, mapId)
      mvs.map {case (k, v) => 
        hsetnx(mapId, k, v) unless { // level 2 hash population
          hset(mapId, k, m append (hget[V](mapId, k).get, v)) 
        }
      }
    }

    // ugly, but don't want the guard check every time for no-predicate scenario
    if (!defn.maybePred.isDefined) 
      defn.values.foreach(load(_)) 
    else 
      for(v <- defn.values if defn.maybePred.get(v) == true) load(v)

    hlen(id)
  }

  def loadReconInputData[T, K, V](ds: Seq[ReconDef[ReconId, T]])(implicit clients: RedisClientPool, parse: Parse[V], m: Monoid[V], p: ReconProtocol[T, K, V], mv: Manifest[V]): Seq[Option[Int]] = {
    val fs =
      ds.map {d =>
        futures {
          loadOneReconSet(d)
        }.within(120.seconds) handle {
          case _: TimeoutException => None
        }
      }
    Future.collect(fs.toSeq) apply
  }

  def recon[K, V](ids: Seq[ReconId], matchFn: List[Option[List[V]]] => Boolean)(implicit clients: RedisClientPool, parsev: Parse[V], parsek: Parse[K], m: Monoid[V]) = {

    val fields = clients.withClient {client =>
      ids.map(client.hkeys[K](_)).view.flatten.flatten.toSet 
    }

    fields.par.map {field => 
      clients.withClient {client =>
        import client._
        val maps: Seq[Option[List[V]]] = ids.map {id =>
          val hk = hget[String](id, field)
          hk match {
            case Some(s) => hgetall[String, V](s).map(_.values).map(_.toList)
            case None => none[List[V]]
          }
        }
        (field, matchFn(maps.toList))
      }
    }
  }
}

