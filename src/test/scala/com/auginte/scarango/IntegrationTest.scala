package com.auginte.scarango

import akka.actor._
import com.auginte.scarango.common.TestKit
import com.auginte.scarango.errors.{UnexpectedResponse, ScarangoError, UnexpectedRequest}
import com.auginte.scarango.helpers.AkkaSpec
import com.auginte.scarango.request._
import com.auginte.scarango.request.parts.{Authorisation, User}
import com.auginte.scarango.response._
import com.auginte.scarango.response.meta.collection.{Statuses, Types}
import com.auginte.scarango.state.{DatabaseNames, CollectionName, DatabaseName}
import spray.http.StatusCodes
import sun.misc.BASE64Decoder
import scala.language.implicitConversions
import scala.concurrent.duration._
import scala.language.postfixOps

/**
 * Testing integration with ArangoDB
 */
class IntegrationTest extends AkkaSpec {
  implicit val config = new ScarangoHostConfig("10.0.0.10", 8529)
  "In environment with real ArangoDB instance, driver" should {

    "get version of ArangoDB" in {
      val system = akkaSystem("TestVersions")
      var version: Option[Version] = None

      class ClientGetVersion extends Actor {
        override def receive: Receive = {
          case "start" =>
            val db = system.actorOf(Props(new Scarango()))
            db ! GetVersion

          case v: Version =>
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

      system.awaitTermination(5 seconds)
      assert(version.isDefined)
      info("Version: " + version.get.version)

      assert(version.get.version === latestApiVersion)
    }

    "get multiple results: version and database list" in {
      val system = akkaSystem("TestMultiple")
      var version: Option[Version] = None
      var databases: Option[DatabaseList] = None

      class ClientMultiple extends Actor {
        override def receive: Receive = {
          case "start" =>
            val db = system.actorOf(Props(new Scarango()))
            db ! GetVersion
            db ! ListDatabases

          case v: Version =>
            version = Some(v)

          case d: DatabaseList =>
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

      system.awaitTermination(5 seconds)
      assert(version.isDefined)
      info("Version: " + version.get.version)
      assert(version.get.version === latestApiVersion)

      assert(databases.isDefined)
      info("Databases: " + databases.get.result.mkString(", "))
      assert(DatabaseNames.default ===  DatabaseName("_system"))
      assert(databases.get.result.contains(DatabaseNames.default))
      assert(databases.get.code === 200)
      assert(!databases.get.error)
    }

    "create and drop new database" in {
      val system = akkaSystem("TestCreateDatabase")
      val dbName = DatabaseName(TestKit.unique)
      var created: Option[DatabaseCreated] = None
      var removed: Option[DatabaseRemoved] = None
      var databases1: Option[DatabaseList] = None
      var databases2: Option[DatabaseList] = None

      class ClientDatabases extends Actor {
        override def receive: Receive = {
          case "start" =>
            val db = system.actorOf(Props(new Scarango()))
            db ! CreateDatabase(dbName)
            db ! request.Identifiable(ListDatabases, id = "created?")
            db ! RemoveDatabase(dbName)
            db ! request.Identifiable(ListDatabases, id = "removed?")

          case d: DatabaseCreated =>
            created = Some(d)

          case response.Identifiable(d: DatabaseList, id, _, _, _) if id == "created?" =>
            databases1 = Some(d)

          case r: DatabaseRemoved =>
            removed = Some(r)

          case response.Identifiable(d: DatabaseList, id, _, _, _) if id == "removed?" =>
            databases2 = Some(d)
            system.shutdown()

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

      system.awaitTermination(5 seconds)
      assert(created.isDefined)
      assert(created.get.name === dbName)
      assert(created.get.raw.result === true)
      info("New database: " + created.get.name)

      assert(databases1.isDefined)
      info("Databases: " + databases1.get.result.mkString(", "))
      assert(databases1.get.result.contains(DatabaseNames.default))
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
      info("Databases: " + databases2.get.result.mkString(", "))
      assert(databases2.get.result.contains(DatabaseNames.default))
      assert(!databases2.get.result.contains(dbName))
      assert(databases2.get.code === 200)
      assert(!databases2.get.error)
    }

    "create and drop collection in _system database" in {
      val system = akkaSystem("TestCreateCollection")
      val collectionName = CollectionName(TestKit.unique)
      var created: Option[CollectionCreated] = None
      var collection: Option[Collection] = None
      var removed: Option[CollectionRemoved] = None

      class ClientCollections extends Actor {
        override def receive: Receive = {
          case "start" =>
            val db = system.actorOf(Props(new Scarango()))
            db ! CreateCollection(collectionName)
            db ! GetCollection(collectionName)
            db ! RemoveCollection(collectionName)

          case c: CollectionCreated =>
            created = Some(c)

          case c: Collection =>
            collection = Some(c)

          case r: CollectionRemoved =>
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

      system.awaitTermination(5 seconds)
      assert(created.isDefined)
      assert(created.get.name === collectionName)
      assert(created.get.id.length > 3)
      assert(created.get.raw.error === false)
      assert(created.get.raw.code === 200)
      info("New collection: " + created.get.name)
      info("With id: " + created.get.id)

      assert(collection.isDefined)
      assert(collection.get.name == collectionName)
      assert(collection.get.id.length > 5)
      assert(collection.get.enumStatus === Statuses.Loaded)
      assert(collection.get.enumType === Types.Document)

      assert(removed.isDefined)
      info("Removed collection: " + removed.get.element.name)
      info("With id: " + removed.get.raw.id)
      assert(removed.get.element.name === collectionName)
      assert(removed.get.raw.id === created.get.id)
      assert(removed.get.raw.code === 200)
      assert(removed.get.raw.error === false)
    }

    "create and remove document asynchronously" in {
      info("Testing removal of document, while database still processing read operation")

      val system = akkaSystem("TestCreateDocument")
      val collectionName = CollectionName(TestKit.unique)
      var collectionCreated: Option[CollectionCreated] = None
      var collectionRemoved: Option[CollectionRemoved] = None
      var created: Option[DocumentCreated] = None
      var removed: Option[DocumentRemoved] = None
      var document: Option[Document] = None
      var documents: Option[DocumentList] = None
      val documentData = """{"some": "data"}"""

      val db = system.actorOf(Props(new Scarango()))
      class ClientCollections extends Actor {
        override def receive: Receive = {
          case "start" =>
            db ! CreateCollection(collectionName)
            db ! CreateDocument(documentData)(collectionName)
            db ! ListDocuments(collectionName)

          case "removeDocument" =>
            db ! RemoveDocument(created.get.id)
            self ! "cleanup"

          case "cleanup" =>
            db ! RemoveCollection(collectionName)

          case c: CollectionCreated =>
            collectionCreated = Some(c)

          case c: DocumentCreated =>
            created = Some(c)
            db ! GetDocument(c.id)

          case d: Document =>
            info("Document: " + d.json)
            document = Some(d)
            self ! "removeDocument"

          case r: DocumentRemoved =>
            removed = Some(r)

          case d: DocumentList =>
            documents = Some(d)
            info("Documents: " + d.ids.mkString(", "))

          case r: CollectionRemoved =>
            collectionRemoved = Some(r)
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

      system.awaitTermination(5 seconds)
      assert(collectionCreated.isDefined)
      assert(collectionRemoved.isDefined)

      assert(created.isDefined)
      info("Document created with id: " + created.get.id)
      assert(created.get.id.startsWith(collectionName + "/"))
      assert(created.get.collection === collectionName)
      assert(created.get.raw.error === false)

      assert(document.isDefined)
      assert(document.get.id === created.get.id)
      assert(document.get.json.startsWith( """{"some":"data","_id""""))

      assert(documents.isDefined)
      assert(documents.get.ids.length === 1)
      assert(documents.get.ids.head === created.get.id)

      assert(removed.isDefined)
      assert(removed.get.id === created.get.id)

      assert(collectionRemoved.isDefined)
    }
  }

  "Wen using driver in unexpected way" should {
    "return error about bad request to client" in {
      val system = akkaSystem("TestErrorHandling")
      var exception: Option[UnexpectedRequest] = None

      class ErrorHandling extends Actor {
        override def receive: Receive = {
          case "start" =>
            val db = system.actorOf(Props(new Scarango()))
            db ! GetVersion
            db ! ListDocuments // Common mistype: db ! GetDocuments("collection")

          case e: UnexpectedRequest =>
            info("Error description: " + e.getMessage)
            exception = Some(e)

          case v: Version =>
            info("Version: " + v.version)
            context.system.shutdown()

          case e: ScarangoError =>
            fail("[ScarangoError] " + e.getMessage)
            context.system.shutdown()

          case other =>
            fail("[UNEXPECTED] " + other)
            context.system.shutdown()
        }
      }

      val client = system.actorOf(Props(new ErrorHandling()))
      client ! "start"

      system.awaitTermination(5 seconds)
      assert(exception.isDefined)
      assert(exception.get.getMessage === "Unexpected Request to driver: ListDocuments")
    }
  }

  "For faster software development" should {
    "use current database implicitly" in {
      val system = akkaSystem("CurrentDatabaseTest")

      var database: Option[DatabaseCreated] = None
      var collection: Option[CollectionCreated] = None
      var document: Option[DocumentCreated] = None
      var documents: Option[DocumentList] = None
      val documentContent = """{"test":"document"}"""
      implicit val currentDatabase = DatabaseName(TestKit.unique)
      implicit val currentCollection = CollectionName(TestKit.unique)
      info(s"Will be using: $currentDatabase")

      val db = system.actorOf(Props(new Scarango()))
      class ClientState extends Actor {
        override def receive: Receive = {
          case "start" =>
            db ! CreateDatabase(currentDatabase)
            db ! CreateCollection(currentCollection) // No database provided
            db ! CreateDocument(documentContent) // No collection, no database provided
            db ! ListDocuments(currentCollection)

          case "cleanup" =>
            db ! RemoveCollection(currentCollection) // No database provided
            db ! RemoveDatabase(currentDatabase)

          case d: DatabaseCreated => database = Some(d)
          case c: CollectionCreated => collection = Some(c)
          case d: DocumentCreated => document = Some(d)
          case d: DocumentList =>
            documents = Some(d)
            self ! "cleanup"

          case _: CollectionRemoved => info("Collection with documents removed")
          case _: DatabaseRemoved => info("Database removed")

          case e: ScarangoError =>
            fail("[ScarangoError] " + e.getMessage)
            context.system.shutdown()

          case other =>
            fail("[UNEXPECTED] " + other)
            context.system.shutdown()
        }

        val client = system.actorOf(Props(new ClientState()))
        client ! "start"

        system.awaitTermination(5 seconds)
        assert(database.isDefined)
        assert(database.get.name === currentDatabase)
        assert(currentDatabase !== DatabaseNames.default)

        assert(collection.isDefined)
        assert(collection.get.database === currentDatabase)
        assert(collection.get.name === currentCollection)
        assert(currentCollection !== "")

        assert(document.isDefined)
        assert(document.get.collection === currentCollection)
        assert(document.get.database === currentDatabase)
        assert(document.get.raw.error === false)

        assert(documents.isDefined)
        assert(documents.get.collection === currentCollection)
        assert(documents.get.database === currentDatabase)
        assert(documents.get.ids.length === Array(document.get.id))
      }
    }
    "allow to create and use multiple database with custom credentials" in {
      val system = akkaSystem("CurrentDatabaseTest")

      val authorisedUsers1 = List(
        User("owner", "123456", active = true, Map("name" -> "John Smith", "email" -> "a@b.com")),
        User("public", "", active = false)
      )
      val authorisedUsers2 = List(
        User("owner", "qwerty", active = true, Map("name" -> "Robot", "email" -> "c@d.com")),
        User("public", "", active = false)
      )
      val userDatabase1 = DatabaseName("u_" + TestKit.unique)
      val userDatabase2 = DatabaseName("u_" + TestKit.unique)
      val userCollection1 = CollectionName("userCollection1")
      val userCollection2 = CollectionName("userCollection2")
      val userAuthorisation1 = Authorisation.forUser(authorisedUsers1.head)
      val userAuthorisation2 = Authorisation.forUser(authorisedUsers2.head)

      var databaseCreated: List[DatabaseCreated] = List()
      var collectionCreated: List[CollectionCreated] = List()
      var collections: List[Collection] = List()
      var databaseRemoved: List[DatabaseRemoved] = List()
      var unauthorised: List[Request] = List()

      val db = system.actorOf(Props(new Scarango()))
      class CustomCredentials extends Actor {
        override def receive: Receive = {
          case "start" =>
            db ! CreateDatabase(userDatabase1, authorisedUsers1)
            db ! CreateDatabase(userDatabase2, authorisedUsers2)
            db ! CreateCollection(userCollection1)(userDatabase1, userAuthorisation1)
            db ! CreateCollection(userCollection2)(userDatabase2, userAuthorisation2)
            db ! GetCollection(userCollection1)(userDatabase1, userAuthorisation1)
            db ! GetCollection(userCollection2)(userDatabase2, userAuthorisation2)

            db ! GetCollection(userCollection1)(userDatabase1, userAuthorisation2) // first database with wrong credentials

            db ! RemoveDatabase(userDatabase1)
            db ! RemoveDatabase(userDatabase2)

          case d : DatabaseCreated => databaseCreated = d :: databaseCreated
          case c : CollectionCreated => collectionCreated = c :: collectionCreated
          case c : Collection => collections = c :: collections
          case d : DatabaseRemoved =>
            databaseRemoved = d :: databaseRemoved
            if (databaseRemoved.size == 2) {
              context.system.shutdown()
            }

          case r: UnexpectedResponse if r.raw.status == StatusCodes.Unauthorized =>
            unauthorised = r.lastRequest :: unauthorised

          case e: ScarangoError =>
            fail("[ScarangoError] " + e.getMessage)
            context.system.shutdown()

          case other =>
            fail("[UNEXPECTED] " + other)
            context.system.shutdown()
        }
      }
      val client = system.actorOf(Props(new CustomCredentials()))
      client ! "start"

      system.awaitTermination(5 seconds)
      assert(databaseCreated.size == 2)
      assert(databaseCreated.head.name == userDatabase2)
      assert(databaseCreated(1).name == userDatabase1)

      assert(collectionCreated.size == 2)
      assert(collectionCreated.head.name == userCollection2)
      assert(collectionCreated.head.database == userDatabase2)
      assert(collectionCreated(1).name == userCollection1)
      assert(collectionCreated(1).database == userDatabase1)

      assert(collections.size == 2)
      assert(collections.head.name == userCollection2)
      assert(collections.head.database == userDatabase2)
      assert(collections(1).name == userCollection1)
      assert(collections(1).database == userDatabase1)

      assert(unauthorised.size == 1)
      unauthorised.head match {
        case c: GetCollection =>
          assert(c.name == userCollection1)
          assert(c.database == userDatabase1)
          info("Headers of unauthorised: " + unauthorised.head.http.headers.mkString(" | "))
          val requestCredentials = unauthorised.head.http.headers.head.value.split("Basic ")(1)
          val decoded = new String(new BASE64Decoder().decodeBuffer(requestCredentials))
          info("Requested with credentials: " + decoded)
        case other => fail("Unauthorised error for wrong request: " + other)
      }

      assert(databaseRemoved.size == 2)
      assert(databaseRemoved.head.name == userDatabase2)
      assert(databaseRemoved(1).name == userDatabase1)
    }
  }
}