package run.qontract.core

import run.qontract.core.pattern.*
import run.qontract.core.utilities.flatZipNullable
import java.lang.StringBuilder

data class Scenario(val name: String, val httpRequestPattern: HttpRequestPattern, val httpResponsePattern: HttpResponsePattern, val expectedState: HashMap<String, Any>, val examples: List<PatternTable>, val patterns: HashMap<String, Pattern>, val fixtures: HashMap<String, Any>) {
    private fun serverStateMatches(actualState: HashMap<String, Any>, resolver: Resolver) =
            expectedState.keys == actualState.keys &&
                    flatZipNullable(expectedState, actualState).all { (key, patternValue, stateValue) ->
                        when {
                            stateValue == true || patternValue == true -> true
                            isPatternToken(patternValue) -> resolver.matchesPattern(key, patternValue, stateValue) is Result.Success
                            else -> patternValue == stateValue
                        }
                    }

    fun matches(httpRequest: HttpRequest, serverState: HashMap<String, Any>): Result {
        val resolver = Resolver(serverState, false).also {
            it.addCustomPatterns(patterns)
        }
        if (!serverStateMatches(serverState, resolver.copy())) {
            return Result.Failure("Server State mismatch").also { it.updateScenario(this) }
        }
        return httpRequestPattern.matches(httpRequest, resolver).also {
            it.updateScenario(this)
        }
    }

    fun generateHttpResponse(actualServerState: HashMap<String, Any>): HttpResponse {
        val combinedState = Resolver(actualServerState, false).let { resolver ->
            resolver.customPatterns = patterns
            combineExpectedWithActual(expectedState, actualServerState, resolver)
        }

        return Resolver(combinedState, false).let { resolver ->
            resolver.customPatterns = patterns
            httpResponsePattern.generateResponse(resolver)
        }
    }

    private fun combineExpectedWithActual(
            expected: HashMap<String, Any>,
            actual: HashMap<String, Any>,
            resolver: Resolver
    ): HashMap<String, Any> {
        val combinedServerState = HashMap<String, Any>()

        for (key in expected.keys + actual.keys) {
            val expectedValue = expected[key]
            val actualValue = actual[key]

            if (expectedValue != null && actualValue != null) {
                when {
                    key in expected && key in actual -> {
                        if (expectedValue == actualValue)
                            combinedServerState[key] = actualValue
                        else if (isPatternToken(expectedValue)) {
                            //TODO: remove toBoolean and pass the result
                            if (resolver.matchesPattern(key, expectedValue, actualValue).toBoolean())
                                combinedServerState.put(key, actualValue)
                        }
                    }
                    key in expected -> combinedServerState.put(key, expectedValue)
                    key in actual -> combinedServerState[key] = actualValue
                }
            }
        }

        return combinedServerState
    }

    fun generateHttpRequest(): HttpRequest {
        val resolver = Resolver(expectedState, false)
        resolver.customPatterns = patterns
        return httpRequestPattern.generate(resolver)
    }

    fun matches(httpResponse: HttpResponse): Result {
        val resolver = Resolver(expectedState, false)
        resolver.customPatterns = patterns
        return try {
            httpResponsePattern.matches(httpResponse, resolver).also { it.updateScenario(this) }.let {
                when (it) {
                    is Result.Failure -> it.add("Response did not match")
                            .also { failure -> failure.updateScenario(this) }
                    else -> it
                }
            }
        } catch (exception: Throwable) {
            return Result.Failure("Error: ${exception.message}")
        }
    }

    private fun newBasedOn(row: Row): List<Scenario> {
        val resolver = Resolver(expectedState, false)
        resolver.customPatterns = patterns

        val newExpectedServerState = newExpectedServerStateBasedOn(row, expectedState, resolver)
        return httpRequestPattern.newBasedOn(row, resolver).map { newHttpRequestPattern ->
            Scenario(name, newHttpRequestPattern, httpResponsePattern, newExpectedServerState, examples, patterns, fixtures)
        }
    }

    fun generateTestScenarios(): List<Scenario> =
            when (examples.size) {
                0 -> listOf(Row())
                else -> examples.flatMap { it.rows }
            }.flatMap { row -> newBasedOn(row) }

    private fun newExpectedServerStateBasedOn(row: Row, expectedServerState: Map<String, Any>, resolver: Resolver): HashMap<String, Any> {
        return HashMap(expectedServerState.mapValues { (key, value) ->
            when {
                row.containsField(key) -> {
                    val fieldValue: Any = row.getField(key) ?: ""
                    if (fieldValue is String) {
                        when {
                            fixtures.containsKey(fieldValue) -> fixtures[fieldValue]
                            isPatternToken(value) -> generateValue(fieldValue, resolver)
                            else -> fieldValue
                        }
                    } else fieldValue
                }
                isPatternToken(value) -> generateValue(value, resolver)
                else -> value
            } as Any
        })
    }

    val serverState: Map<String, Any?>
        get() = generateServerState()

    private fun generateServerState(): Map<String, Any?> {
        val resolver = Resolver(expectedState, false)
        resolver.customPatterns = patterns

        return expectedState.mapValues { (key, value) ->
            when(value) {
                true -> httpRequestPattern.resolvePattern(key, resolver)?.generate(resolver) ?: true
                else -> value
            }
        }
    }

    fun matchesMock(response: HttpResponse): Result {
        val resolver = Resolver(IgnoreServerState(), true)
        resolver.customPatterns = patterns
        return httpResponsePattern.matchesMock(response, resolver).also {
            it.updateScenario(this)
        }
    }

    fun generateHttpResponseFrom(response: HttpResponse?): HttpResponse {
        val resolver = Resolver(expectedState, false)
        resolver.customPatterns = patterns
        return HttpResponsePattern(response!!).generateResponse(resolver)
    }

    override fun toString(): String {
        val scenarioDescription = StringBuilder()
        scenarioDescription.append("Scenario: ")
        when {
            !name.isNullOrEmpty() -> scenarioDescription.append("$name ")
        }
        return scenarioDescription.append("$httpRequestPattern").toString()
    }

    fun newBasedOn(scenario: Scenario): Scenario =
        Scenario(this.name, this.httpRequestPattern, this.httpResponsePattern, this.expectedState, scenario.examples, this.patterns, this.fixtures)

    fun newBasedOn(suggestions: List<Scenario>) =
        this.newBasedOn(suggestions.find { it.name == this.name } ?: this)

}
