package br.com.cristorei.etiquetas;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

/** Le o formato ~DGR:DEMO.GRF,...,:Z64:... do arquivo atual da Shopee. */
public final class ZplDecoder {
    private static final Pattern RASTER = Pattern.compile(
        "~DG([A-Z]):([A-Z0-9_.-]+),(\\d+),(\\d+),:Z64:([A-Za-z0-9+/=]+):([A-Fa-f0-9]{4})");
    private static final int MAX_RAW = 8 * 1024 * 1024;

    private ZplDecoder() {}

    public record Raster(int width, int height, byte[] dots) {
        public BufferedImage asImage() {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
            // ZPL: bit 1 significa preto. TYPE_BYTE_BINARY: bit 1 significa branco.
            byte[] target = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
            if (target.length != dots.length) throw new IllegalStateException("Raster com stride inesperado");
            for (int i = 0; i < dots.length; i++) target[i] = (byte) ~dots[i];
            return image;
        }
    }

    public static Raster decode(String zpl) {
        Matcher m = RASTER.matcher(zpl);
        if (!m.find()) throw new IllegalArgumentException("~DG com imagem Z64 nao encontrado");
        int size = Integer.parseInt(m.group(3));
        int bpr = Integer.parseInt(m.group(4));
        if (size <= 0 || size > MAX_RAW || bpr <= 0 || size % bpr != 0) {
            throw new IllegalArgumentException("Dimensoes invalidas no ZPL");
        }
        try {
            byte[] compressed = Base64.getDecoder().decode(m.group(5));
            ByteArrayOutputStream out = new ByteArrayOutputStream(size);
            try (InflaterInputStream zip = new InflaterInputStream(new java.io.ByteArrayInputStream(compressed))) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = zip.read(chunk)) != -1) {
                    if (out.size() + n > MAX_RAW) throw new IllegalArgumentException("Imagem Z64 grande demais");
                    out.write(chunk, 0, n);
                }
            }
            byte[] raster = out.toByteArray();
            if (raster.length != size) throw new IllegalArgumentException("Comprimento Z64 divergente");
            return new Raster(bpr * 8, size / bpr, raster);
        } catch (Exception e) {
            throw new IllegalArgumentException("Erro ao descompactar imagem Z64", e);
        }
    }
}
