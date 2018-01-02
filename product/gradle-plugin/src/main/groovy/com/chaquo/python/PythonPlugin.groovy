package com.chaquo.python

import org.gradle.api.*
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.*
import org.gradle.api.plugins.*
import org.gradle.util.*
import org.json.JSONObject

import java.nio.file.*
import java.security.MessageDigest

import static com.chaquo.python.Common.pyVersionShort;
import static java.nio.file.StandardCopyOption.*


class PythonPlugin implements Plugin<Project> {
    static final def NAME = "python"
    static final def MIN_ANDROID_PLUGIN_VER = VersionNumber.parse("2.2.0")  // First version to use the current ndk {} syntax.
    static final def MAX_TESTED_ANDROID_PLUGIN_VER = VersionNumber.parse("3.0.1")
    // static final def MAX_ANDROID_PLUGIN_VER = VersionNumber.parse("9.9.9-alpha1")  // Not currently applicable

    Project project
    Object android
    File genDir

    public void apply(Project p) {
        project = p
        genDir = new File(project.buildDir, "generated/$NAME")

        if (!project.hasProperty("android")) {
            throw new GradleException("project.android not set. Did you apply plugin " +
                                              "com.android.application before com.chaquo.python?")
        }
        android = project.android
        checkAndroidPluginVersion()

        extendProductFlavor(android.defaultConfig)
        android.productFlavors.all { extendProductFlavor(it) }
        extendSourceSets()

        // For extraction performance, we want to avoid compressing these files a second time, but
        // .zip is not one of the default noCompress extensions (frameworks/base/tools/aapt/Package.cpp
        // and tools/base/build-system/builder/src/main/java/com/android/builder/packaging/PackagingUtils.java).
        // We don't want to set noCompress "zip" because the user might have an uncompressed ZIP
        // which they were relying on the APK to compress. Luckily this option works just as well
        // with entire filenames.
        android.aaptOptions {
            noCompress(Common.ASSET_APP, Common.ASSET_CHAQUOPY, Common.ASSET_REQUIREMENTS,
                       Common.ASSET_STDLIB)
        }

        setupDependencies()
        project.afterEvaluate { afterEvaluate() }
    }

    void checkAndroidPluginVersion() {
        def depVer = null
        for (dep in project.rootProject.buildscript.configurations.getByName("classpath")
                .getAllDependencies()) {
            if (dep.group == "com.android.tools.build"  &&  dep.name == "gradle") {
                depVer = VersionNumber.parse(dep.version)
                if (depVer < MIN_ANDROID_PLUGIN_VER) {
                    throw new GradleException("Chaquopy requires Android Gradle plugin version " +
                                              "$MIN_ANDROID_PLUGIN_VER or later (current version is " +
                                              "$depVer). Please edit the buildscript block.")
                }
                /* Not currently applicable: re-enable test "AndroidPlugin/old" if this changes.
                if (depVer >= MAX_ANDROID_PLUGIN_VER) {
                    throw new GradleException("Chaquopy does not work with Android Gradle plugin " +
                                    "version $MAX_ANDROID_PLUGIN_VER or later (current version is " +
                                    "$depVer). Please edit the buildscript block.")
                } */
                if (depVer > MAX_TESTED_ANDROID_PLUGIN_VER) {
                    println("Warning: Chaquopy has not been tested with Android Gradle plugin " +
                            "versions beyond $MAX_TESTED_ANDROID_PLUGIN_VER (current version is " +
                            "$depVer). If you experience problems, try editing the " +
                            "buildscript block.")
                }
                break;
            }
        }
        if (depVer == null) {
            println("Warning: Chaquopy was unable to determine the Android Gradle plugin " +
                    "version. Supported versions are $MIN_ANDROID_PLUGIN_VER to " +
                    "$MAX_TESTED_ANDROID_PLUGIN_VER. If you experience problems with a different " +
                    "version, try editing the buildscript block.")
        }
    }

    void extendProductFlavor(ExtensionAware ea) {
        ea.extensions.create(NAME, PythonExtension)
    }

    // See https://issues.apache.org/jira/browse/GROOVY-3493 for why we can't simply assign
    // to sourceSet.metaClass.setRoot, and see previous commit for why we can't use the workaround
    // given there.
    //
    // This alternative workaround (from https://stackoverflow.com/a/31143363/220765) is to assign
    // to metaClass.invokeMethod instead. This actually works for for syntax like
    // `sourceSets.main.setRoot()`, but (FIXME) because of the way Gradle implements
    // NamedDomainObjectContainer using ConfigureDelegate, it has no effect for the more common
    // syntax `sourceSets { main { setRoot(...) } }`. (The other FIXME below is an unrelated
    // problem which I never even got as far as exposing.)
    void extendSourceSets() {
        android.sourceSets.all { sourceSet ->
            sourceSet.metaClass.pyDirSet = sourceSet.java.getClass().newInstance(
                [sourceSet.displayName + " Python source", project] as Object[])
            sourceSet.metaClass.getPython = { return pyDirSet }
            sourceSet.metaClass.python = { closure ->
                closure.delegate = pyDirSet
                closure()
            }
            sourceSet.python.srcDirs = ["src/$sourceSet.name/python"]

            def originalInvokeMethod = sourceSet.metaClass.&invokeMethod
            sourceSet.metaClass.setRoot = { name -> println "setRoot $name" }
            sourceSet.metaClass.invokeMethod = { String name, args ->
                if (name.equals("setRoot")) {
                    python { srcDirs = ["$path/python"] }
                }
                return originalInvokeMethod(name, args) // FIXME how to indicate delegate?
            }
        }
    }

    void setupDependencies() {
        project.repositories { maven { url "https://chaquo.com/maven" } }

        def filename = "chaquopy_java.jar"
        extractResource("runtime/$filename", genDir)
        project.dependencies {
            compile project.files("$genDir/$filename")
        }
    }

    void afterEvaluate() {
        Task buildPackagesTask = createBuildPackagesTask()

        for (variant in android.applicationVariants) {
            def python = new PythonExtension()
            python.mergeFrom(android.defaultConfig.python)
            for (flavor in variant.getProductFlavors().reverse()) {
                python.mergeFrom(flavor.python)
            }

            if (variant.mergedFlavor.minSdkVersion.apiLevel < Common.MIN_SDK_VERSION) {
                throw new GradleException("$variant.name: Chaquopy requires minSdkVersion " +
                                          "$Common.MIN_SDK_VERSION or higher.")
            }
            if (python.version == null) {
                throw new GradleException("$variant.name: python.version not set: you may want to " +
                                          "add it to defaultConfig.")
            }
            if (! Common.PYTHON_VERSIONS.contains(python.version)) {
                throw new GradleException("$variant.name: invalid Python version '${python.version}'. " +
                                          "Available versions are ${Common.PYTHON_VERSIONS}.")
            }

            createConfigs(variant, python)
            Task reqsTask = createReqsTask(variant, python, buildPackagesTask)
            Task mergeSrcTask = createMergeSrcTask(variant, python)
            createProxyTask(variant, python, buildPackagesTask, reqsTask, mergeSrcTask)
            Task ticketTask = createTicketTask(variant)
            createAssetsTasks(variant, python, reqsTask, mergeSrcTask, ticketTask)
            createJniLibsTasks(variant, python)
        }
    }

    void createConfigs(variant, PythonExtension python) {
        def stdlibConfig = configName(variant, "targetStdlib")
        project.configurations.create(stdlibConfig)
        project.dependencies.add(stdlibConfig, targetDependency(python.version, "stdlib"))

        def abiConfig = configName(variant, "targetAbis")
        project.configurations.create(abiConfig)
        for (abi in getAbis(variant)) {
            if (! Common.ABIS.contains(abi)) {
                throw new GradleException("$variant.name: Chaquopy does not support the ABI '$abi'. " +
                                          "Supported ABIs are ${Common.ABIS}.")
            }
            project.dependencies.add(abiConfig, targetDependency(python.version, abi))
        }
    }

    Configuration getConfig(variant, name) {
        return project.configurations.getByName(configName(variant, name))
    }

    String targetDependency(String version, String classifier) {
        def buildNo = Common.PYTHON_BUILD_NUMBERS.get(version)
        return "com.chaquo.python:target:$version-$buildNo:$classifier@zip"
    }

    String[] getAbis(variant) {
        // variant.getMergedFlavor returns a DefaultProductFlavor base class object, which, perhaps
        // by an oversight, doesn't contain the NDK options.
        def abis = new TreeSet<String>()
        def ndk = android.defaultConfig.ndkConfig
        if (ndk.abiFilters) {
            abis.addAll(ndk.abiFilters)  // abiFilters is a HashSet, so its order is undefined.
        }
        for (flavor in variant.getProductFlavors().reverse()) {
            ndk = flavor.ndkConfig
            if (ndk.abiFilters) {
                // Replicate the accumulation behaviour of MergedNdkConfig.append
                abis.addAll(ndk.abiFilters)
            }
        }
        if (abis.isEmpty()) {
            // The Android plugin doesn't make abiFilters compulsory, but we will, because
            // adding every single ABI to the APK is not something we want to do by default.
            throw new GradleException("$variant.name: Chaquopy requires ndk.abiFilters: " +
                                       "you may want to add it to defaultConfig.")
        }
        return abis.toArray()
    }

    Task createBuildPackagesTask() {
        // pip by default finds the cacert file using a path relative to __file__, which won't work
        // when __file__ is something like path/to/a.zip/path/to/module.py. It's easier to run
        // directly from the ZIP and extract the cacert file, than it is to extract the entire ZIP
        // and then deal with auto-generated pyc files complicating the up-to-date checks.
        return project.task("extractPythonBuildPackages") {
            ext.buildPackagesZip = "$genDir/build-packages.zip"
            def cacertRelPath = "pip/_vendor/requests/cacert.pem"
            ext.cacertPem = "$genDir/$cacertRelPath"
            outputs.files(buildPackagesZip, cacertPem)
            doLast {
                extractResource("gradle/build-packages.zip", genDir)
                project.copy {
                    from project.zipTree(buildPackagesZip)
                    include cacertRelPath
                    into genDir
                }
            }
        }
    }

    Task createReqsTask(variant, PythonExtension python, Task buildPackagesTask) {
        return project.task(taskName("generate", variant, "requirements")) {
            ext.destinationDir = variantGenDir(variant, "requirements")
            dependsOn buildPackagesTask
            inputs.property("python", python.serialize())
            inputs.files(getConfig(variant, "targetAbis"))
            def reqsArgs = []
            for (req in python.pip.reqs) {
                reqsArgs.addAll(["--req", req])
                if (project.file(req).exists()) {
                    inputs.files(req)
                }
            }
            for (reqFile in python.pip.reqFiles) {
                reqsArgs.addAll(["--req-file", reqFile])
                inputs.files(reqFile)
            }
            outputs.dir(destinationDir)
            doLast {
                project.delete(destinationDir)
                project.mkdir(destinationDir)
                if (! reqsArgs.isEmpty()) {
                    def pythonAbi = Common.PYTHON_ABIS.get(python.version)
                    execBuildPython(python, buildPackagesTask) {
                        args "-m", "chaquopy.pip_install"
                        args "--target", destinationDir
                        args "--android-abis"
                        args getAbis(variant)
                        args reqsArgs
                        args "--"
                        args "--chaquopy"  // Ensure we never run the system copy of pip by mistake.
                        args "--cert", buildPackagesTask.cacertPem
                        args "--extra-index-url", "https://chaquo.com/pypi"
                        args "--only-binary", ":all:"
                        args "--implementation", pythonAbi.substring(0, 2)
                        args "--python-version", pythonAbi.substring(2, 4)
                        args "--abi", pythonAbi
                        args "--no-compile"
                        args python.pip.options
                    }
                }
            }
        }
    }

    Task createMergeSrcTask(variant, PythonExtension python) {
        // Create the main source set directory if it doesn't already exist, to invite the user
        // to put things in it.
        for (dir in android.sourceSets.main.python.srcDirs) {
            project.mkdir(dir)
        }

        def dirSets = (variant.sourceSets.collect { it.python }
                       .findAll { ! it.sourceFiles.isEmpty() })
        def needMerge = ! (dirSets.size() == 1 &&
                           dirSets[0].srcDirs.size() == 1 &&
                           dirSets[0].filter.excludes.isEmpty() &&
                           dirSets[0].filter.includes.isEmpty())

        def mergeDir = variantGenDir(variant, "sources")
        return project.task(taskName("merge", variant, "sources")) {
            ext.destinationDir = needMerge ? mergeDir : dirSets[0].srcDirs.asList()[0]
            inputs.files(dirSets.collect { it.srcDirs })
            outputs.dir(destinationDir)
            doLast {
                project.delete(mergeDir)
                project.mkdir(mergeDir)
                if (! needMerge) return
                project.copy {
                    into mergeDir
                    duplicatesStrategy "fail"
                    for (dirSet in dirSets) {
                        for (File srcDir in dirSet.srcDirs) {
                            from(srcDir) {
                                excludes = dirSet.filter.excludes
                                includes = dirSet.filter.includes
                            }
                        }
                    }
                }
            }
        }
    }

    void createProxyTask(variant, PythonExtension python, Task buildPackagesTask, Task reqsTask,
                          Task mergeSrcTask) {
        File destinationDir = variantGenDir(variant, "proxies")
        Task proxyTask = project.task(taskName("generate", variant, "proxies")) {
            inputs.files(buildPackagesTask, reqsTask, mergeSrcTask)
            inputs.property("python", python.serialize())
            outputs.dir(destinationDir)
            doLast {
                project.delete(destinationDir)
                project.mkdir(destinationDir)
                if (!python.staticProxy.isEmpty()) {
                    execBuildPython(python, buildPackagesTask) {
                        args "-m", "chaquopy.static_proxy"
                        args "--path", (mergeSrcTask.destinationDir.toString() +
                                        File.pathSeparator + reqsTask.destinationDir)
                        args "--java", destinationDir
                        args python.staticProxy
                    }
                }
            }
        }
        variant.registerJavaGeneratingTask(proxyTask, destinationDir)
    }

    void execBuildPython(PythonExtension python, Task buildPackagesTask, Closure closure) {
        project.exec {
            environment "PYTHONPATH", buildPackagesTask.buildPackagesZip
            executable python.buildPython
            closure.delegate = delegate
            closure()
        }
    }

    Task createTicketTask(variant) {
        def localProps = new Properties()
        def propsStream = project.rootProject.file('local.properties').newInputStream()
        localProps.load(propsStream)
        propsStream.close()  // Otherwise the Gradle daemon may keep the file in use indefinitely.
        def key = localProps.getProperty("chaquopy.license")

        return project.task(taskName("generate", variant, "ticket")) {
            ext.destinationDir = variantGenDir(variant, "license")
            inputs.property("app", variant.applicationId)
            inputs.property("key", key)
            outputs.dir(destinationDir)
            doLast {
                project.delete(destinationDir)
                project.mkdir(destinationDir)
                def ticket = "";  // See note in AndroidPlatform
                if (key != null) {
                    final def TIMEOUT = 10000
                    def url = ("https://chaquo.com/license/get_ticket" +
                               "?app=$variant.applicationId&key=$key")
                    def connection = (HttpURLConnection) new URL(url).openConnection()
                    connection.setConnectTimeout(TIMEOUT)
                    connection.setReadTimeout(TIMEOUT)
                    def code = connection.getResponseCode()
                    if (code == connection.HTTP_OK) {
                        ticket = connection.getInputStream().getText();
                    } else {
                        throw new GradleException(connection.getErrorStream().getText())
                    }
                }
                project.file("$destinationDir/$Common.ASSET_TICKET").write(ticket);
            }
        }
    }

    void createAssetsTasks(variant, python, Task reqsTask, Task mergeSrcTask, Task ticketTask) {
        def assetBaseDir = variantGenDir(variant, "assets")
        def assetDir = new File(assetBaseDir, Common.ASSET_DIR)
        def stdlibConfig = getConfig(variant, "targetStdlib")
        def abiConfig = getConfig(variant, "targetAbis")
        def genTask = project.task(taskName("generate", variant, "assets")) {
            inputs.files(reqsTask, mergeSrcTask, ticketTask)
            inputs.files(stdlibConfig, abiConfig)
            outputs.dir(assetBaseDir)
            doLast {
                project.delete(assetBaseDir)
                project.mkdir(assetDir)

                def excludes = "**/*.pyc **/*.pyo"
                project.ant.zip(basedir: mergeSrcTask.destinationDir, excludes: excludes,
                                destfile: "$assetDir/$Common.ASSET_APP", whenempty: "create")
                project.ant.zip(basedir: reqsTask.destinationDir, excludes: excludes,
                                destfile: "$assetDir/$Common.ASSET_REQUIREMENTS", whenempty: "create")

                def artifacts = abiConfig.resolvedConfiguration.resolvedArtifacts
                for (art in artifacts) {    // Stdlib native modules
                    project.copy {
                        from project.zipTree(art.file)
                        include "lib-dynload/**"
                        into assetDir
                    }
                }
                project.copy {              // Stdlib Python modules
                    from stdlibConfig
                    into assetDir
                    rename { Common.ASSET_STDLIB }
                }

                extractResource("runtime/$Common.ASSET_CHAQUOPY", assetDir)
                for (abi in getAbis(variant)) {
                    def resDir = "runtime/lib-dynload/${pyVersionShort(python.version)}/$abi/java"
                    def outDir = "$assetDir/lib-dynload/$abi/java"
                    extractResource("$resDir/chaquopy.so", outDir)

                    // extend_path is called in runtime/src/main/python/java/__init__.py
                    new File("$outDir/__init__.py").text = ""
                }

                project.copy {
                    from ticketTask.destinationDir
                    into assetDir
                }

                def buildJson = new JSONObject()
                buildJson.put("version", python.version)
                buildJson.put("assets", hashAssets(assetDir))
                project.file("$assetDir/$Common.ASSET_BUILD_JSON").text = buildJson.toString(4)
            }
        }
        extendMergeTask(variant.getMergeAssets(), genTask)
    }

    JSONObject hashAssets(File assetDir) {
        def assetsJson = new JSONObject()
        def digest = MessageDigest.getInstance("SHA-1")
        hashAssets(assetsJson, digest, assetDir, "")
        return assetsJson
    }

    void hashAssets(JSONObject assetsJson, MessageDigest digest, File dir, String prefix) {
        for (file in dir.listFiles()) {
            def path = prefix + file.name
            if (file.isDirectory()) {
                hashAssets(assetsJson, digest, file, path + "/")
            } else {
                digest.reset()
                assetsJson.put(path, digest.digest(file.bytes).encodeHex())
            }
        }
    }

    void createJniLibsTasks(variant, PythonExtension python) {
        def libsDir = variantGenDir(variant, "jniLibs")
        def abiConfig = getConfig(variant, "targetAbis")
        def genTask = project.task(taskName("generate", variant, "jniLibs")) {
            inputs.files(abiConfig)
            outputs.dir(libsDir)
            doLast {
                project.delete(libsDir)
                def artifacts = abiConfig.resolvedConfiguration.resolvedArtifacts
                for (art in artifacts) {
                    // Copy jniLibs/<arch>/ in the ZIP to jniLibs/<variant>/<arch>/ in the build
                    // directory. (https://discuss.gradle.org/t/copyspec-support-for-moving-files-directories/7412/1)
                    project.copy {
                        from project.zipTree(art.file)
                        include "jniLibs/**"
                        into libsDir
                        eachFile { FileCopyDetails fcd ->
                            fcd.relativePath = new RelativePath
                                    (!fcd.file.isDirectory(),
                                     fcd.relativePath.segments[1..-1] as String[])
                        }
                        includeEmptyDirs = false
                    }
                }

                for (abi in getAbis(variant)) {
                    def resDir = "runtime/jniLibs/${pyVersionShort(python.version)}/$abi"
                    extractResource("$resDir/libchaquopy_java.so", "$libsDir/$abi")
                }
            }
        }
        extendMergeTask(project.tasks.getByName("merge${variant.name.capitalize()}JniLibFolders"),
                        genTask)
    }

    void extendMergeTask(Task mergeTask, Task genTask) {
        mergeTask.dependsOn(genTask)
        mergeTask.inputs.files(genTask.outputs)
        mergeTask.doLast {
            project.copy {
                from genTask.outputs
                into mergeTask.outputDir
            }
        }
    }

    File variantGenDir(variant, String type) {
        return new File(genDir, "$type/$variant.dirName")
    }

    String configName(variant, String type) {
        return "$NAME${variant.name.capitalize()}${type.capitalize()}"
    }

    String taskName(String verb, variant, String object) {
        return "$verb${variant.name.capitalize()}${NAME.capitalize()}${object.capitalize()}"
    }

    void extractResource(String name, targetDir) {
        project.mkdir(targetDir)
        def outFile = new File(targetDir, new File(name).name)
        def tmpFile = new File("${outFile.path}.tmp")
        InputStream is = getClass().getResourceAsStream(name)
        if (is == null) {
            throw new IOException("getResourceAsString failed for '$name'")
        }
        Files.copy(is, tmpFile.toPath(), REPLACE_EXISTING)
        project.delete(outFile)
        if (! tmpFile.renameTo(outFile)) {
            throw new IOException("Failed to create '$outFile'")
        }
    }
}


class PythonExtension extends BaseExtension {
    String version
    String buildPython = "python"
    List<String> staticProxy = new ArrayList<>();
    PipExtension pip = new PipExtension()

    void staticProxy(String... args) {
        staticProxy.addAll(Arrays.asList(args))
    }

    void pip(Closure closure) {
        closure.delegate = pip
        closure()
    }

    void mergeFrom(PythonExtension overlay) {
        version = chooseNotNull(overlay.version, version)
        buildPython = chooseNotNull(overlay.buildPython, buildPython)
        staticProxy.addAll(overlay.staticProxy)
        pip.mergeFrom(overlay.pip)
    }

    // Removed in 0.6.0
    void pipInstall(String... args) {
        throw new GradleException("'pipInstall' has been removed: use 'pip { install ... }' " +
                                  "or 'pip { options ... }' instead")
    }
}


class PipExtension extends BaseExtension {
    List<String> reqs = new ArrayList<>();
    List<String> reqFiles = new ArrayList<>();
    List<String> options = new ArrayList<>();

    void install(String... args) {
        if (args.length == 1) {
            reqs.add(args[0])
        } else if (args.length == 2  &&  args[0].equals("-r")) {
            reqFiles.add(args[1])
        } else {
            throw new GradleException("Invalid python.pip.install format: '" + args.join(" ") + "'")
        }
    }

    void options (String... args) {
        options.addAll(Arrays.asList(args))
    }

    void mergeFrom(PipExtension overlay) {
        reqs.addAll(overlay.reqs)
        reqFiles.addAll(overlay.reqFiles)
        options.addAll(overlay.options)
    }
}


class BaseExtension implements Serializable {
    static <T> T chooseNotNull(T overlay, T base) {
        return overlay != null ? overlay : base
    }

    // Using custom classes as task input properties doesn't work in Gradle 2.14.1 / Android
    // Studio 2.2 (https://github.com/gradle/gradle/issues/784), so we use a String as the input
    // property instead. We don't use a byte[] because this version of Gradle apparently compares
    // all properties using equals(), which only checks array identity, not content.
    //
    // This approach also avoids the need for equals and hashCode methods
    // (https://github.com/gradle/gradle/pull/962).
    String serialize() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        ObjectOutputStream oos = new ObjectOutputStream(baos)
        oos.writeObject(this)
        oos.close()
        return escape(baos.toByteArray())
    }

    static String escape(byte[] data) {
        StringBuilder cbuf = new StringBuilder();
        for (byte b : data) {
            if (b >= 0x20 && b <= 0x7e) {
                cbuf.append((char) b);
            } else {
                cbuf.append(String.format("\\x%02x", b & 0xFF));
            }
        }
        return cbuf.toString();
    }
}
