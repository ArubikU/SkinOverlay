plugins {
    id("java")
    id("io.papermc.paperweight.userdev") version "1.7.2"
}

group = "dev.arubik"
version = "2.0"

repositories {
    mavenCentral()
    maven("https://repo.codemc.org/repository/maven-public/")
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperDevBundle("1.21-R0.1-SNAPSHOT")
    compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")
    compileOnly("net.skinsrestorer:skinsrestorer-api:15.4.3")
}

paperweight.reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}