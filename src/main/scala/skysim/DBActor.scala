package skysim

import java.sql.{Connection, DriverManager, Statement}
import java.util.Properties

import akka.actor.{ActorRef, Props}
import com.mysql.cj.jdbc.NonRegisteringDriver
import skysim.DBActor.{InitDb, _}
import skysim.Log._

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Random

object DBActor {
  def props: Props = Props(new DBActor)

  case class StoreDb(seq: Seq[CharState])

  case class InitDb(
                     url: String = "jdbc:mysql://localhost:8889/mysql",
                     driver: String = "com.mysql.jdbc.Driver",
                     username: String = "root",
                     password: String = "root"
                   )

  object InitDbDone

  case class ExecSql(sql: String)

  case class SqlResult(results: List[Map[String, Any]], affected: Int)

  val setupSQL: String =
    """
drop table if exists skysim;

CREATE TABLE `skysim` (
  `name` varchar(300) CHARACTER SET utf8 COLLATE utf8_bin NOT NULL,
  `parent` text CHARACTER SET utf8 COLLATE utf8_bin NOT NULL,
  `state` text CHARACTER SET utf8 COLLATE utf8_bin NOT NULL,
  `type` text CHARACTER SET utf8 COLLATE utf8_bin NOT NULL default '',
  `attr` text CHARACTER SET utf8 COLLATE utf8_bin NOT NULL default '',
  `x` bigint(11) NOT NULL DEFAULT '0',
  `y` bigint(11) NOT NULL DEFAULT '0',
  `data` text CHARACTER SET utf8 COLLATE utf8_bin NOT NULL,
  `timestamp` bigint(22) NOT NULL,
  PRIMARY KEY (`name`)
);

drop table if exists skysim_jobs;

CREATE TABLE `skysim_jobs` (
  `job` varchar(255) NOT NULL,
  `states` text CHARACTER SET utf8 COLLATE utf8_bin NOT NULL DEFAULT '',
  `minpopulation` bigint(22) NOT NULL DEFAULT 0,
  `maxpercity` bigint(22) NOT NULL DEFAULT 0,
  PRIMARY KEY (`job`)
);

insert into skysim_jobs (job, minpopulation, maxpercity, states) values
('lumberjack', 0, 0, 'idle, chopping, eating, selling, sleeping, idle'),
('citizen',    0, 0, 'idle, buying, eating, sleeping, idle'),
('alchemist',  0, 0, 'idle, selling, eating, collecting, sleeping, idle'),
('mage',       0, 0, 'idle, studying, eating, practicing, sleeping, idle'),
('merchant',   0, 0, 'idle, selling, eating, sleeping, idle');


drop table if exists skysim_cities;

CREATE TABLE `skysim_cities` (
  `name` varchar(255) NOT NULL,
  `population` bigint(22) NOT NULL DEFAULT 0,
  `nextTo` varchar(255) NOT NULL DEFAULT '',
  `directionInDeg` bigInt(255) NOT NULL DEFAULT 90,
  `dist` bigInt(255) NOT NULL DEFAULT 100,
  PRIMARY KEY (`name`)
);

insert into skysim_cities (name, population, nextTo, directionInDeg, dist) values
 ("Riften", 450, "Whiterun", 9, 100),
 ("Windhelm", 450, "Whiterun", 9, 100),
 ("Whiterun", 450, "Whiterun", 9, 100),
 ("Markarth", 450, "Whiterun", 9, 100),
 ("Solitude", 450, "Whiterun", 9, 100),
 ("Morthal", 450, "Whiterun", 9, 100),
 ("Dawnstar", 450, "Whiterun", 9, 100),
 ("Winterhold", 450, "Whiterun", 100, 100);


 drop table if exists skyrim_system;

 CREATE TABLE `skyrim_system` (
    `wait` bigint(22) NOT NULL DEFAULT 0,
    `lastRun` bigint(22) NOT NULL DEFAULT 0
 );

 insert into skyrim_system (wait) values
 (10000);

"""
}

case class DBActorState(
                         connectionData: InitDb
                       )

class DBActor extends SimActor {
  override def receive: Receive =
    withState(DBActorState(null))

  def getConnection(connectionData: InitDb): Connection = {
    val d = new com.mysql.cj.jdbc.Driver
    val p = new Properties
    assert(connectionData != null, "InitDB was not sent")
    assert(connectionData.username != null, "InitDB username was not sent")

    p.setProperty("user", connectionData.username)
    p.setProperty("password", connectionData.password)
    d.connect(
      connectionData.url, p
    )
  }

  def r(name: String): String = "'" + name.replace("'", "''") + "'"

  def m(data: Map[String, Int]): String = r(
    data.toSeq.map(x => x._2 + ":" + x._1).mkString("|")
  )


  def withState(actorState: DBActorState): Receive = {

    case i@InitDb(url, driver, username, password) =>
      val connection = getConnection(i)

      try {
        //DriverManager.registerDriver(
        //)

        val statement = connection.createStatement

        val rs = statement.executeQuery("select count(*) from skysim;")

        var count = 0
        while (rs.next) {
          count += 1
        }
        println("skysim: " + count)
      } catch {
        case e: Exception =>
          println(e)
      } finally {
        connection.close()
      }
      sender ! InitDbDone
      context become withState(actorState.copy(connectionData = i))

    case StoreDb(seq) =>
      val now = System.currentTimeMillis
      val values = seq.map { x =>
        "(" + r(x.name) + ", " + r(x.city) + ", " + r(x.status) + ", " + x.x + ", " + x.y + ", " + m(x.data) + ", " + now + ")"
      }.mkString(", ")

      val sql =
        s"""
INSERT INTO skysim (parent, name, state, x, y, data, timestamp)
VALUES $values
ON DUPLICATE KEY UPDATE parent=parent,x=x,y=y,state=state,data=data,timestamp=timestamp;
          """
      this.self.tell(ExecSql(sql), sender)

    case ExecSql(sql) =>
      val connection = getConnection(actorState.connectionData)
      try {
        val statement = connection.createStatement
        println(sql)
        if (sql.toLowerCase.startsWith("select ")) {
          val buf: ListBuffer[Map[String, Any]] = execSql(sql, statement)
          sender() ! SqlResult(buf.toList, 0)

        } else {
          val res = statement.executeUpdate(sql)
          sender() ! SqlResult(Nil, res)
          println("sql update: " + res)
        }
      } catch {
        case e: Exception =>
          println(e)
      } finally {
        connection.close()
      }

    case SqlResult(_, _) => ()

    case other => sys.error(this.self.path + " UNKNOWN " + other)
  }

  def execSql(sql: String, statement: Statement): ListBuffer[Map[String, Any]] = {
    val rs = statement.executeQuery(sql)
    val meta = rs.getMetaData
    val fields = 1.to(meta.getColumnCount).map(i =>
      meta.getColumnName(i)
    ).toList
    var i = 0
    val buf = ListBuffer[Map[String, Any]]()
    while (rs.next) {
      val res = fields.map(x => x -> rs.getObject(x))
      buf += res.toMap
      println(i + ": " + res.map { case (a, b) => a + " -> " + b }.mkString(", "))
      i += 1
    }
    buf
  }
}