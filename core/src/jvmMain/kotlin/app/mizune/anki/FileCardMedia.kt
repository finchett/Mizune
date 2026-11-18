package app.mizune.anki

import java.io.File

/**
 * [CardMedia] made as files, as the apps' video tools make them: each is made in [dir], read,
 * and deleted again.
 */
abstract class FileCardMedia(private val dir: File) : CardMedia {

    /** Writes the line's audio to [out] (Ogg Opus); false if it couldn't. */
    abstract fun audio(word: MinedWord, out: File): Boolean

    /**
     * Writes the line's picture next to [base], named [base] plus the extension of whatever it
     * turned out to be ("avif", "webp"), and returns that file; null if it couldn't.
     */
    abstract fun image(word: MinedWord, base: File): File?

    final override fun audio(word: MinedWord): ByteArray? {
        dir.mkdirs()
        val out = File(dir, word.mediaBaseName() + ".ogg")
        return try {
            out.takeIf { audio(word, it) && it.length() > 0 }?.readBytes()
        } finally {
            out.delete()
        }
    }

    final override fun image(word: MinedWord): Picture? {
        dir.mkdirs()
        val file = image(word, File(dir, word.mediaBaseName())) ?: return null
        return try {
            file.takeIf { it.length() > 0 }?.let { Picture(it.extension, it.readBytes()) }
        } finally {
            file.delete()
        }
    }
}
