import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders the Scrcpy Vwr app icon ("Mirror": a phone with its outlined reflection behind it) from the
 * geometry in app-icon.svg, and writes the packaging formats:
 *
 *   icon.png        512 px, for Linux packaging and the window/taskbar icon (copied into jvmMain resources)
 *   icon.ico        16-256 px PNG-compressed entries, for Windows
 *   icon.iconset/   macOS sizes; turn into icon.icns with `iconutil -c icns icon.iconset`
 *
 * Run from this directory: `java GenerateIcons.java && iconutil -c icns icon.iconset`
 */
public class GenerateIcons {

    /** Draws the icon on a 512-unit canvas. [inset] shrinks the tile toward the centre (macOS grid uses ~0.8). */
    static BufferedImage render(int size, double inset) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        double s = size / 512.0;
        AffineTransform t = new AffineTransform();
        t.scale(s, s);
        t.translate(256, 256);
        t.scale(inset, inset);
        t.translate(-256, -256);
        g.transform(t);

        // Tile: dark teal-black, diagonal gradient.
        g.setPaint(new GradientPaint(16, 16, new Color(0x1d2a2a), 496, 496, new Color(0x0b1111)));
        g.fill(round(16, 16, 480, 480, 112));

        // Reflection: outlined phone behind, offset down-right.
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
        g.setColor(new Color(0x4cdada));
        g.setStroke(new BasicStroke(14f));
        g.draw(round(204, 128, 150, 270, 30));
        g.setComposite(AlphaComposite.SrcOver);

        // Phone: light teal body, dark screen.
        g.setPaint(new GradientPaint(0, 104, new Color(0x9cf1f0), 0, 374, new Color(0x4cdada)));
        g.fill(round(158, 104, 150, 270, 30));
        g.setColor(new Color(0x004f4f));
        g.fill(round(176, 128, 114, 210, 14));

        g.dispose();
        return img;
    }

    static Shape round(double x, double y, double w, double h, double r) {
        return new RoundRectangle2D.Double(x, y, w, h, r * 2, r * 2);
    }

    static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** ICO container with PNG-compressed entries (supported since Windows Vista). */
    static byte[] ico(int[] sizes) throws IOException {
        List<byte[]> images = new ArrayList<>();
        for (int sz : sizes) images.add(png(render(sz, 1.0)));
        int headerSize = 6 + 16 * sizes.length;
        int total = headerSize + images.stream().mapToInt(b -> b.length).sum();
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = headerSize;
        for (int i = 0; i < sizes.length; i++) {
            int sz = sizes[i];
            buf.put((byte) (sz >= 256 ? 0 : sz)).put((byte) (sz >= 256 ? 0 : sz));
            buf.put((byte) 0).put((byte) 0);
            buf.putShort((short) 1).putShort((short) 32);
            buf.putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        for (byte[] b : images) buf.put(b);
        return buf.array();
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(".");
        ImageIO.write(render(512, 1.0), "png", dir.resolve("icon.png").toFile());
        Files.write(dir.resolve("icon.ico"), ico(new int[]{16, 24, 32, 48, 64, 128, 256}));

        // macOS: Apple's grid puts the tile at ~824/1024 of the canvas.
        Path iconset = dir.resolve("icon.iconset");
        Files.createDirectories(iconset);
        int[] base = {16, 32, 128, 256, 512};
        for (int b : base) {
            ImageIO.write(render(b, 0.85), "png", iconset.resolve("icon_" + b + "x" + b + ".png").toFile());
            ImageIO.write(render(b * 2, 0.85), "png", iconset.resolve("icon_" + b + "x" + b + "@2x.png").toFile());
        }
    }
}
