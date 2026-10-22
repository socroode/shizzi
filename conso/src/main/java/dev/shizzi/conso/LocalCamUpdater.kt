package dev.shizzi.conso

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Network
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit, signed, router-local APK update; never downloads from the Internet.
 * The router's pinned TLS certificate protects both manifest and APK transfer.
 * Android always retains the final install confirmation.
 */
internal object LocalCamUpdater {
    private val busy=AtomicBoolean(false)
    private const val MAX_APK_SIZE=128L*1024*1024
    fun checkAndOffer(
        activity:Activity,
        role:String,
        pin:String,
        network:Network,
        adminToken:String="",
        report:(String)->Unit
    ) {
        if(!busy.compareAndSet(false,true)) return
        try {
            val c=RouterTls.connection("https://192.0.2.1/api/v1/cam/updates",pin,network)
            val manifest=try {
                c.connectTimeout=5000
                c.readTimeout=5000
                if(adminToken.isNotEmpty())c.setRequestProperty("X-Shizzi-Admin-Token",adminToken)
                if(c.responseCode!=200)error("Manifest : HTTP "+c.responseCode)
                JSONObject(c.inputStream.bufferedReader().use{it.readText()})
            }finally{c.disconnect()}
            check(manifest.optBoolean("ok")){"Manifest incomplet"}
            val entry=manifest.getJSONObject(role)
            check(entry.optBoolean("available")){"APK non disponible sur le routeur"}
            val code=entry.getLong("versionCode")
            val version=entry.getString("version")
            val expectedSize=entry.getLong("sizeBytes")
            val sha=entry.getString("sha256").lowercase()
            check(expectedSize in 1024L..MAX_APK_SIZE && sha.matches(Regex("[0-9a-f]{64}"))){
                "Taille / SHA-256 non conforme"
            }
            val current=activity.packageManager.getPackageInfo(activity.packageName,0).longVersionCode
            if(code<=current){
                report("Application à jour (version $version)")
                return
            }
            activity.runOnUiThread {
                if(activity.isFinishing||activity.isDestroyed)return@runOnUiThread
                AlertDialog.Builder(activity)
                    .setTitle("Mise à jour locale disponible")
                    .setMessage("Version $version disponible sur ShizziCam Hotspot. " +
                        "Téléchargement par le Wi-Fi local sans voucher, puis confirmation Android.")
                    .setNegativeButton("Plus tard",null)
                    .setPositiveButton("Télécharger") { _,_ ->
                        Thread {
                            try { downloadAndInstall(activity,role,version,code,expectedSize,sha,pin,network,report) }
                            catch(e:Exception){report("Mise à jour refusée : "+(e.message ?: "erreur"))}
                        }.start()
                    }.show()
            }
        } catch(e:Exception) {
            report("Vérification locale : "+(e.message ?: "indisponible"))
        } finally {busy.set(false)}
    }

    private fun downloadAndInstall(
        activity:Activity,role:String,version:String,code:Long,size:Long,sha:String,
        pin:String,network:Network,report:(String)->Unit
    ) {
        val name=if(role=="client")"shizzi-plus.apk" else "shizzicam-admin.apk"
        check(role=="client" || role=="viewer"){"Type d'application invalide"}
        val c=RouterTls.connection("https://192.0.2.1/$name?sha256=$sha",pin,network)
        val directory=File(activity.cacheDir,"shizzicam-updates")
        check(directory.isDirectory||directory.mkdirs()){"Répertoire des mises à jour inaccessible"}
        val target=File(directory,if(role=="client")"Shizzi-PlusCam.apk" else "ShizziCam-Admin.apk")
        try {
            c.connectTimeout=5000
            c.readTimeout=90000
            c.instanceFollowRedirects=false
            check(c.responseCode==200){"Téléchargement HTTP "+c.responseCode}
            check(c.contentLengthLong==size){"Taille annoncée incorrecte"}
            check(c.getHeaderField("X-Shizzi-SHA256")?.equals(sha,true)==true){"SHA annoncée incorrecte"}
            check(c.contentType?.startsWith("application/vnd.android.package-archive")==true){
                "Le routeur n'a pas renvoyé une APK"
            }
            val md=MessageDigest.getInstance("SHA-256")
            var received=0L
            c.inputStream.use{input->
                FileOutputStream(target).use{output->
                    val buf=ByteArray(32*1024)
                    while(true){
                        val count=input.read(buf)
                        if(count<0)break
                        received+=count
                        check(received<=size){"APK trop volumineuse"}
                        md.update(buf,0,count)
                        output.write(buf,0,count)
                    }
                    output.fd.sync()
                }
            }
            check(received==size){"APK incomplète : $received / $size octets"}
            val digest=md.digest().joinToString(""){"%02x".format(it)}
            check(digest==sha){"APK altérée (SHA-256)"}
            val flags=PackageManager.GET_SIGNING_CERTIFICATES
            val current=activity.packageManager.getPackageInfo(activity.packageName,flags)
            val archive=activity.packageManager.getPackageArchiveInfo(target.absolutePath,flags)
                ?:error("APK Android non reconnue")
            check(archive.packageName==activity.packageName){"Application incorrecte"}
            check(archive.longVersionCode==code){"Version APK différente du manifeste"}
            val sourceSigner=current.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                ?:error("Signature installée introuvable")
            val targetSigner=archive.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
                ?:error("Signature APK introuvable")
            check(sourceSigner.contentEquals(targetSigner)){"Signature Android différente"}
            report("APK $version vérifiée ; ouverture de l'installateur Android")
            activity.runOnUiThread{
                if(activity.isFinishing||activity.isDestroyed)return@runOnUiThread
                try{
                    val uri=FileProvider.getUriForFile(activity,
                        activity.packageName+".updates",target)
                    val intent=Intent(Intent.ACTION_VIEW).apply{
                        setDataAndType(uri,"application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(intent)
                }catch(e:Exception){report("Installateur Android : "+(e.message ?: "indisponible"))}
            }
        }finally{
            c.disconnect()
            // Invalid or cancelled transfers must not leave a falsely installable APK.
            if(target.exists()){
                // The verified file is kept for Android's consent screen only.
                if(target.length()!=size)target.delete()
            }
        }
    }
}
