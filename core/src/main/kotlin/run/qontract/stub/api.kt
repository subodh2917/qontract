@file:JvmName("API")
package run.qontract.stub

import run.qontract.consoleLog
import run.qontract.core.*
import run.qontract.core.pattern.ContractException
import run.qontract.core.utilities.jsonStringToValueMap
import run.qontract.core.utilities.readFile
import run.qontract.core.value.KafkaMessage
import run.qontract.core.value.StringValue
import run.qontract.mock.*
import java.io.File
import java.time.Duration

fun createStubFromContractAndData(contractGherkin: String, dataDirectory: String, host: String = "localhost", port: Int = 9000): HttpStub {
    val contractBehaviour = Feature(contractGherkin)

    val mocks = (File(dataDirectory).listFiles()?.filter { it.name.endsWith(".json") } ?: emptyList()).map { file ->
        println("Loading data from ${file.name}")

        stringToMockScenario(StringValue(file.readText(Charsets.UTF_8)))
                .also {
                    contractBehaviour.matchingStubResponse(it)
                }
    }

    return HttpStub(contractBehaviour, mocks, host, port, ::consoleLog)
}

fun allContractsFromDirectory(dirContainingContracts: String): List<String> =
    File(dirContainingContracts).listFiles()?.filter { it.extension == QONTRACT_EXTENSION }?.map { it.absolutePath } ?: emptyList()

fun createStubFromContracts(contractPaths: List<String>, dataDirPaths: List<String>, host: String = "localhost", port: Int = 9000): HttpStub {
    val contractInfo = loadContractStubsFromFiles(contractPaths, dataDirPaths)
    val features = contractInfo.map { it.first }
    val httpExpectations = contractInfoToHttpExpectations(contractInfo)

    return HttpStub(features, httpExpectations, host, port, ::consoleLog)
}

fun loadContractStubsFromFiles(contractPaths: List<String>, dataDirPaths: List<String>): List<Pair<Feature, List<ScenarioStub>>> {
    val dataDirFileList = allDirsInTree(dataDirPaths)

    val features = contractPaths.map { path ->
        Pair(path, Feature(readFile(path)))
    }

    val dataFiles = dataDirFileList.flatMap {
        it.listFiles()?.toList() ?: emptyList<File>()
    }.filter { it.extension == "json" }
    if (dataFiles.isNotEmpty())
        println("Reading the stub files below:${System.lineSeparator()}${dataFiles.joinToString(System.lineSeparator())}")

    val mockData = dataFiles.map { Pair(it.path, stringToMockScenario(StringValue(it.readText()))) }

    return loadQontractStubs(features, mockData)
}

fun loadQontractStubs(features: List<Pair<String, Feature>>, stubData: List<Pair<String, ScenarioStub>>): List<Pair<Feature, List<ScenarioStub>>> {
    val contractInfoFromStubs = stubData.mapNotNull { (stubFile, stub) ->
        val matchResults = features.asSequence().map { (qontractFile, feature) ->
            try {
                val kafkaMessage = stub.kafkaMessage
                if (kafkaMessage != null) {
                    feature.assertMatchesMockKafkaMessage(kafkaMessage)
                } else {
                    feature.matchingStubResponse(stub.request, stub.response)
                }
                Pair(feature, null)
            } catch (e: NoMatchingScenario) {
                Pair(null, Pair(e, qontractFile))
            }
        }

        when (val feature = matchResults.mapNotNull { it.first }.firstOrNull()) {
            null -> {
                println(matchResults.mapNotNull { it.second }.map { (exception, contractFile) ->
                    "$stubFile didn't match $contractFile${System.lineSeparator()}${exception.message}"
                }.joinToString("${System.lineSeparator()}${System.lineSeparator()}"))
                null
            }
            else -> Pair(feature, stub)
        }
    }.groupBy { it.first }.mapValues { it.value.map { it.second } }.entries.map { Pair(it.key, it.value) }

    val stubbedFeatures = contractInfoFromStubs.map { it.first }
    val missingFeatures = features.map { it.second }.filter { it !in stubbedFeatures }

    return contractInfoFromStubs.plus(missingFeatures.map { Pair(it, emptyList<ScenarioStub>()) })
}

fun allDirsInTree(dataDirPath: String): List<File> = allDirsInTree(listOf(dataDirPath))
fun allDirsInTree(dataDirPaths: List<String>): List<File> =
        dataDirPaths.map { File(it) }.filter {
            it.isDirectory
        }.flatMap {
            val fileList: List<File> = it.listFiles()?.toList()?.filterNotNull() ?: emptyList()
            pathToFileListRecursive(fileList).plus(it)
        }

private fun pathToFileListRecursive(dataDirFiles: List<File>): List<File> =
        dataDirFiles.filter {
            it.isDirectory
        }.map {
            val fileList: List<File> = it.listFiles()?.toList()?.filterNotNull() ?: emptyList()
            pathToFileListRecursive(fileList).plus(it)
        }.flatten()

fun createStubFromContracts(contractPaths: List<String>, host: String = "localhost", port: Int = 9000): ContractStub {
    val dataDirPaths = implicitContractDataDirs(contractPaths)
    return createStubFromContracts(contractPaths, dataDirPaths, host, port)
}

fun implicitContractDataDirs(contractPaths: List<String>) =
        contractPaths.map { implicitContractDataDir(it).absolutePath }

fun implicitContractDataDir(path: String): File {
    val contractFile = File(path)
    return File("${contractFile.absoluteFile.parent}/${contractFile.nameWithoutExtension}$DATA_DIR_SUFFIX")
}

fun stubKafkaMessage(contractPath: String, message: String, bootstrapServers: String) {
    val kafkaMessage = kafkaMessageFromJSON(getJSONObjectValue(MOCK_KAFKA_MESSAGE, jsonStringToValueMap(message)))
    Feature(File(contractPath).readText()).assertMatchesMockKafkaMessage(kafkaMessage)
    createProducer(bootstrapServers).use {
        it.send(producerRecord(kafkaMessage))
    }
}

fun testKafkaMessage(contractPath: String, bootstrapServers: String, commit: Boolean) {
    val feature = Feature(File(contractPath).readText())

    val results = feature.scenarios.map {
        testKafkaMessages(it, bootstrapServers, commit)
    }

    if(results.any { it is Result.Failure }) {
        throw ContractException(Results(results.toMutableList()).report())
    }
}

fun testKafkaMessages(scenario: Scenario, bootstrapServers: String, commit: Boolean): Result {
    return createConsumer(bootstrapServers, commit).use { consumer ->
        if(scenario.kafkaMessagePattern == null) throw ContractException("No kafka message found to test with")

        val topic = scenario.kafkaMessagePattern.topic
        consumer.subscribe(listOf(topic))

        val messages = consumer.poll(Duration.ofSeconds(1)).map {
            KafkaMessage(topic, it.key()?.let { key -> StringValue(key) }, StringValue(it.value()))
        }

        val results = messages.map {
            scenario.kafkaMessagePattern.matches(it, scenario.resolver)
        }

        Results(results.toMutableList()).toResultIfAny()
    }
}
