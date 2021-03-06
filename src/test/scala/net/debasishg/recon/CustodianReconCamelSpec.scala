package net.debasishg.recon

import scala.collection.parallel.ParSet
import org.scalatest.{Spec, BeforeAndAfterEach, BeforeAndAfterAll}
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import com.redis._
import MatchFunctions._

import sjson.json.DefaultProtocol._
import Util._
import FileUtils._
import com.redis.serialization._
import org.joda.time.{DateTime, LocalDate}

import scalaz._
import Scalaz._

import akka.actor.{Actor, ActorRef}
import akka.camel.{Message, Consumer}
import akka.camel.CamelServiceManager._
import akka.actor.Actor._
import akka.camel.CamelContextManager
import akka.dispatch.Future
import akka.event.EventHandler

import ReconActors._

class CustodianReconConsumer(engine: ReconEngine[CustodianFetchValue, Double], 
  totalNoOfFiles: Int, date: String)
  (implicit clients: RedisClientPool, 
            parse: Parse[Double], 
            m: Monoid[Double], 
            r: ReconProtocol[CustodianFetchValue, Double], 
            p: Parse[MatchList[Double]], 
            f: Format) extends ReconConsumer[CustodianFetchValue, Double](engine, totalNoOfFiles) {

  override def endpointUri = "file:/Users/debasishghosh/projects/recon/src/test/resources/australia/" + date + "?noop=true&include=.*\\.(txt|csv)&sortBy=reverse:file:name"

  override def getSourceConfig(file: String): ReconSource[CustodianFetchValue] =
    if (file contains "DATA_CUSTODIAN_A") CustodianAConfig
    else if (file contains "DATA_CUSTODIAN_B") CustodianBConfig
    else CustodianCConfig
}

@RunWith(classOf[JUnitRunner])
class CustodianReconCamelSpec extends Spec 
                              with ShouldMatchers
                              with BeforeAndAfterEach
                              with BeforeAndAfterAll {

  implicit val clients = new RedisClientPool("localhost", 6379)
  implicit val format = Format {case l: MatchList[Double] => serializeMatchList(l)}
  implicit val parseList = Parse[MatchList[Double]](deSerializeMatchList[Double](_))
  import Parse.Implicits.parseDouble

  override def beforeEach = {
  }

  override def afterEach = clients.withClient{
    client => client.flushdb
  }

  override def afterAll = {
    clients.withClient {client => client.disconnect}
    clients.close
  }

  def runReconFor(date: LocalDate, dateString: String) = {
    val engine = new CustodianReconEngine {
      override val runDate = date
    }
    import engine._
    actorOf(new CustodianReconConsumer(engine, 3, dateString)).start 
  }

  describe("Custodian A B and C for 2010-10-24") {
    it("should load csv data from file") {
      startCamelService
      CamelContextManager.init  // optionally takes a CamelContext as argument
      CamelContextManager.start // starts the managed CamelContext

      EventHandler.info(this, "start: " + System.currentTimeMillis)

      runReconFor(new DateTime("2010-10-24").toLocalDate, "20101024")
      runReconFor(new DateTime("2010-10-25").toLocalDate, "20101025")
      runReconFor(new DateTime("2010-10-26").toLocalDate, "20101026")

      Thread.sleep(12000)

      clients.withClient {client => 
        EventHandler.info(this, "No of Breaks: " + ReconUtils.fetchBreakEntries[Double](client, "australia-bank", new DateTime("2010-10-24").toLocalDate).get.keys.size)
        EventHandler.info(this, "No of Matches: " + ReconUtils.fetchMatchEntries[Double](client, "australia-bank", new DateTime("2010-10-24").toLocalDate).get.keys.size)
        EventHandler.info(this, "No of Unmatches: " + ReconUtils.fetchUnmatchEntries[Double](client, "australia-bank", new DateTime("2010-10-24").toLocalDate).get.keys.size)
        EventHandler.info(this, "No of Breaks: " + ReconUtils.fetchBreakEntries[Double](client, "australia-bank", new DateTime("2010-10-25").toLocalDate).get.keys.size)
        EventHandler.info(this, "No of Matches: " + ReconUtils.fetchMatchEntries[Double](client, "australia-bank", new DateTime("2010-10-25").toLocalDate).get.keys.size)
        EventHandler.info(this, "No of Unmatches: " + ReconUtils.fetchUnmatchEntries[Double](client, "australia-bank", new DateTime("2010-10-25").toLocalDate).get.keys.size)
        EventHandler.info(this, "No of Breaks: " + ReconUtils.fetchBreakEntries[Double](client, "australia-bank", new DateTime("2010-10-26").toLocalDate).get.keys.size)
        EventHandler.info(this, "No of Matches: " + ReconUtils.fetchMatchEntries[Double](client, "australia-bank", new DateTime("2010-10-26").toLocalDate).get.keys.size)
        EventHandler.info(this, "No of Unmatches: " + ReconUtils.fetchUnmatchEntries[Double](client, "australia-bank", new DateTime("2010-10-26").toLocalDate).get.keys.size)
        EventHandler.shutdown()
        Actor.registry.shutdownAll()
        CamelContextManager.stop
        stopCamelService
      }
    }
  }
}
