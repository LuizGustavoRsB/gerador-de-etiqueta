package br.com.cristorei.etiquetas;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Le a linha "Pedido: XXXX" do canto superior esquerdo da etiqueta Shopee. */
public final class LabelIdReader {
    private final String command;
    private final String lang;

    public LabelIdReader(String command, String lang) {
        this.command = command;
        this.lang = lang;
    }

    /** Retorna o ID ou null se nao conseguir ler. */
    public String extract(ZplDecoder.Raster raster) {
        BufferedImage img = raster.asImage();
        if (img.getWidth() != 816 || img.getHeight() != 1218) return null;

        // Coordenadas exatas baseadas no layout (816x1218)
        BufferedImage crop = img.getSubimage(30, 245, 340, 35);

        // Escala 3x ajuda o Tesseract a ler fontes pequenas
        BufferedImage upscaled = new BufferedImage(crop.getWidth() * 3, crop.getHeight() * 3,
                BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = upscaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(crop, 0, 0, upscaled.getWidth(), upscaled.getHeight(), null);
        g.dispose();

        Path png = null, txt = null;
        try {
            png = Files.createTempFile("pedido-", ".png");
            txt = Files.createTempFile("pedido-", ".txt");
            ImageIO.write(upscaled, "PNG", png.toFile());

            // PSM 7 = Trata a imagem como uma unica linha de texto.
            ProcessBuilder pb = new ProcessBuilder(command, png.toString(), "stdout",
                    "-l", lang, "--psm", "7",
                    "-c", "tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789:");
            pb.environment().put("OMP_THREAD_LIMIT", "1");
            // Avisos do Tesseract (ex.: "Invalid resolution 0 dpi") vao para stderr e nao podem se misturar ao ID.
            Process p = pb.redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(txt.toFile()).start();

            boolean finished;
            try { finished = p.waitFor(60, TimeUnit.SECONDS); }
            catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
                return null;
            }
            if (!finished) { p.destroyForcibly(); return null; }
            if (p.exitValue() != 0) return null;

            String raw = Files.readString(txt, StandardCharsets.UTF_8).trim();
            return parseId(raw);
        } catch (IOException e) {
            return null;
        } finally {
            try {
                if (png != null) Files.deleteIfExists(png);
                if (txt != null) Files.deleteIfExists(txt);
            } catch (IOException ignored) {}
        }
    }

    /** Extrai o codigo alfanumerico depois de "Pedido:". */
    private static String parseId(String raw) {
        if (raw == null) return null;
        String cleaned = raw.toUpperCase().replaceAll("\\s+", "");
        String after = cleaned.replaceFirst("^.*?PEDIDO:?", "");
        after = after.replaceAll("[^A-Z0-9]", "");
        return after.length() >= 10 ? after : null;
    }
}