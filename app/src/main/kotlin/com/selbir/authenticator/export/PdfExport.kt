package com.selbir.authenticator.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.selbir.authenticator.core.Base32
import com.selbir.authenticator.core.OtpAlgorithm
import com.selbir.authenticator.core.OtpSpec
import com.selbir.authenticator.core.OtpType
import java.io.OutputStream

/**
 * Generates a printable PDF with one QR code per account.
 * Each QR encodes the standard `otpauth://` URI so the codes can be re-scanned
 * into any authenticator app.
 *
 * Uses only the built-in Android [PdfDocument] API — no third-party PDF library.
 */
object PdfExport {

    private const val PAGE_WIDTH = 595   // A4 portrait, 72 dpi
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48
    private const val QR_SIZE = 140
    private const val ROW_HEIGHT = 170
    private const val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN

    fun generate(context: Context, accounts: List<OtpSpec>, out: OutputStream) {
        val doc = PdfDocument()
        try {
            val titlePaint = Paint().apply {
                color = Color.BLACK
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            val subtitlePaint = Paint().apply {
                color = Color.DKGRAY
                textSize = 10f
                isAntiAlias = true
            }
            val namePaint = Paint().apply {
                color = Color.BLACK
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            val detailPaint = Paint().apply {
                color = Color.DKGRAY
                textSize = 10f
                isAntiAlias = true
            }
            val warningPaint = Paint().apply {
                color = Color.RED
                textSize = 9f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                isAntiAlias = true
            }

            var pageNumber = 0
            var y = 0f
            var page: PdfDocument.Page? = null
            var canvas: Canvas? = null

            fun ensurePage(): Canvas {
                if (page == null || y + ROW_HEIGHT > PAGE_HEIGHT - MARGIN) {
                    page?.let { doc.finishPage(it) }
                    pageNumber++
                    val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                    page = doc.startPage(info)
                    canvas = page!!.canvas
                    y = MARGIN.toFloat()

                    if (pageNumber == 1) {
                        canvas!!.drawText("Veilo Authenticator — Backup", MARGIN.toFloat(), y + 18f, titlePaint)
                        y += 30f
                        canvas!!.drawText(
                            "Keep this document in a secure location. Anyone with these QR codes can generate your 2FA codes.",
                            MARGIN.toFloat(), y + 10f, warningPaint,
                        )
                        y += 28f
                        val dateStr = java.time.LocalDate.now().toString()
                        canvas!!.drawText("Generated: $dateStr — Accounts: ${accounts.size}", MARGIN.toFloat(), y + 10f, subtitlePaint)
                        y += 30f
                    }
                }
                return canvas!!
            }

            for (spec in accounts) {
                val c = ensurePage()
                val uri = buildOtpAuthUri(spec)
                val qrBitmap = QrCodes.bitmap(uri, QR_SIZE)

                // Draw QR
                c.drawBitmap(qrBitmap, MARGIN.toFloat(), y, null)

                // Draw text next to QR
                val textX = MARGIN + QR_SIZE + 16f
                val textY = y + 20f

                val title = spec.issuer.ifBlank { spec.accountName }
                c.drawText(title, textX, textY, namePaint)

                if (spec.issuer.isNotBlank() && spec.accountName.isNotBlank()) {
                    c.drawText(spec.accountName, textX, textY + 18f, detailPaint)
                }

                val typeStr = spec.type.name
                val algoStr = spec.algorithm.name
                val info = "$typeStr · ${spec.digits} digits · ${algoStr} · ${spec.period}s"
                c.drawText(info, textX, textY + 36f, detailPaint)

                // Divider line
                val lineY = y + ROW_HEIGHT - 10f
                c.drawLine(MARGIN.toFloat(), lineY, (PAGE_WIDTH - MARGIN).toFloat(), lineY,
                    Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f })

                qrBitmap.recycle()
                y += ROW_HEIGHT
            }

            page?.let { doc.finishPage(it) }
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun buildOtpAuthUri(spec: OtpSpec): String {
        val type = when (spec.type) {
            OtpType.TOTP -> "totp"
            OtpType.HOTP -> "hotp"
        }
        val label = buildString {
            if (spec.issuer.isNotBlank()) {
                append(java.net.URLEncoder.encode(spec.issuer, "UTF-8"))
                append(':')
            }
            append(java.net.URLEncoder.encode(spec.accountName, "UTF-8"))
        }
        val secret = Base32.encode(spec.secret)
        val sb = StringBuilder("otpauth://$type/$label?secret=$secret")
        if (spec.issuer.isNotBlank()) sb.append("&issuer=${java.net.URLEncoder.encode(spec.issuer, "UTF-8")}")
        if (spec.algorithm != OtpAlgorithm.SHA1) sb.append("&algorithm=${spec.algorithm.name}")
        if (spec.digits != 6) sb.append("&digits=${spec.digits}")
        if (spec.type == OtpType.TOTP && spec.period != 30) sb.append("&period=${spec.period}")
        if (spec.type == OtpType.HOTP) sb.append("&counter=${spec.counter}")
        return sb.toString()
    }
}
