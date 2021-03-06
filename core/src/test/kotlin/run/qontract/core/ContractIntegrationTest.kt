package run.qontract.core

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import run.qontract.test.HttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class ContractIntegrationTest {
    @Test
    @Throws(Throwable::class)
    fun shouldInvokeTargetUsingServerState() {
        val contractGherkin = "" +
                "Feature: Contract for /balance API\n\n" +
                "  Scenario: api call\n\n" +
                "    Given fact user jack\n" +
                "    When POST /accounts\n" +
                "    And request-body {name: \"jack\", address: \"Mumbai\"}\n" +
                "    Then status 409\n" +
                ""
        wireMockServer.stubFor(WireMock.post("/_state_setup").withRequestBody(WireMock.equalToJson("{\"user\": \"jack\"}")).willReturn(WireMock.aResponse().withStatus(200)))
        wireMockServer.stubFor(WireMock.post("/accounts").withRequestBody(WireMock.equalToJson("{\"name\": \"jack\", \"address\": \"Mumbai\"}")).willReturn(WireMock.aResponse().withStatus(409)))
        val contractBehaviour = Feature(contractGherkin)
        val results = contractBehaviour.executeTests(HttpClient(wireMockServer.baseUrl()))
        assertTrue(results.success(), results.report())
    }

    companion object {
        private val wireMockServer = WireMockServer()

        @JvmStatic
        @BeforeAll
        fun startWiremock() {
            wireMockServer.start()
        }

        @JvmStatic
        @AfterAll
        fun stopWiremock() {
            wireMockServer.stop()
        }
    }
}