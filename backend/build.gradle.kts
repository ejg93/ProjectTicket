import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
	// Kotlin 버전은 Boot 4.1.1 BOM 이 관리하는 값(2.3.21)과 맞춘다. 플러그인은 BOM 밖이라 직접 적는다 —
	// 갈리면 컴파일러와 stdlib 가 다른 판이 되고 증상이 엉뚱한 자리(리플렉션·직렬화)에서 난다.
	kotlin("jvm") version "2.3.21"
	// @Configuration·@Service 등 Spring 어노테이션이 붙은 클래스를 open 으로 만든다.
	// Kotlin 은 기본이 final 이라 이 플러그인 없이는 프록시가 안 만들어진다.
	kotlin("plugin.spring") version "2.3.21"
	jacoco
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
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	// Kotlin data class 를 Jackson 이 읽고 쓰게 한다. 없으면 기본 생성자가 없다고 죽는다.
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
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
	useJUnitPlatform { includeTags("db") }
	shouldRunAfter(tasks.test)
}

tasks.test {
	useJUnitPlatform { excludeTags("db") }
}

// `gradlew build` 가 두 레인을 다 돈다. 빠른 레인만 보고 push 하면 DB 결함이 CI 에서야 드러난다.
tasks.check {
	dependsOn(tasks.test, integrationTest)
}

tasks.withType<Test> {
	// 실패한 테스트의 이름과 원인만 콘솔에 낸다. 통과한 것을 나열하면 실패가 묻힌다.
	testLogging {
		events("failed")
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
	}
}
