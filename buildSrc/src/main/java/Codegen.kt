import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PrintStream
import java.security.SecureRandom
import java.util.*
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.asKotlinRandom

// Set non-zero value here to fix the random seed for reproducible builds
const val RAND_SEED = 0
private lateinit var RANDOM: Random
private val kRANDOM get() = RANDOM.asKotlinRandom()

private val c1 = mutableListOf<String>()
private val c2 = mutableListOf<String>()
private val c3 = mutableListOf<String>()

fun initRandom(dict: File) {
    RANDOM = if (RAND_SEED != 0) Random(RAND_SEED.toLong()) else SecureRandom()
    c1.clear()
    c2.clear()
    c3.clear()
    for (a in chain('a'..'z', 'A'..'Z')) {
        if (a != 'a' && a != 'A') {
            c1.add("$a")
        }
        for (b in chain('a'..'z', 'A'..'Z', '0'..'9')) {
            c2.add("$a$b")
            for (c in chain('a'..'z', 'A'..'Z', '0'..'9')) {
                c3.add("$a$b$c")
            }
        }
    }
    c1.shuffle(RANDOM)
    c2.shuffle(RANDOM)
    c3.shuffle(RANDOM)
    PrintStream(dict).use {
        for (c in chain(c1, c2, c3)) {
            it.println(c)
        }
    }
}

private fun <T> chain(vararg iters: Iterable<T>) = sequence {
    iters.forEach { it.forEach { v -> yield(v) } }
}

private fun PrintStream.byteField(name: String, bytes: ByteArray) {
    println("public static byte[] $name() {")
    print("byte[] buf = {")
    print(bytes.joinToString(",") { it.toString() })
    println("};")
    println("return buf;")
    println("}")
}

fun genKeyData(keysDir: File, outSrc: File) {
    outSrc.parentFile.mkdirs()
    PrintStream(outSrc).use {
        it.println("package com.topjohnwu.magisk.signing;")
        it.println("public final class KeyData {")

        it.byteField("testCert", File(keysDir, "testkey.x509.pem").readBytes())
        it.byteField("testKey", File(keysDir, "testkey.pk8").readBytes())
        it.byteField("verityCert", File(keysDir, "verity.x509.pem").readBytes())
        it.byteField("verityKey", File(keysDir, "verity.pk8").readBytes())

        it.println("}")
    }
}

fun genStubManifest(srcDir: File, outDir: File): String {
    class Component(
        val real: String,
        val stub: String,
        val xml: String,
        val genClass: Boolean = false
    )

    outDir.deleteRecursively()

    val mainPkgDir = File(outDir, "com/topjohnwu/magisk")
    mainPkgDir.mkdirs()

    fun String.ind(level: Int) = replaceIndentByMargin("    ".repeat(level))

    val cmpList = mutableListOf<Component>()

    cmpList.add(Component(
        "androidx.core.app.CoreComponentFactory",
        "DelegateComponentFactory",
        "",
        true
    ))

    cmpList.add(Component(
        "com.topjohnwu.magisk.core.App",
        "DelegateApplication",
        "",
        true
    ))

    cmpList.add(Component(
        "PHOENIX",
        "PhoenixActivity",
        """
        |<activity
        |    android:name="%s"
        |    android:process=":%s"
        |    android:exported="false" />""".ind(2),
        true
    ))

    cmpList.add(Component(
        "com.topjohnwu.magisk.core.Provider",
        "dummy.DummyProvider",
        """
        |<provider
        |    android:name="%s"
        |    android:authorities="${'$'}{applicationId}.provider"
        |    android:directBootAware="true"
        |    android:exported="false"
        |    android:grantUriPermissions="true" />""".ind(2)
    ))

    cmpList.add(Component(
        "com.topjohnwu.magisk.core.Receiver",
        "dummy.DummyReceiver",
        """
        |<receiver
        |    android:name="%s"
        |    android:directBootAware="true"
        |    android:exported="false">
        |    <intent-filter>
        |        <action android:name="android.intent.action.LOCALE_CHANGED" />
        |        <action android:name="android.intent.action.UID_REMOVED" />
        |    </intent-filter>
        |    <intent-filter>
        |        <action android:name="android.intent.action.PACKAGE_REPLACED" />
        |        <action android:name="android.intent.action.PACKAGE_FULLY_REMOVED" />
        |
        |        <data android:scheme="package" />
        |    </intent-filter>
        |</receiver>""".ind(2)
    ))

    cmpList.add(Component(
        "com.topjohnwu.magisk.ui.MainActivity",
        "DownloadActivity",
        """
        |<activity
        |    android:name="%s"
        |    android:exported="true">
        |    <intent-filter>
        |        <action android:name="android.intent.action.MAIN" />
        |        <category android:name="android.intent.category.LAUNCHER" />
        |    </intent-filter>
        |</activity>""".ind(2)
    ))

    cmpList.add(Component(
        "com.topjohnwu.magisk.ui.surequest.SuRequestActivity",
        "",
        """
        |<activity
        |    android:name="%s"
        |    android:directBootAware="true"
        |    android:excludeFromRecents="true"
        |    android:exported="false"
        |    android:taskAffinity=""
        |    tools:ignore="AppLinkUrlError">
        |    <intent-filter>
        |        <action android:name="android.intent.action.VIEW"/>
        |        <category android:name="android.intent.category.DEFAULT"/>
        |    </intent-filter>
        |</activity>""".ind(2)
    ))

    cmpList.add(Component(
        "com.topjohnwu.magisk.core.download.DownloadService",
        "",
        """
        |<service
        |    android:name="%s"
        |    android:exported="false" />""".trimIndent().ind(2)
    ))

    cmpList.add(Component(
        "com.topjohnwu.magisk.core.JobService",
        "",
        """
        |<service
        |    android:name="%s"
        |    android:exported="false"
        |    android:permission="android.permission.BIND_JOB_SERVICE" />""".ind(2)
    ))

    val names = mutableListOf<String>()
    names.addAll(c1)
    names.addAll(c2.subList(0, 10))
    names.addAll(c3.subList(0, 10))
    names.shuffle(RANDOM)

    val pkgNames = names.subList(0, 50)
        // Distinct by lower case to support case insensitive file systems
        .distinctBy { it.toLowerCase(Locale.ROOT) }
        // Old Android does not support capitalized package names
        // Check Android 7.0.0 PackageParser#buildClassName
        .map { it.decapitalize(Locale.ROOT) }

    var idx = 0
    fun isJavaKeyword(name: String) = when (name) {
        "do", "if", "for", "int", "new", "try" -> true
        else -> false
    }

    fun genCmpName() : String {
        var pkgName : String
        do {
            pkgName = pkgNames[idx++]
        } while (isJavaKeyword(pkgName))

        var clzName : String
        do {
            clzName = names.random(kRANDOM)
        } while (isJavaKeyword(clzName))
        return "${pkgName}.${clzName}"
    }

    fun genClass(clzName: String, type: String) {
        val (pkg, name) = clzName.split('.')
        val pkgDir = File(outDir, pkg)
        pkgDir.mkdir()
        PrintStream(File(pkgDir, "$name.java")).use {
            it.println("package $pkg;")
            it.println("public class $name extends com.topjohnwu.magisk.$type {}")
        }
    }

    val cmps = mutableListOf<String>()
    val usedNames = mutableListOf<String>()
    val maps = StringBuilder()

    for (gen in cmpList) {
        val name = genCmpName()
        usedNames.add(name)
        maps.append("|map.put(\"$name\", \"${gen.real}\");".ind(2))
        maps.append('\n')
        if (gen.stub.isNotEmpty()) {
            if (gen.stub != "DelegateComponentFactory") {
                maps.append("|internalMap.put(\"$name\", com.topjohnwu.magisk.${gen.stub}.class);".ind(2))
                maps.append('\n')
            }
            if (gen.genClass) {
                genClass(name, gen.stub)
            }
        }
        if (gen.xml.isNotEmpty()) {
            cmps.add(gen.xml.format(name, names.random(kRANDOM)))
        }
    }

    // Shuffle the order of the components
    cmps.shuffle(RANDOM)
    val xml = File(srcDir, "AndroidManifest.xml").readText()
    val genXml = xml.format(usedNames[0], usedNames[1], cmps.joinToString("\n\n"))

    // Write mapping information to code
    val mapping = File(srcDir, "Mapping.java").readText().format(maps)
    PrintStream(File(mainPkgDir, "Mapping.java")).use {
        it.print(mapping)
    }

    return genXml
}

fun genEncryptedResources(res: InputStream, outDir: File) {
    val mainPkgDir = File(outDir, "com/topjohnwu/magisk")

    // Generate iv and key
    val iv = ByteArray(16)
    val key = ByteArray(32)
    RANDOM.nextBytes(iv)
    RANDOM.nextBytes(key)

    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    val bos = ByteArrayOutputStream()

    res.use {
        CipherOutputStream(bos, cipher).use { os ->
            it.transferTo(os)
        }
    }

    PrintStream(File(mainPkgDir, "Bytes.java")).use {
        it.println("package com.topjohnwu.magisk;")
        it.println("public final class Bytes {")

        it.byteField("key", key)
        it.byteField("iv", iv)
        it.byteField("res", bos.toByteArray())

        it.println("}")
    }
}
