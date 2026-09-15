package com.projectticket.ticket

import org.assertj.core.api.Assertions.assertThat
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

/**
 * 응답 계약을 파일로 굳힌다(`D8` 「스냅샷 테스트」). 화면이 그대로 읽는 응답은 필드 하나가 빠져도 타입 검사가 못 잡는다.
 *
 * 흔들리는 필드(`version`·`id`·시각)는 이름으로 골라 자리표시로 바꾼 뒤 비교한다.
 * 갱신은 `-Psnapshot.update=true` 로만 — 없으면 처음 실행에서도 실패한다. 자동으로 쓰면 「계약이 바뀌었다」가 초록으로 지나간다.
 *
 * 줄바꿈은 `\n` 으로 맞춘다. Jackson 의 기본 들여쓰기가 OS 줄바꿈을 쓰고 CI 는 리눅스라, 안 맞추면 Windows 에서 쓴 파일이 CI 에서 빨갛다.
 */
class Snapshot(private val json: ObjectMapper, private val testClass: Class<*>) {

    fun assertMatches(name: String, actualJson: String, volatileFields: Set<String> = setOf("version", "id")) {
        val file = DIR.resolve("${testClass.simpleName}.$name.json")
        val actual = json.writerWithDefaultPrettyPrinter()
            .writeValueAsString(masked(json.readTree(actualJson), volatileFields))
            .replace("\r\n", "\n") + "\n"

        if (System.getProperty("snapshot.update") == "true") {
            Files.createDirectories(DIR)
            Files.writeString(file, actual)
            return
        }
        assertThat(file).describedAs("스냅샷이 없다. -Psnapshot.update=true 로 만든다").exists()
        assertThat(actual)
            .describedAs("응답 계약이 스냅샷과 다르다. 계약을 바꾼 것이면 -Psnapshot.update=true 로 갱신하고 이력에 적는다")
            .isEqualTo(Files.readString(file).replace("\r\n", "\n"))
    }

    private fun masked(node: JsonNode, volatileFields: Set<String>): JsonNode {
        when (node) {
            is ObjectNode -> node.properties().forEach { (key, value) ->
                if (key in volatileFields) node.put(key, "<$key>") else masked(value, volatileFields)
            }
            else -> node.forEach { masked(it, volatileFields) }
        }
        return node
    }

    companion object {
        private val DIR: Path = Path.of("src/test/resources/snapshots")
    }
}
