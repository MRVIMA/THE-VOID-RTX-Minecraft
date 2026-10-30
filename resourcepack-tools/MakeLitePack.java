import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * Turns a copy of THE VOID RTX Materials into the Lite pack for low-end PCs: every
 * texture wider than 32 pixels shrinks to 32, and the 3D grass goes, since it adds
 * thousands of cut-out surfaces for every ray to test. Colour maps stay full size,
 * because they are lookup tables rather than pictures.
 *
 * Usage: java MakeLitePack.java <pack folder copy>
 */
public class MakeLitePack {
    static final int MAX_WIDTH = 32;

    public static void main(String[] args) throws IOException {
        Path pack = Path.of(args[0]);
        Path assets = pack.resolve("assets/minecraft");
        int shrunk = 0;
        List<Path> pngs;
        try (Stream<Path> walk = Files.walk(assets.resolve("textures"))) {
            pngs = walk.filter(p -> p.toString().endsWith(".png")).toList();
        }
        for (Path png : pngs) {
            if (png.toString().replace('\\', '/').contains("/colormap/")) continue;
            BufferedImage img = ImageIO.read(png.toFile());
            if (img == null || img.getWidth() <= MAX_WIDTH) continue;
            int factor = img.getWidth() / MAX_WIDTH;
            boolean map = png.getFileName().toString().matches(".*_[ns]\\.png");
            ImageIO.write(shrink(img, factor, map), "png", png.toFile());
            shrunk++;
        }
        Files.deleteIfExists(assets.resolve("blockstates/grass_block.json"));
        Files.deleteIfExists(assets.resolve("textures/block/void_grass_tuft.png"));
        Files.deleteIfExists(assets.resolve("textures/block/void_grass_tuft_n.png"));
        Files.deleteIfExists(assets.resolve("textures/block/void_grass_tuft_s.png"));
        try (Stream<Path> models = Files.list(assets.resolve("models/block"))) {
            for (Path model : models.filter(p -> p.getFileName().toString().startsWith("grass_block")).toList()) {
                Files.delete(model);
            }
        }
        System.out.println(shrunk + " textures shrunk");
    }

    /**
     * Averages each block of pixels. Colour counts only where it is visible, and alpha
     * comes out solid or empty, so cut-out leaves stay crisp. Surface maps average
     * every channel, because their alpha holds height.
     */
    static BufferedImage shrink(BufferedImage img, int factor, boolean map) {
        int w = img.getWidth() / factor, h = img.getHeight() / factor;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                long a = 0, r = 0, g = 0, b = 0;
                int seen = 0;
                for (int dy = 0; dy < factor; dy++) {
                    for (int dx = 0; dx < factor; dx++) {
                        int p = img.getRGB(x * factor + dx, y * factor + dy);
                        int alpha = p >>> 24;
                        a += alpha;
                        if (map || alpha >= 128) {
                            r += (p >> 16) & 255;
                            g += (p >> 8) & 255;
                            b += p & 255;
                            seen++;
                        }
                    }
                }
                int count = factor * factor;
                int alpha = (int) (a / count);
                if (!map) alpha = alpha >= 110 ? 255 : 0;
                if (seen == 0) seen = 1;
                out.setRGB(x, y, alpha << 24 | (int) (r / seen) << 16 | (int) (g / seen) << 8 | (int) (b / seen));
            }
        }
        return out;
    }
}
