package com.auginte.scarango

import akka.actor._
import com.auginte.scarango.common.TestKit
import com.auginte.scarango.errors.ScarangoError
import com.auginte.scarango.helpers.AkkaSpec
import com.auginte.scarango.request._
import com.auginte.scarango.response._

import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Testing integration with ArangoDB
 */
class IntegrationTest extends AkkaSpec {
  "In environment with real ArangoDB instance, driver" should {

    "get version of ArangoDB" in {
      val system = akkaSystem("TestVersions")
      var version: Option[Version] = None

      class ClientGetVersion extends Actor {
        override def receive: Receive = {
          case "start" =>
            val db = system.actorOf(Props(new Scarango()))
            db ! GetVersion

          case ResponseIdentifier(v: Version, _) =>
            version = Some(v)
            context.system.shutdown()

          case e: ScarangoError =>
            fail("[ScarangoError] " + e.getMessage)
            context.system.shutdown()

          case other =>
            fail("[UNEXPECTED] " + other)
            context.system.shutdown()
        }
      }

      val client = system.actorOf(Props(new ClientGetVersion()))
      client ! "start"

      system.awaitTermination(10 seconds)
      assert(version.isDefined)
      info("Version: " + version.get.version)

      assert(version.get.version === "2.6.5")
    }

    "get multiple results: version and database list" in {
      val system = akkaSystem("TestMultiple")
      var version: Option[Version] = None
      var databases: Option[Databases] = None

      class ClientMultiple extends Actor {
        override def receive: Receive = {
          case "start" =>
            val db = system.actorOf(Props(new Scarango()))
            db ! GetVersion
            db ! GetDatabases

          case ResponseIdentifier(v: Version, _) =>
            version = Some(v)

          case ResponseIdentifier(d: Databases, _) =>
            databases = Some(d)
            system.shutdown() // Testing, if it is second

          case e: ScarangoError =>
            fail("[ScarangoError] " + e.getMessage)
            context.system.shutdown()

          case other =>
            fail("[UNEXPECTED] " + other)
            context.system.shutdown()
        }
      }

      val client = system.actorOf(Props(new ClientMultiple()))
      client ! "start"

      system.awaitTermination(10 seconds)
      assert(version.isDefined)
      info("Version: " + version.get.version)
      assert(version.get.version === "2.6.5")

      assert(databases.isDefined)
      info("Databases: " + databases.get.result.mkString(", "))
      assert(databases.get.result.contains("_system"))
      assert(databases.get.code === 200)
      assert(!databases.get.error)
    }

    "create and drop new database" in {
      val system = akkaSystem("TestCreateDatabase")
      val dbName = TestKit.unique
      var created: Option[DatabaseCreated] = None
      var removed: Option[DatabaseRemoved] = None
      var databases1: Option[Databases] = None
      var databases2: Option[Databases] = None
      var fetchCount = 0

      class ClientDatabases extends Actor {
        override def receive: Receive = {
          case "start" =>
            val db = system.actorOf(Props(new Scarango()))
            db ! CreateDatabase(dbName)
            db ! GetDatabases
            db ! RemoveDatabase(dbName)
            db ! GetDatabases

          case ResponseIdentifier(d: DatabaseCreated, _) =>
            created = Some(d)

          case ResponseIdentifier(d: Databases, _) if fetchCount == 0 =>
            databases1 = Some(d)
            fetchCount = fetchCount + 1

          case ResponseIdentifier(r: DatabaseRemoved, _) =>
            removed = Some(r)

          case ResponseIdentifier(d: Databases, _) if fetchCount == 1 =>
            databases2 = Some(d)
            fetchCount = fetchCount + 1
            system.shutdown() // Testing, if it is second

          case e: ScarangoError =>
            fail("[ScarangoError] " + e.getMessage)
            context.system.shutdown()

          case other =>
            fail("[UNEXPECTED] " + other)
            context.system.shutdown()
        }
      }

      val client = system.actorOf(Props(new ClientDatabases()))
      client ! "start"

      system.awaitTermination(10 seconds)
      assert(created.isDefined)
      assert(created.get.name === dbName)
      assert(created.get.raw.result === true)
      info("New database: " + created.get.name)

      assert(databases1.isDefined)
      info("Databases: " + databases1.get.result.mkString(", "))
      assert(databases1.get.result.contains("_system"))
      assert(databases1.get.result.contains(dbName))
      assert(databases1.get.code === 200)
      assert(!databases1.get.error)

      assert(removed.isDefined)
      info("Removed database: " + removed.get.element.name)
      assert(removed.get.element.name === dbName)
      assert(removed.get.raw.code === 200)
      assert(removed.get.raw.error === false)
      assert(removed.get.raw.result === true)

      assert(databases2.isDefined)
      assert(fetchCount === 2)
      info("Databases: " + databases2.get.result.mkString(", "))
      assert(databases2.get.result.contains("_system"))
      assert(!databases2.get.result.contains(dbName))
      assert(databases2.get.code === 200)
      assert(!databases2.get.error)
    }

    "create and drop collection in _system database" in {
      val system = akkaSystem("TestCreateCollection")
      val collectionName = TestKit.unique
      var created: Option[CollectionCreated] = None
      var removed: Option[CollectionRemoved] = None

      class ClientCollections extends Actor {
        override def receive: Receive = {
          case "start" =>
            val db = system.actorOf(Props(new Scarango()))
            db ! CreateCollection(collectionName)
            db ! RemoveCollection(collectionName)

          case ResponseIdentifier(c: CollectionCreated, _) =>
            created = Some(c)

          case ResponseIdentifier(r: CollectionRemoved, _) =>
            removed = Some(r)
            system.shutdown()

          case e: ScarangoError =>
            fail("[ScarangoError] " + e.getMessage)
            context.system.shutdown()

          case other =>
            fail("[UNEXPECTED] " + other)
            context.system.shutdown()
        }
      }

      val client = system.actorOf(Props(new ClientCollections()))
      client ! "start"

      system.awaitTermination(10 seconds)
      assert(created.isDefined)
      assert(created.get.name === collectionName)
      assert(created.get.id.length > 3)
      assert(created.get.error === false)
      assert(created.get.code === 200)
      info("New collection: " + created.get.name)
      info("With id: " + created.get.id)

      assert(removed.isDefined)
      info("Removed colection: " + removed.get.element.name)
      info("With id: " + removed.get.raw.id)
      assert(removed.get.element.name === collectionName)
      assert(removed.get.raw.id === created.get.id)
      assert(removed.get.raw.code === 200)
      assert(removed.get.raw.error === false)
    }
  }
}