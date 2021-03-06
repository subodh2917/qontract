package run.qontract.core

import run.qontract.core.pattern.Keyed
import run.qontract.core.pattern.Pattern
import run.qontract.core.pattern.Row
import run.qontract.core.value.NullValue
import run.qontract.core.value.Value

data class URLPathPattern(override val pattern: Pattern, override val key: String? = null) : Pattern, Keyed {
    override fun matches(sampleData: Value?, resolver: Resolver): Result =
            resolver.matchesPattern(key, pattern, sampleData ?: NullValue)

    override fun generate(resolver: Resolver): Value =
            if(key != null) resolver.generate(key, pattern) else pattern.generate(resolver)

    override fun newBasedOn(row: Row, resolver: Resolver): List<URLPathPattern> =
            pattern.newBasedOn(row, resolver).map { URLPathPattern(it, key) }

    override fun parse(value: String, resolver: Resolver): Value = pattern.parse(value, resolver)

    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver): Result {
        if(otherPattern !is URLPathPattern)
            return Result.Failure("Expected url type, got ${otherPattern.typeName}")

        return otherPattern.pattern.fitsWithin(patternSet(thisResolver), otherResolver, thisResolver)
    }

    override val typeName: String = "url path"
}