package pl.luki.frankenflasher.ota

/**
 * Natywny dekoder XZ (liblzma z xz-utils, biblioteka libxzjni.so budowana NDK w GitHub Actions).
 * Kilka razy szybszy od dekodera w Javie; gdy biblioteka sie nie zaladuje albo zwroci blad,
 * PayloadWriter uzywa dekodera w Javie.
 */
object NativeXz {
    val available: Boolean = try { System.loadLibrary("xzjni"); true } catch (_: Throwable) { false }

    /** Dekoduje caly strumien .xz z [input] (pierwsze [inLen] bajtow) do [output]; liczba bajtow albo -kod bledu. */
    @JvmStatic external fun decode(input: ByteArray, inLen: Int, output: ByteArray): Int

    @JvmStatic external fun version(): String

    fun describe(): String = if (available) try { "liblzma ${version()}" } catch (_: Throwable) { "liblzma" } else "Java"
}
