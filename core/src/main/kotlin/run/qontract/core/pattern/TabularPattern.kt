package run.qontract.core.pattern

import io.cucumber.messages.Messages
import run.qontract.core.*
import run.qontract.core.utilities.mapZip
import run.qontract.core.value.*

data class TabularPattern(override val pattern: Map<String, Pattern>) : Pattern {
    override fun matches(sampleData: Value?, resolver: Resolver): Result {
        if(sampleData !is JSONObjectValue)
            return mismatchResult("JSON object", sampleData)

        val missingKey = resolver.findMissingKey(pattern, sampleData.jsonObject)
        if(missingKey != null)
            return missingKeyToResult(missingKey, "key")

        mapZip(pattern, sampleData.jsonObject).forEach { (key, patternValue, sampleValue) ->
            when (val result = withNumberTypePattern(resolver).matchesPattern(key, patternValue, sampleValue)) {
                is Result.Failure -> return result.breadCrumb(key)
            }
        }

        return Result.Success()
    }

    override fun generate(resolver: Resolver) =
            JSONObjectValue(pattern.mapKeys { entry -> withoutOptionality(entry.key) }.mapValues { (key, pattern) ->
                attempt(breadCrumb = key) { resolver.generate(key, pattern) }
            })

    override fun newBasedOn(row: Row, resolver: Resolver): List<Pattern> =
        multipleValidKeys(pattern, row) { pattern ->
            newBasedOn(pattern, row, resolver)
        }.map { TabularPattern(it) }

    override fun parse(value: String, resolver: Resolver): Value = parsedJSONStructure(value)
    override fun encompasses(otherPattern: Pattern, thisResolver: Resolver, otherResolver: Resolver): Result {
        when (otherPattern) {
            is ExactValuePattern -> return otherPattern.fitsWithin(listOf(this), otherResolver, thisResolver)
            !is TabularPattern -> return Result.Failure("Expected tabular json type, got ${otherPattern.typeName}")
            else -> {
                val myRequiredKeys = pattern.keys.filter { !isOptional(it) }
                val otherRequiredKeys = otherPattern.pattern.keys.filter { !isOptional(it) }

                val missingFixedKey = myRequiredKeys.find { it !in otherRequiredKeys }
                if (missingFixedKey != null)
                    return Result.Failure("Key $missingFixedKey was missing", breadCrumb = missingFixedKey)

                val thisResolverWithNumberType = withNumberTypePattern(thisResolver)
                val otherResolverWithNumberType = withNumberTypePattern(otherResolver)

                val result = pattern.keys.asSequence().map { key ->
                    val bigger = pattern.getValue(key)
                    val smaller = otherPattern.pattern[key] ?: otherPattern.pattern[withoutOptionality(key)]

                    val result = if (smaller != null)
                        bigger.encompasses(resolvedHop(smaller, otherResolverWithNumberType), thisResolverWithNumberType, otherResolverWithNumberType)
                    else Result.Success()
                    Pair(key, result)
                }.find { it.second is Result.Failure }

                return result?.second?.breadCrumb(breadCrumb = result.first) ?: Result.Success()
            }
        }
    }

    override val typeName: String = "json object"
}

fun newBasedOn(patternMap: Map<String, Pattern>, row: Row, resolver: Resolver): List<Map<String, Pattern>> {
    val patternCollection = patternMap.mapValues { (key, pattern) ->
        attempt(breadCrumb = key) {
            newBasedOn(row, key, pattern, resolver)
        }
    }

    return patternList(patternCollection)
}

fun newBasedOn(row: Row, key: String, pattern: Pattern, resolver: Resolver): List<Pattern> {
    val keyWithoutOptionality = withoutOptionality(when(pattern) {
        is Keyed -> pattern.key ?: key
        else -> key
    })

    return when {
        row.containsField(keyWithoutOptionality) -> {
            val rowValue = row.getField(keyWithoutOptionality)
            if (isPatternToken(rowValue)) {
                val rowPattern = resolver.getPattern(rowValue)

                attempt(breadCrumb = key) {
                    when (val result = pattern.encompasses(rowPattern, resolver, resolver)) {
                        is Result.Success -> rowPattern.newBasedOn(row, resolver)
                        else -> throw ContractException(resultReport(result))
                    }
                }
            } else {
                attempt("Format error in example of \"$keyWithoutOptionality\"") { listOf(ExactValuePattern(pattern.parse(rowValue, resolver))) }
            }
        }
        else -> pattern.newBasedOn(row, resolver)
    }
}

fun <ValueType> patternList(patternCollection: Map<String, List<ValueType>>): List<Map<String, ValueType>> {
    if(patternCollection.isEmpty())
        return listOf(emptyMap())

    val key = patternCollection.keys.first()

    return (patternCollection[key] ?: throw ContractException("key $key should not be empty in $patternCollection"))
            .flatMap { pattern ->
                val subLists = patternList<ValueType>(patternCollection - key)
                subLists.map { generatedPatternMap ->
                    generatedPatternMap.plus(Pair(key, pattern))
                }
            }
}

fun <ValueType> multipleValidKeys(patternMap: Map<String, ValueType>, row: Row, creator: (Map<String, ValueType>) -> List<Map<String, ValueType>>): List<Map<String, ValueType>> =
    keySets(patternMap.keys.toList(), row).map { keySet ->
        patternMap.filterKeys { key -> key in keySet }
    }.map { newPattern ->
        creator(newPattern)
    }.flatten()

internal fun keySets(listOfKeys: List<String>, row: Row): List<List<String>> {
    if(listOfKeys.isEmpty())
        return listOf(listOfKeys)

    val key = listOfKeys.last()
    val subLists = keySets(listOfKeys.dropLast(1), row)

    return subLists.flatMap { subList ->
        when {
            row.containsField(withoutOptionality(key)) -> listOf(subList + key)
            isOptional(key) -> listOf(subList, subList + key)
            else -> listOf(subList + key)
        }
    }
}

fun rowsToTabularPattern(rows: List<Messages.GherkinDocument.Feature.TableRow>) =
        TabularPattern(rows.map { it.cellsList }.map { (key, value) ->
            key.value to toJSONPattern(value.value)
        }.toMap())

fun toJSONPattern(value: String): Pattern {
    return value.trim().let {
        val asNumber: Number? = try { convertToNumber(value) } catch (e: Throwable) { null }

        when {
            asNumber != null -> ExactValuePattern(NumberValue(asNumber))
            it.startsWith("\"") && it.endsWith("\"") ->
                ExactValuePattern(StringValue(it.removeSurrounding("\"")))
            it == "null" -> ExactValuePattern(NullValue)
            it == "true" -> ExactValuePattern(BooleanValue(true))
            it == "false" -> ExactValuePattern(BooleanValue(false))
            else -> parsedPattern(value)
        }
    }
}

fun isNumber(value: StringValue): Boolean {
    return try {
        convertToNumber(value.string)
        true
    } catch(e: ContractException) {
        false
    }
}

fun convertToNumber(value: String): Number {
    value.trim().let {
        try {
            return it.toInt()
        } catch (ignored: Exception) {
        }
        try {
            return it.toLong()
        } catch (ignored: Exception) {
        }
        try {
            return it.toFloat()
        } catch (ignored: Exception) {
        }
        try {
            return it.toDouble()
        } catch (ignored: Exception) {
        }

        throw ContractException("""Expected number, actual was "$value"""")
    }
}

