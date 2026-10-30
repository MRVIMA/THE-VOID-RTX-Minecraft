/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A whole-look profile for {@code /void profile}: one edition of THE VOID RTX Materials
 * together with one Remix graphics tier. Each edition is found among the installed packs by
 * its name, so a pack works from a folder or a zip of any version.
 */
public enum RemixProfile {
    LOW("materials-lite", QualityPreset.LOW),
    BALANCED("materials-v1.5", QualityPreset.MEDIUM),
    CINEMATIC("materials", QualityPreset.HIGH);

    private static final String PACK_FAMILY = "the-void-rtx-materials";

    private final String packName;
    private final QualityPreset quality;

    RemixProfile(String packName, QualityPreset quality) {
        this.packName = packName;
        this.quality = quality;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public QualityPreset quality() {
        return quality;
    }

    /** Whether a pack id belongs to any edition of the materials pack. */
    public static boolean isMaterialsPack(String packId) {
        return normalize(packId).contains(PACK_FAMILY);
    }

    /** Whether a pack id is this profile's edition. Cinematic is whichever edition is neither of the others. */
    public boolean matches(String packId) {
        if (!isMaterialsPack(packId)) {
            return false;
        }
        String pack = normalize(packId);
        boolean otherEdition = this == CINEMATIC
                && (pack.contains(LOW.packName) || pack.contains(BALANCED.packName));
        return pack.contains(packName) && !otherEdition;
    }

    private static String normalize(String packId) {
        return packId.toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    public static Optional<RemixProfile> byId(String id) {
        return Arrays.stream(values()).filter(profile -> profile.id().equals(id)).findFirst();
    }

    /** Every id, lightest profile first. */
    public static List<String> ids() {
        return Arrays.stream(values()).map(RemixProfile::id).toList();
    }
}
