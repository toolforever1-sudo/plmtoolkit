package com.sandisk.plm.tracker.util;

/**
 * Deterministic "did the user mash the keyboard?" detector. Used to gate
 * free-text inputs (Ask AI expectation, Feedback form, etc.) before they
 * become Bug Reports in the triage queue.
 *
 * Approach: three cheap checks on the Latin-letter portion of the input.
 * Non-Latin scripts (CJK, Arabic, Devanagari, etc.) are filtered out before
 * the checks run, so users writing in Mandarin / Hindi / Hebrew never get
 * blocked by a vowel-ratio rule that doesn't apply to their script.
 *
 * Tuned against the cases we've actually seen leak through to prod:
 *   "vhjdfbk df,vndsf" (0% vowels)
 *   "gibebjvpdfslvnksfdbkfsb" (~9% vowels)
 *   "bcfkjdfbkdbkdbkd" (0% vowels, PT-45)
 * Real terse expectations like "answer should be 42 not 0", "OK fix it",
 * and "this is wrong" all pass — borderline content-free strings are left
 * to the LLM (in the AI Eval path) or to human triage (Feedback form).
 */
public final class GibberishHeuristic {

    private GibberishHeuristic() {}

    /**
     * @return {@code true} when the input has the shape of keyboard-mashing,
     *         {@code false} for plausible English text, non-Latin scripts,
     *         and short inputs that the heuristic can't judge confidently.
     */
    public static boolean looksLikeGibberish(String text) {
        if (text == null) return false;

        // Split into Latin-letter tokens via any non-letter separator. Word
        // boundaries reset the consonant-run counter so legitimate text like
        // "running JVM matches" (which would concatenate to "runningjvmmatches"
        // → "ngjvmm" run of 6 consonants) doesn't false-positive.
        //
        // Non-Latin scripts (CJK, Arabic, Devanagari, Hebrew, Tamil, …) are
        // deliberately ignored — the vowel-ratio check below is English-
        // specific and would false-positive on every Mandarin or Hindi
        // submission otherwise.
        String[] tokens = text.toLowerCase().split("[^a-z]+");
        int totalLetters = 0;
        int vowels = 0;
        int maxConsonantRun = 0;
        for (String tok : tokens) {
            if (tok.isEmpty()) continue;
            int curRun = 0;
            for (int i = 0; i < tok.length(); i++) {
                char c = tok.charAt(i);
                if (c < 'a' || c > 'z') continue;
                totalLetters++;
                boolean isVowel = (c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u');
                if (isVowel) {
                    vowels++;
                    curRun = 0;
                } else {
                    // y is usually a vowel inside words but counts here as a
                    // consonant — keeps "rhythm" / "my" from skewing the ratio.
                    curRun++;
                    if (curRun > maxConsonantRun) maxConsonantRun = curRun;
                }
            }
        }

        // Too short — or input is mostly non-Latin script. Either way the
        // heuristic abstains.
        if (totalLetters < 6) return false;

        double vowelRatio = (double) vowels / totalLetters;
        if (vowelRatio < 0.15) return true;
        if (maxConsonantRun >= 6) return true;

        // Token-level vowel check — every word of 4+ Latin letters should
        // have a vowel. Catches "asdf asdf asdf" which has lots of 'a's
        // across the whole string but no vowel-bearing words.
        int wordsChecked = 0;
        int wordsWithoutVowel = 0;
        for (String tok : tokens) {
            if (tok.length() < 4) continue;
            wordsChecked++;
            boolean hasVowel = false;
            for (int i = 0; i < tok.length(); i++) {
                char c = tok.charAt(i);
                if (c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u') { hasVowel = true; break; }
            }
            if (!hasVowel) wordsWithoutVowel++;
        }
        if (wordsChecked > 0 && (double) wordsWithoutVowel / wordsChecked > 0.5) return true;

        return false;
    }
}
