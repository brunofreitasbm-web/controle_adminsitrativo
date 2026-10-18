package br.app.assinador.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.app.assinador.MainViewModel
import br.app.assinador.sign.FileResult
import br.app.assinador.sign.StampSpec
import br.app.assinador.verify.SigStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Blue = Color(0xFF1F4E79)

@Composable
fun AppScreen(vm: MainViewModel) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Blue)) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Assinar PDF", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Blue)
                when {
                    vm.cert == null -> ImportCert(vm)
                    vm.results != null -> Results(vm)
                    else -> SignScreen(vm)
                }
                if (vm.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (vm.progress.second > 0) Text("Assinando ${vm.progress.first} de ${vm.progress.second}…")
                }
            }
        }
    }
    vm.error?.let { msg ->
        AlertDialog(onDismissRequest = { vm.error = null }, confirmButton = { TextButton({ vm.error = null }) { Text("OK") } }, text = { Text(msg) })
    }
    vm.verification?.let { VerifyDialog(vm) }
}

@Composable
private fun ImportCert(vm: MainViewModel) {
    var picked by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked = it }

    Text("Primeiro uso: importe o certificado A1 (arquivo .pfx ou .p12). Isso é feito uma única vez.")
    Button({ picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Escolher certificado A1") }

    picked?.let { uri ->
        AlertDialog(
            onDismissRequest = { picked = null; password = "" },
            title = { Text("Senha do certificado") },
            text = {
                OutlinedTextField(
                    password, { password = it }, singleLine = true, label = { Text("Senha") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
            },
            confirmButton = {
                TextButton({
                    vm.importCertificate(uri, password.toCharArray()); picked = null; password = ""
                }) { Text("Importar") }
            },
            dismissButton = { TextButton({ picked = null; password = "" }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun SignScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val cert = vm.cert!!
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.pickFiles(it) }
    val verifyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::verify) }
    var confirmRemove by remember { mutableStateOf(false) }

    CertCard(vm)

    Button({ pdfPicker.launch(arrayOf("application/pdf")) }, Modifier.fillMaxWidth().height(56.dp), enabled = !vm.busy) {
        Text(if (vm.files.isEmpty()) "Escolher PDFs" else "Trocar PDFs")
    }
    if (vm.files.isEmpty()) {
        Text("Dica: em qualquer app (WhatsApp, e-mail, Arquivos) use Compartilhar → Assinar PDF.", color = Color.Gray)
    } else {
        vm.names.forEach { Text("• $it", maxLines = 1) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(vm.visible, vm.let { { v: Boolean -> it.chooseVisible(v) } })
            Spacer(Modifier.width(8.dp)); Text("Selo visível")
        }
        if (vm.visible) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(vm.lastPage, { vm.chooseLastPage(true) }, { Text("Última página") })
                FilterChip(!vm.lastPage, { vm.chooseLastPage(false) }, { Text("Primeira página") })
            }
            Text("Arraste o selo para o lugar desejado (a posição fica salva).", color = Color.Gray, fontSize = 13.sp)
            StampPreview(vm)
        }
        Button(
            { vm.sign() }, Modifier.fillMaxWidth().height(64.dp), enabled = !vm.busy && cert.notAfter.after(Date()),
        ) { Text(if (vm.files.size == 1) "ASSINAR" else "ASSINAR ${vm.files.size} PDFs", fontSize = 18.sp) }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ verifyPicker.launch(arrayOf("application/pdf")) }) { Text("Verificar assinaturas") }
        OutlinedButton({ confirmRemove = true }) { Text("Remover certificado") }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            text = { Text("Remover o certificado deste celular? Para assinar de novo será preciso importar o .pfx outra vez.") },
            confirmButton = { TextButton({ vm.removeCertificate(); confirmRemove = false }) { Text("Remover") } },
            dismissButton = { TextButton({ confirmRemove = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun CertCard(vm: MainViewModel) {
    val c = vm.cert!!
    val days = ((c.notAfter.time - System.currentTimeMillis()) / 86_400_000L).toInt()
    val fmt = SimpleDateFormat("dd/MM/yyyy", Locale("pt", "BR"))
    val (msg, color) = when {
        days < 0 -> "VENCIDO em ${fmt.format(c.notAfter)} — não é possível assinar" to Color(0xFFB00020)
        days <= 30 -> "Vence em $days dia(s) (${fmt.format(c.notAfter)})" to Color(0xFFB26A00)
        else -> "Válido até ${fmt.format(c.notAfter)}" to Color(0xFF2E7D32)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(c.name, fontWeight = FontWeight.Bold)
            if (c.document.isNotEmpty()) Text("${c.documentLabel}: ${c.formattedDocument}")
            Text(msg, color = color)
        }
    }
}

@Composable
private fun StampPreview(vm: MainViewModel) {
    val p = vm.preview ?: return
    var size by remember { mutableStateOf(IntSize.Zero) }
    val aspect = p.bitmap.width.toFloat() / p.bitmap.height
    Box(
        Modifier.fillMaxWidth().aspectRatio(aspect).border(1.dp, Color.LightGray).onSizeChanged { size = it },
    ) {
        Image(p.bitmap.asImageBitmap(), null, Modifier.fillMaxSize())
        if (size.width > 0) {
            val density = LocalDensity.current
            val wFrac = (StampSpec.WIDTH_PT / p.widthPt).coerceAtMost(1f)
            val hFrac = (StampSpec.HEIGHT_PT / p.heightPt).coerceAtMost(1f)
            val wDp = with(density) { (wFrac * size.width).toDp() }
            val hDp = with(density) { (hFrac * size.height).toDp() }
            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset((vm.stampX * size.width).toInt(), (vm.stampY * size.height).toInt()) }
                    .size(wDp, hDp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Blue.copy(alpha = 0.25f))
                    .border(1.5.dp, Blue)
                    .pointerInput(size, wFrac, hFrac) {
                        detectDragGestures(onDragEnd = { vm.commitStamp() }) { change, drag: Offset ->
                            change.consume()
                            vm.moveStamp(
                                (vm.stampX + drag.x / size.width).coerceIn(0f, 1f - wFrac),
                                (vm.stampY + drag.y / size.height).coerceIn(0f, 1f - hFrac),
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Text("Assinatura", color = Blue, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun Results(vm: MainViewModel) {
    val ctx = LocalContext.current
    val results = vm.results!!
    val ok = results.filter { it.outUri != null }
    Text("${ok.size} de ${results.size} assinado(s). Salvos em Download/Assinados.", fontWeight = FontWeight.Bold)
    results.forEach { r: FileResult ->
        Text(if (r.outUri != null) "✔ ${r.name}" else "✘ ${r.name}: ${r.error}", color = if (r.outUri != null) Color(0xFF2E7D32) else Color(0xFFB00020))
    }
    if (ok.isNotEmpty()) {
        Button({
            val send = Intent(if (ok.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/pdf"
                if (ok.size == 1) putExtra(Intent.EXTRA_STREAM, ok[0].outUri)
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(ok.map { it.outUri }))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, "Compartilhar PDF assinado"))
        }, Modifier.fillMaxWidth().height(56.dp)) { Text("Compartilhar") }
    }
    OutlinedButton({ vm.reset() }, Modifier.fillMaxWidth().height(48.dp)) { Text("Assinar outros") }
}

@Composable
private fun VerifyDialog(vm: MainViewModel) {
    val list = vm.verification!!
    AlertDialog(
        onDismissRequest = { vm.verification = null },
        confirmButton = { TextButton({ vm.verification = null }) { Text("OK") } },
        title = { Text("Assinaturas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (list.isEmpty()) Text("Este PDF não possui assinatura digital.")
                list.forEach {
                    val s = when (it.status) {
                        SigStatus.VALID -> "✔ Íntegra"
                        SigStatus.MODIFIED_AFTER -> "⚠ Documento alterado após esta assinatura"
                        SigStatus.INVALID -> "✘ Inválida"
                    }
                    Text("${it.signer}\n${it.date}\n$s")
                }
                Text("Verifica integridade; não valida a cadeia ICP-Brasil. Use verificador.iti.gov.br para isso.", fontSize = 12.sp, color = Color.Gray)
            }
        },
    )
}
