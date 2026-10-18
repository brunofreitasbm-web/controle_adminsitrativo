package br.app.assinador.sign

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import br.app.assinador.cert.SignerKey
import java.io.File

data class FileResult(val name: String, val outUri: Uri?, val error: String?)

object BatchSigner {
    const val OUTPUT_DIR = "Assinados"

    fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "documento.pdf"

    /** Copia o conteúdo do Uri para o cache (PDFBox precisa de arquivo para a atualização incremental). */
    fun cacheCopy(context: Context, uri: Uri, tag: String): File {
        val f = File(context.cacheDir, "$tag.pdf")
        context.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f
    }

    /** Assina todos; uma falha não interrompe os demais. Saída: Download/Assinados/<nome>_assinado.pdf */
    fun run(context: Context, signer: SignerKey, uris: List<Uri>, stamp: StampSpec, onProgress: (Int) -> Unit): List<FileResult> =
        uris.mapIndexed { i, uri ->
            onProgress(i)
            val name = displayName(context, uri)
            val tmpIn = File(context.cacheDir, "in_$i.pdf")
            val tmpOut = File(context.cacheDir, "out_$i.pdf")
            try {
                context.contentResolver.openInputStream(uri)!!.use { s -> tmpIn.outputStream().use { s.copyTo(it) } }
                val last = stamp.pageIndex < 0
                // pageIndex negativo = última página; resolvido por documento
                val effective = if (last) stamp.copy(pageIndex = lastPage(tmpIn)) else stamp
                PadesSigner.sign(signer, tmpIn, tmpOut, effective)
                FileResult(name, save(context, tmpOut, name.removeSuffix(".pdf").removeSuffix(".PDF") + "_assinado.pdf"), null)
            } catch (e: Exception) {
                FileResult(name, null, e.message ?: e.javaClass.simpleName)
            } finally {
                tmpIn.delete(); tmpOut.delete()
            }
        }

    private fun lastPage(f: File): Int =
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(f).use { it.numberOfPages - 1 }

    private fun save(context: Context, file: File, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$OUTPUT_DIR")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Não foi possível criar o arquivo de saída")
        resolver.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
        return uri
    }
}
