package com.scanbook.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { ScanBookScreen(this) }
            }
        }
    }
}

private val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(300, TimeUnit.SECONDS)
    .writeTimeout(300, TimeUnit.SECONDS)
    .build()

private const val DOCX_MIME =
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

private fun mimeOf(name: String) = if (name.endsWith(".docx")) DOCX_MIME else "application/pdf"

/** Limpia la URL: quita espacios y barra final, y agrega http:// si falta. */
private fun norm(base: String): String {
    val t = base.trim().trimEnd('/')
    return if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
}

/** Prueba /health y devuelve un mensaje claro con la causa si falla. */
private suspend fun ping(base: String): String = withContext(Dispatchers.IO) {
    val url = norm(base) + "/health"
    try {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) "Servidor OK ($url)" else "El servidor respondió HTTP ${r.code} en $url"
        }
    } catch (e: Exception) {
        "No conecta a $url\n${e.javaClass.simpleName}: ${e.message}"
    }
}

/** Sube las páginas, espera el procesado y guarda los 3 archivos en Descargas/ScanBook. */
private suspend fun process(
    ctx: Context,
    base: String,
    pages: List<Uri>,
    onMsg: (String) -> Unit,
): List<Pair<String, Uri>> = withContext(Dispatchers.IO) {
    val b = norm(base)

    fun call(req: Request): String = http.newCall(req).execute().use { r ->
        val body = r.body?.string().orEmpty()
        if (!r.isSuccessful) error("HTTP ${r.code}: $body")
        body
    }

    onMsg("Conectando…")
    val id = JSONObject(
        call(Request.Builder().url("$b/jobs").post(FormBody.Builder().add("lang", "spa+eng").build()).build())
    ).getString("id")

    pages.forEachIndexed { i, uri ->
        onMsg("Subiendo ${i + 1}/${pages.size}")
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("index", i.toString())
            .addFormDataPart("file", "p$i.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
            .build()
        call(Request.Builder().url("$b/jobs/$id/pages").post(body).build())
    }

    call(Request.Builder().url("$b/jobs/$id/start").post(ByteArray(0).toRequestBody(null)).build())

    while (true) {
        delay(2000)
        val j = JSONObject(call(Request.Builder().url("$b/jobs/$id").build()))
        when (j.getString("status")) {
            "done" -> break
            "error" -> error(j.getString("error"))
            else -> onMsg("Procesando ${j.getInt("done")}/${j.getInt("pages")}")
        }
    }

    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
    val out = mutableListOf<Pair<String, Uri>>()
    listOf("pdf_a" to "libro_fiel.pdf", "pdf_b" to "libro_reconstruido.pdf", "docx" to "libro.docx")
        .forEach { (kind, name) ->
            onMsg("Descargando $name")
            http.newCall(Request.Builder().url("$b/jobs/$id/file/$kind").build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code} al descargar $name")
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "${stamp}_$name")
                    put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/ScanBook")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                ctx.contentResolver.openOutputStream(uri)!!.use { o -> r.body!!.byteStream().copyTo(o) }
                out.add(name to uri)
            }
        }
    out
}

private fun openFile(ctx: Context, name: String, uri: Uri) {
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeOf(name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No hay app para abrir $name. Está en Descargas/ScanBook.", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun ScanBookScreen(activity: ComponentActivity) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("sb", Context.MODE_PRIVATE) }
    var server by remember { mutableStateOf(prefs.getString("server", "http://192.168.1.10:8000")!!) }
    val pages = remember { mutableStateListOf<Uri>() }
    var busy by remember { mutableStateOf(false) }
    var useServer by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<String, Uri>>>(emptyList()) }
    val scope = rememberCoroutineScope()

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            GmsDocumentScanningResult.fromActivityResultIntent(r.data)?.pages?.forEach { pages.add(it.imageUri) }
        }
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        pages.addAll(uris)
    }

    fun startScan() {
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(100)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(activity)
            .addOnSuccessListener { scanLauncher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { msg = "Escáner: ${it.message}" }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ScanBook", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = useServer, onCheckedChange = { useServer = it }, enabled = !busy)
            Text("  " + if (useServer) "Servidor (mejor calidad)" else "En el teléfono (sin internet)")
        }
        OutlinedTextField(
            value = server,
            onValueChange = { server = it; prefs.edit().putString("server", it).apply() },
            label = { Text("URL del servidor") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                msg = "Probando conexión…"
                scope.launch { msg = ping(server) }
            },
            enabled = !busy,
        ) { Text("Probar conexión") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { startScan() }, enabled = !busy) { Text("Escanear") }
            OutlinedButton(
                onClick = { pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy,
            ) { Text("Galería") }
            OutlinedButton(onClick = { pages.clear() }, enabled = !busy && pages.isNotEmpty()) { Text("Vaciar") }
        }
        Button(
            onClick = {
                busy = true
                results = emptyList()
                scope.launch {
                    try {
                        results = if (useServer) process(ctx, server, pages.toList()) { msg = it }
                                  else processLocal(ctx, pages.toList()) { msg = it }
                        msg = "Listo. Guardado en Descargas/ScanBook"
                    } catch (e: Exception) {
                        msg = "Error: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && pages.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Generar PDF y Word (${pages.size} págs)") }

        if (msg.isNotEmpty()) Text(msg)
        if (results.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                results.forEach { (name, uri) ->
                    OutlinedButton(onClick = { openFile(ctx, name, uri) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)) {
                        Text(
                            when {
                                name.endsWith(".docx") -> "Word"
                                name.contains("fiel") -> "PDF fiel"
                                else -> "PDF reconstruido"
                            }
                        )
                    }
                }
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(pages) { i, uri ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp))
                    Text("  Pág ${i + 1}", Modifier.weight(1f))
                    TextButton(onClick = { if (i > 0) Collections.swap(pages, i, i - 1) }, enabled = !busy) { Text("↑") }
                    TextButton(onClick = { if (i < pages.lastIndex) Collections.swap(pages, i, i + 1) }, enabled = !busy) { Text("↓") }
                    TextButton(onClick = { pages.removeAt(i) }, enabled = !busy) { Text("✕") }
                }
            }
        }
        Text(
            "Usa solo libros propios, de dominio público o con permiso del autor.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
