package com.tornado.vocab

import com.tornado.vocab.audio.KokoroEngine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * النصُّ الذي لا يُنطق يُسقط التطبيق كلَّه، لا القطعةَ وحدها.
 *
 * كوكورو يمرّ النصَّ على espeak ليستخرج الرموز. ونصٌّ بلا حرفٍ لاتينيّ
 * يُخرج صفر رموز، فيقرأ onnxruntime مؤشّراً فارغاً ويسقط بـ SIGSEGV —
 * وهو انهيارٌ أصليّ لا يلتقطه `runCatching` ولا أيُّ حارسٍ في كوتلن.
 *
 * وقع اثنتا عشرة مرّة على جهاز صاحب المكتبة، كلُّها في هذا الموضع بعينه:
 * يقف المشغّل في منتصف القائمة، وتُظهر سامسونج «التطبيق يتوقّف مراراً»
 * وتعرض إخماده. وهو الغرضُ الأوّل من التطبيق كلِّه.
 */
class KokoroSpeakableTest {

    @Test
    fun `الفارغ لا يُنطق`() {
        assertFalse(KokoroEngine.speakable(null))
        assertFalse(KokoroEngine.speakable(""))
        assertFalse(KokoroEngine.speakable("   "))
        assertFalse(KokoroEngine.speakable("\n\t "))
    }

    @Test
    fun `ما لا حرف لاتينيّ فيه لا يُنطق`() {
        assertFalse(KokoroEngine.speakable("…"))
        assertFalse(KokoroEngine.speakable("· — ·"))
        assertFalse(KokoroEngine.speakable("؟!،."))
        assertFalse(KokoroEngine.speakable("يطيق · يحتمل"))
        assertFalse(KokoroEngine.speakable("١٢٣"))
        assertFalse(KokoroEngine.speakable("123"))
        assertFalse(KokoroEngine.speakable("«»"))
    }

    @Test
    fun `النصُّ الإنجليزيّ يُنطق`() {
        assertTrue(KokoroEngine.speakable("abide"))
        assertTrue(KokoroEngine.speakable("  She copes well under pressure.  "))
        assertTrue(KokoroEngine.speakable("a"))
        // حرفٌ واحدٌ وسط رموز يكفي — espeak يجد ما ينطقه
        assertTrue(KokoroEngine.speakable("· a ·"))
        assertTrue(KokoroEngine.speakable("3 apples"))
    }
}
