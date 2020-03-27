package run.qontract.core.pattern

import run.qontract.core.ContractParseException
import run.qontract.core.Resolver
import run.qontract.core.Result
import run.qontract.core.value.StringValue
import run.qontract.core.value.Value

class UnknownPattern : Pattern {
    override fun matches(sampleData: Value?, resolver: Resolver) = Result.Failure("Unknown pattern $sampleData")

    override fun generate(resolver: Resolver): Value = StringValue("")
    override fun newBasedOn(row: Row, resolver: Resolver): List<Pattern> = listOf(this)
    override fun parse(value: String, resolver: Resolver): Value = throw ContractParseException("Unknown pattern, don't know how to interpret the value $value")
    override fun resolveType(key: String, resolver: Resolver): Pattern? = this

    override val pattern: Any = ""
    override fun toString(): String = "(Unknown pattern)"
}