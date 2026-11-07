package com.example.bubbletranslate

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

/** OCR -> deteksi bahasa otomatis -> terjemahan, semuanya on-device (ML Kit). */
class OcrTranslator {
    data class Box(val rect: Rect, val text: String)

    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val zh = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val ja = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val ko = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val langId = LanguageIdentification.getClient()
    private val translators = HashMap<String, Translator>()
    private val ready = HashSet<String>()

    private fun recognizer(code: String): TextRecognizer = when (code) {
        "ja" -> ja; "zh" -> zh; "ko" -> ko; else -> latin
    }

    private fun isHan(cp: Int) = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF
    private fun count(s: String, f: (Int) -> Boolean) = s.codePoints().filter { f(it) }.count().toInt()

    /** Jalankan 3 recognizer CJK lalu pilih bahasa berdasarkan jenis aksara yang paling dominan. */
    private suspend fun detect(img: InputImage): Pair<String, Text> = coroutineScope {
        val j = async { ja.process(img).await() }
        val k = async { ko.process(img).await() }
        val c = async { zh.process(img).await() }
        val jr = j.await(); val kr = k.await(); val cr = c.await()
        val kana = count(jr.text) { it in 0x3040..0x30FF }
        val jTot = kana + count(jr.text) { isHan(it) }
        val hangul = count(kr.text) { it in 0xAC00..0xD7AF }
        val han = count(cr.text) { isHan(it) }
        when {
            hangul >= 3 && hangul > 1.5 * han && hangul >= kana -> "ko" to kr
            kana >= 3 && kana >= 0.25 * jTot -> "ja" to jr
            han >= 2 -> "zh" to cr
            else -> "latin" to latin.process(img).await()
        }
    }

    suspend fun run(bmp: Bitmap, source: String, target: String, status: (String) -> Unit): List<Box> {
        val img = InputImage.fromBitmap(bmp, 0)
        val (lang, result) =
            if (source == "auto") detect(img) else source to recognizer(source).process(img).await()

        val blocks = result.textBlocks.mapNotNull { b ->
            val r = b.boundingBox ?: return@mapNotNull null
            val t = b.text.replace("\n", " ").trim()
            if (t.length < 2 || t.none { it.isLetter() }) null else Box(r, t)
        }
        if (blocks.isEmpty()) return emptyList()

        val src: String = when (lang) {
            "ja", "zh", "ko", "en" -> lang
            else -> {
                val tag = langId.identifyLanguage(blocks.joinToString(" ") { it.text }).await()
                TranslateLanguage.fromLanguageTag(tag) ?: TranslateLanguage.ENGLISH
            }
        }
        if (src == target) return emptyList()

        val key = "$src>$target"
        val tr = translators.getOrPut(key) {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(target).build()
            )
        }
        if (key !in ready) {
            status("Mengunduh model terjemahan (hanya sekali, butuh internet)…")
            tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            ready.add(key)
        }
        return blocks.map { b ->
            Box(b.rect, try { tr.translate(b.text).await() } catch (e: Exception) { b.text })
        }
    }

    fun close() {
        translators.values.forEach { it.close() }
        listOf(latin, zh, ja, ko).forEach { it.close() }
        langId.close()
    }
}
