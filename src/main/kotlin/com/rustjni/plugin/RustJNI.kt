package com.rustjni.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File
import java.util.Properties

class RustJNI : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("rustJni", RustJniExtension::class.java)
        registerCompileTask(project, extension)
        registerInitTask(project, extension)
        configureAndroidSettings(project, extension)
    }

    private fun configureAndroidSettings(project: Project, extension: RustJniExtension) {
        AndroidSettings.configureAndroidSourceSets(project, extension)
        AndroidSettings.configureNdkAbiFilters(project, extension)
    }

    private fun initializeRustProject(project: Project, extension: RustJniExtension) {
        if (rustDirExists(project)) {
            println("The directory 'rust' already exists. Skipping initialization.")
            return
        }
        createNewRustProject(project)
        configureRustEnvironment(project, extension)
    }

    private fun rustDirExists(project: Project) = project.file("${project.rootProject.projectDir}/rust").exists()

    private fun createNewRustProject(project: Project) {
        project.exec {
            workingDir = project.rootProject.projectDir
            commandLine = listOf("cargo", "new", "rust")
        }
    }

    private fun configureRustEnvironment(project: Project, extension: RustJniExtension) {
        configCargo(project, extension)
        configRustLib(project, extension)
        generateConfigToml(project, extension)
        reconfigureNativeMethods(project, extension)
    }

    private fun reconfigureNativeMethods(project: Project, extension: RustJniExtension) {
        Reflection.removeNativeMethodDeclaration(project, extension)
        Reflection.addNativeMethodDeclaration(project, extension)
    }

    private fun registerCompileTask(project: Project, extension: RustJniExtension) {
        project.tasks.register("rust-jni-compile") {
            group = "build"
            description = "Compiles Rust code for specified architectures"

            doFirst {
                if (!rustDirExists(project)) {
                    println("Rust directory not found. Initializing Rust project...")
                    initializeRustProject(project, extension)
                }
            }

            doLast {
                compileRustCode(project, extension)
                copyCompiledLibraries(project, extension)
                reconfigureNativeMethods(project, extension)
            }
        }
    }

    private fun compileRustCode(project: Project, extension: RustJniExtension) {
        val rustDir = project.file("${project.rootProject.projectDir}/rust")
        validateArchitectures(extension)
        addRustTargets(project, rustDir, extension.architecturesList)
        cleanBuildDirectory(rustDir)
        buildRustForArchitectures(project, rustDir, extension.architecturesList)
    }

    private fun validateArchitectures(extension: RustJniExtension) {
        if (extension.architecturesList.isEmpty()) {
            throw org.gradle.api.GradleException("No architectures specified in rustJni extension")
        }
    }

    private fun addRustTargets(project: Project, rustDir: File, architectures: List<ArchitectureConfig>) {
        architectures.forEach { archConfig ->
            project.exec {
                workingDir = rustDir
                commandLine = listOf("rustup", "target", "add", archConfig.target)
            }
        }
    }

    private fun cleanBuildDirectory(rustDir: File) {
        val buildDir = File(rustDir, "build")
        if (buildDir.exists()) {
            buildDir.deleteRecursively()
        }
    }

    private fun buildRustForArchitectures(project: Project, rustDir: File, architectures: List<ArchitectureConfig>) {
        architectures.forEach { archConfig ->
            project.exec {
                workingDir = rustDir
                commandLine = listOf("cargo", "build", "--target", archConfig.target, "--release")
            }
        }
    }

    private fun copyCompiledLibraries(project: Project, extension: RustJniExtension) {
        val rustDir = project.file("${project.rootProject.projectDir}/rust")
        val libName = extension.libName
        val architectures = extension.architecturesList

        architectures.forEach { archConfig ->
            val outputDir = createOutputDirForArchitecture(rustDir, archConfig)
            val fileExtension = getFileExtensionForTarget(archConfig.target)
            val sourceLib = File(rustDir, "target/${archConfig.target}/release/lib${libName}$fileExtension")
            val destLib = File(outputDir, "lib${libName}$fileExtension")
            copyLibraryFile(sourceLib, destLib)
        }
    }

    private fun createOutputDirForArchitecture(rustDir: File, archConfig: ArchitectureConfig): File {
        val outputDirName = when (archConfig.target) {
            AndroidTarget.ARMV7_LINUX_ANDROIDEABI -> "armeabi-v7a"
            AndroidTarget.AARCH64_LINUX_ANDROID -> "arm64-v8a"
            AndroidTarget.I686_LINUX_ANDROID -> "x86"
            AndroidTarget.X86_64_LINUX_ANDROID -> "x86_64"
            "aarch64-apple-ios" -> "ios-arm64"
            else -> archConfig.target.replace('-', '_')
        }
        val outputDir = File(rustDir, "build/$outputDirName")
        outputDir.mkdirs()
        return outputDir
    }

    private fun getFileExtensionForTarget(target: String) = if (target.contains("apple-ios")) ".dylib" else ".so"

    private fun copyLibraryFile(sourceLib: File, destLib: File) {
        if (sourceLib.exists()) {
            sourceLib.copyTo(destLib, overwrite = true)
        } else {
            throw org.gradle.api.GradleException("Compiled library not found: ${sourceLib.absolutePath}")
        }
    }

    private fun registerInitTask(project: Project, extension: RustJniExtension) {
        project.tasks.register("rust-jni-init") {
            group = "setup"
            description = "Initializes the Rust project"

            doFirst {
                println("architectureList: ${extension.architecturesList}")
            }

            doLast {
                initializeRustProject(project, extension)
            }
        }
    }

    private fun configCargo(project: Project, extension: RustJniExtension) {
        val configToml = project.file("${project.rootProject.projectDir}/rust/Cargo.toml")
        configToml.writeText(buildCargoConfig(extension))
        println("Cargo.toml updated")
    }

    private fun buildCargoConfig(extension: RustJniExtension): String {
        return """
            [package]
            name = "${extension.libName}"
            version = "${extension.libVersion}"
            edition = "2021"

            [lib]
            crate-type = ["cdylib"]
            path = "src/rust_jni.rs"

            [dependencies]
            jni = "0.19"
        """.trimIndent()
    }

    private fun configRustLib(project: Project, extension: RustJniExtension) {
        val rustSrcDir = project.file("${project.rootProject.projectDir}/rust/src")
        deleteMainRsFile(rustSrcDir)
        createRustJNIFile(rustSrcDir, extension.jniHost)
    }

    private fun deleteMainRsFile(rustSrcDir: File) {
        val mainFile = File(rustSrcDir, "main.rs")
        if (mainFile.exists() && !mainFile.delete()) {
            throw org.gradle.api.GradleException("Failed to delete main.rs")
        } else {
            println("Deleted 'main.rs'")
        }
    }

    private fun createRustJNIFile(rustSrcDir: File, jniHost: String) {
        val libFile = File(rustSrcDir, "rust_jni.rs")
        libFile.writeText(buildRustJNIContent(jniHost))
        println("Updated 'rust_jni.rs' with the new content.")
    }

    private fun buildRustJNIContent(jniHost: String): String {
        val javaClassPath = jniHost.replace('.', '_')
        return """
            use jni::JNIEnv;
            use jni::objects::JClass;
            use jni::sys::{jstring};

            #[no_mangle]
            pub extern "C" fn Java_${javaClassPath}_sayHello(
                env: JNIEnv,
                _class: JClass,
            ) -> jstring {
                let output = r#"
            __________________________
            < Hello RustJNI >
            --------------------------
                    \\
                     \\
                        _~^~^~_
                    \\) /  o o  \\ (/
                      '_   -   _'
                      / '-----' \\
            _________________________________________________
            Do your rust implementation there: /rust/src/rust_jni.rs
            ---------------------------------------------------------"#;

                env.new_string(output)
                    .expect("Couldn't create Java string!")
                    .into_inner()
            }
        """.trimIndent()
    }

    private fun generateConfigToml(project: Project, extension: RustJniExtension) {
        val configToml = project.file("${project.rootProject.projectDir}/rust/.cargo/config.toml")
        if (configToml.exists()) {
            println("The file 'config.toml' already exists. Skipping generation.")
            return
        }
        val prebuiltPath = getPrebuiltPath(project, extension)
        configToml.parentFile.mkdirs()
        configToml.writeText(buildConfigTomlContent(extension, prebuiltPath))
        println("config.toml generated at: ${configToml.absolutePath}")
    }

    private fun getPrebuiltPath(project: Project, extension: RustJniExtension): String {
        val props = Properties()
        project.file("${project.rootProject.projectDir}/local.properties").inputStream().use { props.load(it) }
        val ndkDir = props.getProperty("ndk.dir")
            ?: throw org.gradle.api.GradleException("ndk.dir not defined in local.properties")

        val osName = System.getProperty("os.name").toLowerCase()
        val defaultPrebuilt = when {
            osName.contains("win") -> "windows-x86_64"
            osName.contains("mac") -> "darwin-x86_64"
            osName.contains("linux") -> "linux-x86_64"
            else -> throw org.gradle.api.GradleException("Unsupported operating system: $osName")
        }

        val localPrebuilt = props.getProperty("prebuilt")
        val extensionPrebuilt = extension.preBuilt

        return when {
            !localPrebuilt.isNullOrEmpty() -> {
                if (extensionPrebuilt.isNotEmpty()) {
                    println("Warning: 'prebuilt' specified in local.properties overrides the value in rustJni extension.")
                }
                localPrebuilt
            }
            extensionPrebuilt.isNotEmpty() -> extensionPrebuilt
            else -> {
                println("No 'prebuilt' specified. Using default for OS: $defaultPrebuilt")
                defaultPrebuilt
            }
        }.let { prebuilt -> "$ndkDir/toolchains/llvm/prebuilt/$prebuilt/bin/" }
    }

    private fun buildConfigTomlContent(extension: RustJniExtension, prebuiltPath: String): String {
        val architectures = extension.architecturesList
        if (architectures.isEmpty()) {
            throw org.gradle.api.GradleException("No architectures specified in rustJni extension")
        }

        return buildString {
            architectures.forEach { archConfig ->
                appendLine("[target.${archConfig.target}]")
                appendLine("""ar = "${prebuiltPath}${archConfig.ar}"""")
                appendLine("""linker = "${prebuiltPath}${archConfig.linker}"""")
                appendLine()
            }
        }
    }
}
