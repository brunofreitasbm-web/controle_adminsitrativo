package br.app.assinador

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.app.assinador.cert.CertInfo
import br.app.assinador.cert.CertificateStore
import br.app.assinador.cert.WrongPasswordException
import br.app.assinador.sign.BatchSigner
import br.app.assinador.sign.FileResult
import br.app.assinador.sign.StampSpec
import br.app.assinador.verify.SigResult
import br.app.assinador.verify.SignatureVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class Preview(val bitmap: Bitmap, val widthPt: Float, val heightPt: Float)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)

    var cert by mutableStateOf<CertInfo?>(null); private set
    var files by mutableStateOf<List<Uri>>(emptyList()); private set
    var names by mutableStateOf<List<String>>(emptyList()); private set
    var preview by mutableStateOf<Preview?>(null); private set
    var stampX by mutableStateOf(prefs.x); private set
    var stampY by mutableStateOf(prefs.y); private set
    var lastPage by mutableStateOf(prefs.lastPage); private set
    var visible by mutableStateOf(prefs.visible); private set
    var busy by mutableStateOf(false); private set
    var progress by mutableStateOf(0 to 0); private set
    var results by mutableStateOf<List<FileResult>?>(null); private set
    var error by mutableStateOf<String?>(null)
    var verification by mutableStateOf<List<SigResult>?>(null)

    init { refreshCert() }

    fun refreshCert() { cert = runCatching { CertificateStore.info() }.getOrNull() }

    fun importCertificate(uri: Uri, password: CharArray) = viewModelScope.launch {
        busy = true
        try {
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)!!.use { CertificateStore.import(it, password) }
            }
            refreshCert()
        } catch (e: WrongPasswordException) {
            error = "Senha incorreta."
        } catch (e: Exception) {
            error = "Não foi possível importar: ${e.message}"
        } finally { busy = false }
    }

    fun removeCertificate() { CertificateStore.remove(); refreshCert() }

    fun pickFiles(uris: List<Uri>) {
        results = null
        files = uris
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            names = withContext(Dispatchers.IO) { uris.map { BatchSigner.displayName(ctx, it) } }
            loadPreview()
        }
    }

    fun reset() { files = emptyList(); names = emptyList(); preview = null; results = null }

    fun moveStamp(x: Float, y: Float) { stampX = x; stampY = y }
    fun commitStamp() { prefs.x = stampX; prefs.y = stampY }
    fun chooseLastPage(v: Boolean) { lastPage = v; prefs.lastPage = v; viewModelScope.launch { loadPreview() } }
    fun chooseVisible(v: Boolean) { visible = v; prefs.visible = v }

    private suspend fun loadPreview() {
        val first = files.firstOrNull() ?: run { preview = null; return }
        val ctx = getApplication<Application>()
        preview = withContext(Dispatchers.IO) {
            runCatching {
                val f = BatchSigner.cacheCopy(ctx, first, "preview")
                ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { r ->
                        r.openPage(if (lastPage) r.pageCount - 1 else 0).use { p ->
                            val w = 900
                            val h = (w * p.height.toFloat() / p.width).toInt()
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            Preview(bmp, p.width.toFloat(), p.height.toFloat())
                        }
                    }
                }.also { f.delete() }
            }.getOrNull()
        }
    }

    fun sign() = viewModelScope.launch {
        val ctx = getApplication<Application>()
        val signer = CertificateStore.load() ?: run { error = "Certificado não encontrado. Importe novamente."; return@launch }
        busy = true
        progress = 0 to files.size
        val spec = StampSpec(if (lastPage) -1 else 0, stampX, stampY, visible)
        try {
            results = withContext(Dispatchers.IO) {
                BatchSigner.run(ctx, signer, files, spec) { i -> progress = i + 1 to files.size }
            }
        } finally { busy = false }
    }

    fun verify(uri: Uri) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        busy = true
        try {
            verification = withContext(Dispatchers.IO) {
                val f = BatchSigner.cacheCopy(ctx, uri, "verify")
                try { SignatureVerifier.verify(f) } finally { f.delete() }
            }
        } catch (e: Exception) {
            error = "Não foi possível ler o PDF: ${e.message}"
        } finally { busy = false }
    }
}
