package run.qontract.mock

import run.qontract.core.utilities.*
import run.qontract.stub.ktorHttpRequestToHttpRequest
import run.qontract.stub.respondToKtorHttpResponse
import io.ktor.application.ApplicationCall
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.response.header
import io.ktor.response.respondText
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import run.qontract.core.value.*
import run.qontract.core.*
import run.qontract.core.pattern.ContractException
import java.io.Closeable

typealias Expectation = Triple<HttpRequestPattern, Resolver, HttpResponse>

class ContractMock(contractGherkin: String, port: Int) : Closeable {
    private val contractBehaviour: Feature = Feature(contractGherkin)
    private val expectations: MutableList<Expectation> = mutableListOf()

    private val server: ApplicationEngine = embeddedServer(Netty, port) {
        intercept(ApplicationCallPipeline.Call) {
            val httpRequest = ktorHttpRequestToHttpRequest(call)

            if(isStubRequest(httpRequest))
                 registerExpectation(call, httpRequest)
            else
                respond(call, expectations, httpRequest)
        }
    }

    val baseURL = "http://localhost" + if(port == 80) "" else ":$port"
    val mockSetupURL = "$baseURL/_mock_setup"

    private fun registerExpectation(call: ApplicationCall, httpRequest: HttpRequest) {
        try {
            createMockScenario(stringToMockScenario(httpRequest.body ?: throw ContractException("Expectation payload was empty")))

            call.response.status(HttpStatusCode.OK)
        }
        catch(e: NoMatchingScenario) {
            writeBadRequest(call, e.message)
        }
        catch(e: ContractException) {
            writeBadRequest(call, e.report())
        }
        catch (e: Exception) {
            writeBadRequest(call, e.message)
        }
    }

    fun createMockScenario(mocked: ScenarioStub) {
        val (resolver, _, mockedResponse) = contractBehaviour.matchingStubResponse(mocked.request, mocked.response)
        expectations.add(Expectation(mocked.request.toPattern(), resolver, mockedResponse))
    }

    override fun close() {
        server.stop(0, 5000)
    }

    fun start() {
        server.start()
    }

    @Throws(InterruptedException::class)
    fun waitUntilClosed() {
        while(true) {
            Thread.sleep(1000)

            try {
                if (!server.application.isActive) break
            } catch(e: Exception) {
                println(e.localizedMessage)
            }
        }
    }

    companion object {
        @JvmStatic
        @Throws(Throwable::class)
        fun fromGherkin(contractGherkin: String, port: Int = 8080): ContractMock {
            return ContractMock(contractGherkin, port)
        }

        @JvmStatic
        fun fromGherkin(contractGherkin: String): ContractMock {
            return ContractMock(contractGherkin, 8080)
        }
    }
}

fun stringToMockScenario(text: Value): ScenarioStub {
    val mockSpec =
            jsonStringToValueMap(text.toStringValue()).also {
                validateMock(it)
            }

    return mockFromJSON(mockSpec)
}

fun respond(call: ApplicationCall, expectations: List<Expectation>, httpRequest: HttpRequest) {
    expectations.find { (requestPattern, resolver, _) ->
        requestPattern.matches(httpRequest, resolver) is Result.Success
    }?.let { it ->
        respondToKtorHttpResponse(call, it.third)
    } ?: respondToKtorHttpResponse(call, debugInfoIn400Response(httpRequest))
}

fun debugInfoIn400Response(httpRequest: HttpRequest): HttpResponse {
    val message = """
The http request did not match any of the stubs.

HttpRequest: ${httpRequest.toJSON()}
"""
    return HttpResponse(400, message)
}

fun writeBadRequest(call: ApplicationCall, errorMessage: String?) {
    call.response.status(HttpStatusCode.UnprocessableEntity)
    call.response.header("X-Qontract-Result", "failure")
    runBlocking { call.respondText(errorMessage ?: "") }
}

fun isStubRequest(httpRequest: HttpRequest) =
        httpRequest.path == "/_stub_setup" && httpRequest.method == "POST"
