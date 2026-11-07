import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// ---------------------------------------------------------------------------
// Rust core: cargo-ndk builds libferride_ffi.so for each ABI, then
// uniffi-bindgen generates the Kotlin bindings from the arm64 library.
// Both outputs are registered as generated sources through the Variant API,
// so every assemble/test task depends on them automatically.
// ---------------------------------------------------------------------------

val repoRoot: Directory = rootProject.layout.projectDirectory.dir("..")
val rustAbis = mapOf("arm64-v8a" to "aarch64-linux-android", "x86_64" to "x86_64-linux-android")
val rustSources = files(
    repoRoot.dir("crates"),
    repoRoot.file("Cargo.toml"),
    repoRoot.file("Cargo.lock"),
)

abstract class CargoNdkBuild : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input abstract val abis: ListProperty<String>

    @get:Input abstract val minApi: Property<Int>

    @get:Internal abstract val workspace: DirectoryProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val args = mutableListOf("cargo", "ndk")
        abis.get().forEach { args += listOf("-t", it) }
        args += listOf("-P", minApi.get().toString(), "-o", out.absolutePath, "build", "--locked", "--release", "-p", "ferride-ffi")
        exec.exec {
            workingDir = workspace.get().asFile
            commandLine(args)
        }
    }
}

abstract class UniffiBindgen : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val libraries: ConfigurableFileCollection

    @get:Input abstract val libraryRelPath: Property<String>

    @get:Internal abstract val workspace: DirectoryProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val library = libraries.singleFile.resolve(libraryRelPath.get())
        exec.exec {
            workingDir = workspace.get().asFile
            commandLine(
                "cargo", "run", "--locked", "-p", "ferride-ffi", "--features", "bindgen", "--bin", "uniffi-bindgen", "--",
                "generate", "--library", library.absolutePath, "--language", "kotlin", "--no-format",
                "--out-dir", out.absolutePath,
            )
        }
    }
}

/**
 * Builds `libferride_ffi.so` for the build machine, so JVM unit tests
 * (Robolectric) can load the real Rust core through JNA: screenshots then show
 * actual tree-sitter highlighting instead of the fake model's regexes. Same
 * features as `uniffiBindgen`'s host build, so it mostly reuses that work.
 */
abstract class CargoHostBuild : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal abstract val workspace: DirectoryProperty

    @get:OutputFile abstract val library: RegularFileProperty

    @TaskAction
    fun build() {
        exec.exec {
            workingDir = workspace.get().asFile
            commandLine("cargo", "build", "--locked", "-p", "ferride-ffi", "--features", "bindgen", "--lib")
        }
    }
}

val hostLibName = when {
    System.getProperty("os.name").startsWith("Mac") -> "libferride_ffi.dylib"
    System.getProperty("os.name").startsWith("Windows") -> "ferride_ffi.dll"
    else -> "libferride_ffi.so"
}

val cargoHostBuild = tasks.register<CargoHostBuild>("cargoHostBuild") {
    group = "rust"
    description = "Builds the Rust core for this machine (JVM unit tests load it through JNA)"
    sources.from(rustSources)
    workspace.set(repoRoot)
    library.set(repoRoot.file("target/debug/$hostLibName"))
}

val cargoNdkBuild = tasks.register<CargoNdkBuild>("cargoNdkBuild") {
    group = "rust"
    description = "Builds libferride_ffi.so for ${rustAbis.keys} with cargo-ndk"
    sources.from(rustSources)
    abis.set(rustAbis.keys.toList())
    minApi.set(30)
    workspace.set(repoRoot)
    outputDir.set(layout.buildDirectory.dir("rust/jniLibs"))
}

val uniffiBindgen = tasks.register<UniffiBindgen>("uniffiBindgen") {
    group = "rust"
    description = "Generates Kotlin bindings for the Rust core with uniffi-bindgen"
    libraries.from(cargoNdkBuild.flatMap { it.outputDir })
    libraryRelPath.set("arm64-v8a/libferride_ffi.so")
    workspace.set(repoRoot)
    outputDir.set(layout.buildDirectory.dir("generated/uniffi/kotlin"))
}

android {
    namespace = "dev.ferride"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ferride"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += rustAbis.keys
        }
    }

    // Storage differs by store (docs/ARCHITECTURE.md, "Storage"):
    // - play: app-private projects + folders from the system picker (SAF) + Ferrux.
    // - foss (F-Droid, GitHub): additionally all-files access, so device folders get real paths.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "ALL_FILES_ACCESS", "false")
        }
        create("foss") {
            dimension = "distribution"
            versionNameSuffix = "-foss"
            buildConfigField("boolean", "ALL_FILES_ACCESS", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        // Sample files per language (shared with ferride-syntax's golden tests) for screenshots.
        getByName("test").resources.directories.add(repoRoot.dir("crates/ferride-syntax/samples").asFile.absolutePath)
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.maxHeapSize = "2g"
                // The host build of the Rust core (see cargoHostBuild).
                it.dependsOn(cargoHostBuild)
                it.inputs.file(cargoHostBuild.flatMap { t -> t.library })
                it.systemProperty("jna.library.path", repoRoot.dir("target/debug").asFile.absolutePath)
                // UniFFI's Android bindings free objects through android.system.SystemCleaner,
                // which Robolectric backs with the JDK's internal cleaner.
                it.jvmArgs("--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED")
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoNdkBuild, CargoNdkBuild::outputDir)
        variant.sources.kotlin?.addGeneratedSourceDirectory(uniffiBindgen, UniffiBindgen::outputDir)
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(composeBom)
    testImplementation(libs.junit)
    // The JVM flavour of JNA (with the host's libjnidispatch) for loading the Rust core in unit tests.
    testImplementation(libs.jna)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
