package run.qontract.core

import io.cucumber.messages.Messages

data class StepInfo(val text: String, val rowsList: MutableList<Messages.GherkinDocument.Feature.TableRow>) {
    val line = text.trim()
    val words = line.split("\\s+".toRegex(), 2)
    val keyword = words[0].toUpperCase()
    val rest = if (words.size == 2) words[1] else ""

    val isEmpty = line.isEmpty()
}