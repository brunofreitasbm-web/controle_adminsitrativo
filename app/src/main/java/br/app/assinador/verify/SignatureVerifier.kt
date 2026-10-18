package br.app.assinador.verify

import br.app.assinador.cert.CertificateStore
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.spongycastle.cert.X509CertificateHolder
import org.spongycastle.cms.CMSProcessableByteArray
import org.spongycastle.cms.CMSSignedData
import org.spongycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.spongycastle.jce.provider.BouncyCastleProvider
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

enum class SigStatus { VALID, MODIFIED_AFTER, INVALID }

data class SigResult(val signer: String, val date: String, val status: SigStatus)

/**
 * Confere integridade matemática (hash do ByteRange + assinatura). NÃO valida a cadeia
 * até uma AC raiz ICP-Brasil nem revogação; para isso use o Verificador do ITI.
 */
object SignatureVerifier {
    fun verify(file: File): List<SigResult> {
        val bytes = file.readBytes()
        val provider = BouncyCastleProvider()
        val converter = JcaX509CertificateConverter().setProvider(provider)
        val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
        return PDDocument.load(file).use { doc ->
            doc.signatureDictionaries.map { sig ->
                try {
                    val signedData = CMSSignedData(CMSProcessableByteArray(sig.getSignedContent(bytes)), sig.getContents(bytes))
                    val signerInfo = signedData.signerInfos.signers.first()
                    @Suppress("UNCHECKED_CAST")
                    val selector = signerInfo.sid as org.spongycastle.util.Selector<X509CertificateHolder>
                    val cert = converter.getCertificate(signedData.certificates.getMatches(selector).first())
                    val ok = signerInfo.verify(JcaSimpleSignerInfoVerifierBuilder().setProvider(provider).build(cert))
                    val range = sig.byteRange
                    val coversAll = range.size == 4 && (range[2] + range[3]).toLong() == bytes.size.toLong()
                    val status = when {
                        !ok -> SigStatus.INVALID
                        !coversAll -> SigStatus.MODIFIED_AFTER
                        else -> SigStatus.VALID
                    }
                    SigResult(CertificateStore.infoOf(cert).name, sig.signDate?.time?.let(fmt::format).orEmpty(), status)
                } catch (e: Exception) {
                    SigResult(sig.name ?: "Desconhecido", "", SigStatus.INVALID)
                }
            }
        }
    }
}
