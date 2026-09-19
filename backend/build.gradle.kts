import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	// Kotlin 버전은 Boot 4.1.1 BOM 이 관리하는 값(2.3.21)과 맞춘다. 플러그인은 BOM 밖이라 직접 적는다 —
	// 갈리면 컴파일러와 stdlib 가 다른 판이 되고 증상이 엉뚱한 자리(리플렉션·직렬화)에서 난다.
	kotlin("jvm") version "2.3.21"
	// @Configuration·@Service 등 Spring 어노테이션이 붙은 클래스를 open 으로 만든다.
	// Kotlin 은 기본이 final 이라 이 플러그인 없이는 프록시가 안 만들어진다.
	kotlin("plugin.spring") version "2.3.21"
	jacoco
	// 정적 분석(47). ProjectShop 의 SpotBugs 자리다 — 그쪽은 Java 바이트코드를 보고 이쪽은 Kotlin 소스를 본다.
	// **2.0 알파를 쓴다**: 1.23.8 은 묶인 IntelliJ 유틸이 JDK 25 의 `25.0.1` 을 못 읽어 그냥 선다(`stack.md`).
	id("dev.detekt") version "2.0.0-alpha.6"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

description = "ProjectTicket backend (Apache-2.0)"
group = "com.projectticket"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

kotlin {
	compilerOptions {
		// 널 안전을 Spring 어노테이션(@Nullable 등)까지 엄격하게 본다. 없으면 Java 에서 온 타입이 플랫폼 타입이 돼서
		// 널이 런타임에야 터진다.
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
		jvmTarget = JvmTarget.JVM_25
	}
}

repositories {
	mavenCentral()
}

// Boot 의 BOM 이 Testcontainers 버전을 관리하지 않아서 직접 넣는다. 2.x 는 모듈 좌표에 `testcontainers-` 접두어가 붙는다.
dependencyManagement {
	imports {
		mavenBom("org.testcontainers:testcontainers-bom:2.0.5")
	}
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	// 세션을 톰캣 메모리가 아니라 Redis 에 둔다(ADR 0004). 스타터가 Lettuce 와 `spring-session-data-redis` 를 같이 끌고 온다 —
	// `spring-boot-starter-data-redis` 를 따로 안 넣는다. 두 번 적으면 한쪽만 올라가는 날이 온다.
	implementation("org.springframework.boot:spring-boot-starter-session-data-redis")
	// 사건을 나르는 브로커(28, ADR 0007). 소비자가 둘이 된 뒤에 들였다 — 하나일 땐 함수 호출과 다르지 않다.
	implementation("org.springframework.boot:spring-boot-starter-kafka")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	// 추적 ID 를 발급하고 MDC 까지 나르는 것(D10). Boot 4 는 자동설정이 모듈로 쪼개져 있어서 **둘 다** 넣어야 한다 —
	// 자동설정만 넣으면 Brave 를 optional 로 잡아 조건이 안 맞고, 브리지만 넣으면 자동설정이 없다.
	// 어느 쪽이 빠져도 증상은 같다: 빈은 뜨는데 그게 `Tracer.NOOP` 이라 추적 ID 가 조용히 안 찍힌다.
	implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
	implementation("io.micrometer:micrometer-tracing-bridge-brave")
	// 지표를 Prometheus 형식으로 내놓는다(30). 액추에이터가 `/actuator/prometheus` 를 여는 것은 이 의존이 있을 때뿐이다.
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	// Kotlin data class 를 Jackson 이 읽고 쓰게 한다. 없으면 기본 생성자가 없다고 죽는다.
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.testcontainers:testcontainers-kafka")
	// Redis 는 Testcontainers 2.x 에 전용 모듈이 없다. 코어의 `GenericContainer` 로 띄우므로 코어를 직접 적는다.
	testImplementation("org.testcontainers:testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	// 계층 규칙을 문서에서 테스트로 내린다(D14). JUnit 6 아티팩트다 — Boot 4 BOM 이 JUnit 6 을 준다.
	testImplementation("com.tngtech.archunit:archunit-junit6:1.5.0")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 테스트를 두 레인으로 가른다(D8). 되먹임 속도가 자율 실행의 상한을 정한다 —
// 컨테이너를 안 타는 테스트는 초 단위로 답해야 하고, 한 태스크에 섞이면 전부 느린 쪽 속도로 돈다.
//
// 소스셋이 아니라 태그로 가른다. 컨테이너가 `PostgresTestBase` 에만 있어서 DB 를 쓰려면 상속해야 하고,
// 상속하면 `db` 태그가 따라온다. 상속하지 않고 DB 를 쓰면 빠른 레인에서 곧바로 빨개진다.
val integrationTest = tasks.register<Test>("integrationTest") {
	description = "컨테이너를 띄우는 테스트만 돌린다."
	group = "verification"
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform { includeTags("db"); excludeTags("measure") }
	shouldRunAfter(tasks.test)
}

// 측정 레인(D8). 결과가 초록·빨강이 아니라 숫자라 `build` 밖이다 — 손으로 돌리고 `doc/notes/` 에 기계 사양과 같이 적는다.
// 측정 클래스는 `ConcurrencyTestBase` 를 상속해 `db` 태그도 들므로 느린 레인이 `measure` 를 빼야 두 번 안 돈다.
val measure = tasks.register<Test>("measure") {
	description = "수치를 남기는 측정만 돌린다. build 밖이다."
	group = "verification"
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform { includeTags("measure") }
	// 측정은 늘 다시 돈다. 입력이 같다고 건너뛰면 지난 숫자를 이번 것으로 읽는다.
	outputs.upToDateWhen { false }
	// 표를 그대로 본다. 실패한 것만 내는 공통 설정 위에 표준 출력을 더한다.
	testLogging { showStandardStreams = true }
}

tasks.test {
	useJUnitPlatform { excludeTags("db") }

	// 아래 파일들을 테스트가 **글자로 읽는다**. 입력으로 안 걸면 Kotlin 이 그대로일 때 Gradle 이
	// `UP-TO-DATE` 로 건너뛰어서, 그 파일만 고친 커밋에서 대조가 안 돈다(점검 2차).
	// `verify-fingerprint.sh` 의 backend 레인에도 같은 경로가 있어야 도장이 다시 찍힌다.
	inputs.files(
		rootProject.file("../docker-compose.yml"),
		rootProject.file("../docker/nginx/nginx.conf"),
		rootProject.file("Dockerfile"),
		rootProject.file("../doc/reference/api-guidelines.md"),
		rootProject.file("../doc/reference/observability-rules.md"),
		rootProject.file("../doc/reference/stack.md"),
		rootProject.file("../doc/reference/event-catalog.md"),
		rootProject.file("../doc/reference/state-machines.md"),
		rootProject.file("../.github/workflows/ci.yml"),
		rootProject.file("../.github/workflows/codeql.yml"),
		rootProject.file("../.github/workflows/claude-review.yml"),
		rootProject.file("../doc/reference/quality-gates.md"),
	).withPathSensitivity(PathSensitivity.RELATIVE)
}

// 문턱은 「새 검출 0건」이다(`D18`). 기준선 파일을 안 만든다 — 눌러 둔 목록은 아무도 다시 안 본다.
detekt {
	config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
	// 기본 규칙 위에 우리 설정만 얹는다. 처음부터 다시 쓰면 새 규칙이 생겨도 안 켜진다.
	buildUponDefaultConfig = true
	// 빌드를 세운다. 경고로 두면 그 줄은 아무도 안 읽는다(`D18` 「게이트가 만든 신호」).
	ignoreFailures = false
}

// **detekt 는 자기가 빌드된 Kotlin 으로만 돈다.** 우리 판(2.3.21)과 다르면 「not supported」로 그냥 선다 —
// 그래서 분석기가 쓰는 의존만 그쪽 판에 묶는다(detekt 문서가 정한 방법). 우리 코드가 컴파일되는 판은 그대로다.
configurations.named("detekt") {
	resolutionStrategy.eachDependency {
		if (requested.group == "org.jetbrains.kotlin") {
			useVersion("2.4.10")
		}
	}
}


// `gradlew build` 가 두 레인을 다 돈다. 빠른 레인만 보고 push 하면 DB 결함이 CI 에서야 드러난다.
tasks.check {
	dependsOn(tasks.test, integrationTest)
}

tasks.withType<Test> {
	// 스냅샷 갱신은 계약 변경이라 손으로 켠다(D8): `gradlew integrationTest -Psnapshot.update=true`
	systemProperty("snapshot.update", providers.gradleProperty("snapshot.update").orElse("false").get())
	// 실패한 테스트의 이름과 원인만 콘솔에 낸다. 통과한 것을 나열하면 실패가 묻힌다.
	testLogging {
		events("failed")
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
	}
}
