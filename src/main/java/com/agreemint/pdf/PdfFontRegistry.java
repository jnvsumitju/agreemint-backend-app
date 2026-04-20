package com.agreemint.pdf;

import com.itextpdf.io.font.FontProgram;
import com.itextpdf.io.font.FontProgramFactory;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads the embedded TTFs we ship for pixel-parity rendering and exposes them
 * as {@link PdfFont} instances to the renderer.
 *
 * <p>iText 7's {@code PdfFont} is document-scoped — a single instance cannot
 * safely serve two concurrent renders — so we cache the immutable
 * {@link FontProgram} per family/weight/style combination and mint a fresh
 * {@code PdfFont} from it each time a render asks. The program cache loads
 * once at startup.
 *
 * <p>When a TTF is missing (dev, CI, phase 0 before fonts are committed),
 * we log a WARN and return {@code null} from {@link #createFont}. Callers must
 * handle that by falling back to the legacy iText default (Helvetica from the
 * standard 14) — the pixel-parity flag is off by default, so the missing-font
 * fallback only ever runs on the non-parity code path.
 */
@Component
public class PdfFontRegistry {

    private static final Logger log = LoggerFactory.getLogger(PdfFontRegistry.class);

    /** The three families we ship. Keep in sync with {@code public/fonts/} on the frontend. */
    public static final String FAMILY_SANS  = "Inter";
    public static final String FAMILY_SERIF = "Source Serif 4";
    public static final String FAMILY_MONO  = "JetBrains Mono";

    /** Lookup key is {@code family|bold|italic}. */
    private final Map<String, FontProgram> programs = new HashMap<>();

    /** Filenames we expect under {@code classpath:fonts/}. Absence is survivable (warn + fallback). */
    private static final Map<String, String> EXPECTED_FILES = buildExpectedFiles();

    private static Map<String, String> buildExpectedFiles() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(key(FAMILY_SANS,  false, false), "fonts/Inter-Regular.ttf");
        m.put(key(FAMILY_SANS,  true,  false), "fonts/Inter-Bold.ttf");
        m.put(key(FAMILY_SANS,  false, true ), "fonts/Inter-Italic.ttf");
        m.put(key(FAMILY_SANS,  true,  true ), "fonts/Inter-BoldItalic.ttf");
        m.put(key(FAMILY_SERIF, false, false), "fonts/SourceSerif4-Regular.ttf");
        m.put(key(FAMILY_SERIF, true,  false), "fonts/SourceSerif4-Bold.ttf");
        m.put(key(FAMILY_SERIF, false, true ), "fonts/SourceSerif4-Italic.ttf");
        m.put(key(FAMILY_SERIF, true,  true ), "fonts/SourceSerif4-BoldItalic.ttf");
        m.put(key(FAMILY_MONO,  false, false), "fonts/JetBrainsMono-Regular.ttf");
        m.put(key(FAMILY_MONO,  true,  false), "fonts/JetBrainsMono-Bold.ttf");
        m.put(key(FAMILY_MONO,  false, true ), "fonts/JetBrainsMono-Italic.ttf");
        m.put(key(FAMILY_MONO,  true,  true ), "fonts/JetBrainsMono-BoldItalic.ttf");
        return Collections.unmodifiableMap(m);
    }

    @PostConstruct
    void loadPrograms() {
        int loaded = 0;
        int missing = 0;
        for (Map.Entry<String, String> entry : EXPECTED_FILES.entrySet()) {
            ClassPathResource resource = new ClassPathResource(entry.getValue());
            if (!resource.exists()) {
                missing++;
                continue;
            }
            try (InputStream in = resource.getInputStream()) {
                byte[] bytes = in.readAllBytes();
                FontProgram program = FontProgramFactory.createFont(bytes, /* cached */ false);
                programs.put(entry.getKey(), program);
                loaded++;
            } catch (IOException e) {
                log.warn("Failed to load font {}: {}", entry.getValue(), e.getMessage());
            }
        }
        if (missing > 0) {
            log.warn("PdfFontRegistry: loaded {}/{} fonts ({} missing). Pixel-parity rendering "
                    + "will fall back to iText defaults until the TTFs are added under "
                    + "src/main/resources/fonts/.", loaded, EXPECTED_FILES.size(), missing);
        } else {
            log.info("PdfFontRegistry: loaded {} font programs.", loaded);
        }
    }

    /**
     * Mint a fresh {@link PdfFont} for the given family variant, bound to the
     * caller's current document. Returns {@code null} if the TTF isn't loaded —
     * callers must handle that by routing through the legacy path.
     */
    public PdfFont createFont(String family, boolean bold, boolean italic) {
        FontProgram program = resolveProgram(family, bold, italic);
        if (program == null) return null;
        return PdfFontFactory.createFont(program, PdfEncodings.IDENTITY_H, EmbeddingStrategy.PREFER_EMBEDDED);
    }

    /** True when every expected TTF loaded — guarantees we can render every weight/style requested. */
    public boolean isFullyLoaded() {
        return programs.size() == EXPECTED_FILES.size();
    }

    /**
     * Find the best {@link FontProgram} for the requested variant. Falls back
     * to the same-family regular, then Inter-Regular, then {@code null}.
     */
    private FontProgram resolveProgram(String family, boolean bold, boolean italic) {
        FontProgram exact = programs.get(key(family, bold, italic));
        if (exact != null) return exact;
        FontProgram regular = programs.get(key(family, false, false));
        if (regular != null) return regular;
        return programs.get(key(FAMILY_SANS, false, false));
    }

    private static String key(String family, boolean bold, boolean italic) {
        return family + "|" + bold + "|" + italic;
    }
}
