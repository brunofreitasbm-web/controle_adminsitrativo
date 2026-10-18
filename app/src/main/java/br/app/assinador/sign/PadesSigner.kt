package br.app.assinador.sign

import br.app.assinador.cert.CertificateStore
import br.app.assinador.cert.SignerKey
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import org.spongycastle.asn1.cms.Attribute
import org.spongycastle.asn1.cms.AttributeTable
import org.spongycastle.asn1.cms.CMSAttributes
import org.spongycastle.asn1.ASN1EncodableVector
import org.spongycastle.asn1.ASN1ObjectIdentifier
import org.spongycastle.asn1.DERIA5String
import org.spongycastle.asn1.DEROctetString
import org.spongycastle.asn1.DERSet
import org.spongycastle.asn1.esf.OtherHashAlgAndValue
import org.spongycastle.asn1.esf.SigPolicyQualifierInfo
import org.spongycastle.asn1.esf.SigPolicyQualifiers
import org.spongycastle.asn1.esf.SignaturePolicyId
import org.spongycastle.asn1.ess.ESSCertIDv2
import org.spongycastle.asn1.ess.SigningCertificateV2
import org.spongycastle.asn1.nist.NISTObjectIdentifiers
import org.spongycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.spongycastle.asn1.x509.AlgorithmIdentifier
import org.spongycastle.cms.CMSAttributeTableGenerator
import org.spongycastle.cms.CMSProcessableByteArray
import org.spongycastle.cms.CMSSignedDataGenerator
import org.spongycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.spongycastle.cert.jcajce.JcaCertStore
import org.spongycastle.jce.provider.BouncyCastleProvider
import org.spongycastle.operator.ContentSigner
import org.spongycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.spongycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.Signature
import java.text.Normalizer
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.GregorianCalendar

/** Posição do selo: fração (0..1) do canto superior esquerdo em relação à página exibida. */
data class StampSpec(val pageIndex: Int, val x: Float, val y: Float, val visible: Boolean = true) {
    companion object {
        const val WIDTH_PT = 230f
        const val HEIGHT_PT = 60f
    }
}

object PadesSigner {
    private val provider = BouncyCastleProvider()

    /** Assina [input] e grava o resultado em [output]. O original nunca é alterado. */
    fun sign(signer: SignerKey, input: File, output: File, stamp: StampSpec, now: ZonedDateTime = ZonedDateTime.now()) {
        PDDocument.load(input).use { doc ->
            val info = CertificateStore.infoOf(signer.certificate)

            val signature = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED)
                setName(info.name)
                setSignDate(GregorianCalendar.from(now) as Calendar)
            }

            val options = SignatureOptions().apply { preferredSignatureSize = 24000 }
            if (stamp.visible) {
                val pageIndex = stamp.pageIndex.coerceIn(0, doc.numberOfPages - 1)
                val page = doc.getPage(pageIndex)
                val lines = stampLines(info.name, info.documentLabel, info.formattedDocument, now)
                options.setVisualSignature(ByteArrayInputStream(visualTemplate(page, stamp, lines)))
                options.page = pageIndex
            }

            doc.addSignature(signature, options)
            FileOutputStream(output).use { out ->
                val external = doc.saveIncrementalForExternalSigning(out as OutputStream)
                val content = external.content.readBytes()
                external.setSignature(cms(signer, content))
            }
            options.close()
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
    internal fun cms(signer: SignerKey, content: ByteArray): ByteArray {
        val cert = signer.certificate
        val certHash = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val ess = SigningCertificateV2(ESSCertIDv2(AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256), certHash))

        // Atributos assinados exigidos pela política AD-RB: contentType, messageDigest,
        // signingCertificateV2 e sigPolicyId. Sem signingTime nem CMSAlgorithmProtection
        // (em PAdES a data vai no /M do dicionário da assinatura).
        val attrs = object : CMSAttributeTableGenerator {
            override fun getAttributes(parameters: MutableMap<Any?, Any?>): AttributeTable {
                val v = ASN1EncodableVector()
                v.add(Attribute(CMSAttributes.contentType, DERSet(parameters[CMSAttributeTableGenerator.CONTENT_TYPE] as ASN1ObjectIdentifier)))
                v.add(Attribute(CMSAttributes.messageDigest, DERSet(DEROctetString(parameters[CMSAttributeTableGenerator.DIGEST] as ByteArray))))
                v.add(Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2, DERSet(ess)))
                v.add(Attribute(PKCSObjectIdentifiers.id_aa_ets_sigPolicyId, DERSet(policyId())))
                return AttributeTable(DERSet(v))
            }
        }

        val contentSigner = KeystoreContentSigner(signer)
        val digestProvider = JcaDigestCalculatorProviderBuilder().setProvider(provider).build()
        val signerInfo = JcaSignerInfoGeneratorBuilder(digestProvider)
            .setSignedAttributeGenerator(attrs)
            .build(contentSigner, cert)

        val gen = CMSSignedDataGenerator()
        gen.addSignerInfoGenerator(signerInfo)
        gen.addCertificates(JcaCertStore(signer.chain))
        return gen.generate(CMSProcessableByteArray(content), false).encoded
    }

    /** Política AD-RB v1.3 (PAdES) da LPA da ICP-Brasil: OID e SHA-256 do arquivo PA_PAdES_AD_RB_v1_3.der. */
    private const val POLICY_OID = "2.16.76.1.7.1.11.1.3"
    private const val POLICY_URI = "http://politicas.icpbrasil.gov.br/PA_PAdES_AD_RB_v1_3.der"
    private const val POLICY_SHA256 = "23da544aef71f7a75dc85fa6e17a83875741e4baef41ec178258a5c86ace54dd"

    internal fun policyId(): SignaturePolicyId {
        val hash = POLICY_SHA256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return SignaturePolicyId(
            ASN1ObjectIdentifier(POLICY_OID),
            OtherHashAlgAndValue(AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256), DEROctetString(hash)),
            SigPolicyQualifiers(arrayOf(SigPolicyQualifierInfo(PKCSObjectIdentifiers.id_spq_ets_uri, DERIA5String(POLICY_URI)))),
        )
    }

    /** Delega a assinatura bruta ao Keystore; a chave privada nunca sai dele. */
    private class KeystoreContentSigner(private val signer: SignerKey) : ContentSigner {
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
