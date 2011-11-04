package recon

import scala.collection.parallel.ParSet
import org.scalatest.{Spec, BeforeAndAfterEach, BeforeAndAfterAll}
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import org.scala_tools.time.Imports._

import com.redis._
import BalanceRecon._
import MatchFunctions._

@RunWith(classOf[JUnitRunner])
class ReconSpec extends Spec 
                with ShouldMatchers
                with BeforeAndAfterEach
                with BeforeAndAfterAll {

  implicit val clients = new RedisClientPool("localhost", 6379)

  override def beforeEach = {
  }

  override def afterEach = clients.withClient{
    client => client.flushdb
  }

  override def afterAll = {
    clients.withClient{ client => client.disconnect }
    clients.close
  }

  describe("load data into redis") {
    it("should load into hash") {
      val now = DateTime.now.toLocalDate
      val balances = 
        List(
          Balance("a-123", now, "USD", 1000), 
          Balance("a-134", now, "AUD", 2000),
          Balance("a-134", now, "GBP", 2500),
          Balance("a-123", now, "JPY", 250000))
      loadBalance(CollectionDef("r1", balances))
    }
  }

  describe("run recon with a 1:1 balance matching data set") {
    it("should generate report") {
      val now = DateTime.now.toLocalDate
      val bs1 = 
        List(
          Balance("a-123", now, "USD", 1000), 
          Balance("a-134", now, "USD", 2000))

      val bs2 = 
        List(
          Balance("a-123", now, "USD", 1000), 
          Balance("a-134", now, "USD", 2000))

      val l = loadBalances(Seq(CollectionDef("r21", bs1), CollectionDef("r22", bs2)))
      l.size should equal(2)
      reconBalance(List("r21", "r22"), match1on1) should equal(ParSet(("a-1232011-11-04",true), ("a-1342011-11-04",true)))
    }
  }

  describe("run recon with another 1:1 balance matching data set") {
    it("should generate report") {
      val now = DateTime.now.toLocalDate
      val bs1 = 
        List(
          Balance("a-123", now, "USD", 1000), 
          Balance("a-134", now, "AUD", 2000),
          Balance("a-134", now, "GBP", 2500),
          Balance("a-136", now, "GBP", 2500),
          Balance("a-123", now, "JPY", 250000))

      val bs2 = 
        List(
          Balance("a-123", now, "USD", 126000), 
          Balance("a-124", now, "USD", 26000), 
          Balance("a-134", now, "AUD", 3250))

      val l = loadBalances(Seq(CollectionDef("r21", bs1), CollectionDef("r22", bs2)))
      l.size should equal(2)
      reconBalance(List("r21", "r22"), match1on1) should equal(ParSet(("a-1362011-11-04",false), ("a-1342011-11-04",false), ("a-1242011-11-04",false), ("a-1232011-11-04",true)))
    }
  }

  describe("run recon with a unbalanced matching data set") {
    it("should generate report") {
      val now = DateTime.now.toLocalDate
      val bs1 = 
        List(
          Balance("a-1", now, "USD", 1000), 
          Balance("a-2", now, "USD", 2000),
          Balance("a-3", now, "USD", 2500),
          Balance("a-4", now, "USD", 2500))

      val bs2 = 
        List(
          Balance("a-1", now, "USD", 300), 
          Balance("a-2", now, "USD", 1000), 
          Balance("a-4", now, "USD", 500), 
          Balance("a-3", now, "USD", 2000))

      val bs3 = 
        List(
          Balance("a-1", now, "USD", 700), 
          Balance("a-2", now, "USD", 1000), 
          Balance("a-4", now, "USD", 2000), 
          Balance("a-3", now, "USD", 500))

      val l = loadBalances(Seq(CollectionDef("r31", bs1), CollectionDef("r32", bs2), CollectionDef("r33", bs3)))
      l.size should equal(3)

      implicit def matchCriterion(is: List[Int]) = is.head == is.tail.head + is.tail.tail.head
      reconBalance(List("r31", "r32", "r33"), matchAsExpr).forall(_. _2 == true) should equal(true)
    }
  }

  describe("run recon with a 1:1 balance matching data set and predicate") {
    it("should generate report") {
      val now = DateTime.now.toLocalDate
      val bs1 = 
        List(
          Balance("a-123", now, "USD", 1000), 
          Balance("a-134", now, "AUD", 2000),
          Balance("a-134", now, "GBP", 2500),
          Balance("a-136", now, "GBP", 2500),
          Balance("a-123", now, "USD", 50),
          Balance("a-123", now, "JPY", 250000))

      val bs2 = 
        List(
          Balance("a-123", now, "USD", 126000), 
          Balance("a-124", now, "USD", 26000), 
          Balance("a-134", now, "USD", 50), 
          Balance("a-134", now, "AUD", 3250))

      val gr100 = (b: Balance) => b.amount > 100
      val l = loadBalances(Seq(CollectionDef("r21", bs1, Some(gr100)), CollectionDef("r22", bs2, Some(gr100))))
      l.size should equal(2)
      reconBalance(List("r21", "r22"), match1on1) should equal(ParSet(("a-1362011-11-04",false), ("a-1342011-11-04",false), ("a-1242011-11-04",false), ("a-1232011-11-04",true)))

    }
  }

  describe("generate data") {
    it("should generate data") {
      import ReconDataGenerator._
      val (m, s1, s2) = generateDataForMultipleAccounts
      val start = System.currentTimeMillis
      val l = loadBalances(Seq(CollectionDef("r41", m), CollectionDef("r42", s1), CollectionDef("r43", s2)))
      val afterLoad = System.currentTimeMillis
      println("load time = " + (afterLoad - start))
      l.size should equal(3)

      implicit def matchCriterion(is: List[Int]) = is.head == is.tail.head + is.tail.tail.head
      reconBalance(List("r41", "r42", "r43"), matchAsExpr)
      val end = System.currentTimeMillis
      println("recon time = " + (end - afterLoad))
    }
  }
}

