// The embedded limbo server.
//
// `upstream/` is a pristine git subtree of NanoLimbo (https://github.com/Nan1t/NanoLimbo, GPL-3.0).
// Its own build.gradle.kts and settings.gradle.kts are inert — Gradle only reads the root settings
// file — which is why this module's build script lives here instead of being overwritten inside the
// subtree. Keeping upstream untouched is what makes `git subtree pull` conflict-free.
//
// Dependencies are declared against this repository's version catalog rather than upstream's, so
// that a single Netty/Gson/Adventure version resolves across the whole shaded jar. See docs/limbo.md
// for the list of local patches and how to update the subtree.

plugins {
    `java-library`
}

sourceSets {
    main {
        java.srcDir("upstream/src/main/java")
        resources.srcDir("upstream/src/main/resources")
    }
}

dependencies {
    // Brings Adventure (api, nbt, serializers, minimessage), Gson, Configurate 4, SLF4J and
    // checker-qual transitively, all at the versions the proxy already runs.
    api(project(":velocity-api"))

    // Not re-exported by velocity-api, but both are already on the proxy's runtime classpath.
    implementation(libs.adventure.nbt)
    implementation(libs.adventure.text.serializer.json.legacy.impl)

    implementation(libs.netty.handler)
    implementation(libs.netty.transport.native.epoll)
    implementation(libs.netty.transport.native.iouring)
    implementation(libs.netty.transport.native.kqueue)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
}

// Vendored third-party source: upstream formatting and copyright headers are preserved verbatim.
// Spotless in particular MUST NOT run over `upstream/` — velocity-spotless applies
// licenseHeaderFile(HEADER.txt), which replaces everything above the package declaration and would
// silently rewrite NanoLimbo's GPL notices to "Velocity Contributors".
tasks.withType<Checkstyle>().configureEach {
    isEnabled = false
}

spotless {
    java {
        targetExclude("upstream/**")
    }
}
