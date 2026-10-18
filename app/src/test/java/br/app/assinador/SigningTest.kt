package br.app.assinador

import androidx.test.core.app.ApplicationProvider
import br.app.assinador.cert.SignerKey
import br.app.assinador.sign.PadesSigner
import br.app.assinador.sign.StampSpec
import br.app.assinador.verify.SigStatus
import br.app.assinador.verify.SignatureVerifier
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.spongycastle.asn1.x500.X500Name
import org.spongycastle.cert.jcajce.JcaX509CertificateConverter
import org.spongycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.spongycastle.jce.provider.BouncyCastleProvider
import org.spongycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class SigningTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun init() { PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext()) }

    private fun testSigner(): SignerKey {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=JOAO DA SILVA:12345678901,O=ICP-Brasil,C=BR")
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(0), Date(System.currentTimeMillis() + 86_400_000L), name, kp.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
        val cert = JcaX509CertificateConverter().setProvider(BouncyCastleProvider()).getCertificate(holder)
        return SignerKey(kp.private, listOf(cert))
    }

    private fun blankPdf(pages: Int): File {
        val f = tmp.newFile()
        PDDocument().use { d -> repeat(pages) { d.addPage(PDPage()) }; d.save(f) }
        return f
    }

    @Test fun signsAndVerifiesVisible() {
        val out = tmp.newFile()
        PadesSigner.sign(testSigner(), blankPdf(2), out, StampSpec(1, 0.1f, 0.8f, true))
        System.getenv("SIGNED_COPY")?.let { out.copyTo(File(it), true) }
        val r = SignatureVerifier.verify(out)
        assertEquals(1, r.size)
        assertEquals(SigStatus.VALID, r[0].status)
        assertEquals("JOAO DA SILVA", r[0].signer)
    }

    @Test fun signsInvisibleAndSecondSignatureKeepsFirstValid() {
        val signer = testSigner()
        val a = tmp.newFile(); val b = tmp.newFile()
        PadesSigner.sign(signer, blankPdf(1), a, StampSpec(0, 0f, 0f, false))
        PadesSigner.sign(signer, a, b, StampSpec(0, 0.1f, 0.1f, true))
        val r = SignatureVerifier.verify(b)
        assertEquals(2, r.size)
        assertTrue(r.all { it.status != SigStatus.INVALID })
    }

    @Test fun tamperingIsDetected() {
        val out = tmp.newFile()
        PadesSigner.sign(testSigner(), blankPdf(1), out, StampSpec(0, 0.1f, 0.8f, true))
        val bytes = out.readBytes()
        val i = String(bytes, Charsets.ISO_8859_1).indexOf("/Type /Catalog").takeIf { it >= 0 } ?: 20
        bytes[i] = (bytes[i] + 1).toByte()
        out.writeBytes(bytes)
        assertTrue(SignatureVerifier.verify(out).any { it.status == SigStatus.INVALID })
    }
}
