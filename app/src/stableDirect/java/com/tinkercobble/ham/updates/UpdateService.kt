package com.tinkercobble.ham.updates

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.tinkercobble.ham.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Direct Stable updater. No APK is published until the separate release gates pass. */
object UpdateService {
    private const val PREFS = "ham_direct_updates"
    private const val LAST_CHECK = "last_check_ms"
    private const val LATER_VERSION = "later_version_code"
    private const val MAX_MANIFEST = 16_384
    private const val MAX_APK = 100L * 1024L * 1024L
    private const val APK_NAME = "ham-update.apk"
    private const val APK_MIME = "application/vnd.android.package-archive"

    fun shouldAutoCheck(context: Context): Boolean {
        if (DirectUpdatePolicy.route(false, installer(context)) != UpdateRoute.DIRECT) return false
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LAST_CHECK, 0)
        val now = System.currentTimeMillis()
        return DirectUpdatePolicy.checkDue(now, last)
    }

    suspend fun check(context: Context, manual: Boolean): UpdateCheckResult = withContext(Dispatchers.IO) {
        when (DirectUpdatePolicy.route(false, installer(context))) {
            UpdateRoute.GOOGLE_PLAY -> return@withContext UpdateCheckResult("Check Google Play for HAM updates.")
            UpdateRoute.F_DROID -> return@withContext UpdateCheckResult("Check F-Droid for HAM updates.")
            else -> Unit
        }
        if (!manual && !shouldAutoCheck(context)) return@withContext UpdateCheckResult("")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(LAST_CHECK, System.currentTimeMillis()).apply()
        try {
            val connection = open(DirectUpdatePolicy.FEED_URL, 8_000)
            try {
                when (connection.responseCode) {
                    404 -> UpdateCheckResult(if (manual) "Public Stable updates are not available yet." else "")
                    200 -> {
                        val bytes = ByteArrayOutputStream()
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(4096)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(bytes.size() + count <= MAX_MANIFEST) { "Update information is too large." }
                                bytes.write(buffer, 0, count)
                            }
                        }
                        val json = JSONObject(bytes.toString(Charsets.UTF_8.name()))
                        val update = AvailableUpdate(
                            json.getInt("versionCode"), json.getString("versionName"),
                            json.getString("apkUrl"), json.getString("sha256"),
                        )
                        require(json.getString("packageName") == context.packageName &&
                            json.getString("channel") == "stable" && update.versionCode > 0 &&
                            update.versionName.isNotBlank() &&
                            DirectUpdatePolicy.validApkUrl(update.apkUrl) &&
                            DirectUpdatePolicy.validSha256(update.sha256)) { "Invalid update information." }
                        if (update.versionCode <= BuildConfig.VERSION_CODE) UpdateCheckResult("HAM is up to date.")
                        else {
                            val later = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(LATER_VERSION, 0)
                            if (!DirectUpdatePolicy.shouldOffer(update.versionCode, BuildConfig.VERSION_CODE, later, manual)) UpdateCheckResult("")
                            else UpdateCheckResult("HAM ${update.versionName} is available.", update)
                        }
                    }
                    else -> throw IllegalStateException("Update check returned ${connection.responseCode}.")
                }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            UpdateCheckResult(if (manual) "Could not check updates. Try again." else "")
        }
    }

    fun later(context: Context, update: AvailableUpdate) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(LATER_VERSION, update.versionCode).apply()
    }

    suspend fun downloadAndVerify(context: Context, update: AvailableUpdate, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            require(DirectUpdatePolicy.validApkUrl(update.apkUrl) && DirectUpdatePolicy.validSha256(update.sha256)) {
                "The update address or checksum is invalid."
            }
            require(update.versionCode > BuildConfig.VERSION_CODE) { "This update is not newer than HAM." }
            val folder = File(context.cacheDir, "updates")
            require(folder.isDirectory || folder.mkdirs()) { "Could not prepare the update folder." }
            val temporary = File(folder, "$APK_NAME.part")
            val target = File(folder, APK_NAME)
            temporary.delete()
            target.delete()
            try {
                val connection = open(update.apkUrl, 30_000)
                try {
                    require(connection.responseCode == 200) { "Could not download the update (${connection.responseCode})." }
                    val expected = connection.contentLengthLong
                    require(expected <= MAX_APK) { "The update file is too large." }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    var lastProgress = -1
                    connection.inputStream.use { input ->
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(65_536)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_APK) { "The update file is too large." }
                                output.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                                if (expected > 0) {
                                    val progress = (total * 100 / expected).toInt().coerceIn(0, 100)
                                    if (progress != lastProgress) {
                                        lastProgress = progress
                                        withContext(Dispatchers.Main) { onProgress(progress) }
                                    }
                                }
                            }
                        }
                    }
                    require(total >= 100_000 && (expected < 0 || total == expected)) { "The update download was incomplete." }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    require(actual.equals(update.sha256, ignoreCase = true)) { "The update checksum did not match." }
                } finally {
                    connection.disconnect()
                }
                verifyPackage(context, temporary, update)
                require(temporary.renameTo(target)) { "Could not save the verified update." }
                withContext(Dispatchers.Main) { onProgress(100) }
                target
            } catch (error: Exception) {
                temporary.delete()
                target.delete()
                throw error
            }
        }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"))

    fun installerIntent(context: Context, file: File): Intent {
        val folder = File(context.cacheDir, "updates").canonicalFile
        require(file.canonicalFile.parentFile == folder && file.name == APK_NAME && file.isFile) {
            "The verified update file is missing."
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, APK_MIME)
            clipData = ClipData.newRawUri("HAM update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun open(url: String, readTimeout: Int): HttpsURLConnection =
        (URL(url).openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 8_000
            this.readTimeout = readTimeout
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Accept", if (url.endsWith(".json")) "application/json" else APK_MIME)
        }

    @Suppress("DEPRECATION")
    private fun installer(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) context.packageManager
            .getInstallSourceInfo(context.packageName).installingPackageName
        else context.packageManager.getInstallerPackageName(context.packageName)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun verifyPackage(context: Context, file: File, update: AvailableUpdate) {
        val manager = context.packageManager
        // Some Android 9 package parsers omit archive SigningInfo even for a valid APK.
        val flags = if (Build.VERSION.SDK_INT >= 28)
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        else PackageManager.GET_SIGNATURES
        val candidate = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IllegalStateException("The downloaded file is not a valid Android app.")
        val installed = manager.getPackageInfo(context.packageName, flags)
        val code = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        require(candidate.packageName == context.packageName && candidate.versionName == update.versionName &&
            code == update.versionCode.toLong() && code > installedCode) {
            "The downloaded app identity or version does not match the update."
        }
        val oldSigner = signer(installed)
        val newSigner = signer(candidate)
        require(oldSigner != null && newSigner != null && MessageDigest.isEqual(oldSigner, newSigner)) {
            "The update is not signed by HAM's installed signing identity."
        }
    }

    @Suppress("DEPRECATION")
    private fun signer(info: PackageInfo): ByteArray? =
        (if (Build.VERSION.SDK_INT >= 28)
            info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() } ?: info.signatures
        else info.signatures)
            ?.singleOrNull()?.toByteArray()
}
