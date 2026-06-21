import org.hjson.JsonValue
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.objectweb.asm.ClassReader
import proguard.gradle.ProGuardTask
import java.lang.reflect.Modifier
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
}
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.hjson:hjson:3.1.0")
        classpath("org.ow2.asm:asm:9.10.1")
        classpath("com.guardsquare:proguard-gradle:7.9.1")
    }
}

repositories {
    mavenCentral()
    ivy {
        url = URI("https://github.com/")
        patternLayout { artifact("/[organisation]/[module]/releases/download/[revision]/dependencies.jar") }
        metadataSources { artifact() }
    }
}
configurations.runtimeClasspath {
    exclude("org.jetbrains.kotlin")
    exclude("org.jetbrains.kotlinx")
}
sourceSets.main.get().kotlin.srcDir("src")
dependencies {
    // Sync Mindustry dependency version with manifest
    val modFile = File("${projectDir.absolutePath}/mod.hjson")
    val modData = JsonValue.readHjson(modFile.reader()).asObject()
    val mindustryVersion = modData["minGameVersion"].toString().replace("-", "")

    compileOnly("Anuken:Mindustry:v$mindustryVersion")
}
tasks {
    compileKotlin {
        compilerOptions.jvmTarget = JvmTarget.JVM_1_8
    }
    compileJava {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
        options.release = 8
    }

    // Completes the 'mod.hjson' manifest with version and main class, then inserts it into output
    val generateMetadata = register("generateMetadata") {
        dependsOn(compileKotlin)
        // Need source manifest + compile output to find the main class
        inputs.files(compileKotlin.get().outputs.files + "${projectDir.absolutePath}/mod.hjson")
        // Final mod manifest
        outputs.file("mod.hjson")
        doLast {
            // Load source manifest
            val modFile = inputs.files.filter { it.name == "mod.hjson" }.singleFile
            val modData = JsonValue.readHjson(modFile.reader()).asObject()

            // search for a main class
            modData["main"] = findDerivedClass(
                "mindustry.mod.Mod",
                compileKotlin.get().outputs.files,
                configurations.run { compileClasspath.get() + runtimeClasspath.get() },
            ) ?: throw GradleException("No main mod class was found")

            // derive version from the environment
            modData["version"] = if (System.getenv("GITHUB_ACTIONS") == "true") {
                // GitHub workflow: release tag or commit hash
                val ref = System.getenv("GITHUB_REF")
                if(ref.startsWith("refs/tags/")) {
                    ref.removePrefix("refs/tags/")
                } else {
                    "commit-${System.getenv("GITHUB_SHA")}"
                }
            } else {
                // local build - timestamp (I don't have any better ideas)
                "local-${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}"
            }

            println("Mod class is: ${modData["main"]}")
            println("Mod version is: ${modData["version"]}")
            // Complete manifest
            outputs.files.singleFile.writeText(modData.toString(org.hjson.Stringify.HJSON))
            println("Written metadata to: ${outputs.files.singleFile.absolutePath}")
        }
    }

    // Generates raw (see "optimize") jar package
    jar {
        dependsOn(generateMetadata)
        inputs.file(generateMetadata.get().outputs.files.singleFile)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        archiveFileName = "${project.name}Raw.jar"

        // Complied code
        from(configurations.runtimeClasspath.get().flatMap {
            if (it.isDirectory()) {
                fileTree(it)
            } else {
                zipTree(it)
            }
        })
        // Complete mod manifest from "generateMetadata"
        from(inputs.files.filter { it.name == "mod.hjson" }.singleFile)
        // Media assets
        from("assets").include("**")

        doLast {
            println("Built JAR at: ${outputs.files.singleFile.absolutePath}")
        }
    }

    // Optimizes raw jar package, creating complete Desktop mod
    val optimize = register<ProGuardTask>("optimize") {
        dependsOn(jar)
        dependsOn(generateMetadata)
        inputs.file(generateMetadata.get().outputs.files.singleFile)
        injars(jar.get().outputs.files)
        libraryjars(
            mapOf(
                Pair("jarfilter", "!**.jar"),
                Pair("filter", "!module-info.class")
            ),
            listOf("${System.getProperty("java.home")}/jmods/java.base.jmod")
                .plus(configurations.runtimeClasspath.get())
                .plus(configurations.compileClasspath.get())
        )
        outjars("${layout.buildDirectory.get()}/libs/${project.name}Desktop.jar")
        doFirst {
            // Keep main class (loaded from manifest) from being trimmed out
            var infoFile = inputs.files.filter { it.name == "mod.hjson" }.singleFile
            var modInfo = JsonValue.readHjson(infoFile.reader()).asObject()
            keepclasseswithmembers("public class ${modInfo["main"]}")
            dontwarn()
        }
        doLast {
            println("Optimized JAR at: ${outputs.files.singleFile.absolutePath}")
        }
    }

    // Generates a "dexed" (special Android format) mod package
    val jarAndroid = register("jarAndroid") {
        dependsOn(optimize)
        inputs.files(optimize.get().outputs.files.singleFile)
        outputs.files("${layout.buildDirectory.get()}/libs/${project.name}Android.jar")

        doLast {
            val sdkRoot = arrayOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
                .mapNotNull { System.getenv(it) }
                .firstOrNull { File(it).exists() }
                ?: throw GradleException("No valid Android SDK found")

            val platformRoot = File("$sdkRoot/platforms/").listFiles()
                .sorted()
                .map { File(it, "android.jar") }
                .findLast(File::exists)
                ?: throw GradleException("No android.jar found")

            val dependencies = (configurations.run { compileClasspath.get() + runtimeClasspath.get() + platformRoot })
                .map { "--classpath ${it.absolutePath}" }
                .toTypedArray()

            val args = listOf("d8", *dependencies,
                "--min-api", "14",
                "--output", outputs.files.singleFile.absolutePath,
                inputs.files.singleFile.absolutePath)
            ProcessBuilder(args)
                .start()
                .waitFor()

            println("Built Android JAR at: ${outputs.files.singleFile.absolutePath}")
        }
    }

    // Prepares and runs an isolated Mindustry installation
    val run = register<JavaExec>("run") {
        dependsOn(optimize)
        inputs.file(optimize.get().outputs.files.singleFile)
        doFirst {
            // Load manifest to get the game's version
            var infoFile = generateMetadata.get().outputs.files.singleFile
            var modInfo = JsonValue.readHjson(infoFile.reader()).asObject()

            // Download game unless it already is
            val gamePath = "${temporaryDir.absolutePath}/Mindustry-v${modInfo["minGameVersion"]}.jar"
            val gameUrl = "https://github.com/Anuken/Mindustry/releases/download/v${modInfo["minGameVersion"]}/Mindustry.jar"
            var gameFile = File(gamePath)
            if (!gameFile.exists()) {
                println("Downloading Mindustry v${modInfo["minGameVersion"]} to $gamePath")
                URI(gameUrl).toURL().openStream().copyTo(gameFile.outputStream())
            }

            // Copy mod to game installation
            val dataDirPath = "${temporaryDir.absolutePath}/datadir"
            copy {
                from(inputs.files.singleFile)
                into("${dataDirPath}/mods")
            }

            // Settings to run Mindustry
            environment("MINDUSTRY_DATA_DIR", dataDirPath)
            classpath = files(gamePath)
        }
    }

    // Generates a Desktop+Android bundle to upload
    val deploy = register<Jar>("deploy") {
        dependsOn(optimize)
        dependsOn(jarAndroid)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        archiveFileName = "${project.name}.jar"

        from(
            zipTree(optimize.get().outputs.files.singleFile) +
            zipTree(jarAndroid.get().outputs.files.singleFile)
        )
    }
}

fun findDerivedClass(base: String, user: Iterable<File>, classpath: Iterable<File>): String? {
    val base = base.replace('.', '/')
    val user = unpackClasses(user)
    val classpath = unpackClasses(classpath)

    // build inheritance map of all classes
    val inheritance = (user + classpath)
        .flatMap {
            if (it.extension == "jar") {
                zipTree(it)
            } else if(it.isDirectory) {
                fileTree(it)
            } else {
                listOf(it)
            }
        }
        .filter { it.extension == "class" }
        .associate {
            val cr = ClassReader(it.inputStream())
            Pair(cr.className, cr.superName)
        }

    // search a class
    return user
        .asSequence()
        .filter { it.extension == "class" }
        .map { ClassReader(it.inputStream()) }
        .filter { Modifier.isPublic(it.access) }
        .map { it.className }
        .find {
            var name = it
            loop {
                if (name == base) return@find true
                if (name == null) return@find false
                name = inheritance[name]
            }
        }
        ?.replace('/', '.')
}

fun unpackClasses(user: Iterable<File>): Iterable<File> = user
    .flatMap {
        if (it.extension == "jar") {
            zipTree(it)
        } else if(it.isDirectory) {
            fileTree(it)
        } else {
            listOf(it)
        }
    }
    .filter { it.extension == "class" }

inline fun loop(body: ()->Unit): Nothing {
    while(true) body()
}