package com.auginte.scarango.response

import com.auginte.scarango.response.common.CommonResponse

case class Databases(result: List[String], error: Boolean, code: Int) extends Data with CommonResponse
