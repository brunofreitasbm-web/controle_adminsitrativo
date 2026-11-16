package br.app.assinador.desktop

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm
import org.apache.pdfbox.pdmodel.interactive.form.PDField
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERIA5String
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.AttributeTable
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.esf.OtherHashAlgAndValue
import org.bouncycastle.asn1.esf.SigPolicyQualifierInfo
import org.bouncycastle.asn1.esf.SigPolicyQualifiers
import org.bouncycastle.asn1.esf.SignaturePolicyId
import org.bouncycastle.asn1.ess.ESSCertIDv2
import org.bouncycastle.asn1.ess.SigningCertificateV2
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cms.CMSAttributeTableGenerator
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale

// ---------------------------------------------------------------- certificado

/** Dados do titular extraídos do certificado A1 (CN ICP-Brasil: "NOME:DOCUMENTO"). */
data class CertInfo(val name: String, val document: String, val notAfter: Date, val issuer: String) {
    val formattedDocument: String
        get() = when (document.length) {
            11 -> "${document.substring(0, 3)}.${document.substring(3, 6)}.${document.substring(6, 9)}-${document.substring(9)}"
            14 -> "${document.substring(0, 2)}.${document.substring(2, 5)}.${document.substring(5, 8)}/${document.substring(8, 12)}-${document.substring(12)}"
            else -> document
        }
    val documentLabel: String get() = if (document.length == 14) "CNPJ" else "CPF"
}

class SignerKey(val key: PrivateKey, val chain: List<X509Certificate>) {
    val certificate: X509Certificate get() = chain.first()
    val signatureAlgorithm: String
        get() = if (key.algorithm.equals("EC", true) || key.algorithm.equals("ECDSA", true)) "SHA256withECDSA" else "SHA256withRSA"
}

class WrongPasswordException : Exception("Senha do certificado incorreta")

object CertificateStore {
    /** Abre o .pfx/.p12. A senha só vive em memória; nada é gravado em disco. */
    fun load(pfx: File, password: CharArray): SignerKey {
        val p12 = openPkcs12(pfx, password)
        val alias = p12.aliases().toList().firstOrNull { p12.isKeyEntry(it) }
            ?: throw IllegalArgumentException("O arquivo não contém chave privada")
        val key = p12.getKey(alias, password) as PrivateKey
        val chain = p12.getCertificateChain(alias).map { it as X509Certificate }
        return SignerKey(key, chain)
    }

    private fun openPkcs12(pfx: File, password: CharArray): KeyStore {
        // Tenta o provedor padrão do JDK e, se falhar, o BouncyCastle (cobre PFX com cifras antigas).
        val providers = listOf<() -> KeyStore>(
            { KeyStore.getInstance("PKCS12") },
            { KeyStore.getInstance("PKCS12", BouncyCastleProvider()) },
        )
        var last: Exception? = null
        for (make in providers) {
            try {
                return make().also { ks -> FileInputStream(pfx).use { ks.load(it, password) } }
            } catch (e: Exception) {
                last = e
            }
        }
        throw WrongPasswordException().also { it.initCause(last) }
    }

    fun infoOf(cert: X509Certificate): CertInfo {
        val holder = JcaX509CertificateHolder(cert)
        val cn = holder.subject.getRDNs(BCStyle.CN).firstOrNull()?.first?.value?.toString() ?: cert.subjectX500Principal.name
        val issuerCn = holder.issuer.getRDNs(BCStyle.CN).firstOrNull()?.first?.value?.toString().orEmpty()
        val name = cn.substringBefore(':').trim()
        val doc = cn.substringAfter(':', "").filter { it.isDigit() }
        return CertInfo(name, doc, cert.notAfter, issuerCn)
    }
}

// ---------------------------------------------------------------- assinatura

/**
 * Posição do selo: fração (0..1) do canto superior esquerdo em relação à página.
 * [pageIndex] negativo = última página (resolvido por documento).
 */
data class StampSpec(val pageIndex: Int, val x: Float, val y: Float, val visible: Boolean = true) {
    companion object {
        const val WIDTH_PT = 230f
        const val HEIGHT_PT = 60f
    }
}

/** Versões da política AD-RB (PAdES) da LPA da ICP-Brasil: OID e SHA-256 do arquivo .der, conforme a LPA oficial. */
enum class PolicyVersion(val label: String, val oid: String, val file: String, val sha256: String) {
    V1_3("AD-RB v1.3 (mais recente)", "2.16.76.1.7.1.11.1.3", "PA_PAdES_AD_RB_v1_3.der", "23da544aef71f7a75dc85fa6e17a83875741e4baef41ec178258a5c86ace54dd"),
    V1_2("AD-RB v1.2", "2.16.76.1.7.1.11.1.2", "PA_PAdES_AD_RB_v1_2.der", "84ed4620c6531e4a4853adecc9e2496926c823418dd3141963ed9c4f9704a03d"),
    V1_1("AD-RB v1.1", "2.16.76.1.7.1.11.1.1", "PA_PAdES_AD_RB_v1_1.der", "95752d26ca974d46675ae7fb787b606a71ea941f26b59f6b6a321f97d63b9cb1");

    val uri: String get() = "http://politicas.icpbrasil.gov.br/$file"
    override fun toString() = label
}

object PadesSigner {
    private val provider = BouncyCastleProvider()

    /** Assina [input] e grava o resultado em [output]. O original nunca é alterado. */
    fun sign(signer: SignerKey, input: File, output: File, stamp: StampSpec, now: ZonedDateTime = ZonedDateTime.now(), policy: PolicyVersion = PolicyVersion.V1_3) {
        PDDocument.load(input).use { doc ->
            val info = CertificateStore.infoOf(signer.certificate)

            val signature = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED)
                name = info.name
                signDate = GregorianCalendar.from(now) as Calendar
            }

            val options = SignatureOptions().apply { preferredSignatureSize = 24000 }
            try {
                if (stamp.visible) {
                    val pageIndex = (if (stamp.pageIndex < 0) doc.numberOfPages - 1 else stamp.pageIndex)
                        .coerceIn(0, doc.numberOfPages - 1)
                    val page = doc.getPage(pageIndex)
                    val lines = stampLines(info.name, info.documentLabel, info.formattedDocument, now)
                    options.setVisualSignature(ByteArrayInputStream(visualTemplate(page, stamp, lines)))
                    options.page = pageIndex
                }

                doc.addSignature(signature, options)
                FileOutputStream(output).use { out ->
                    val external = doc.saveIncrementalForExternalSigning(out as OutputStream)
                    val content = external.content.readBytes()
                    external.setSignature(cms(signer, content, policy))
                }
            } finally {
                options.close()
            }
        }
    }

    private fun stampLines(name: String, docLabel: String, doc: String, now: ZonedDateTime): List<String> {
        val date = now.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss xxx"))
        return listOf(
            "Assinado digitalmente por:",
            latin1(name),
            if (doc.isNotEmpty()) "$docLabel: $doc" else "",
            "Data: $date",
        ).filter { it.isNotEmpty() }
    }

    /** Fonte Helvetica (WinAnsi) não tem todos os caracteres; mantém só o que ela suporta. */
    private fun latin1(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFC).filter { it.code in 32..255 }

    /** PDF de uma página com um campo de assinatura cujo aspecto é o selo; é mesclado pelo PDFBox. */
    private fun visualTemplate(srcPage: PDPage, stamp: StampSpec, lines: List<String>): ByteArray {
        val media = srcPage.mediaBox
        val w = minOf(StampSpec.WIDTH_PT, media.width)
        val h = minOf(StampSpec.HEIGHT_PT, media.height)
        val left = media.lowerLeftX + (stamp.x * media.width).coerceIn(0f, media.width - w)
        val top = media.upperRightY - (stamp.y * media.height).coerceIn(0f, media.height - h)
        val rect = PDRectangle(left, top - h, w, h)

        PDDocument().use { tpl ->
            val page = PDPage(media)
            tpl.addPage(page)
            val acroForm = PDAcroForm(tpl)
            tpl.documentCatalog.acroForm = acroForm
            val field = PDSignatureField(acroForm)
            val widget = field.widgets[0]
            acroForm.fields = mutableListOf<PDField>(field)
            acroForm.isSignaturesExist = true
            acroForm.isAppendOnly = true

            widget.rectangle = rect
            widget.isPrinted = true
            widget.page = page
            page.annotations.add(widget)

            val ap = PDAppearanceStream(tpl).apply {
                resources = PDResources()
                bBox = PDRectangle(w, h)
            }
            widget.appearance = PDAppearanceDictionary().apply { setNormalAppearance(ap) }

            PDPageContentStream(tpl, ap).use { cs ->
                cs.setLineWidth(0.8f)
                cs.setStrokingColor(0.12f, 0.31f, 0.47f)
                cs.addRect(0.4f, 0.4f, w - 0.8f, h - 0.8f)
                cs.stroke()
                cs.setNonStrokingColor(0f, 0f, 0f)
                var y = h - 12f
                lines.forEachIndexed { i, line ->
                    cs.beginText()
                    cs.setFont(if (i == 1) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA, 8.5f)
                    cs.newLineAtOffset(5f, y)
                    cs.showText(line)
                    cs.endText()
                    y -= 12.5f
                }
            }
            val out = ByteArrayOutputStream()
            tpl.save(out)
            return out.toByteArray()
        }
    }

    /** CMS CAdES-detached AD-RB: SHA-256, cadeia embutida, ESS signing-certificate-v2 e política ICP-Brasil. */
    internal fun cms(signer: SignerKey, content: ByteArray, policy: PolicyVersion = PolicyVersion.V1_3): ByteArray {
        val cert = signer.certificate
        val certHash = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val ess = SigningCertificateV2(ESSCertIDv2(AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256), certHash))

        // Atributos assinados exigidos pela política AD-RB: contentType, messageDigest,
        // signingCertificateV2 e sigPolicyId. Sem signingTime (em PAdES a data vai no /M).
        val attrs = object : CMSAttributeTableGenerator {
            override fun getAttributes(parameters: MutableMap<Any?, Any?>): AttributeTable {
                val v = ASN1EncodableVector()
                v.add(Attribute(CMSAttributes.contentType, DERSet(parameters[CMSAttributeTableGenerator.CONTENT_TYPE] as ASN1ObjectIdentifier)))
                v.add(Attribute(CMSAttributes.messageDigest, DERSet(DEROctetString(parameters[CMSAttributeTableGenerator.DIGEST] as ByteArray))))
                v.add(Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2, DERSet(ess)))
                v.add(Attribute(PKCSObjectIdentifiers.id_aa_ets_sigPolicyId, DERSet(policyId(policy))))
                return AttributeTable(DERSet(v))
            }
        }

        val digestProvider = JcaDigestCalculatorProviderBuilder().setProvider(provider).build()
        val signerInfo = JcaSignerInfoGeneratorBuilder(digestProvider)
            .setSignedAttributeGenerator(attrs)
            .build(KeyContentSigner(signer), cert)

        val gen = CMSSignedDataGenerator()
        gen.addSignerInfoGenerator(signerInfo)
        gen.addCertificates(JcaCertStore(signer.chain))
        return gen.generate(CMSProcessableByteArray(content), false).encoded
    }

    internal fun policyId(policy: PolicyVersion = PolicyVersion.V1_3): SignaturePolicyId {
        val hash = policy.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return SignaturePolicyId(
            ASN1ObjectIdentifier(policy.oid),
            OtherHashAlgAndValue(AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256), DEROctetString(hash)),
            SigPolicyQualifiers(arrayOf(SigPolicyQualifierInfo(PKCSObjectIdentifiers.id_spq_ets_uri, DERIA5String(policy.uri)))),
        )
    }

    private class KeyContentSigner(private val signer: SignerKey) : ContentSigner {
        private val buffer = ByteArrayOutputStream()
        private val algId = DefaultSignatureAlgorithmIdentifierFinder().find(signer.signatureAlgorithm.uppercase())
        override fun getAlgorithmIdentifier() = algId
        override fun getOutputStream(): OutputStream = buffer
        override fun getSignature(): ByteArray =
            Signature.getInstance(signer.signatureAlgorithm).run {
                initSign(signer.key)
                update(buffer.toByteArray())
                sign()
            }
    }
}

// ---------------------------------------------------------------- lote

data class FileResult(val name: String, val output: File?, val error: String?)

object BatchSigner {
    const val OUTPUT_DIR = "Assinados"

    /** Assina todos; uma falha não interrompe os demais. Saída: <pasta do PDF>/Assinados/<nome>_assinado.pdf */
    fun run(signer: SignerKey, files: List<File>, stamp: StampSpec, policy: PolicyVersion = PolicyVersion.V1_3, onProgress: (Int) -> Unit = {}): List<FileResult> =
        files.mapIndexed { i, file ->
            onProgress(i)
            try {
                val dir = File(file.absoluteFile.parentFile, OUTPUT_DIR).also { it.mkdirs() }
                val out = uniqueFile(dir, file.nameWithoutExtension + "_assinado.pdf")
                PadesSigner.sign(signer, file, out, stamp, policy = policy)
                FileResult(file.name, out, null)
            } catch (e: Exception) {
                FileResult(file.name, null, e.message ?: e.javaClass.simpleName)
            }
        }

    /** Nunca sobrescreve: se já existe, usa " (2)", " (3)"... */
    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var n = 2
        val base = name.removeSuffix(".pdf")
        while (f.exists()) f = File(dir, "$base ($n).pdf").also { n++ }
        return f
    }
}

// ---------------------------------------------------------------- verificação

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
                    val matches = signedData.certificates.getMatches(signerInfo.sid as org.bouncycastle.util.Selector<X509CertificateHolder>)
                    val cert = converter.getCertificate(matches.first())
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
