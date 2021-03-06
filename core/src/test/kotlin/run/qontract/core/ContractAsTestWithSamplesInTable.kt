package run.qontract.core

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import run.qontract.core.pattern.ContractException
import run.qontract.core.pattern.Examples.Companion.fromPSV
import run.qontract.core.pattern.NumberPattern
import run.qontract.core.pattern.StringPattern
import run.qontract.core.value.*
import run.qontract.test.TestExecutor
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContractAsTestWithSamplesInTable {
    @Test
    fun tabularDataParsing() {
        val background = "" +
                "    | account-id | calls-left | messages-left | \n" +
                "    | 10 | 20 | 30 | " +
                ""
        val table = fromPSV(background)
        Assertions.assertEquals("10", table.getRow(0).getField("account-id"))
    }

    @Test
    @Throws(Throwable::class)
    fun GETAndResponseBodyGeneratedThroughDataTableWithPathParams() {
        val contractGherkin = """Feature: Contract for /balance API

  Scenario Outline: api call
    When GET /balance/(account_id:number)
    Then status 200
    And response-body {calls_left: "(number)", messages_left: "(number)"}

  Examples:
  | account_id | calls_left | messages_left | 
  | 10 | 20 | 30 | 
  | hello | 30 | 40 | 
    """
        Assertions.assertThrows(ContractException::class.java) { jsonResponsesTestsShouldBeVerifiedAgainstTable(contractGherkin) }
    }

    @Throws(Throwable::class)
    private fun jsonResponsesTestsShouldBeVerifiedAgainstTable(contractGherkin: String) {
        val contractBehaviour = Feature(contractGherkin)
        val results = contractBehaviour.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val accountId = request.queryParams.getOrElse("account_id") {
                    val pathParts = request.path!!.split("/".toRegex()).toTypedArray()
                    pathParts[pathParts.size - 1]
                }
                Assertions.assertEquals("GET", request.method)
                Assertions.assertTrue(NumberPattern.matches(NumberValue(accountId.toInt()), Resolver()) is Result.Success)
                val headers: HashMap<String, String> = object : HashMap<String, String>() {
                    init {
                        put("Content-Type", "application/json")
                    }
                }
                var jsonResponseString: String? = null
                if (accountId == "10") {
                    jsonResponseString = "{calls_left: 20, messages_left: 30}"
                } else if (accountId == "20") {
                    jsonResponseString = "{calls_left: 30, messages_left: \"hello\"}"
                }
                return HttpResponse(200, jsonResponseString, headers)
            }

            override fun setServerState(serverState: Map<String, Value>) {}
        })

        assertTrue(results.success(), results.report())
    }

    @Test
    @Throws(Throwable::class)
    fun POSTBodyAndResponseGeneratedThroughDataTable() {
        val contractGherkin = """
Feature: Contract for /balance API

  Scenario Outline: api call
    When POST /account
    And request-body {"name": "(string)", "city": "(string)"}
    Then status 200
    And response-body {"account_id": "(number)"}

  Examples:
  | account_id | name | city | 
  | 10 | John Doe | Mumbai | 
  | 20 | Jane Doe | Bangalore | 
"""
        jsonRequestAndResponseTest(contractGherkin)
    }

    @Test
    @Throws(Throwable::class)
    fun `Examples in multiple tables should be used`() {
        val contractGherkin = """
Feature: Contract for /balance API

  Scenario Outline: api call
    When POST /account
    And request-body {"name": "(string)", "city": "(string)"}
    Then status 200
    And response-body {"account_id": "(number)"}

  Examples:
  | account_id | name | city | 
  | 10 | John Doe | Mumbai | 
  
  Examples:
  | account_id | name | city | 
  | 20 | Jane Doe | Bangalore | 
"""
        jsonRequestAndResponseTest(contractGherkin)
    }

    @Test
    @Throws(Throwable::class)
    fun `Example values are picked up in the keys of json objects defined in lazy patterns`() {
        val contractGherkin = """
Feature: Contract for /balance API

  Scenario Outline: api call
    Given pattern Person {"name": "(string)", "address": "(Address)"}
    And pattern Address {"city": "(string)"}
    When POST /account
    And request-body (Person)
    Then status 200
    And response-body {"account_id": "(number)"}

  Examples:
  | account_id | name | city | 
  | 10 | John Doe | Mumbai | 
  | 20 | Jane Doe | Bangalore | 
"""

        val contractBehaviour = Feature(contractGherkin)

        val results = contractBehaviour.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val requestJSON = jsonObject(request.body)
                val name = requestJSON["name"] as StringValue
                val city = (requestJSON["address"] as JSONObjectValue).jsonObject["city"] as StringValue
                Assertions.assertEquals("POST", request.method)
                Assertions.assertTrue(StringPattern.matches(city, Resolver()) is Result.Success)
                val headers: HashMap<String, String> = object : HashMap<String, String>() {
                    init {
                        put("Content-Type", "application/json")
                    }
                }

                if (name.string !in listOf("John Doe", "Jane Doe"))
                    throw Exception("Unexpected name $name")

                when (name.string) {
                    "John Doe" -> assertEquals("Mumbai", city.string)
                    "Jane Doe" -> assertEquals("Bangalore", city.string)
                }

                val jsonResponseString: String? = when (name.string) {
                    "John Doe" -> "{account_id: 10}"
                    else -> "{account_id: 20}"
                }

                return HttpResponse(200, jsonResponseString, headers)
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertTrue(results.success(), results.report())
    }

    @Throws(Throwable::class)
    private fun jsonRequestAndResponseTest(contractGherkin: String) {
        val contractBehaviour = Feature(contractGherkin)
        val flags = mutableMapOf("john" to false, "jane" to false)

        val results = contractBehaviour.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val requestJSON = request.body!!
                assert(requestJSON is JSONObjectValue)

                if (requestJSON is JSONObjectValue) {
                    val name = requestJSON.jsonObject.getValue("name") as StringValue

                    Assertions.assertEquals("POST", request.method)

                    val headers: HashMap<String, String> = object : HashMap<String, String>() {
                        init {
                            put("Content-Type", "application/json")
                        }
                    }

                    var jsonResponseString: String? = null
                    if (name.string == "John Doe") {
                        flags["john"] = true
                        jsonResponseString = "{account_id: 10}"
                    } else if (name.string == "Jane Doe") {
                        flags["jane"] = true
                        jsonResponseString = "{account_id: 20}"
                    }
                    return HttpResponse(200, jsonResponseString, headers)
                } else {
                    return HttpResponse.ERROR_400
                }
            }

            override fun setServerState(serverState: Map<String, Value>) {
            }
        })

        assertTrue(flags["john"] ?: false)
        assertTrue(flags["jane"] ?: false)
        assertTrue(results.success(), results.report())
    }

    @Test
    @Throws(Throwable::class)
    fun POSTBodyAndResponseXMLGeneratedThroughDataTable() {
        val contractGherkin = """Feature: Contract for /balance API

  Scenario Outline: api call
    When POST /account
    And request-body <account><name>(string)</name><city>(string)</city></account>
    Then status 200
    And response-body <account><account_id>(number)</account_id></account>
  Examples: 
    | account_id | name | city | 
    | 10 | John Doe | Mumbai | 
    | 20 | Jane Doe | Bangalore | 
    """
        xmlRequestAndResponseTest(contractGherkin)
    }

    @Throws(Throwable::class)
    private fun xmlRequestAndResponseTest(contractGherkin: String) {
        val contractBehaviour = Feature(contractGherkin)
        val results = contractBehaviour.executeTests(object : TestExecutor {
            override fun execute(request: HttpRequest): HttpResponse {
                val root = (request.body as XMLValue).node
                val nameItem = root.childNodes.item(0)
                val cityItem = root.childNodes.item(1)
                Assertions.assertEquals("name", nameItem.nodeName)
                Assertions.assertEquals("city", cityItem.nodeName)
                val name = nameItem.firstChild.nodeValue
                Assertions.assertTrue(StringPattern.matches(StringValue(name), Resolver()) is Result.Success)
                Assertions.assertTrue(StringPattern.matches(StringValue(cityItem.firstChild.nodeValue), Resolver()) is Result.Success)
                Assertions.assertEquals("POST", request.method)
                val headers: HashMap<String, String> = object : HashMap<String, String>() {
                    init {
                        put("Content-Type", "application/xml")
                    }
                }
                var xmlResponseString: String? = null
                if (name == "John Doe") {
                    xmlResponseString = "<account><account_id>10</account_id></account>"
                } else if (name == "Jane Doe") {
                    xmlResponseString = "<account><account_id>20</account_id></account>"
                }
                return HttpResponse(200, xmlResponseString, headers)
            }

            override fun setServerState(serverState: Map<String, Value>) {}
        })

        assertTrue(results.success(), results.report())
    }
}
