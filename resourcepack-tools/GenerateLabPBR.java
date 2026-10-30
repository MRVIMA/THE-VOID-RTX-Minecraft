import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/**
 * Generates LabPBR 1.3 maps (_n and _s) for vanilla textures, for RTX Remix for
 * Minecraft: every block, every tool and armor item, chests, and worn armor.
 *
 * Each texture is classified by name into a material (wood, stone, iron, gold,
 * diamond...). Tools are split per pixel: vanilla paints every handle with the
 * stick's colours, so those pixels become wood and the rest the head material.
 * Chests are split the same way into wood and a metal latch. Height comes from
 * each texture's brightness, the normal from that height, and roughness,
 * metalness and porosity from the material.
 *
 * Usage:
 *   java GenerateLabPBR.java <minecraft client jar> <resource pack folder> [skip list]
 *
 * The optional skip list names textures (paths under textures/, without .png,
 * one per line) that already have hand-made maps, so they keep them: the RTX mod's
 * own maps for glowing blocks, leaves and ice. Glowing, glass, animated and food
 * textures are skipped by name too. To change a material, edit the tables below
 * and run again; the generator replaces its own maps.
 */
public class GenerateLabPBR {

    /**
     * smoothness: 0 = rough, 1 = mirror. metal: true for metals (LabPBR F0 230+).
     * f0: reflectance of a non-metal, about 0.04 for most. porosity: 0..1, how much
     * the surface darkens when wet. depth: how far dark pixels sink, 0..1.
     * normalStrength: how strongly brightness changes bend the surface.
     * gapFraction: the darkest share of pixels that become deep, rough grooves, such
     * as the gaps between planks (0 = none). faceRelief: how much of the height range
     * the rest of the surface keeps (1 = all of it, near 0 = flat, like honed stone).
     * bevel: width in pixels of a chamfer round the texture's edge, so each block
     * reads as a separate cut piece (0 = none).
     */
    record Material(String name, double smoothness, boolean metal, double f0, double porosity,
                    double depth, double normalStrength, double gapFraction, double faceRelief, int bevel) {
        Material(String name, double smoothness, boolean metal, double f0, double porosity,
                 double depth, double normalStrength) {
            this(name, smoothness, metal, f0, porosity, depth, normalStrength, 0, 1, 0);
        }
    }

    static final Material WOOD = new Material("wood", 0.35, false, 0.04, 0.55, 0.20, 2.0);
    // Planks: recessed gaps between boards, a satin finish on the board faces with
    // shallow grain.
    static final Material PLANKS = new Material("planks", 0.45, false, 0.04, 0.45, 0.40, 3.0, 0.12, 0.25, 0);
    static final Material STRIPPED = new Material("stripped wood", 0.42, false, 0.04, 0.50, 0.15, 1.6);
    static final Material BARK = new Material("bark", 0.20, false, 0.04, 0.70, 0.35, 3.0);
    static final Material STONE = new Material("stone", 0.30, false, 0.04, 0.35, 0.30, 2.5);
    static final Material ORE = new Material("ore", 0.38, false, 0.05, 0.30, 0.35, 2.8);
    static final Material BRICK = new Material("brick", 0.25, false, 0.04, 0.45, 0.45, 3.0);
    // Polished and smooth stone: honed, nearly flat and glossy faces with a chamfered
    // edge, so a wall of it reads as separate cut stone blocks.
    static final Material POLISHED = new Material("polished stone", 0.75, false, 0.04, 0.05, 0.30, 2.5, 0, 0.08, 1);
    static final Material OBSIDIAN = new Material("obsidian", 0.90, false, 0.05, 0.0, 0.10, 1.5);
    static final Material GLAZED = new Material("glazed ceramic", 0.88, false, 0.05, 0.0, 0.08, 1.2);
    static final Material TERRACOTTA = new Material("terracotta", 0.30, false, 0.04, 0.50, 0.15, 1.5);
    static final Material CONCRETE = new Material("concrete", 0.50, false, 0.04, 0.20, 0.05, 1.0);
    static final Material WOOL = new Material("wool", 0.05, false, 0.04, 0.90, 0.25, 2.0);
    static final Material SOIL = new Material("soil", 0.10, false, 0.04, 0.80, 0.35, 2.5);
    static final Material SAND = new Material("sand", 0.15, false, 0.04, 0.70, 0.20, 2.0);
    static final Material SNOW = new Material("snow", 0.30, false, 0.04, 0.0, 0.10, 1.2);
    static final Material ICE = new Material("ice", 0.95, false, 0.02, 0.0, 0.05, 1.0);
    static final Material SOFT = new Material("organic", 0.20, false, 0.04, 0.60, 0.20, 2.0);
    static final Material PLANT = new Material("plant", 0.12, false, 0.03, 0.40, 0.10, 1.5);
    static final Material GENERIC = new Material("generic", 0.30, false, 0.04, 0.30, 0.20, 2.0);
    static final Material IRON = new Material("iron", 0.78, true, 0, 0, 0.25, 2.5);
    static final Material RAW_METAL = new Material("raw metal", 0.40, true, 0, 0, 0.40, 3.0);
    static final Material GOLD = new Material("gold", 0.88, true, 0, 0, 0.20, 2.5);
    static final Material COPPER = new Material("copper", 0.70, true, 0, 0, 0.25, 2.5);
    static final Material PATINA = new Material("copper patina", 0.40, false, 0.04, 0.30, 0.25, 2.5);
    static final Material NETHERITE = new Material("netherite", 0.62, true, 0, 0, 0.35, 3.5);
    static final Material CHAIN = new Material("chainmail", 0.60, true, 0, 0, 0.50, 4.0);
    // Diamond is a gem, not a metal: F0 0.17 is diamond's real reflectance (IOR 2.42).
    static final Material DIAMOND = new Material("diamond", 0.92, false, 0.17, 0, 0.25, 2.5);
    static final Material GEM = new Material("gem", 0.85, false, 0.07, 0, 0.20, 2.0);
    static final Material TURTLE = new Material("turtle shell", 0.65, false, 0.05, 0, 0.30, 3.0);
    static final Material LEATHER = new Material("leather", 0.25, false, 0.04, 0.60, 0.20, 2.0);

    /** How a texture's pixels split between its primary and secondary material. */
    enum Split { NONE, STICK_HANDLE, METAL_LATCH }

    record Rule(Material primary, Material secondary, Split split) {
        Rule(Material primary) { this(primary, null, Split.NONE); }
    }

    /** Names that glow, are see-through, animate in odd ways, or are food: left as they are. */
    static final String[] SKIP_WORDS = {
        "lantern", "torch", "glowstone", "shroomlight", "froglight", "lamp", "magma", "lava", "fire",
        "candle", "beacon", "end_rod", "glow", "respawn_anchor", "crying_obsidian", "sculk", "amethyst",
        "light_0", "light_1", "redstone_ore", "_on", "_lit", "bulb", "conduit", "portal", "spawner", "vault",
        "creaking_heart", "firefly", "water", "glass", "leaves", "destroy_stage", "debug", "slime",
        "honey", "structure_block", "jigsaw", "barrier", "command_block", "apple", "carrot", "melon_slice",
        "beetroot", "potato", "bread", "cookie", "stew", "soup", "pie", "_cooked", "cooked_", "beef",
        "chicken", "porkchop", "mutton", "rabbit", "cod", "salmon", "pufferfish", "tropical_fish", "berries",
        "chorus", "spider_eye", "rotten", "cake", "potion", "bottle", "experience", "ender_eye", "ender_pearl",
        "blaze", "nether_star", "dragon", "heart_of_the_sea", "totem", "music_disc", "banner_pattern",
        "spawn_egg", "template", "_dye", "ink_sac", "bundle", "filled_map", "empty_map", "_book", "paper",
        "firework", "_egg", "bone_meal", "sugar", "gunpowder", "string", "feather", "ghast", "phantom", "scute"
    };

    static final Map<String, Material> ARMOR = new LinkedHashMap<>();
    static {
        ARMOR.put("diamond", DIAMOND);
        ARMOR.put("gold", GOLD);
        ARMOR.put("iron", IRON);
        ARMOR.put("copper", COPPER);
        ARMOR.put("netherite", NETHERITE);
        ARMOR.put("chainmail", CHAIN);
        ARMOR.put("turtle_scute", TURTLE);
        ARMOR.put("leather", LEATHER);
        ARMOR.put("leather_overlay", LEATHER);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: java GenerateLabPBR.java <minecraft client jar> <resource pack folder> [skip list]");
            System.exit(2);
        }
        Set<String> keep = new HashSet<>();
        if (args.length > 2) {
            for (String line : Files.readAllLines(Path.of(args[2]))) {
                if (!line.isBlank()) keep.add(line.strip());
            }
        }
        Path textures = Path.of(args[1], "assets", "minecraft", "textures");
        Map<String, Integer> counts = new TreeMap<>();
        try (ZipFile jar = new ZipFile(args[0])) {
            Set<Integer> stickPalette = palette(jar, "item/stick");
            List<String> paths = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> en = jar.entries(); en.hasMoreElements(); ) {
                String n = en.nextElement().getName();
                if (n.startsWith("assets/minecraft/textures/") && n.endsWith(".png")) {
                    paths.add(n.substring("assets/minecraft/textures/".length(), n.length() - 4));
                }
            }
            paths.sort(null);
            for (String path : paths) {
                if (keep.contains(path) || path.endsWith("_n") || path.endsWith("_s")) continue;
                if (jar.getEntry("assets/minecraft/textures/" + path + ".png.mcmeta") != null) continue;
                Rule rule = classify(path);
                if (rule == null) continue;
                BufferedImage albedo;
                try (InputStream in = jar.getInputStream(jar.getEntry("assets/minecraft/textures/" + path + ".png"))) {
                    albedo = ImageIO.read(in);
                }
                if (albedo == null) continue;
                // A see-through block that was not named is a plant: cross models and crops.
                if (path.startsWith("block/") && rule.primary == GENERIC && hasTransparency(albedo)) {
                    rule = new Rule(PLANT);
                }
                Material[][] mats = pixelMaterials(albedo, rule, stickPalette);
                Path base = textures.resolve(path);
                Files.createDirectories(base.getParent());
                Shape shape = shape(albedo, rule.primary);
                ImageIO.write(normalMap(shape, mats), "png", Path.of(base + "_n.png").toFile());
                ImageIO.write(specularMap(shape, mats), "png", Path.of(base + "_s.png").toFile());
                counts.merge(path.substring(0, path.indexOf('/')) + " / " + rule.primary.name, 1, Integer::sum);
            }
        }
        try (ZipFile jar = new ZipFile(args[0])) {
            counts.put("block / clear glass (frame removed)", writeClearGlass(jar, textures));
            counts.put("block / natural ground (hi-res, generated)", writeNaturalGround(jar, textures));
        }
        int total = 0;
        for (Map.Entry<String, Integer> c : counts.entrySet()) {
            System.out.printf("%5d  %s%n", c.getValue(), c.getKey());
            total += c.getValue();
        }
        System.out.println(total + " textures done");
    }

    // ---- Natural ground --------------------------------------------------------

    /** Ground textures are this many pixels wide; vanilla's are 16. */
    static final int GROUND_SIZE = 128;
    static final int PLANT_SIZE = 64;
    /** How tall the 3D grass on a grass block stands, in 1/16ths of a block. */
    static final int TUFT_HEIGHT = 6;
    /** The biome grass colours are scaled by these: lower saturation and value read as natural. */
    static final double GRASS_COLOUR_SATURATION = 0.72;
    static final double GRASS_COLOUR_BRIGHTNESS = 0.78;
    /** LabPBR blue for thin blades, 65+ being subsurface scattering; 0 keeps them opaque. The mod scatters with one fixed pale colour that ignores the biome tint, which bleaches grass. */
    static final int BLADE_SUBSURFACE = 0;

    static final Material GRASS = new Material("grass", 0.08, false, 0.02, 0.50, 0.30, 2.5);

    /**
     * Replaces grass and bare ground with original generated textures, eight times
     * vanilla's resolution, in the spirit of realism packs: soil with pebbles and
     * roots, dense blades, 3D grass on every grass block and muted biome colours.
     * Everything here is drawn from seeded noise, so runs are repeatable.
     */
    static int writeNaturalGround(ZipFile jar, Path textures) throws IOException {
        int n = 0;
        int[] dirtA = {96, 71, 50}, dirtB = {52, 37, 26};
        BufferedImage dirt = soil(11, dirtA, dirtB, 0.10);
        n += writeGround(textures, "dirt", dirt, SOIL);
        n += writeGround(textures, "coarse_dirt", soil(12, new int[] {104, 84, 64}, new int[] {48, 36, 28}, 0.45), SOIL);
        n += writeGround(textures, "rooted_dirt", rooted(soil(13, dirtA, dirtB, 0.15), 13), SOIL);
        n += writeGround(textures, "podzol_top", soil(14, new int[] {82, 56, 34}, new int[] {38, 26, 18}, 0.05), SOIL);
        n += writeGround(textures, "podzol_side", darkTop(soil(15, dirtA, dirtB, 0.12), 0.45, 22), SOIL);
        n += writeGround(textures, "dirt_path_top", soil(16, new int[] {132, 104, 72}, new int[] {90, 68, 46}, 0.20), SAND);
        n += writeGround(textures, "dirt_path_side", darkTop(soil(17, dirtA, dirtB, 0.12), 0.65, 10), SOIL);

        n += writeGround(textures, "grass_block_top", grassTop(21), GRASS);
        n += writeGround(textures, "grass_block_side", darkTop(soil(22, dirtA, dirtB, 0.10), 0.45, 26), SOIL);
        n += writeBlades(textures, "grass_block_side_overlay", sideFringe(23));
        n += writeBlades(textures, "short_grass", blades(31, 60, 0.55, false));
        n += writeBlades(textures, "tall_grass_bottom", blades(32, 70, 1.00, true));
        n += writeBlades(textures, "tall_grass_top", blades(33, 50, 0.85, false));
        n += writeBlades(textures, "void_grass_tuft", blades(34, 70, 0.95, false));
        n += writeFarm(textures);
        n += writeLava(textures);
        n += writeLeaves(jar, textures);
        n += writeSplashes(textures);
        n += writeTimberAndStone(jar, textures);

        writeGrassModel(textures.getParent());
        for (String map : new String[] {"colormap/grass", "colormap/foliage"}) {
            BufferedImage colormap = read(jar, map);
            if (colormap != null) {
                write(mutedColours(colormap), textures.resolve(map + ".png"));
                n++;
            }
        }
        return n;
    }

    // ---- Timber and stone -------------------------------------------------------

    static final String[] TIMBERS = {
        "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak",
    };
    static final int[][] BOOK_COLOURS = {
        {168, 38, 36}, {38, 68, 132}, {48, 112, 62}, {28, 122, 122},
        {192, 158, 48}, {216, 206, 176}, {104, 44, 92}, {190, 92, 36},
    };
    /** Texture width for timber and stone; pixel measures below are scaled by U from 128. */
    static final int HERO_SIZE = Integer.getInteger("heroSize", 256);
    static final double U = HERO_SIZE / 128.0;

    /** Height steps shrink per pixel as textures grow, so normal maps need more strength. */
    static Material hero(Material m) {
        return new Material(m.name, m.smoothness, m.metal, m.f0, m.porosity, m.depth,
                m.normalStrength * Math.sqrt(U), m.gapFraction, m.faceRelief, m.bevel);
    }

    static final Material SATIN_PLANKS = new Material("planks", 0.30, false, 0.04, 0.45, 0.16, 2.0, 0.05, 0.3, 0);
    static final Material BOOKS = new Material("books", 0.22, false, 0.04, 0.55, 0.45, 3.0);

    /**
     * Redraws planks, logs, bookshelves, cobblestone, stone and stone bricks at eight
     * times vanilla's detail, each timber keeping its vanilla colour.
     */
    static int writeTimberAndStone(ZipFile jar, Path textures) throws IOException {
        int n = 0;
        for (int i = 0; i < TIMBERS.length; i++) {
            String wood = TIMBERS[i];
            long seed = 300L + i * 10;
            BufferedImage planks = read(jar, "block/" + wood + "_planks");
            BufferedImage log = read(jar, "block/" + wood + "_log");
            BufferedImage top = read(jar, "block/" + wood + "_log_top");
            if (planks != null) n += writeGround(textures, wood + "_planks", planks(seed, meanColour(planks)), hero(SATIN_PLANKS));
            if (log != null) n += writeGround(textures, wood + "_log", bark(seed + 1, meanColour(log)), hero(BARK));
            if (log != null && top != null) {
                n += writeGround(textures, wood + "_log_top", logEnd(seed + 2, centreColour(top), meanColour(log)), hero(WOOD));
            }
        }
        BufferedImage oak = read(jar, "block/oak_planks");
        if (oak != null) n += writeGround(textures, "bookshelf", bookshelf(420, meanColour(oak)), hero(BOOKS));
        for (Object[] rock : new Object[][] {{"cobblestone", 7}, {"mossy_cobblestone", 7}}) {
            BufferedImage img = read(jar, "block/" + rock[0]);
            if (img != null) n += writeGround(textures, (String) rock[0], cobble(430, (int) rock[1], meanColour(img)), hero(STONE));
        }
        BufferedImage stone = read(jar, "block/stone");
        if (stone != null) n += writeGround(textures, "stone", rock(440, meanColour(stone)), hero(STONE));
        BufferedImage bricks = read(jar, "block/stone_bricks");
        if (bricks != null) n += writeGround(textures, "stone_bricks", stoneBricks(450, meanColour(bricks)), hero(BRICK));
        return n;
    }

    static double[] centreColour(BufferedImage img) {
        int s = img.getWidth();
        return meanColour(img.getSubimage(s / 4, s / 4, s / 2, s / 2));
    }

    static int[] shade(double[] colour, double k) {
        return new int[] {to8(colour[0] * k), to8(colour[1] * k), to8(colour[2] * k)};
    }

    /** Four boards with staggered end joints and flowing grain. */
    static BufferedImage planks(long seed, double[] colour) {
        int s = HERO_SIZE, boards = 4, board = s / boards;
        double[][] grain = noise(s, 24, 6, seed), wave = noise(s, 4, 3, seed + 1), fine = noise(s, 48, 4, seed + 2);
        int[] light = shade(colour, 1.2), dark = shade(colour, 0.72), gap = shade(colour, 0.42);
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        java.util.Random rnd = new java.util.Random(seed);
        double[] tone = new double[boards];
        int[] joint = new int[boards];
        for (int b = 0; b < boards; b++) {
            tone[b] = (rnd.nextDouble() - 0.5) * 0.28;
            joint[b] = s / 4 + rnd.nextInt(s / 2);
        }
        for (int y = 0; y < s; y++) {
            // Boards start mid-tile, so no gap falls on the block edge where parallax runs off the sprite.
            int shifted = (y + board / 2) % s, b = shifted / board, inBoard = shifted % board;
            for (int x = 0; x < s; x++) {
                if (inBoard < 2 * U || Math.floorMod(x - joint[b], s) < 2 * U) {
                    img.setRGB(x, y, mix(gap, gap, 0));
                    continue;
                }
                int gy = Math.floorMod(y + (int) ((wave[y][x] - 0.5) * 9 * U), s);
                int gx = (int) (x * 0.1 + b * 17) % s;
                double t = 0.5 + tone[b] + (grain[gy][gx] - 0.5) * 1.3 + (fine[y][(int) (x * 0.25) % s] - 0.5) * 0.3;
                // Boards catch light along the top edge and fall away at the bottom.
                t += inBoard < 4 * U ? 0.12 : inBoard > board - 3 * U ? -0.2 : 0;
                img.setRGB(x, y, mix(light, dark, t));
            }
        }
        return img;
    }

    /** Deep bark: long plates running up the trunk with dark furrows between them. */
    static BufferedImage bark(long seed, double[] colour) {
        int s = HERO_SIZE;
        double[][] ridges = noise(s, 18, 6, seed), fine = noise(s, 40, 5, seed + 1), wander = noise(s, 5, 4, seed + 2);
        int[] light = shade(colour, 1.35), dark = shade(colour, 0.34);
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                int rx = Math.floorMod(x + (int) ((wander[y][x] - 0.5) * 18 * U), s);
                double r = ridges[(int) (y * 0.16) % s][rx];
                double t = (r - 0.38) * 3.4 + (fine[y][x] - 0.5) * 0.7;
                img.setRGB(x, y, mix(light, dark, t));
            }
        }
        return img;
    }

    /** A sawn log end: growth rings around the pith, inside a rim of bark. */
    static BufferedImage logEnd(long seed, double[] heart, double[] barkColour) {
        int s = HERO_SIZE;
        double[][] warp = noise(s, 5, 4, seed), fine = noise(s, 40, 5, seed + 1);
        int[] light = shade(heart, 1.18), dark = shade(heart, 0.66), rimLight = shade(barkColour, 1.1), rimDark = shade(barkColour, 0.4);
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                double dx = x - s / 2.0 + 0.5, dy = y - s / 2.0 + 0.5;
                double edge = Math.max(Math.abs(dx), Math.abs(dy));
                if (edge > s / 2.0 - 9 * U + (warp[y][x] - 0.5) * 8 * U) {
                    img.setRGB(x, y, mix(rimLight, rimDark, (fine[y][x] - 0.5) * 3 + 0.5));
                    continue;
                }
                double r = Math.hypot(dx, dy) * (1 + (warp[y][x] - 0.5) * 0.5);
                img.setRGB(x, y, mix(light, dark, 0.5 + Math.sin(r / U * 1.05) * 0.38 + (fine[y][x] - 0.5) * 0.5));
            }
        }
        return img;
    }

    /** Two shelves of leather-bound books in a timber frame, with dark space behind. */
    static BufferedImage bookshelf(long seed, double[] timber) {
        int s = HERO_SIZE, frame = (int) (7 * U);
        BufferedImage img = planks(seed, timber);
        java.util.Random rnd = new java.util.Random(seed);
        int[] rowTop = {frame, s / 2 + frame / 2}, depth = shade(timber, 0.12);
        int rowHeight = s / 2 - frame - frame / 2;
        double[][] wear = noise(s, 40, 5, seed + 1);
        for (int row = 0; row < 2; row++) {
            int top = rowTop[row];
            for (int y = top; y < top + rowHeight; y++) {
                for (int x = frame; x < s - frame; x++) img.setRGB(x, y, mix(depth, depth, 0));
            }
            int x = frame + (int) U;
            while (x < s - frame - 5 * U) {
                int width = Math.min((int) ((7 + rnd.nextInt(7)) * U), s - frame - x);
                int height = rowHeight - (int) ((2 + rnd.nextInt(10)) * U);
                int[] colour = BOOK_COLOURS[rnd.nextInt(BOOK_COLOURS.length)];
                int band = (int) ((5 + rnd.nextInt(3)) * U), giltWidth = (int) (2 * U);
                for (int dx = 0; dx < width - 1; dx++) {
                    // A rounded spine: bright down the middle, falling away to the edges.
                    double round = 0.5 + 0.62 * Math.sin(Math.PI * (dx + 0.5) / (width - 1));
                    for (int dy = 0; dy < height; dy++) {
                        double k = round * (0.86 + wear[top + rowHeight - 1 - dy][x + dx] * 0.28);
                        boolean gilt = dy >= band && dy < band + giltWidth || dy > height - band - giltWidth && dy <= height - band;
                        int[] c = gilt ? new int[] {214, 182, 96} : colour;
                        img.setRGB(x + dx, top + rowHeight - 1 - dy,
                                0xFF000000 | to8(c[0] * k) << 16 | to8(c[1] * k) << 8 | to8(c[2] * k));
                    }
                }
                x += width;
            }
        }
        return img;
    }

    /** Rounded fieldstones set in dark mortar, on a jittered grid that tiles. */
    static BufferedImage cobble(long seed, int cells, double[] colour) {
        int s = HERO_SIZE;
        double[][] fine = noise(s, 32, 6, seed);
        java.util.Random rnd = new java.util.Random(seed + 1);
        double[][][] centre = new double[cells][cells][3];
        for (double[][] row : centre) {
            for (double[] c : row) {
                c[0] = rnd.nextDouble();
                c[1] = rnd.nextDouble();
                c[2] = 0.72 + rnd.nextDouble() * 0.56;
            }
        }
        double cell = s / (double) cells;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        int[] mortar = shade(colour, 0.26);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                double first = 1e9, second = 1e9, tone = 1;
                int cx = (int) (x / cell), cy = (int) (y / cell);
                for (int j = -1; j <= 1; j++) {
                    for (int i = -1; i <= 1; i++) {
                        double[] c = centre[Math.floorMod(cy + j, cells)][Math.floorMod(cx + i, cells)];
                        double d = Math.hypot((cx + i + c[0]) * cell - x, (cy + j + c[1]) * cell - y);
                        if (d < first) {
                            second = first;
                            first = d;
                            tone = c[2];
                        } else if (d < second) {
                            second = d;
                        }
                    }
                }
                double edge = second - first;
                if (edge < 2.2 * U) {
                    img.setRGB(x, y, mix(mortar, mortar, 0));
                    continue;
                }
                double dome = Math.min(1, edge / (9.0 * U));
                img.setRGB(x, y, mix(shade(colour, tone * 1.25), shade(colour, tone * 0.5),
                        0.15 + dome * 0.6 + (fine[y][x] - 0.5) * 1.4));
            }
        }
        return img;
    }

    /** Weathered stone: broad mottling crossed by fine dark veins. */
    static BufferedImage rock(long seed, double[] colour) {
        int s = HERO_SIZE;
        double[][] large = noise(s, 5, 7, seed), veins = noise(s, 10, 4, seed + 1), fine = noise(s, 40, 5, seed + 2);
        int[] light = shade(colour, 1.22), dark = shade(colour, 0.68), crack = shade(colour, 0.38);
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                double vein = Math.abs(veins[y][x] - 0.5);
                int p = mix(light, dark, (large[y][x] - 0.5) * 2.6 + 0.5 + (fine[y][x] - 0.5) * 0.9);
                img.setRGB(x, y, vein < 0.012 ? mixRgb(p, crack[0] << 16 | crack[1] << 8 | crack[2], 0.7) : p);
            }
        }
        return img;
    }

    /** Dressed stone blocks in staggered courses with recessed mortar. */
    static BufferedImage stoneBricks(long seed, double[] colour) {
        int s = HERO_SIZE, courses = 2, height = s / courses, width = s / 2;
        double[][] large = noise(s, 6, 6, seed), fine = noise(s, 40, 5, seed + 1);
        int[] mortar = shade(colour, 0.3);
        java.util.Random rnd = new java.util.Random(seed);
        double[] tone = new double[8];
        for (int i = 0; i < tone.length; i++) tone[i] = 0.85 + rnd.nextDouble() * 0.3;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < s; y++) {
            int course = y / height, inCourse = y % height;
            for (int x = 0; x < s; x++) {
                int sx = Math.floorMod(x + course * width / 2, s), brick = sx / width, inBrick = sx % width;
                if (inCourse < 3 * U || inBrick < 3 * U) {
                    img.setRGB(x, y, mix(mortar, mortar, 0));
                    continue;
                }
                double k = tone[course * 2 + brick];
                img.setRGB(x, y, mix(shade(colour, k * 1.2), shade(colour, k * 0.7),
                        (large[y][x] - 0.5) * 2.2 + 0.5 + (fine[y][x] - 0.5) * 0.9));
            }
        }
        return img;
    }

    // ---- Rain splashes ---------------------------------------------------------

    /**
     * The four shapes a raindrop's splash picks from: crowns of water with droplets
     * flying off. Minecraft spawns these where rain lands; they draw cut out, so
     * every pixel is either solid water or nothing.
     */
    static int writeSplashes(Path textures) throws IOException {
        //             seed, droplets, crown width, crown height
        double[][] shapes = {{1, 7, 0.34, 0.30}, {2, 10, 0.42, 0.55}, {3, 9, 0.46, 0.38}, {4, 14, 0.48, 0.70}};
        for (int i = 0; i < shapes.length; i++) {
            write(splash((long) shapes[i][0], (int) shapes[i][1], shapes[i][2], shapes[i][3]),
                    textures.resolve("particle/splash_" + i + ".png"));
        }
        return shapes.length;
    }

    static BufferedImage splash(long seed, int droplets, double width, double height) {
        int s = 32;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        java.util.Random rnd = new java.util.Random(seed * 977);
        double cx = s / 2.0, base = s - 4;
        // The crown: a low ring of thrown-up water, thickest at the sides.
        for (double a = 0; a < Math.PI; a += 0.02) {
            double x = cx - Math.cos(a) * width * s;
            double rise = Math.sin(a) * Math.sin(a) * 0.35 + 0.25 + Math.sin(a * 7 + seed) * 0.12;
            for (double t = 0; t < rise * height * s; t += 0.5) {
                splashPixel(img, x, base - t, 0.75 + 0.25 * t / (rise * height * s));
            }
        }
        // Droplets flung up and out from the rim, smaller the further they fly.
        for (int i = 0; i < droplets; i++) {
            double a = rnd.nextDouble() * Math.PI, reach = 0.5 + rnd.nextDouble() * 0.6;
            double x = cx - Math.cos(a) * width * s * (0.8 + reach * 0.5);
            double y = base - height * s * reach * (0.5 + Math.sin(a) * 0.6);
            int r = reach > 0.85 ? 0 : 1;
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (dx * dx + dy * dy <= r * r + 1) splashPixel(img, x + dx, y + dy, 0.85 + dy * -0.1);
                }
            }
        }
        return img;
    }

    static void splashPixel(BufferedImage img, double x, double y, double light) {
        int px = (int) Math.round(x), py = (int) Math.round(y);
        if (px < 0 || py < 0 || px >= img.getWidth() || py >= img.getHeight()) return;
        img.setRGB(px, py, mix(new int[] {238, 244, 250}, new int[] {150, 172, 196}, light));
    }

    // ---- Leaves ----------------------------------------------------------------

    /** Species, and the size of one leaf in pixels of the 64-pixel texture. */
    static final Object[][] LEAVES = {
        {"oak_leaves", 9, 3.0}, {"birch_leaves", 8, 2.6}, {"jungle_leaves", 12, 3.4},
        {"acacia_leaves", 7, 2.2}, {"dark_oak_leaves", 10, 3.2}, {"mangrove_leaves", 8, 2.4},
        {"cherry_leaves", 7, 2.8}, {"azalea_leaves", 7, 2.6}, {"pale_oak_leaves", 9, 3.0},
        {"orange_poplar_leaves", 8, 2.8}, {"red_poplar_leaves", 8, 2.8}, {"yellow_poplar_leaves", 8, 2.8},
    };
    /** How many leaves a texture holds; fewer leave bigger gaps of sky. */
    static final int LEAF_COUNT = 150;
    static final int NEEDLE_COUNT = 300;

    /**
     * Redraws tree leaves as many small pointed leaves, keeping each species' vanilla
     * colour (grey for the biome-tinted ones). Only the colour texture changes:
     * NVIDIA's maps for leaves carry their glow-through subsurface scattering and
     * tile over the larger texture, so they are left in place.
     */
    static int writeLeaves(ZipFile jar, Path textures) throws IOException {
        int n = 0;
        for (int i = 0; i < LEAVES.length; i++) {
            String name = (String) LEAVES[i][0];
            BufferedImage vanilla = read(jar, "block/" + name);
            if (vanilla == null) continue;
            write(leafTexture(200L + i, meanColour(vanilla), (int) LEAVES[i][1], (double) LEAVES[i][2], LEAF_COUNT),
                    textures.resolve("block/" + name + ".png"));
            n++;
        }
        BufferedImage spruce = read(jar, "block/spruce_leaves");
        if (spruce != null) {
            write(leafTexture(230, meanColour(spruce), 7, 0.8, NEEDLE_COUNT), textures.resolve("block/spruce_leaves.png"));
            n++;
        }
        return n;
    }

    static double[] meanColour(BufferedImage img) {
        double r = 0, g = 0, b = 0;
        int count = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getRGB(x, y);
                if ((p >>> 24) < 128) continue;
                r += (p >> 16) & 255;
                g += (p >> 8) & 255;
                b += p & 255;
                count++;
            }
        }
        return new double[] {r / count, g / count, b / count};
    }

    static BufferedImage leafTexture(long seed, double[] colour, int length, double width, int count) {
        int s = PLANT_SIZE;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        java.util.Random rnd = new java.util.Random(seed);
        for (int i = 0; i < count; i++) {
            double x = rnd.nextInt(s), y = rnd.nextInt(s), angle = rnd.nextDouble() * Math.PI * 2;
            double shade = 0.62 + rnd.nextDouble() * 0.62;
            int len = length + rnd.nextInt(Math.max(1, length / 3));
            double cos = Math.cos(angle), sin = Math.sin(angle);
            for (int t = 0; t <= len; t++) {
                double f = t / (double) len;
                double half = width * Math.sin(Math.PI * Math.pow(f, 0.7));
                for (double w = -half; w <= half; w += 0.5) {
                    int px = Math.floorMod((int) Math.round(x + cos * t - sin * w), s);
                    int py = Math.floorMod((int) Math.round(y + sin * t + cos * w), s);
                    // One lit side and a darker midrib make each leaf read as a separate shape.
                    double k = shade * (w > 0 ? 1.1 : 0.9) * (Math.abs(w) < 0.6 && width > 1 ? 0.8 : 1);
                    img.setRGB(px, py, 0xFF000000 | to8(colour[0] * k) << 16 | to8(colour[1] * k) << 8 | to8(colour[2] * k));
                }
            }
        }
        return img;
    }

    static int writeGround(Path textures, String name, BufferedImage img, Material m) throws IOException {
        Path base = textures.resolve("block/" + name);
        write(img, Path.of(base + ".png"));
        Material[][] mats = pixelMaterials(img, new Rule(m), Set.of());
        Shape shape = shape(img, m);
        write(normalMap(shape, mats), Path.of(base + "_n.png"));
        write(specularMap(shape, mats), Path.of(base + "_s.png"));
        return 1;
    }

    /** As {@link #writeGround}, for thin cut-out blades that let light through. */
    static int writeBlades(Path textures, String name, BufferedImage img) throws IOException {
        writeGround(textures, name, img, PLANT);
        Path s = textures.resolve("block/" + name + "_s.png");
        BufferedImage spec = ImageIO.read(s.toFile());
        for (int y = 0; y < spec.getHeight(); y++) {
            for (int x = 0; x < spec.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) < 8) continue;
                if (BLADE_SUBSURFACE >= 65) spec.setRGB(x, y, (spec.getRGB(x, y) & 0xFFFFFF00) | BLADE_SUBSURFACE);
            }
        }
        write(spec, s);
        return 1;
    }

    /**
     * Tuft layouts: per variant a height change, then per plane {z, x from, x to,
     * angle}. With one layout on every block the grass lines up in rows like a crop,
     * so the blockstate picks among these at random. Each plane has one face, since
     * the path tracer draws both sides of it.
     */
    static final double[][][] TUFT_LAYOUTS = {
        {{0}, {8, 0, 16, 0}, {8, 0, 16, 45}, {8, 0, 16, -45}},
        {{1}, {4, 1, 13, 22.5}, {11, 3, 16, -22.5}, {7, 2, 14, 45}},
        {{-1}, {12, 0, 11, -45}, {5, 5, 16, 22.5}, {9, 1, 12, 0}},
        {{2}, {3, 3, 15, 0}, {13, 2, 14, 45}, {8, 0, 12, -22.5}},
        {{-1}, {6, 4, 16, -45}, {10, 0, 10, 22.5}},
    };

    /** Writes the grass block's models and the blockstate that picks among them at random. */
    static void writeGrassModel(Path assets) throws IOException {
        StringBuilder variants = new StringBuilder();
        for (int v = 0; v < TUFT_LAYOUTS.length; v++) {
            String name = v == 0 ? "grass_block" : "grass_block_tuft_" + v;
            writeGrassVariant(assets.resolve("models/block/" + name + ".json"), TUFT_LAYOUTS[v]);
            for (int y = 0; y < 360; y += 90) {
                if (variants.length() > 0) variants.append(",\n");
                variants.append("      { \"model\": \"minecraft:block/").append(name).append('"')
                        .append(y == 0 ? "" : ", \"y\": " + y).append(" }");
            }
        }
        writeText(assets.resolve("blockstates/grass_block.json"), "{\n  \"variants\": {\n    \"snowy=false\": [\n"
                + variants + "\n    ],\n    \"snowy=true\": { \"model\": \"minecraft:block/grass_block_snow\" }\n  }\n}\n");
    }

    static void writeGrassVariant(Path file, double[][] layout) throws IOException {
        String plane = """
                    {
                      "from": [%s, 16, %s], "to": [%s, %d, %s],
                      "rotation": { "origin": [8, 16, %s], "axis": "y", "angle": %s, "rescale": true },
                      "faces": { "north": { "uv": [%s, 0, %s, 16], "texture": "#tuft", "tintindex": 0 } }
                    }""";
        int top = 16 + TUFT_HEIGHT + (int) layout[0][0];
        StringBuilder planes = new StringBuilder();
        for (int i = 1; i < layout.length; i++) {
            double[] p = layout[i];
            String z = fmt(p[0]);
            // Every other plane mirrors the texture, so neighbouring blades don't match.
            String u0 = i % 2 == 0 ? "16" : "0", u1 = i % 2 == 0 ? "0" : "16";
            if (i > 1) planes.append(",\n");
            planes.append(String.format(plane, fmt(p[1]), z, fmt(p[2]), top, z, z, fmt(p[3]), u0, u1));
        }
        writeText(file, """
                {
                  "parent": "block/block",
                  "textures": {
                    "particle": "block/dirt",
                    "bottom": "block/dirt",
                    "top": "block/grass_block_top",
                    "side": "block/grass_block_side",
                    "overlay": "block/grass_block_side_overlay",
                    "tuft": "block/void_grass_tuft"
                  },
                  "elements": [
                    {
                      "from": [0, 0, 0], "to": [16, 16, 16],
                      "faces": {
                        "down": { "uv": [0, 0, 16, 16], "texture": "#bottom", "cullface": "down" },
                        "up": { "uv": [0, 0, 16, 16], "texture": "#top", "cullface": "up", "tintindex": 0 },
                        "north": { "uv": [0, 0, 16, 16], "texture": "#side", "cullface": "north" },
                        "south": { "uv": [0, 0, 16, 16], "texture": "#side", "cullface": "south" },
                        "west": { "uv": [0, 0, 16, 16], "texture": "#side", "cullface": "west" },
                        "east": { "uv": [0, 0, 16, 16], "texture": "#side", "cullface": "east" }
                      }
                    },
                    {
                      "from": [0, 0, 0], "to": [16, 16, 16],
                      "faces": {
                        "north": { "uv": [0, 0, 16, 16], "texture": "#overlay", "tintindex": 0, "cullface": "north" },
                        "south": { "uv": [0, 0, 16, 16], "texture": "#overlay", "tintindex": 0, "cullface": "south" },
                        "west": { "uv": [0, 0, 16, 16], "texture": "#overlay", "tintindex": 0, "cullface": "west" },
                        "east": { "uv": [0, 0, 16, 16], "texture": "#overlay", "tintindex": 0, "cullface": "east" }
                      }
                    },
                """ + planes + """

                  ]
                }
                """);
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? Integer.toString((int) v) : Double.toString(v);
    }

    static BufferedImage mutedColours(BufferedImage map) {
        BufferedImage out = new BufferedImage(map.getWidth(), map.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < map.getHeight(); y++) {
            for (int x = 0; x < map.getWidth(); x++) {
                int p = map.getRGB(x, y);
                double r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                double grey = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                r = (grey + (r - grey) * GRASS_COLOUR_SATURATION) * GRASS_COLOUR_BRIGHTNESS;
                g = (grey + (g - grey) * GRASS_COLOUR_SATURATION) * GRASS_COLOUR_BRIGHTNESS;
                b = (grey + (b - grey) * GRASS_COLOUR_SATURATION) * GRASS_COLOUR_BRIGHTNESS;
                out.setRGB(x, y, (p & 0xFF000000) | (to8(r) << 16) | (to8(g) << 8) | to8(b));
            }
        }
        return out;
    }

    /** Seamless fractal noise, 0..1, so a texture tiles without a visible edge. */
    static double[][] noise(int size, int cells, int octaves, long seed) {
        double[][] out = new double[size][size];
        double amp = 1, total = 0;
        for (int o = 0; o < octaves; o++) {
            int c = cells << o;
            java.util.Random rnd = new java.util.Random(seed + o * 7919L);
            double[][] lattice = new double[c][c];
            for (double[] row : lattice) for (int i = 0; i < c; i++) row[i] = rnd.nextDouble();
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    double fx = x * (double) c / size, fy = y * (double) c / size;
                    int ix = (int) fx, iy = (int) fy;
                    double tx = smooth(fx - ix), ty = smooth(fy - iy);
                    double a = lattice[iy % c][ix % c], b = lattice[iy % c][(ix + 1) % c];
                    double d = lattice[(iy + 1) % c][ix % c], e = lattice[(iy + 1) % c][(ix + 1) % c];
                    out[y][x] += amp * ((a + (b - a) * tx) * (1 - ty) + (d + (e - d) * tx) * ty);
                }
            }
            total += amp;
            amp *= 0.55;
        }
        for (double[] row : out) for (int x = 0; x < size; x++) row[x] /= total;
        return out;
    }

    static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }

    static int mix(int[] a, int[] b, double t) {
        t = clamp01(t);
        return 0xFF000000 | to8(b[0] + (a[0] - b[0]) * t) << 16 | to8(b[1] + (a[1] - b[1]) * t) << 8
                | to8(b[2] + (a[2] - b[2]) * t);
    }

    /** Soil: layered noise between two colours with pebbles scattered through it. */
    static BufferedImage soil(long seed, int[] light, int[] dark, double pebbles) {
        int s = GROUND_SIZE;
        double[][] large = noise(s, 5, 5, seed), fine = noise(s, 32, 2, seed + 1);
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                img.setRGB(x, y, mix(light, dark, (large[y][x] - 0.5) * 2.4 + 0.5 + (fine[y][x] - 0.5) * 0.9));
            }
        }
        java.util.Random rnd = new java.util.Random(seed + 2);
        // Small dull stones in the soil's own hue: lumpy outlines, lit from one side.
        double[][] lumps = noise(s, 24, 2, seed + 3);
        int count = (int) (pebbles * 500);
        for (int i = 0; i < count; i++) {
            int cx = rnd.nextInt(s), cy = rnd.nextInt(s), r = 1 + rnd.nextInt(3);
            double shade = 0.75 + rnd.nextDouble() * 0.5;
            for (int dy = -r - 1; dy <= r + 1; dy++) {
                for (int dx = -r - 1; dx <= r + 1; dx++) {
                    int px = Math.floorMod(cx + dx, s), py = Math.floorMod(cy + dy, s);
                    double d = (dx * dx + dy * dy) / (double) (r * r);
                    if (d > 0.6 + lumps[py][px] * 0.9) continue;
                    int p = img.getRGB(px, py);
                    double k = shade * (dy < 0 ? 1.12 : 0.88);
                    img.setRGB(px, py, 0xFF000000 | to8(((p >> 16) & 0xFF) * k * 1.05) << 16
                            | to8(((p >> 8) & 0xFF) * k * 1.05) << 8 | to8((p & 0xFF) * k));
                }
            }
        }
        return img;
    }

    static BufferedImage rooted(BufferedImage img, long seed) {
        int s = img.getWidth();
        java.util.Random rnd = new java.util.Random(seed);
        for (int i = 0; i < 26; i++) {
            double x = rnd.nextInt(s), y = rnd.nextInt(s), a = rnd.nextDouble() * Math.PI * 2;
            int len = 20 + rnd.nextInt(50);
            for (int t = 0; t < len; t++) {
                int px = Math.floorMod((int) x, s), py = Math.floorMod((int) y, s), p = img.getRGB(px, py);
                img.setRGB(px, py, 0xFF000000 | to8((((p >> 16) & 0xFF) + 150) * 0.5) << 16
                        | to8((((p >> 8) & 0xFF) + 120) * 0.5) << 8 | to8(((p & 0xFF) + 86) * 0.5));
                a += (rnd.nextDouble() - 0.5) * 0.7;
                x += Math.cos(a);
                y += Math.sin(a);
            }
        }
        return img;
    }

    /** Darkens the top of a side texture, where topsoil and roots meet the surface. */
    static BufferedImage darkTop(BufferedImage img, double factor, int depth) {
        for (int y = 0; y < depth; y++) {
            double k = factor + (1 - factor) * y / depth;
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getRGB(x, y);
                img.setRGB(x, y, 0xFF000000 | to8(((p >> 16) & 0xFF) * k) << 16
                        | to8(((p >> 8) & 0xFF) * k) << 8 | to8((p & 0xFF) * k));
            }
        }
        return img;
    }

    /** Dense blades seen from above, in grey: the biome colour tints them in game. */
    static BufferedImage grassTop(long seed) {
        int s = GROUND_SIZE;
        double[][] clumps = noise(s, 6, 4, seed);
        double[] v = new double[s * s];
        java.util.Arrays.fill(v, 0.18);
        java.util.Random rnd = new java.util.Random(seed + 1);
        int strokes = s * s / 3;
        for (int i = 0; i < strokes; i++) {
            double x = rnd.nextInt(s), y = rnd.nextInt(s), a = rnd.nextDouble() * Math.PI * 2;
            double shade = 0.25 + 0.40 * rnd.nextDouble() * (0.5 + clumps[(int) y][(int) x]);
            int len = 5 + rnd.nextInt(9);
            for (int t = 0; t < len; t++) {
                int px = Math.floorMod((int) x, s), py = Math.floorMod((int) y, s);
                v[py * s + px] = shade + 0.15 * t / len;
                x += Math.cos(a);
                y += Math.sin(a);
                a += (rnd.nextDouble() - 0.5) * 0.3;
            }
        }
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        for (int p = 0; p < v.length; p++) {
            int g = to8(clamp01(v[p]) * 255);
            img.setRGB(p % s, p / s, 0xFF000000 | g << 16 | g << 8 | g);
        }
        return img;
    }

    /** The grass that hangs over a block's side: an uneven fringe of blades. */
    static BufferedImage sideFringe(long seed) {
        int s = GROUND_SIZE;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        double[][] edge = noise(s, 8, 3, seed);
        java.util.Random rnd = new java.util.Random(seed + 1);
        for (int x = 0; x < s; x++) {
            int h = 12 + (int) (edge[0][x] * 26) + rnd.nextInt(10);
            double shade = 0.45 + rnd.nextDouble() * 0.5;
            for (int y = 0; y < h; y++) {
                if (rnd.nextInt(5) == 0) shade = 0.45 + rnd.nextDouble() * 0.5;
                int g = to8((shade - 0.25 * y / h) * 255);
                img.setRGB(x, y, 0xFF000000 | g << 16 | g << 8 | g);
            }
        }
        return img;
    }

    /** Curved blades rising from the bottom of a transparent texture, in grey for tinting. */
    static BufferedImage blades(long seed, int count, double maxHeight, boolean fromFullHeight) {
        int s = PLANT_SIZE;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        java.util.Random rnd = new java.util.Random(seed);
        for (int i = 0; i < count; i++) {
            double x = 3 + rnd.nextDouble() * (s - 6), lean = (rnd.nextDouble() - 0.5) * 0.9;
            double curve = (rnd.nextDouble() - 0.5) * 0.03;
            double height = s * maxHeight * (fromFullHeight ? 0.75 + rnd.nextDouble() * 0.25 : 0.45 + rnd.nextDouble() * 0.55);
            double shade = 0.45 + rnd.nextDouble() * 0.5;
            for (int t = 0; t < height; t++) {
                double f = t / height;
                int half = f < 0.35 ? 1 : 0;
                int g = to8((shade * (0.65 + 0.35 * f)) * 255);
                for (int dx = -half; dx <= half; dx++) {
                    int px = (int) x + dx, py = s - 1 - t;
                    if (px >= 0 && px < s && py >= 0) {
                        img.setRGB(px, py, 0xFF000000 | g << 16 | g << 8 | g);
                    }
                }
                x += lean;
                lean += curve;
            }
        }
        return img;
    }

    // ---- Lava ------------------------------------------------------------------

    static final int LAVA_SIZE = 64, LAVA_FRAMES = 20;
    /** Crust plates per texture edge. More plates make smaller, busier cracks. */
    static final int LAVA_PLATES = 4;
    /** Width of the glowing cracks, as a fraction of the texture. */
    static final double LAVA_CRACK_WIDTH = 0.05;
    /** LabPBR emission (0..254) at the hottest point of a crack. The crust emits nothing. */
    static final int LAVA_MAX_EMISSION = 150;

    /**
     * Lava as dark drifting crust with glowing cracks. NVIDIA's map makes every texel
     * emit at 80%, which path tracing blows out to one flat pale sheet; here only the
     * cracks emit. Frames loop seamlessly, and flowing lava scrolls the same pattern.
     * No _n map: the mod does not animate normals, so one would slide over the plates.
     */
    static int writeLava(Path textures) throws IOException {
        writeLavaSheet(textures, "lava_still", 101, 4, false);
        writeLavaSheet(textures, "lava_flow", 102, 3, true);
        return 2;
    }

    static void writeLavaSheet(Path textures, String name, long seed, int frametime, boolean flow) throws IOException {
        int s = LAVA_SIZE;
        BufferedImage img = new BufferedImage(s, s * LAVA_FRAMES, BufferedImage.TYPE_INT_ARGB);
        BufferedImage spec = new BufferedImage(s, s * LAVA_FRAMES, BufferedImage.TYPE_INT_ARGB);
        double[][] grain = noise(s, 16, 3, seed + 1), wide = noise(s, 5, 3, seed + 2);
        double[][] warpX = noise(s, 4, 3, seed + 3), warpY = noise(s, 4, 3, seed + 4);
        java.util.Random rnd = new java.util.Random(seed);
        int n = LAVA_PLATES;
        double[][] cx = new double[n][n], cy = new double[n][n], phase = new double[n][n], speed = new double[n][n];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                cx[j][i] = (i + rnd.nextDouble()) / n;
                cy[j][i] = (j + rnd.nextDouble()) / n;
                phase[j][i] = rnd.nextDouble() * Math.PI * 2;
                speed[j][i] = 0.02 + rnd.nextDouble() * 0.03;
            }
        }
        for (int f = 0; f < LAVA_FRAMES; f++) {
            double t = f / (double) LAVA_FRAMES, angle = t * Math.PI * 2;
            for (int y = 0; y < s; y++) {
                for (int x = 0; x < s; x++) {
                    // Warped coordinates bend the straight cell edges into irregular cracks.
                    double px = x / (double) s + (warpX[y][x] - 0.5) * 0.28, py = y / (double) s + (warpY[y][x] - 0.5) * 0.28;
                    double d1 = 9, d2 = 9;
                    for (int j = 0; j < n; j++) {
                        for (int i = 0; i < n; i++) {
                            double ax = cx[j][i] + Math.cos(angle + phase[j][i]) * speed[j][i];
                            double ay = cy[j][i] + Math.sin(angle + phase[j][i]) * speed[j][i] + (flow ? t : 0);
                            double dx = Math.abs(px - ax + 2) % 1, dy = Math.abs(py - ay + 2) % 1;
                            dx = Math.min(dx, 1 - dx);
                            dy = Math.min(dy, 1 - dy);
                            double d = Math.sqrt(dx * dx + dy * dy);
                            if (d < d1) { d2 = d1; d1 = d; } else if (d < d2) { d2 = d; }
                        }
                    }
                    double width = LAVA_CRACK_WIDTH * (0.5 + wide[y][x]);
                    double heat = clamp01(1 - (d2 - d1) / width);
                    heat = heat * heat * (3 - 2 * heat);
                    int rgb = lavaColour(heat, grain[y][x]);
                    img.setRGB(x, y + f * s, rgb);
                    int smooth = to8((0.30 + 0.30 * heat) * 255);
                    int emission = (int) Math.min(254, Math.pow(heat, 1.4) * LAVA_MAX_EMISSION);
                    spec.setRGB(x, y + f * s, emission << 24 | smooth << 16 | 10 << 8);
                }
            }
        }
        write(img, textures.resolve("block/" + name + ".png"));
        write(spec, textures.resolve("block/" + name + "_s.png"));
        writeText(textures.resolve("block/" + name + ".png.mcmeta"),
                "{ \"animation\": { \"frametime\": " + frametime + " } }\n");
    }

    /** Cold basalt through deep red and orange to yellow-hot, by heat 0..1. */
    static int lavaColour(double heat, double grain) {
        int[] crustLight = {62, 50, 46}, crustDark = {18, 14, 14};
        int[] red = {120, 22, 8}, orange = {255, 104, 16}, yellow = {255, 206, 96};
        if (heat < 0.25) {
            int[] crust = unpack(mix(crustLight, crustDark, (grain - 0.3) * 2.2));
            return mix(red, crust, heat / 0.25);
        }
        if (heat < 0.65) return mix(orange, red, (heat - 0.25) / 0.40);
        return mix(yellow, orange, (heat - 0.65) / 0.35);
    }

    static int[] unpack(int argb) {
        return new int[] {(argb >> 16) & 255, (argb >> 8) & 255, argb & 255};
    }

    // ---- Farm ------------------------------------------------------------------

    static final Material WET_SOIL = new Material("wet soil", 0.32, false, 0.04, 0.90, 0.35, 2.5);

    /** Crops in every growth stage, tilled farmland and sugar cane, all generated. */
    static int writeFarm(Path textures) throws IOException {
        int n = 0;
        n += writeGround(textures, "farmland", farmland(41, new int[] {84, 60, 42}, new int[] {38, 26, 18}), SOIL);
        n += writeGround(textures, "farmland_moist", farmland(42, new int[] {54, 37, 26}, new int[] {22, 15, 11}), WET_SOIL);
        for (int stage = 0; stage < 8; stage++) {
            n += writeBlades(textures, "wheat_stage" + stage, wheat(50 + stage, stage));
        }
        int[] carrotLight = {104, 148, 58}, carrotDark = {46, 84, 32};
        int[] potatoLight = {92, 134, 62}, potatoDark = {44, 80, 38};
        int[] beetLight = {88, 128, 56}, beetDark = {40, 72, 34}, beetStem = {132, 38, 52};
        int[] greenStem = {66, 98, 42};
        for (int stage = 0; stage < 4; stage++) {
            double growth = (stage + 1) / 4.0;
            int leaves = 6 + stage * 8;
            BufferedImage carrot = leafy(60 + stage, leaves, growth, carrotLight, carrotDark, greenStem);
            BufferedImage potato = leafy(70 + stage, leaves, growth * 0.85, potatoLight, potatoDark, greenStem);
            BufferedImage beet = leafy(80 + stage, leaves, growth * 0.8, beetLight, beetDark, beetStem);
            if (stage == 3) {
                scatterRoots(carrot, 61, 6, new int[] {226, 128, 44}, new int[] {176, 86, 28}, 3, 5);
                scatterRoots(potato, 71, 6, new int[] {186, 150, 96}, new int[] {132, 100, 62}, 4, 3);
                scatterRoots(beet, 81, 5, new int[] {148, 40, 66}, new int[] {92, 24, 46}, 4, 4);
            }
            n += writeBlades(textures, "carrots_stage" + stage, carrot);
            n += writeBlades(textures, "potatoes_stage" + stage, potato);
            n += writeBlades(textures, "beetroots_stage" + stage, beet);
        }
        n += writeBlades(textures, "sugar_cane", sugarCane(90));
        return n;
    }

    /** Tilled soil: furrows across the block, broken up by clods. Tiles along and across. */
    static BufferedImage farmland(long seed, int[] light, int[] dark) {
        int s = GROUND_SIZE;
        double[][] large = noise(s, 6, 4, seed), fine = noise(s, 40, 2, seed + 1);
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                double wobble = (large[y][x] - 0.5) * 0.9;
                double ridge = 0.5 + 0.5 * Math.sin((y / (double) s) * Math.PI * 2 * 4 + wobble * 3);
                img.setRGB(x, y, mix(light, dark, ridge * 0.75 + (fine[y][x] - 0.5) * 1.2 + 0.05));
            }
        }
        return img;
    }

    /** One tapering wheat or grass blade from the ground; returns where its tip ended. */
    static double[] blade(BufferedImage img, double x, double lean, double curve, double height, int rgb) {
        int s = img.getHeight();
        for (int t = 0; t < height; t++) {
            int half = t < 3 ? 1 : 0;
            for (int dx = -half; dx <= half; dx++) {
                int px = (int) x + dx, py = s - 1 - t;
                if (px >= 0 && px < img.getWidth() && py >= 0) img.setRGB(px, py, 0xFF000000 | rgb);
            }
            x += lean;
            lean += curve;
        }
        return new double[] {x, s - 1 - height};
    }

    /** Wheat from green shoots to golden ears; ears appear on the last three stages. */
    static BufferedImage wheat(long seed, int stage) {
        int s = PLANT_SIZE;
        double growth = (stage + 1) / 8.0;
        double ripe = Math.max(0, (stage - 3) / 4.0);
        int[] green = {104, 140, 56}, greenDark = {66, 100, 42};
        int[] gold = {214, 176, 86}, goldDark = {158, 118, 52};
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        java.util.Random rnd = new java.util.Random(seed);
        int count = 18 + stage * 4;
        for (int i = 0; i < count; i++) {
            double tone = rnd.nextDouble();
            int low = mix(green, greenDark, tone), high = mix(gold, goldDark, tone);
            int rgb = mixRgb(low, high, ripe) & 0xFFFFFF;
            double height = s * (0.15 + 0.72 * growth) * (0.8 + rnd.nextDouble() * 0.2);
            double[] tip = blade(img, 4 + rnd.nextDouble() * (s - 8), (rnd.nextDouble() - 0.5) * 0.25,
                    (rnd.nextDouble() - 0.5) * 0.008, height, rgb);
            if (stage >= 5) {
                // The ear: a short plump head of grains at the blade's tip.
                int len = 6 + stage;
                int ear = mixRgb(mix(gold, goldDark, tone * 0.5), rgb | 0xFF000000, 0.15) & 0xFFFFFF;
                for (int t = 0; t < len; t++) {
                    int py = (int) tip[1] + t - 2;
                    for (int dx = -1; dx <= 1; dx++) {
                        int px = (int) tip[0] + dx;
                        if (px >= 0 && px < s && py >= 0 && py < s && (t + dx) % 3 != 2) {
                            img.setRGB(px, py, 0xFF000000 | ear);
                        }
                    }
                }
            }
        }
        return img;
    }

    static int mixRgb(int a, int b, double t) {
        return mix(new int[] {(b >> 16) & 255, (b >> 8) & 255, b & 255},
                new int[] {(a >> 16) & 255, (a >> 8) & 255, a & 255}, t);
    }

    /** A leafy crop: thin stems rising from the soil, each ending in a pointed leaf. */
    static BufferedImage leafy(long seed, int leaves, double growth, int[] light, int[] dark, int[] stem) {
        int s = PLANT_SIZE;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        java.util.Random rnd = new java.util.Random(seed);
        int stemRgb = (stem[0] << 16) | (stem[1] << 8) | stem[2];
        for (int i = 0; i < leaves; i++) {
            double height = s * growth * (0.45 + rnd.nextDouble() * 0.5);
            double[] tip = blade(img, 8 + rnd.nextDouble() * (s - 16), (rnd.nextDouble() - 0.5) * 0.5,
                    (rnd.nextDouble() - 0.5) * 0.02, height, stemRgb);
            double angle = -Math.PI / 2 + (rnd.nextDouble() - 0.5) * 2.2;
            leaf(img, tip[0], tip[1], angle, 9 + rnd.nextInt(9), 2.5 + rnd.nextDouble() * 2, light, dark, rnd);
        }
        return img;
    }

    /** A pointed leaf with a darker midrib. */
    static void leaf(BufferedImage img, double x, double y, double angle, int len, double width,
                     int[] light, int[] dark, java.util.Random rnd) {
        double tone = rnd.nextDouble();
        double cos = Math.cos(angle), sin = Math.sin(angle);
        for (int t = 0; t <= len; t++) {
            double f = t / (double) len;
            double half = width * Math.sin(Math.PI * Math.pow(f, 0.7));
            for (double w = -half; w <= half; w += 0.5) {
                int px = (int) Math.round(x + cos * t - sin * w), py = (int) Math.round(y + sin * t + cos * w);
                if (px < 0 || py < 0 || px >= img.getWidth() || py >= img.getHeight()) continue;
                double shade = tone * 0.6 + (w > 0 ? 0.3 : 0) + (Math.abs(w) < 0.6 ? -0.25 : 0);
                img.setRGB(px, py, mix(light, dark, 1 - shade));
            }
        }
    }

    /** Ripe roots showing at the soil line. */
    static void scatterRoots(BufferedImage img, long seed, int count, int[] light, int[] dark, int rx, int ry) {
        int s = img.getWidth();
        java.util.Random rnd = new java.util.Random(seed);
        for (int i = 0; i < count; i++) {
            int cx = 8 + rnd.nextInt(s - 16), cy = s - 1 - ry + rnd.nextInt(2);
            for (int dy = -ry; dy <= ry; dy++) {
                for (int dx = -rx; dx <= rx; dx++) {
                    double d = (dx * dx) / (double) (rx * rx) + (dy * dy) / (double) (ry * ry);
                    int py = cy + dy;
                    if (d > 1 || py >= s) continue;
                    img.setRGB(cx + dx, py, mix(light, dark, 1 - (dy < 0 ? 0.9 : 0.4) * (1 - d * 0.6)));
                }
            }
        }
    }

    /** Jointed cane stalks, in grey: the biome colour tints them in game. */
    static BufferedImage sugarCane(long seed) {
        int s = PLANT_SIZE;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        java.util.Random rnd = new java.util.Random(seed);
        for (int stalk = 0; stalk < 4; stalk++) {
            int cx = 8 + stalk * 15 + rnd.nextInt(5), offset = rnd.nextInt(20);
            for (int y = 0; y < s; y++) {
                boolean joint = (y + offset) % 21 < 2;
                for (int dx = -3; dx <= 3; dx++) {
                    double round = 0.62 + 0.3 * (1 - Math.abs(dx - 1) / 4.0);
                    int g = to8((joint ? round * 0.62 : round) * 255);
                    img.setRGB(Math.floorMod(cx + dx, s), y, 0xFF000000 | g << 16 | g << 8 | g);
                }
            }
        }
        return img;
    }

    // ---- Clear glass -----------------------------------------------------------

    static final String[] GLASS_COLOURS = {
        "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
        "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };

    /**
     * Replaces the glass textures with frameless ones. Vanilla paints a 1-pixel
     * opaque frame round every glass face, which under path tracing reads as a
     * dark grid across any glass wall or floor. Clear glass becomes fully
     * transparent: the RTX mod gives placed clear glass its own refracting
     * material, so its transparent texels are the glass itself, not holes.
     * Stained glass keeps its tint; its border takes the colour of its middle.
     */
    static int writeClearGlass(ZipFile jar, Path textures) throws IOException {
        int written = 0;
        for (String clear : new String[] {"block/glass", "block/glass_pane_top"}) {
            BufferedImage src = read(jar, clear);
            if (src == null) continue;
            BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
            write(out, textures.resolve(clear + ".png"));
            written++;
        }
        BufferedImage tinted = read(jar, "block/tinted_glass");
        if (tinted != null) {
            write(smokedGlass(withoutBorder(tinted)), textures.resolve("block/tinted_glass.png"));
            written++;
        }
        written += writeGlassIcons(jar, textures);
        for (String colour : GLASS_COLOURS) {
            BufferedImage glass = read(jar, "block/" + colour + "_stained_glass");
            if (glass == null) continue;
            int middle = glass.getRGB(glass.getWidth() / 2, glass.getHeight() / 2);
            write(withoutBorder(glass), textures.resolve("block/" + colour + "_stained_glass.png"));
            written++;
            BufferedImage paneTop = read(jar, "block/" + colour + "_stained_glass_pane_top");
            if (paneTop != null) {
                BufferedImage top = new BufferedImage(paneTop.getWidth(), paneTop.getHeight(), BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < top.getHeight(); y++) {
                    for (int x = 0; x < top.getWidth(); x++) top.setRGB(x, y, middle);
                }
                write(top, textures.resolve("block/" + colour + "_stained_glass_pane_top.png"));
                written++;
            }
        }
        return written;
    }

    /**
     * Tinted glass under RTX. The mod reads a tinted or stained glass texture's
     * colour as the glass's transmittance: how much light passes through, not how
     * dark the surface looks. Vanilla's tinted glass colour passes about 2% of the
     * light, so anything behind it, even lava, sinks to a dull smudge. Real smoked
     * glass passes roughly a quarter. TRANSMISSION is that fraction (0..1, linear);
     * lower is darker. SATURATION strengthens vanilla's faint purple (1 = vanilla).
     */
    static final double TINTED_GLASS_TRANSMISSION = 0.10;
    static final double TINTED_GLASS_SATURATION = 7.0;
    /** Alpha only matters with RTX off, where it is vanilla's see-through amount. */
    static final int TINTED_GLASS_ALPHA = 150;

    /** The texture recoloured to a smoked-glass transmittance with vanilla's hue, as sRGB. */
    static BufferedImage smokedGlass(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int mid = img.getRGB(w / 2, h / 2);
        double midLum = luminance(mid);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x, y);
                double r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                double grey = (r + g + b) / 3;
                r = Math.max(0, grey + (r - grey) * TINTED_GLASS_SATURATION);
                g = Math.max(0, grey + (g - grey) * TINTED_GLASS_SATURATION);
                b = Math.max(0, grey + (b - grey) * TINTED_GLASS_SATURATION);
                double max = Math.max(1e-6, Math.max(r, Math.max(g, b)));
                // Keep vanilla's streaks: a pixel brighter than the middle passes more light.
                double t = TINTED_GLASS_TRANSMISSION * Math.min(1.5, luminance(p) / Math.max(1e-6, midLum));
                out.setRGB(x, y, (TINTED_GLASS_ALPHA << 24) | (srgb(t * r / max) << 16)
                        | (srgb(t * g / max) << 8) | srgb(t * b / max));
            }
        }
        return out;
    }

    static double luminance(int argb) {
        return 0.2126 * ((argb >> 16) & 0xFF) + 0.7152 * ((argb >> 8) & 0xFF) + 0.0722 * (argb & 0xFF);
    }

    /** A linear 0..1 value encoded as an 8-bit sRGB channel. */
    static int srgb(double linear) {
        double c = clamp01(linear);
        double s = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
        return to8(s * 255);
    }

    /**
     * Keeps the inventory and hand icons of glass, panes and tinted glass looking
     * like vanilla. Their icons draw from the same textures the placed blocks use,
     * which are frameless (clear glass is fully transparent, so its icon vanished)
     * or recoloured for RTX. The icons get copies of the vanilla textures and item
     * models that point at them.
     */
    static int writeGlassIcons(ZipFile jar, Path textures) throws IOException {
        Path assets = textures.getParent();
        int written = 0;
        for (String name : new String[] {"glass", "tinted_glass"}) {
            BufferedImage vanilla = read(jar, "block/" + name);
            if (vanilla == null) continue;
            write(vanilla, textures.resolve("block/void_icon_" + name + ".png"));
            written++;
        }
        writeText(assets.resolve("models/item/void_icon_glass.json"), """
                {
                  "parent": "minecraft:block/cube_all",
                  "textures": {
                    "all": { "force_translucent": true, "sprite": "minecraft:block/void_icon_glass" }
                  }
                }
                """);
        writeText(assets.resolve("models/item/void_icon_tinted_glass.json"), """
                {
                  "parent": "minecraft:block/cube_all",
                  "textures": { "all": "minecraft:block/void_icon_tinted_glass" }
                }
                """);
        writeText(assets.resolve("models/item/glass_pane.json"), """
                {
                  "parent": "minecraft:item/generated",
                  "textures": { "layer0": "minecraft:block/void_icon_glass" }
                }
                """);
        writeText(assets.resolve("items/glass.json"), """
                { "model": { "type": "minecraft:model", "model": "minecraft:item/void_icon_glass" } }
                """);
        writeText(assets.resolve("items/tinted_glass.json"), """
                { "model": { "type": "minecraft:model", "model": "minecraft:item/void_icon_tinted_glass" } }
                """);
        return written;
    }

    static void writeText(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /** The texture with its 1-pixel border painted in the colour of its middle. */
    static BufferedImage withoutBorder(BufferedImage glass) {
        int w = glass.getWidth(), h = glass.getHeight();
        int middle = glass.getRGB(w / 2, h / 2);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean edge = x == 0 || y == 0 || x == w - 1 || y == h - 1;
                out.setRGB(x, y, edge ? middle : glass.getRGB(x, y));
            }
        }
        return out;
    }

    static BufferedImage read(ZipFile jar, String path) throws IOException {
        ZipEntry e = jar.getEntry("assets/minecraft/textures/" + path + ".png");
        if (e == null) return null;
        try (InputStream in = jar.getInputStream(e)) {
            return ImageIO.read(in);
        }
    }

    static void write(BufferedImage img, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        ImageIO.write(img, "png", file.toFile());
    }

    // ---- Classification --------------------------------------------------------

    static Rule classify(String path) {
        String dir = path.substring(0, path.lastIndexOf('/'));
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (dir.startsWith("entity/equipment/")) {
            Material m = ARMOR.get(name);
            return m == null ? null : new Rule(m);
        }
        if (dir.equals("entity/chest")) return classifyChest(name);
        if (has(name, SKIP_WORDS)) return null;
        if (dir.equals("block")) return classifyBlock(name);
        if (dir.equals("item")) return classifyItem(name);
        return null;
    }

    static Rule classifyChest(String name) {
        if (name.startsWith("copper")) {
            return new Rule(name.contains("oxidized") || name.contains("weathered") ? PATINA : COPPER);
        }
        if (name.startsWith("ender")) return new Rule(OBSIDIAN, GOLD, Split.METAL_LATCH);
        return new Rule(WOOD, IRON, Split.METAL_LATCH);
    }

    static Rule classifyBlock(String n) {
        if (n.contains("copper") || n.contains("lightning_rod")) {
            return new Rule(n.contains("oxidized") || n.contains("weathered") ? PATINA : COPPER);
        }
        if (n.startsWith("raw_")) return new Rule(RAW_METAL);
        if (n.contains("netherite")) return new Rule(NETHERITE);
        if (n.startsWith("gold_block")) return new Rule(GOLD);
        if (has(n, "iron_bars", "chain", "anvil", "iron_door", "iron_trapdoor", "iron_block", "heavy_core",
                "cauldron", "hopper", "rail", "lightning_rod", "bell")) return new Rule(IRON);
        if (n.startsWith("diamond_block")) return new Rule(DIAMOND);
        if (has(n, "emerald_block", "lapis_block", "redstone_block")) return new Rule(GEM);
        if (n.contains("_ore")) return new Rule(ORE);
        if (n.contains("obsidian")) return new Rule(OBSIDIAN);
        if (n.contains("glazed_terracotta")) return new Rule(GLAZED);
        if (n.contains("terracotta")) return new Rule(TERRACOTTA);
        if (n.contains("concrete_powder")) return new Rule(SAND);
        if (n.contains("concrete")) return new Rule(CONCRETE);
        if (has(n, "wool", "carpet") || n.startsWith("bed_") || n.contains("_bed_")) return new Rule(WOOL);
        if (n.contains("shulker_box")) return new Rule(POLISHED);
        if (n.contains("sign")) return new Rule(WOOD);
        if (has(n, "coal_block", "ancient_debris", "bone_block", "cinnabar", "sulfur")) return new Rule(STONE);
        if (has(n, "quartz", "polished_", "smooth_", "cut_")) return new Rule(POLISHED);
        if (has(n, "packed_ice", "blue_ice")) return new Rule(ICE);
        if (n.startsWith("stripped_")) return new Rule(STRIPPED);
        if ((n.contains("_log") || n.endsWith("_wood") || n.contains("_stem") || n.contains("hyphae"))
                && !n.endsWith("_top")) return new Rule(BARK);
        if (n.endsWith("_planks")) return new Rule(PLANKS);
        if (has(n, "planks", "_log", "_stem", "door", "trapdoor", "crafting_table", "bookshelf", "barrel",
                "lectern", "composter", "cartography_table", "fletching_table", "smithing_table", "loom",
                "note_block", "jukebox", "bamboo", "scaffolding", "ladder", "beehive", "bee_nest", "shelf",
                "chest", "mushroom_stem")) return new Rule(WOOD);
        if (has(n, "brick")) return new Rule(BRICK);
        if (has(n, "deepslate", "stone", "cobble", "andesite", "diorite", "granite", "tuff", "calcite",
                "blackstone", "basalt", "sandstone", "end_stone", "purpur", "furnace", "smoker", "dispenser",
                "dropper", "observer", "grindstone", "bedrock", "netherrack", "dripstone", "prismarine",
                "piston", "lodestone", "enchanting_table", "brewing_stand", "crafter")) return new Rule(STONE);
        if (has(n, "snow", "powder_snow")) return new Rule(SNOW);
        if (has(n, "sand", "gravel", "suspicious")) return new Rule(SAND);
        if (has(n, "dirt", "podzol", "mycelium", "farmland", "clay", "soul_soil", "mud", "moss", "grass_block",
                "nylium", "path")) return new Rule(SOIL);
        if (has(n, "sponge", "hay", "dried_kelp", "mushroom", "wart_block", "honeycomb", "target", "pumpkin",
                "melon", "cactus", "coral", "nest")) return new Rule(SOFT);
        return new Rule(GENERIC);
    }

    static Rule classifyItem(String n) {
        String[][] tiers = {
            {"wooden_", null}, {"stone_", null}, {"iron_", null}, {"golden_", null},
            {"diamond_", null}, {"netherite_", null}, {"copper_", null},
            {"chainmail_", null}, {"leather_", null}, {"turtle_", null},
        };
        Material[] tierMat = {WOOD, STONE, IRON, GOLD, DIAMOND, NETHERITE, COPPER, CHAIN, LEATHER, TURTLE};
        for (int i = 0; i < tiers.length; i++) {
            if (n.startsWith(tiers[i][0])) {
                Material m = tierMat[i];
                return m == WOOD || m == LEATHER || m == CHAIN || m == TURTLE
                        ? new Rule(m) : new Rule(m, WOOD, Split.STICK_HANDLE);
            }
        }
        if (has(n, "iron_ingot", "iron_nugget", "bucket", "shears", "minecart", "chain", "flint_and_steel",
                "mace", "heavy_core", "iron_bars", "anvil", "cauldron", "hopper", "rail")) {
            return new Rule(IRON, WOOD, Split.STICK_HANDLE);
        }
        if (has(n, "gold_ingot", "gold_nugget", "clock", "bell")) return new Rule(GOLD);
        if (has(n, "copper_ingot", "copper_nugget", "spyglass", "lightning_rod")) return new Rule(COPPER);
        if (has(n, "netherite_ingot", "netherite_scrap")) return new Rule(NETHERITE);
        if (n.startsWith("raw_")) return new Rule(RAW_METAL);
        if (n.equals("diamond")) return new Rule(DIAMOND);
        if (has(n, "emerald", "lapis_lazuli", "quartz", "prismarine_crystals", "prismarine_shard", "echo_shard",
                "trident")) return new Rule(GEM);
        if (n.equals("compass") || n.startsWith("compass_") || n.startsWith("recovery_compass")) return new Rule(IRON);
        if (has(n, "bow", "crossbow", "fishing_rod", "stick", "arrow", "oak_", "spruce_", "birch_", "jungle_",
                "acacia_", "dark_oak_", "mangrove_", "cherry_", "bamboo_", "pale_oak_", "crimson_", "warped_",
                "boat", "sign", "door")) return new Rule(WOOD);
        return null; // everything else keeps its vanilla look
    }

    static boolean has(String name, String... words) {
        for (String w : words) if (name.contains(w)) return true;
        return false;
    }

    // ---- Per-pixel materials ---------------------------------------------------

    static Material[][] pixelMaterials(BufferedImage img, Rule rule, Set<Integer> stickPalette) {
        int w = img.getWidth(), h = img.getHeight();
        Material[][] out = new Material[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                if ((argb >>> 24) < 8) continue; // transparent: no material
                Material m = rule.primary;
                if (rule.split == Split.STICK_HANDLE && stickPalette.contains(argb & 0xFFFFFF)) {
                    m = rule.secondary;
                } else if (rule.split == Split.METAL_LATCH && isGreyMetal(argb)) {
                    m = rule.secondary;
                }
                out[y][x] = m;
            }
        }
        return out;
    }

    /** A chest latch: grey or near-white, unlike the brown wood around it. */
    static boolean isGreyMetal(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double sat = max == 0 ? 0 : (max - min) / (double) max;
        return sat < 0.18 && max > 80;
    }

    static Set<Integer> palette(ZipFile jar, String path) throws IOException {
        Set<Integer> colours = new HashSet<>();
        ZipEntry e = jar.getEntry("assets/minecraft/textures/" + path + ".png");
        if (e == null) return colours;
        BufferedImage img;
        try (InputStream in = jar.getInputStream(e)) {
            img = ImageIO.read(in);
        }
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                if ((argb >>> 24) >= 8) colours.add(argb & 0xFFFFFF);
            }
        }
        return colours;
    }

    static boolean hasTransparency(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) < 250) return true;
            }
        }
        return false;
    }

    // ---- Maps ------------------------------------------------------------------

    /** Brightness stretched to 0..1 over the visible pixels; transparent pixels are NaN. */
    static double[][] heights(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        double[][] lum = new double[h][w];
        double lo = 1, hi = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                if ((argb >>> 24) < 8) {
                    lum[y][x] = Double.NaN;
                    continue;
                }
                double l = (0.2126 * ((argb >> 16) & 0xFF) + 0.7152 * ((argb >> 8) & 0xFF) + 0.0722 * (argb & 0xFF)) / 255.0;
                lum[y][x] = l;
                lo = Math.min(lo, l);
                hi = Math.max(hi, l);
            }
        }
        double range = Math.max(1e-6, hi - lo);
        for (double[] row : lum) {
            for (int x = 0; x < w; x++) row[x] = (row[x] - lo) / range;
        }
        return lum;
    }

    /** A texture's surface heights (0..1, NaN where transparent) and which pixels are grooves. */
    record Shape(double[][] height, boolean[][] groove) {}

    /**
     * Turns brightness into the surface the material describes: the darkest
     * {@code gapFraction} of pixels sink into grooves, the rest keep
     * {@code faceRelief} of the range, and a {@code bevel} chamfers the edge.
     */
    static Shape shape(BufferedImage img, Material m) {
        double[][] ht = heights(img);
        int h = ht.length, w = ht[0].length;
        boolean[][] groove = new boolean[h][w];
        double threshold = -1;
        if (m.gapFraction > 0) {
            List<Double> visible = new ArrayList<>();
            for (double[] row : ht) for (double v : row) if (!Double.isNaN(v)) visible.add(v);
            visible.sort(null);
            if (!visible.isEmpty()) {
                threshold = visible.get(Math.min(visible.size() - 1, (int) (visible.size() * m.gapFraction)));
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double v = ht[y][x];
                if (Double.isNaN(v)) continue;
                double face;
                if (threshold >= 0 && v <= threshold) {
                    groove[y][x] = true;
                    face = 0;
                } else {
                    double t = threshold >= 0 ? (v - threshold) / Math.max(1e-6, 1 - threshold) : v;
                    face = 1 - m.faceRelief + m.faceRelief * t;
                }
                if (m.bevel > 0) {
                    int d = Math.min(Math.min(x, y), Math.min(w - 1 - x, h - 1 - y));
                    if (d < m.bevel) face = Math.min(face, (d + 0.5) / m.bevel * 0.6 + 0.2);
                }
                ht[y][x] = face;
            }
        }
        return new Shape(ht, groove);
    }

    static BufferedImage normalMap(Shape shape, Material[][] mats) {
        double[][] ht = shape.height;
        int w = ht[0].length, h = ht.length;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double c = ht[y][x];
                Material m = mats[y][x];
                if (Double.isNaN(c) || m == null) {
                    out.setRGB(x, y, 0xFF8080FF); // transparent: flat, surface height
                    continue;
                }
                // Sobel on a texture that tiles, so it wraps at the edges. A transparent
                // neighbour counts as level with this pixel, so cut-out edges stay flat.
                double dx = (s(ht, x + 1, y - 1, c) + 2 * s(ht, x + 1, y, c) + s(ht, x + 1, y + 1, c))
                          - (s(ht, x - 1, y - 1, c) + 2 * s(ht, x - 1, y, c) + s(ht, x - 1, y + 1, c));
                double dy = (s(ht, x - 1, y + 1, c) + 2 * s(ht, x, y + 1, c) + s(ht, x + 1, y + 1, c))
                          - (s(ht, x - 1, y - 1, c) + 2 * s(ht, x, y - 1, c) + s(ht, x + 1, y - 1, c));
                double nx = -dx * m.normalStrength / 8, ny = -dy * m.normalStrength / 8, nz = 1;
                double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                int r = to8((nx / len * 0.5 + 0.5) * 255);
                int g = to8((ny / len * 0.5 + 0.5) * 255);
                int ao = 255; // no baked AO: the path tracer computes occlusion itself
                // LabPBR height: 255 is the surface, lower values sink in. Kept shallow.
                int height = to8((1 - m.depth * (1 - c)) * 255);
                out.setRGB(x, y, (height << 24) | (r << 16) | (g << 8) | ao);
            }
        }
        return out;
    }

    static BufferedImage specularMap(Shape shape, Material[][] mats) {
        double[][] ht = shape.height;
        int w = ht[0].length, h = ht.length;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                Material m = mats[y][x];
                if (m == null || Double.isNaN(ht[y][x])) {
                    out.setRGB(x, y, 0xFF000000); // transparent: rough, not emissive
                    continue;
                }
                boolean groove = shape.groove[y][x];
                // Raised parts are worn smoother than the recesses; grooves collect dust.
                double smooth = groove ? 0.12 : clamp01(m.smoothness * (0.85 + 0.3 * ht[y][x]));
                int r = to8(smooth * 255);
                int g = m.metal ? 255 : to8(Math.min(m.f0, 0.89) * 255);
                double porosity = groove ? Math.max(m.porosity, 0.8) : m.porosity;
                int b = to8(porosity * 64); // 0..64 is porosity; 65+ would mean subsurface scattering
                int emission = 255;           // 255 = not emissive
                out.setRGB(x, y, (emission << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    static double s(double[][] ht, int x, int y, double fallback) {
        int h = ht.length, w = ht[0].length;
        double v = ht[Math.floorMod(y, h)][Math.floorMod(x, w)];
        return Double.isNaN(v) ? fallback : v;
    }

    static int to8(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
