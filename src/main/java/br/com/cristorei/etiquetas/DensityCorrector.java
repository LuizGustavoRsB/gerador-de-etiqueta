package br.com.cristorei.etiquetas;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DeflaterOutputStream;

/**
 * Reduz densidade apenas em faixas sinalizadas, atenuando grandes blocos pretos.
 * Conservador: só altera sequências horizontais pretas com pelo menos 80 dots,
 * deixando sequências curtas (como barras de códigos de barras) intactas.
 */
public final class DensityCorrector {
    private static final Pattern RASTER = Pattern.compile(
        "(~DG([A-Z]):([A-Z0-9_.-]+),(\\d+),(\\d+),:Z64:)([A-Za-z0-9+/=]+)(:[A-Fa-f0-9]{4})");
    private static final int WINDOW = 20;
    private static final int MIN_RUN = 80;

    private DensityCorrector() {}

    public record Result(String zpl, double before, double after, int dotsRemoved) {}

    public static Result correct(String zpl, double threshold) {
        Matcher m = RASTER.matcher(zpl);
        if (!m.find()) throw new IllegalArgumentException("Imagem Z64 nao encontrada para correcao");
        int size = Integer.parseInt(m.group(4));
        int bpr = Integer.parseInt(m.group(5));
        byte[] compressed = Base64.getDecoder().decode(m.group(6));
        byte[] dots;
        try (var in = new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(compressed))) {
            dots = in.readAllBytes();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Nao foi possivel descompactar imagem para correcao", ex);
        }
        if (dots.length != size || bpr <= 0 || size % bpr != 0)
            throw new IllegalArgumentException("Dimensoes da imagem Z64 invalidas");
        int width = bpr * 8, height = size / bpr;
        double before = peak20(dots, bpr, height);
        if (before < threshold) return new Result(zpl, before, before, 0);

        int removed = 0;
        // ZPL: bit 1 representa ponto preto. Só atenua runs muito longos, preservando
        // sequências estreitas que normalmente formam barras e texto.
        for (int y = WINDOW; y < height - WINDOW; y++) {
            int runStart = -1;
            for (int x = 0; x <= width; x++) {
                boolean black = x < width && isBlack(dots, bpr, x, y);
                if (black && runStart < 0) runStart = x;
                if ((!black || x == width) && runStart >= 0) {
                    int runEnd = x;
                    if (runEnd - runStart >= MIN_RUN) {
                        for (int px = runStart; px < runEnd; px++) {
                            // Padrão determinístico de 1 ponto removido em cada 3.
                            // O padrão muda por linha para evitar linhas verticais contínuas.
                            if ((px + y * 2) % 3 == 0 && isBlack(dots, bpr, px, y)) {
                                clearBlack(dots, bpr, px, y);
                                removed++;
                            }
                        }
                    }
                    runStart = -1;
                }
            }
        }
        double after = peak20(dots, bpr, height);
        if (removed == 0) throw new IllegalArgumentException(
            "A etiqueta excede o limite, mas nao ha blocos horizontais longos seguros para atenuar. Revisao manual recomendada.");
        byte[] recompressed = deflate(dots);
        String encoded = Base64.getEncoder().encodeToString(recompressed);
        String checksum = String.format(java.util.Locale.ROOT, "%04X", crc16(recompressed));
        String replacement = m.group(1) + encoded + ":" + checksum;
        String updated = zpl.substring(0, m.start()) + replacement + zpl.substring(m.end());
        return new Result(updated, before, after, removed);
    }

    private static byte[] deflate(byte[] raw) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (DeflaterOutputStream def = new DeflaterOutputStream(out)) { def.write(raw); }
            return out.toByteArray();
        } catch (Exception ex) { throw new IllegalStateException("Falha ao recomprimir imagem Z64", ex); }
    }

    private static int crc16(byte[] bytes) {
        int crc = 0;
        for (byte value : bytes) {
            crc ^= (value & 0xff) << 8;
            for (int i = 0; i < 8; i++) crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            crc &= 0xffff;
        }
        return crc;
    }

    private static boolean isBlack(byte[] dots, int bpr, int x, int y) {
        return ((dots[y * bpr + x / 8] & 0xff) & (1 << (7 - x % 8))) != 0;
    }
    private static void clearBlack(byte[] dots, int bpr, int x, int y) {
        int index = y * bpr + x / 8;
        dots[index] = (byte) (dots[index] & ~(1 << (7 - x % 8)));
    }

    private static double peak20(byte[] dots, int bpr, int height) {
        if (height < WINDOW) return 0;
        int[] rows = new int[height];
        for (int y = 0; y < height; y++) {
            int count = 0;
            for (int x = 0; x < bpr; x++) count += Integer.bitCount(dots[y * bpr + x] & 0xff);
            rows[y] = count;
        }
        int sum = 0, max = 0;
        for (int y = 0; y < height; y++) {
            sum += rows[y];
            if (y >= WINDOW) sum -= rows[y - WINDOW];
            if (y >= WINDOW - 1) max = Math.max(max, sum);
        }
        return max / (double) WINDOW;
    }
}
