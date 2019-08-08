package skysim

import akka.actor.{ActorRef, Props}
import skysim.CharacterActor.InitChar
import skysim.CityActor.{ChangePos, GetNearby, GetNearbyRes, InitCity}
import skysim.DBActor.{SqlResult, StoreDb}
import skysim.Log._

import scala.collection.mutable
import scala.util.Random

object CityActor {
  def props: Props = Props(new CityActor)

  case class InitCity(numCitizens: Int, db: ActorRef, job: ActorRef)

  case class ChangePos(ac: ActorRef, state: CharState)

  case class GetNearbyRes(
                           other: ActorRef, otherState: CharState, dist: Double
                         )

  case class GetNearby(requester: ActorRef)

}

class CityActor extends SimActor {
  var job: ActorRef = _
  var citizens: mutable.Map[ActorRef, CharState] = mutable.Map.empty
  var db: ActorRef = _
  val names: mutable.Set[String] = mutable.Set[String]()

  def name(i: Int): String = {
    val random = new Random(i)
    val vowels = "AEIOU"
    val consonants = "BCDFGHJKLMNPQRSTVWYZ"
    val name =
      1.to(random.nextInt(2) + 2) map { _ =>
        consonants.charAt(random.nextInt(consonants.length)) + "" +
          vowels.charAt(random.nextInt(vowels.length))
      } mkString ""
    val res =
      if (names.contains(name)) {
        name + "a"
      } else {
        name
      }
    names += res
    res.charAt(0) + res.substring(1).toLowerCase
  }

  override def receive: Receive = {
    case InitCity(size, db, job) =>
      this.db = db
      this.job = job

      println(s"init city " + this + " with " + size + " citizens")

      0.until(size) foreach { i =>
        val pos = (Random.nextInt(100), Random.nextInt(100))
        val nameVal = name(i)
        val c = context.actorOf(CharacterActor.props, nameVal)
        this.citizens += c -> CharState(
          nameVal, this.self.path.name, "idle", pos._1, pos._2, Map.empty
        )
      }

      this.citizens.zipWithIndex foreach { case ((cha, p), i) =>
        cha ! InitChar(i, this.self, p, job)
      }

      println(this + " done")

    case v@Verb("breakfast") =>
      citizens foreach { cha =>
        cha._1 ! v
      }

    case ChangePos(ac: ActorRef, p: CharState) =>
      citizens.update(ac, p)

    case Verb("save") =>
      this.db ! StoreDb(citizens.values.toSeq)

    case SqlResult(_, _) =>
      println("saved")

    case GetNearby(requester: ActorRef) =>
      val pos: CharState = citizens(requester)
      val (res: ActorRef, d: Double) =
        citizens map { case (other: ActorRef, p: CharState) =>
          if (other == requester)
            other -> Double.PositiveInfinity
          else {
            val dx = pos.x - p.x
            val dy = pos.y - p.y
            other -> (dy * dy + dx * dx).toDouble
          }
        } minBy { case (_, p: Double) => p }
      sender ! GetNearbyRes(res, citizens(res), Math.sqrt(d))

    case other => sys.error(this.self.path + " UNKNOWN <" + other + ">")
  }
}