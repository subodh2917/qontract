package run.qontract.core.pattern

import run.qontract.core.Resolver
import run.qontract.core.Result
import run.qontract.core.value.Value

interface Pattern {
    fun matches(sampleData: Value?, resolver: Resolver): Result
    fun generate(resolver: Resolver): Value
    fun newBasedOn(row: Row, resolver: Resolver): List<Pattern>
    fun parse(value: String, resolver: Resolver): Value

    fun patternSet(resolver: Resolver): List<Pattern> = listOf(this)

    fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver): Result
    fun fitsWithin(otherPatterns: List<Pattern>, thisResolver: Resolver, otherResolver: Resolver): Result {
        val myPatternSet = patternSet(thisResolver)

        val result = myPatternSet.map { my ->
            val encompassResult = otherPatterns.asSequence().map { other ->
                other.encompasses(my, thisResolver, otherResolver)
            }

            encompassResult.find { it is Result.Success } ?: encompassResult.first()
        }

        return result.find { it is Result.Failure } ?: Result.Success()
    }

    val typeName: String
    val pattern: Any
}
