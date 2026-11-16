package br.app.assinador.desktop

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.TransferHandler
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

private val prefs: Preferences = Preferences.userRoot().node("br/app/assinador/desktop")

/** Painel com a página renderizada e o selo arrastável. Posição em fração (0..1) do canto superior esquerdo. */
private class PreviewPanel(private val onMoved: (Float, Float) -> Unit) : JPanel() {
    var image: BufferedImage? = null
    var pageWidthPt = 595f
    var pageHeightPt = 842f
    var stampX = 0.06f
    var stampY = 0.88f
    var stampVisible = true
    var message = "Adicione um PDF para ver a prévia"
    private var dragDx = 0
    private var dragDy = 0

    init {
        background = Color(0xE6E6E6)
        preferredSize = Dimension(420, 560)
        val m = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val r = stampRect() ?: return
                if (r.contains(e.point)) { dragDx = e.x - r.x; dragDy = e.y - r.y } else { dragDx = r.width / 2; dragDy = r.height / 2; mouseDragged(e) }
            }
            override fun mouseDragged(e: MouseEvent) {
                val b = pageBounds() ?: return
                val r = stampRect() ?: return
                val nx = ((e.x - dragDx - b.x).toFloat() / b.width).coerceIn(0f, 1f - r.width.toFloat() / b.width)
                val ny = ((e.y - dragDy - b.y).toFloat() / b.height).coerceIn(0f, 1f - r.height.toFloat() / b.height)
                stampX = nx; stampY = ny
                onMoved(nx, ny)
                repaint()
            }
            override fun mouseMoved(e: MouseEvent) {
                cursor = if (stampRect()?.contains(e.point) == true) Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR) else Cursor.getDefaultCursor()
            }
        }
        addMouseListener(m); addMouseMotionListener(m)
    }

    private fun pageBounds(): java.awt.Rectangle? {
        val img = image ?: return null
        val s = minOf((width - 16).toDouble() / img.width, (height - 16).toDouble() / img.height)
        val w = (img.width * s).toInt(); val h = (img.height * s).toInt()
        return java.awt.Rectangle((width - w) / 2, (height - h) / 2, w, h)
    }

    private fun stampRect(): java.awt.Rectangle? {
        if (!stampVisible) return null
        val b = pageBounds() ?: return null
        val w = (minOf(StampSpec.WIDTH_PT, pageWidthPt) / pageWidthPt * b.width).toInt()
        val h = (minOf(StampSpec.HEIGHT_PT, pageHeightPt) / pageHeightPt * b.height).toInt()
        val x = b.x + (stampX.coerceIn(0f, 1f) * b.width).toInt().coerceAtMost(b.width - w)
        val y = b.y + (stampY.coerceIn(0f, 1f) * b.height).toInt().coerceAtMost(b.height - h)
        return java.awt.Rectangle(x, y, w, h)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val img = image
        val b = pageBounds()
        if (img == null || b == null) {
            g2.color = Color.GRAY
            val fm = g2.fontMetrics
            g2.drawString(message, (width - fm.stringWidth(message)) / 2, height / 2)
            return
        }
        g2.drawImage(img, b.x, b.y, b.width, b.height, null)
        g2.color = Color.GRAY; g2.drawRect(b.x, b.y, b.width, b.height)
        stampRect()?.let { r ->
            g2.color = Color(0x1F, 0x4F, 0x78, 50); g2.fillRect(r.x, r.y, r.width, r.height)
            g2.color = Color(0x1F, 0x4F, 0x78); g2.drawRect(r.x, r.y, r.width, r.height)
            g2.font = g2.font.deriveFont(Font.PLAIN, 10f)
            g2.drawString("Selo (arraste)", r.x + 4, r.y + 13)
        }
    }
}

private class App : JFrame("Assinar PDF (certificado A1)") {
    private var signer: SignerKey? = null
    private var certInfo: CertInfo? = null

    private val certLabel = JLabel("Nenhum certificado carregado")
    private val listModel = DefaultListModel<File>()
    private val list = JList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : javax.swing.DefaultListCellRenderer() {
            override fun getListCellRendererComponent(l: JList<*>?, v: Any?, i: Int, sel: Boolean, foc: Boolean): java.awt.Component =
                super.getListCellRendererComponent(l, (v as? File)?.name ?: v, i, sel, foc).also { toolTipText = (v as? File)?.absolutePath }
        }
    }
    private val preview = PreviewPanel { x, y -> prefs.putFloat("x", x); prefs.putFloat("y", y) }
    private val visibleBox = JCheckBox("Selo visível", prefs.getBoolean("visible", true))
    private val lastRadio = JRadioButton("Última página", prefs.getBoolean("last", true))
    private val firstRadio = JRadioButton("Primeira página")
    private val numRadio = JRadioButton("Página nº")
    private val pageSpin = JSpinner(SpinnerNumberModel(1, 1, 9999, 1))
    private val policyBox = javax.swing.JComboBox(PolicyVersion.values()).apply {
        selectedItem = PolicyVersion.values().firstOrNull { it.name == prefs.get("policy", "V1_3") } ?: PolicyVersion.V1_3
        toolTipText = "Versão da política de assinatura ICP-Brasil gravada na assinatura"
        addActionListener { prefs.put("policy", (selectedItem as PolicyVersion).name) }
    }
    private val log = JTextArea(6, 40).apply { isEditable = false; font = Font(Font.MONOSPACED, Font.PLAIN, 12) }
    private val signBtn = JButton("ASSINAR").apply { font = font.deriveFont(Font.BOLD, 14f) }
    private val status = JLabel(" ")

    private var renderToken = 0

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        layout = BorderLayout(8, 8)
        (contentPane as JPanel).border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        preview.stampX = prefs.getFloat("x", 0.06f); preview.stampY = prefs.getFloat("y", 0.88f)
        preview.stampVisible = visibleBox.isSelected

        // --- certificado
        val certBtn = JButton("Escolher certificado A1 (.pfx / .p12)…").apply { addActionListener { chooseCertificate() } }
        add(JPanel(BorderLayout(8, 0)).apply { add(certBtn, BorderLayout.WEST); add(certLabel, BorderLayout.CENTER) }, BorderLayout.NORTH)

        // --- lista de PDFs
        val addBtn = JButton("Adicionar PDFs…").apply { addActionListener { addFilesDialog() } }
        val rmBtn = JButton("Remover").apply { addActionListener { list.selectedValue?.let { listModel.removeElement(it) } } }
        val clearBtn = JButton("Limpar").apply { addActionListener { listModel.clear() } }
        val left = JPanel(BorderLayout(4, 4)).apply {
            add(JLabel("PDFs a assinar (arraste arquivos para cá)"), BorderLayout.NORTH)
            add(JScrollPane(list), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { add(addBtn); add(rmBtn); add(clearBtn) }, BorderLayout.SOUTH)
            preferredSize = Dimension(300, 400)
        }
        list.transferHandler = dropHandler()
        left.transferHandler = dropHandler()
        list.addListSelectionListener { if (!it.valueIsAdjusting) refreshPreview() }
        listModel.addListDataListener(object : javax.swing.event.ListDataListener {
            override fun intervalAdded(e: javax.swing.event.ListDataEvent?) = autoSelect()
            override fun intervalRemoved(e: javax.swing.event.ListDataEvent?) = autoSelect()
            override fun contentsChanged(e: javax.swing.event.ListDataEvent?) = autoSelect()
        })

        // --- opções do selo
        ButtonGroup().apply { add(lastRadio); add(firstRadio); add(numRadio) }
        if (!lastRadio.isSelected) firstRadio.isSelected = true
        val opts = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { add(visibleBox); add(lastRadio); add(firstRadio); add(numRadio); add(pageSpin); add(JLabel("  Política:")); add(policyBox) }
        val changed = { savePrefs(); refreshPreview() }
        visibleBox.addActionListener { changed() }
        listOf(lastRadio, firstRadio, numRadio).forEach { it.addActionListener { changed() } }
        pageSpin.addChangeListener { numRadio.isSelected = true; changed() }

        val right = JPanel(BorderLayout(4, 4)).apply { add(opts, BorderLayout.NORTH); add(preview, BorderLayout.CENTER) }
        add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply { resizeWeight = 0.3 }, BorderLayout.CENTER)

        // --- ações
        val verifyBtn = JButton("Verificar assinaturas…").apply { addActionListener { verifyDialog() } }
        val openBtn = JButton("Abrir pasta dos assinados").apply { addActionListener { openOutputFolder() } }
        signBtn.addActionListener { signAll() }
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { add(signBtn); add(verifyBtn); add(openBtn); add(status) }
        add(JPanel(BorderLayout(4, 4)).apply {
            add(actions, BorderLayout.NORTH)
            add(JScrollPane(log), BorderLayout.CENTER)
        }, BorderLayout.SOUTH)

        pack()
        setSize(1000, 760)
        setLocationRelativeTo(null)
        log("Saída: pasta \"${BatchSigner.OUTPUT_DIR}\" ao lado de cada PDF, com o sufixo _assinado. O original não é alterado.")
    }

    // ------------------------------------------------------------ certificado

    fun restoreCertificate() {
        val path = prefs.get("cert", null) ?: return
        val f = File(path)
        if (f.isFile) loadCertificate(f)
    }

    private fun chooseCertificate() {
        val fc = JFileChooser(prefs.get("certDir", null)?.let(::File)).apply {
            dialogTitle = "Certificado A1"
            fileFilter = FileNameExtensionFilter("Certificado (*.pfx, *.p12)", "pfx", "p12")
        }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) loadCertificate(fc.selectedFile)
    }

    private fun loadCertificate(file: File) {
        while (true) {
            val field = JPasswordField(20)
            val ok = JOptionPane.showConfirmDialog(
                this, arrayOf<Any>("Senha de ${file.name}", field, "A senha fica só na memória enquanto o programa estiver aberto."),
                "Senha do certificado", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE,
            )
            if (ok != JOptionPane.OK_OPTION) return
            val pwd = field.password
            try {
                val s = CertificateStore.load(file, pwd)
                val info = CertificateStore.infoOf(s.certificate)
                signer = s; certInfo = info
                prefs.put("cert", file.absolutePath); prefs.put("certDir", file.parent ?: "")
                showCertInfo(info)
                return
            } catch (e: WrongPasswordException) {
                JOptionPane.showMessageDialog(this, "Senha incorreta.", "Certificado", JOptionPane.ERROR_MESSAGE)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(this, "Não foi possível abrir o certificado: ${e.message}", "Certificado", JOptionPane.ERROR_MESSAGE)
                return
            } finally {
                pwd.fill('0')
            }
        }
    }

    private fun showCertInfo(info: CertInfo) {
        val fmt = SimpleDateFormat("dd/MM/yyyy")
        val days = TimeUnit.MILLISECONDS.toDays(info.notAfter.time - System.currentTimeMillis())
        val aviso = when {
            days < 0 -> "  ⚠ VENCIDO"
            days <= 30 -> "  ⚠ vence em $days dias"
            else -> ""
        }
        certLabel.text = "${info.name}  ·  ${info.documentLabel} ${info.formattedDocument}  ·  válido até ${fmt.format(info.notAfter)}$aviso"
        certLabel.foreground = if (days <= 30) Color(0xB0, 0x30, 0x00) else Color.BLACK
    }

    // ------------------------------------------------------------ arquivos

    private fun dropHandler() = object : TransferHandler() {
        override fun canImport(support: TransferSupport) = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
        override fun importData(support: TransferSupport): Boolean {
            if (!canImport(support)) return false
            @Suppress("UNCHECKED_CAST")
            val files = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
            addFiles(files)
            return true
        }
    }

    private fun addFilesDialog() {
        val fc = JFileChooser(prefs.get("pdfDir", null)?.let(::File)).apply {
            isMultiSelectionEnabled = true
            fileFilter = FileNameExtensionFilter("PDF (*.pdf)", "pdf")
        }
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            prefs.put("pdfDir", fc.currentDirectory.absolutePath)
            addFiles(fc.selectedFiles.toList())
        }
    }

    fun addFiles(files: List<File>) {
        val pdfs = files.flatMap { if (it.isDirectory) it.listFiles { f -> f.extension.equals("pdf", true) }.orEmpty().sorted() else listOf(it) }
            .filter { it.isFile && it.extension.equals("pdf", true) }
        pdfs.filter { it !in listModel.toArray() }.forEach { listModel.addElement(it) }
        if (pdfs.isEmpty() && files.isNotEmpty()) log("Nenhum PDF encontrado nos arquivos soltos.")
    }

    private fun autoSelect() = SwingUtilities.invokeLater {
        if (list.selectedIndex < 0 && listModel.size() > 0) list.selectedIndex = 0 else refreshPreview()
    }

    // ------------------------------------------------------------ prévia

    private fun savePrefs() {
        prefs.putBoolean("visible", visibleBox.isSelected)
        prefs.putBoolean("last", lastRadio.isSelected)
        preview.stampVisible = visibleBox.isSelected
    }

    private fun pageIndexFor(total: Int): Int = when {
        lastRadio.isSelected -> total - 1
        firstRadio.isSelected -> 0
        else -> ((pageSpin.value as Int) - 1).coerceIn(0, total - 1)
    }

    private fun refreshPreview() {
        preview.stampVisible = visibleBox.isSelected
        val file = list.selectedValue
        val token = ++renderToken
        if (file == null) { preview.image = null; preview.message = "Adicione um PDF para ver a prévia"; preview.repaint(); return }
        object : SwingWorker<Triple<BufferedImage, Float, Float>, Unit>() {
            override fun doInBackground(): Triple<BufferedImage, Float, Float> =
                PDDocument.load(file).use { doc ->
                    val idx = pageIndexFor(doc.numberOfPages)
                    val box = doc.getPage(idx).mediaBox
                    Triple(PDFRenderer(doc).renderImage(idx, 1.0f), box.width, box.height)
                }
            override fun done() {
                if (token != renderToken) return
                try {
                    val (img, w, h) = get()
                    preview.image = img; preview.pageWidthPt = w; preview.pageHeightPt = h
                } catch (e: Exception) {
                    preview.image = null; preview.message = "Não foi possível abrir ${file.name}"
                }
                preview.repaint()
            }
        }.execute()
    }

    // ------------------------------------------------------------ ações

    private fun log(msg: String) { log.append(msg + "\n"); log.caretPosition = log.document.length }

    private fun signAll() {
        val s = signer ?: run { JOptionPane.showMessageDialog(this, "Escolha o certificado A1 primeiro.", "Assinar", JOptionPane.WARNING_MESSAGE); return }
        val info = certInfo
        if (info != null && info.notAfter.before(Date())) {
            JOptionPane.showMessageDialog(this, "O certificado está vencido.", "Assinar", JOptionPane.ERROR_MESSAGE); return
        }
        val files = listModel.toArray().map { it as File }
        if (files.isEmpty()) { JOptionPane.showMessageDialog(this, "Adicione ao menos um PDF.", "Assinar", JOptionPane.WARNING_MESSAGE); return }

        val pageIdx = when { lastRadio.isSelected -> -1; firstRadio.isSelected -> 0; else -> (pageSpin.value as Int) - 1 }
        val stamp = StampSpec(pageIdx, preview.stampX, preview.stampY, visibleBox.isSelected)
        signBtn.isEnabled = false
        log("Política: ${(policyBox.selectedItem as PolicyVersion).label}")
        object : SwingWorker<List<FileResult>, Int>() {
            override fun doInBackground(): List<FileResult> =
                BatchSigner.run(s, files, stamp, policyBox.selectedItem as PolicyVersion) { publish(it) }
            override fun process(chunks: MutableList<Int>) { status.text = "Assinando ${chunks.last() + 1} de ${files.size}…" }
            override fun done() {
                signBtn.isEnabled = true; status.text = " "
                try {
                    val res = get()
                    res.forEach { r -> log(if (r.output != null) "✔ ${r.name} → ${r.output.absolutePath}" else "✘ ${r.name}: ${r.error}") }
                    val ok = res.count { it.error == null }
                    log("$ok de ${res.size} assinado(s).")
                    if (ok > 0) listModel.clear()
                    else JOptionPane.showMessageDialog(this@App, "Nenhum arquivo foi assinado. Veja o registro abaixo.", "Assinar", JOptionPane.ERROR_MESSAGE)
                } catch (e: Exception) {
                    log("Erro: ${e.cause?.message ?: e.message}")
                }
            }
        }.execute()
    }

    private fun verifyDialog() {
        val fc = JFileChooser(prefs.get("pdfDir", null)?.let(::File)).apply { fileFilter = FileNameExtensionFilter("PDF (*.pdf)", "pdf") }
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val f = fc.selectedFile
        try {
            val r = SignatureVerifier.verify(f)
            if (r.isEmpty()) { log("${f.name}: sem assinaturas digitais."); return }
            log("${f.name}:")
            r.forEach {
                val st = when (it.status) {
                    SigStatus.VALID -> "íntegra"
                    SigStatus.MODIFIED_AFTER -> "íntegra, mas o arquivo foi alterado depois"
                    SigStatus.INVALID -> "INVÁLIDA (documento alterado ou assinatura corrompida)"
                }
                log("   • ${it.signer} ${it.date}: $st")
            }
            log("   (Confere só a integridade. Cadeia ICP-Brasil e revogação: use o Verificador do ITI.)")
        } catch (e: Exception) {
            log("Erro ao verificar ${f.name}: ${e.message}")
        }
    }

    private fun openOutputFolder() {
        val dir = (list.selectedValue ?: listModel.elements().toList().firstOrNull())?.parentFile
            ?: prefs.get("pdfDir", null)?.let(::File)
        val out = dir?.let { File(it, BatchSigner.OUTPUT_DIR) }?.takeIf { it.isDirectory } ?: dir
        if (out == null || !out.isDirectory) { log("Ainda não há pasta de saída."); return }
        try { Desktop.getDesktop().open(out) } catch (e: Exception) { log("Não foi possível abrir ${out.absolutePath}") }
    }
}

fun main(args: Array<String>) {
    try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } catch (_: Exception) {}
    SwingUtilities.invokeLater {
        val app = App()
        app.isVisible = true
        // PDFs passados na linha de comando (ex.: "Abrir com" / arrastar sobre o atalho)
        app.addFiles(args.map(::File))
        app.restoreCertificate()
    }
}
