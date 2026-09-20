package com.xtranslator;

import com.xtranslator.translation.FormatProtector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class FormatProtectorTest {

    @Test
    public void testMinecraftColorsAndFormatSpecifiers() {
        String input = "Hold §eShift§r for %s information (Damage: %d, Rate: %.2f)";
        FormatProtector.ProtectedResult protectedResult = FormatProtector.protect(input);

        // Check that placeholders were extracted
        assertFalse(protectedResult.getPlaceholders().isEmpty());
        assertFalse(protectedResult.getProtectedText().contains("§e"));
        assertFalse(protectedResult.getProtectedText().contains("%s"));

        // Simulate translated text containing translated words and tokens
        String simulatedTranslated = "Giữ ⟦P0⟧Shift⟦P1⟧ để xem thông tin ⟦P2⟧ (Sát thương: ⟦P3⟧, Tỷ lệ: ⟦P4⟧)";
        String restored = protectedResult.restore(simulatedTranslated);

        assertEquals("Giữ §eShift§r để xem thông tin %s (Sát thương: %d, Tỷ lệ: %.2f)", restored);
    }

    @Test
    public void testPositionalSpecifiers() {
        String input = "%1$s has completed the quest: %2$s!";
        FormatProtector.ProtectedResult protectedResult = FormatProtector.protect(input);

        String simulatedTranslated = "⟦P0⟧ đã hoàn thành nhiệm vụ: ⟦P1⟧!";
        String restored = protectedResult.restore(simulatedTranslated);

        assertEquals("%1$s đã hoàn thành nhiệm vụ: %2$s!", restored);
    }
}
