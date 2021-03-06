package net.debasishg.recon

import scalaz._
import Scalaz._
import scalaz.effects._
import java.io.File
import FileUtils._

trait Xtractor {
  def loop(fileName: String) = {
    val str = enumFile(new File(fileName), repeatHead).map (_.run).catchLeft
    str.unsafePerformIO
  }
}

trait CSVFieldXtractor extends Xtractor {
  // mapping a field to the position in the record: fieldName -> (0-based start position, length)
  val maps: Map[String, Int]

  // extract "field" position from "maps" and get the value from "splits"
  def xtract(field: String)(implicit splits: Array[String]) = 
    (maps get field) map (s => splits(s).trim)
}

trait FixedLengthFieldXtractor extends Xtractor {
  // mapping a field to the position in the record: fieldName -> (0-based start position, length)
  val maps: Map[String, (Int, Int)]

  // extract "field" from string "s" after looking up field position in "maps"
  def xtract(field: String, s: String) = 
    (maps get field) map (p => s.substring(p._1, p._1 + p._2).trim)
}

/**
 * Processing of Recon source, but only the pure part. All side-effects are handled in the
 * trait <tt>Xtractor</tt>
 */
trait ReconSource[A] {this: Xtractor =>
  def id: String
  def processSingle(str: String): Option[A]
  final def process(fileName: String): Either[Throwable,List[Option[Option[A]]]] = {
    loop(fileName) :-> (_ map (_ map processSingle))
  }
}
