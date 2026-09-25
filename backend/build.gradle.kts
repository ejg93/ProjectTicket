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

// 변이 시험(`G4`, ProjectShop `69` 이식). 측정 레인처럼 `build` 밖이고 손으로 돈다 — 산출물은 살아남은 변이 목록이다.
//
// **플러그인이 아니라 명령줄을 부른다.** Gradle 9 와 맞는 PIT 플러그인 판을 확인하지 못했고, 명령줄은
// 클래스패스와 인자만 받아서 빌드 도구 판에 안 묶인다. 대상은 틀려도 흐름 시험이 초록인 순수 계산이다 —
// 한 변이마다 시험을 다시 돌려서 `db` 태그 시험을 넣으면 한 번에 몇 시간이 된다.
val pitest = configurations.create("pitest")

dependencies {
	pitest("org.pitest:pitest-command-line:1.30.0")
	pitest("org.pitest:pitest-junit5-plugin:1.2.3")
}

val mutationTargets = listOf(
	// 수수료 반올림·D-며칠(`D6`·`D7`). 1원·하루가 틀려도 흐름 시험은 초록이다.
	"com.projectticket.ticket.payment.RefundPolicy",
	// 목록 상한 보정·정렬 허용 목록(`D5`). 부등호 하나가 `size=10000` 을 연다.
	// 이름을 다 적는다 — `CardNumber*` 같은 글롭은 `CardNumberTest` 까지 변이했다(첫 판 실측).
	"com.projectticket.ticket.web.Paging",
	"com.projectticket.ticket.web.OrderBy",
	"com.projectticket.ticket.web.OrderBy\$Companion",
	// 입구 형식 셋 — 카드 자릿수(ISO/IEC 7812)·멱등키(UUIDv4)·구역 코드.
	"com.projectticket.ticket.payment.CardNumbers",
	"com.projectticket.ticket.payment.CardNumberValidator",
	"com.projectticket.ticket.idempotency.IdempotencyKeys",
	"com.projectticket.ticket.event.SectionCodesValidator")

tasks.register<JavaExec>("mutationTest") {
	description = "순수 계산 클래스에 변이를 넣고 빠른 레인 시험이 잡는지 본다(G4). 손으로 돌린다."
	group = "verification"
	dependsOn(tasks.testClasses)
	mainClass = "org.pitest.mutationtest.commandline.MutationCoverageReport"
	classpath = pitest + sourceSets.test.get().runtimeClasspath
	// Windows 에서 클래스패스가 길면 줄여서 넘어가고, 그러면 PIT 가 `java.class.path` 에서 자기 에이전트를
	// 못 찾는다(「Unable to load class content for org.pitest.boot.HotSwapAgent」, ProjectShop 실측). 파일로 한 번 더 준다.
	val classPathFile = layout.buildDirectory.file("pitest-classpath.txt")
	doFirst {
		classPathFile.get().asFile.writeText(classpath.files.joinToString("\n") { it.path })
	}
	args(
		"--classPathFile", classPathFile.get().asFile.path,
		"--reportDir", layout.buildDirectory.dir("reports/pitest").get().asFile.path,
		"--targetClasses", mutationTargets.joinToString(","),
		// 원본은 `<대상>Test*` 로 짝지었는데 여기는 시험 이름이 대상과 안 맞는다(`Paging` 은 `EventListTest` 가 잰다).
		// 빠른 레인 전체를 주면 PIT 가 변이를 덮는 시험만 골라 돈다.
		"--targetTests", "com.projectticket.ticket.*",
		"--sourceDirs", file("src/main/kotlin").path,
		"--excludedGroups", "db,measure",
		"--outputFormats", "XML,HTML",
		"--timestampedReports", "false",
		"--threads", "4")
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
		// `TestConventionTest`(G7d)가 레인 표와 이 빌드 파일을 글자로 읽는다(마무리 12차 독립 리뷰).
		rootProject.file("../doc/reference/testing-strategy.md"),
		rootProject.file("build.gradle.kts"),
		// `SeatLimitConsistencyTest`(41-1)가 화면의 좌석 상한을 글자로 읽는다.
		rootProject.file("../frontend/src/components/seat-map.tsx"),
		// `ScreenLengthTest`(G9)가 폼의 `maxLength` 를 글자로 읽는다.
		fileTree("../frontend/src/app") { include("**/*-form.tsx") },
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


// 타입 해석 규칙은 `detekt` 태스크에서 안 돈다 — detekt 2.0 은 그것을 `detektMain` 에서만 돌린다(`G7a` 실측).
// 켜도 조용히 안 도는 규칙이 되지 않게 `check` 에 `detektMain` 을 걸고, 거기는 `detekt-typed.yml` 에 고른 규칙만 돌린다.
tasks.named<dev.detekt.gradle.Detekt>("detektMain") {
	config.setFrom(files("$rootDir/config/detekt/detekt-typed.yml"))
	buildUponDefaultConfig = false
}

// `gradlew build` 가 두 레인을 다 돈다. 빠른 레인만 보고 push 하면 DB 결함이 CI 에서야 드러난다.
tasks.check {
	dependsOn(tasks.test, integrationTest, "detektMain")
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
