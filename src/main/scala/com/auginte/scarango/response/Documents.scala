package com.auginte.scarango.response

import com.auginte.scarango.response.raw.RawDocuments

case class Documents(raw: RawDocuments) extends Data {
  @inline def paths: Array[String] = raw.documents

  def ids: Array[String] = paths map documentId

  def collection = paths.headOption.map(collectionName).getOrElse("")

  def database = paths.headOption.map(databaseName).getOrElse("")

  private def documentId(path: String): String = {
    val parts = parsePath(path)
    parts(5) + "/" + parts(6)
  }

  private def collectionName(path: String): String = parsePath(path)(5)

  private def databaseName(path: String): String = parsePath(path)(2)

  private def parsePath(path: String): Array[String] = {
    val parts = path.split("/")
    require(parts.length == 7, "Path does not consist of 7 known elements")
    require(parts(0) == "" && parts(1) == "_db" && parts(3) == "_api" && parts(4) == "document", "Path structure invalid")
    parts
  }

  override def toString: String = s"Documents(${raw.documents.mkString(", ")})"
}