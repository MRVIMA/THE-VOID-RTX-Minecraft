/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RemixProfileTest {
    private static final String LITE = "file/THE-VOID-RTX-Materials-Lite-v1.5.0.zip";
    private static final String STANDARD = "file/THE-VOID-RTX-Materials-v1.5.0.zip";
    private static final String CINEMATIC_FOLDER = "file/THE VOID RTX Materials";
    private static final String CINEMATIC_ZIP = "file/THE-VOID-RTX-Materials-v1.6.0.zip";
    private static final String OTHER = "file/Xray_Ultimate_26.X_v5.4.4.zip";

    @Test
    void eachEditionMatchesOnlyItsProfile() {
        assertTrue(RemixProfile.LOW.matches(LITE));
        assertFalse(RemixProfile.BALANCED.matches(LITE));
        assertFalse(RemixProfile.CINEMATIC.matches(LITE));

        assertTrue(RemixProfile.BALANCED.matches(STANDARD));
        assertFalse(RemixProfile.LOW.matches(STANDARD));
        assertFalse(RemixProfile.CINEMATIC.matches(STANDARD));

        assertTrue(RemixProfile.CINEMATIC.matches(CINEMATIC_FOLDER));
        assertTrue(RemixProfile.CINEMATIC.matches(CINEMATIC_ZIP));
        assertFalse(RemixProfile.BALANCED.matches(CINEMATIC_ZIP));
    }

    @Test
    void otherPacksAreLeftAlone() {
        assertFalse(RemixProfile.isMaterialsPack(OTHER));
        for (RemixProfile profile : RemixProfile.values()) {
            assertFalse(profile.matches(OTHER));
        }
    }

    @Test
    void qualityRisesWithTheProfile() {
        assertEquals(QualityPreset.LOW, RemixProfile.LOW.quality());
        assertEquals(QualityPreset.MEDIUM, RemixProfile.BALANCED.quality());
        assertEquals(QualityPreset.HIGH, RemixProfile.CINEMATIC.quality());
    }
}
