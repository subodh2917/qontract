package run.qontract.core.pattern

import run.qontract.core.utilities.jsonStringToValueArray
import run.qontract.core.utilities.jsonStringToValueMap
import run.qontract.core.value.*

internal fun withoutOptionality(key: String) = key.removeSuffix("?")
internal fun isOptional(key: String): Boolean = key.endsWith("?")

internal fun isMissingKey(jsonObject: Map<String, Any?>, key: String) =
        when {
            key.endsWith("?") -> false
            else -> key !in jsonObject && "$key?" !in jsonObject
        }

internal fun containsKey(jsonObject: Map<String, Any?>, key: String) =
        when {
            key.endsWith("?") -> key.removeSuffix("?") in jsonObject
            else -> key in jsonObject
        }

internal val builtInPatterns = mapOf(
    "(number)" to NumberPattern,
    "(string)" to StringPattern,
    "(boolean)" to BooleanPattern,
    "(null)" to NullPattern,
    "(datetime)" to DateTimePattern,
    "(url)" to URLPattern(URLScheme.EITHER),
    "(url-http)" to URLPattern(URLScheme.HTTP),
    "(url-https)" to URLPattern(URLScheme.HTTPS),
    "(url-path)" to URLPattern(URLScheme.PATH))

fun isBuiltInPattern(pattern: Any): Boolean =
    when(pattern) {
        is String -> when {
            pattern in builtInPatterns -> true
            isPatternToken(pattern) -> when {
                ":" in pattern || " in " in pattern || isDictionaryPattern(pattern) -> true
                else -> false
            }
            else -> false
        }
        else -> false
    }

fun isDictionaryPattern(pattern: String): Boolean {
    val pieces = withoutPatternDelimiters(pattern).trim().split("\\s+".toRegex())

    return when(pieces[0]) {
        "dictionary" -> pieces.size == 3
        else -> false
    }
}

fun isPatternToken(patternValue: Any?) =
    when(patternValue) {
        is String -> patternValue.startsWith("(") && patternValue.endsWith(")")
        is StringValue -> patternValue.string.startsWith("(") && patternValue.string.endsWith(")")
        else -> false
    }

internal fun getBuiltInPattern(patternString: String): Pattern =
        when {
            isPatternToken(patternString) -> builtInPatterns.getOrElse(patternString) {
                when {
                    isDictionaryPattern(patternString) ->  {
                        val pieces = withoutPatternDelimiters(patternString).split("\\s+".toRegex())
                        if(pieces.size != 3)
                            throw ContractException("Dictionary type must have 3 parts: type name, key and value")

                        val patterns = pieces.slice(1..2).map { parsedPattern(withPatternDelimiters(it.trim())) }
                        DictionaryPattern(patterns[0], patterns[1])
                    }
                    patternString.contains(":") -> {
                        val patternParts = withoutPatternDelimiters(patternString).split(":")

                        if(patternParts.size != 2)
                            throw ContractException("Type with key must have the key before the colon and the type specification after it. Got $patternString")

                        val (key, patternSpec) = patternParts
                        val pattern = parsedPattern(withPatternDelimiters(patternSpec))

                        LookupRowPattern(pattern, key)
                    }
                    patternString.contains(" in ") -> {
                        val patternParts = withoutPatternDelimiters(patternString).split(" in ").map { it.trim().toLowerCase() }

                        if(patternParts.size != 2)
                            throw ContractException("$patternString seems incomplete")

                        if(patternParts.get(1) != "string")
                            throw ContractException("Only string is supported for declaring a pattern in a pattern")

                        PatternInStringPattern(parsedPattern(withPatternDelimiters(patternParts.get(0))))
                    }
                    else -> throw ContractException("Type $patternString does not exist.")
                }
            }
            else -> throw ContractException("Type $patternString is not a type specifier.")
        }

fun withoutPatternDelimiters(patternValue: String) = patternValue.removeSurrounding("(", ")")
fun withPatternDelimiters(name: String): String = "($name)"

fun withoutListToken(patternValue: Any): String {
    val patternString = (patternValue as String).trim()
    return "(" + withoutPatternDelimiters(patternString).removeSuffix("*") + ")"
}

fun isRepeatingPattern(patternValue: Any?): Boolean =
        patternValue != null && isPatternToken(patternValue) && (patternValue as String).endsWith("*)")

fun stringToPattern(patternValue: String, key: String?): Pattern =
        when {
            isPatternToken(patternValue) -> DeferredPattern(patternValue, key)
            else -> ExactValuePattern(StringValue(patternValue))
        }

fun parsedPattern(rawContent: String, key: String? = null): Pattern {
    return rawContent.trim().let {
        when {
            it.isEmpty() -> NoContentPattern
            it.startsWith("{") -> JSONObjectPattern(it)
            it.startsWith("[") -> JSONArrayPattern(it)
            it.startsWith("<") -> XMLPattern(it)
            isPatternToken(it) -> when {
                isLookupRowPattern(it) -> {
                    val (pattern, lookupKey) = parseLookupRowPattern(it)
                    LookupRowPattern(parsedPattern(pattern), lookupKey)
                }
                isNullablePattern(it) -> AnyPattern(listOf(NullPattern, parsedPattern(withoutNullToken(it))))
                isRestPattern(it) -> RestPattern(parsedPattern(withoutRestToken(it)))
                isRepeatingPattern(it) -> ListPattern(parsedPattern(withoutListToken(it)))
                it == "(number)" -> DeferredPattern(it, null)
                isBuiltInPattern(it) -> getBuiltInPattern(it)
                else -> DeferredPattern(it, key)
            }
            else -> ExactValuePattern(StringValue(it))
        }
    }
}

fun parseLookupRowPattern(token: String): Pair<String, String> {
    val parts = withoutPatternDelimiters(token).split(":".toRegex(), 2).map { it.trim() }

    val key = parts.first()
    val patternToken = parts[1]

    return Pair(withPatternDelimiters(patternToken), key)
}

fun isLookupRowPattern(token: String): Boolean {
    val parts = withoutPatternDelimiters(token).split("\\s+".toRegex())

    return when {
        parts.size == 2 -> true
        else -> false
    }
}

private fun penultimate(parts: List<String>) = parts[parts.size - 2]

fun parsedJSONStructure(content: String): Value {
    return content.trim().let {
        when {
            it.startsWith("{") -> try { JSONObjectValue(jsonStringToValueMap(it)) } catch(e: Throwable) { throw ContractException("String started with { but couldn't be parsed as json object") }
            it.startsWith("[") -> try { JSONArrayValue(jsonStringToValueArray(it)) } catch(e: Throwable) { throw ContractException("String started with [ but couldn't be parsed as json array") }
            else -> throw ContractException("Expected json, actual $content.")
        }
    }
}

fun parsedValue(content: String?): Value {
    return content?.trim()?.let {
        when {
            it.startsWith("{") -> JSONObjectValue(jsonStringToValueMap(it))
            it.startsWith("[") -> JSONArrayValue(jsonStringToValueArray(it))
            it.startsWith("<") -> XMLValue(it)
            else -> StringValue(it)
        }
    } ?: EmptyString
}
