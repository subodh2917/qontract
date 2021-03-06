package run.qontract.core.pattern

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import run.qontract.core.shouldMatch
import run.qontract.core.value.NullValue
import run.qontract.core.value.NumberValue

internal class ConvertTest {
    @Test
    fun `create a pattern that matches null or any other pattern`() {
        val pattern = parsedPattern("(number?)")
        val nullValue = NullValue
        val numberValue = NumberValue(10)

        nullValue shouldMatch pattern
        numberValue shouldMatch pattern
    }

    @Test
    fun `create a pattern that matches null or a list`() {
        val pattern = parsedPattern("(number*?)")
        val nullValue = NullValue
        val numberList = parsedValue("[1, 2, 3]")

        nullValue shouldMatch pattern
        numberList shouldMatch pattern
    }

    @Test
    fun `create a pattern that matches a list of nullable values`() {
        val pattern = parsedPattern("(number?*)")
        val numberList = parsedValue("[1,2,3]")
        val numberListWithNulls = parsedValue("[1,null,3, 4]")

        numberList shouldMatch pattern
        numberListWithNulls shouldMatch pattern
    }

    @Test
    fun `given a number in string question it should generate a nullable number in string`() {
        val pattern = parsedPattern("(number in string?)")
        val expectedPattern = AnyPattern(listOf(NullPattern, PatternInStringPattern(DeferredPattern("(number)"))))

        assertThat(pattern).isEqualTo(expectedPattern)
    }

    @Test
    fun `given a number in string star it should generate a nullable number in string`() {
        val pattern = parsedPattern("(number in string*)")
        val expectedPattern = ListPattern(PatternInStringPattern(DeferredPattern("(number)")))

        assertThat(pattern).isEqualTo(expectedPattern)
    }
}