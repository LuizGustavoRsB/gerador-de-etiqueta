package br.com.cristorei.etiquetas;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Um OCR TSV por checklist; classifica palavras por celula (x) e linha (y). */
public final class ChecklistReader {
    public record Item(String produto, String variacao, String quantidadeOcr) {}
    public record Extraction(List<Item> items, List<String> warnings) {}

    private final String command;
    private final String lang;

    public ChecklistReader(String command, String lang) {
        this.command = command;
        this.lang = lang;
    }

    public Extraction extract(ZplDecoder.Raster raster) {
        List<String> warnings = new ArrayList<>();
        BufferedImage original = raster.asImage();
        if (original.getWidth() != 816 || original.getHeight() != 1218)
            return new Extraction(List.of(), List.of("Layout diferente de 816x1218: precisa adaptar os recortes"));

        BufferedImage page = rotateClockwise(original);
        List<Integer> separators = findHorizontalDividers(page);
        if (separators.size() < 2)
            return new Extraction(List.of(), List.of("Checklist sem linhas de tabela identificaveis"));
        if (separators.size() > 16)
            return new Extraction(List.of(), List.of("Linhas demais: layout do checklist mudou"));

        int startY = separators.getFirst();
        int endY = separators.getLast() + 1;
        int rows = separators.size()-1;
        StringBuilder[] product = new StringBuilder[rows];
        StringBuilder[] variation = new StringBuilder[rows];
        StringBuilder[] qty = new StringBuilder[rows];
        for (int i=0;i<rows;i++) {
            product[i] = new StringBuilder();
            variation[i] = new StringBuilder();
            qty[i] = new StringBuilder();
        }
        try {
            // Uma unica chamada de OCR para toda a tabela: bem mais rapido que 3 por linha.
            String tsv = ocrTableTsv(page, 35, startY, 1183, endY);
            for (String line : tsv.split("\\R")) {
                String[] fields = line.split("\\t", 12);
                if (fields.length != 12 || fields[0].equals("level") || fields[11].isBlank()) continue;
                int left, top;
                try {
                    left = Integer.parseInt(fields[6]);
                    top = Integer.parseInt(fields[7]);
                } catch (NumberFormatException ignored) { continue; }
                int x = 35 + left/2;
                int y = startY + top/2;
                int index = -1;
                for (int r=0;r<rows;r++) {
                    if (separators.get(r) <= y && y < separators.get(r+1)) {
                        index=r;break;
                    }
                }
                if (index < 0) continue;
                if (x >= 76 && x < 560) append(product[index], fields[11]);
                else if (x >= 560 && x < 950) append(variation[index], fields[11]);
                else if (x >= 950 && x < 1008) append(qty[index], fields[11]);
            }
        } catch (IOException e) {
            return new Extraction(List.of(), List.of("OCR falhou: " + e.getMessage()));
        }
        // O "1" fica colado na linha vertical da coluna Qnt e o OCR da tabela inteira as vezes o descarta:
        // so nesses casos, le a celula isolada, apenas com digitos.
        for (int i=0;i<rows;i++) {
            if (!qty[i].toString().isBlank() || product[i].toString().isBlank()) continue;
            int top = separators.get(i) + 3;
            int bottom = Math.min(separators.get(i) + 45, separators.get(i+1) - 2);
            try {
                append(qty[i], ocrQuantityCell(page, 951, top, 1006, bottom));
            } catch (IOException e) {
                warnings.add("Linha " + (i+1) + ": releitura da quantidade falhou: " + e.getMessage());
            }
        }
        List<Item> items = new ArrayList<>();
        for (int i=0;i<rows;i++) {
            items.add(new Item(product[i].toString(), variation[i].toString(), qty[i].toString()));
        }
        if (items.isEmpty()) warnings.add("Nenhum item extraido do checklist");
        return new Extraction(items, warnings);
    }

    private static void append(StringBuilder builder, String text) {
        if (!builder.isEmpty()) builder.append(' ');
        builder.append(text.trim());
    }

    private BufferedImage rotateClockwise(BufferedImage source) {
        BufferedImage rotated = new BufferedImage(source.getHeight(), source.getWidth(), BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = rotated.createGraphics();
        g.translate(source.getHeight(), 0);
        g.rotate(Math.PI / 2);
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return rotated;
    }

    private List<Integer> findHorizontalDividers(BufferedImage img) {
        List<Integer> ys = new ArrayList<>();
        boolean previous = false;
        for (int y = 215; y < Math.min(760, img.getHeight()); y++) {
            int black = 0, samples = 0;
            for (int x = 35; x < Math.min(1182, img.getWidth()); x += 2) {
                if ((img.getRGB(x,y) & 0xFFFFFF) == 0) black++;
                samples++;
            }
            boolean isDivider = black > samples * 0.86;
            if (isDivider && !previous) ys.add(y);
            previous = isDivider;
        }
        return ys;
    }

    private String ocrTableTsv(BufferedImage page, int x1, int y1, int x2, int y2) throws IOException {
        BufferedImage crop = page.getSubimage(x1, y1, x2-x1, y2-y1);
        return runTesseract(scale(crop, 2, 0), "-l", lang, "--psm", "11", "tsv");
    }

    /** Celula de quantidade isolada: apaga restos das linhas verticais, adiciona margem branca e le so digitos. */
    private String ocrQuantityCell(BufferedImage page, int x1, int y1, int x2, int y2) throws IOException {
        BufferedImage cell = new BufferedImage(x2-x1, y2-y1, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = cell.createGraphics();
        g.drawImage(page.getSubimage(x1, y1, x2-x1, y2-y1), 0, 0, null);
        g.dispose();
        for (int x = 0; x < cell.getWidth(); x++) {
            int black = 0;
            for (int y = 0; y < cell.getHeight(); y++) if ((cell.getRGB(x, y) & 0xFFFFFF) == 0) black++;
            if (black > cell.getHeight() * 0.8)
                for (int y = 0; y < cell.getHeight(); y++) cell.setRGB(x, y, 0xFFFFFF);
        }
        String text = runTesseract(scale(cell, 3, 20), "-l", "eng", "--psm", "7",
                "-c", "tessedit_char_whitelist=0123456789");
        return text.replaceAll("[^0-9]", "");
    }

    private static BufferedImage scale(BufferedImage src, int factor, int padding) {
        int w = src.getWidth()*factor + padding*2, h = src.getHeight()*factor + padding*2;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, padding, padding, src.getWidth()*factor, src.getHeight()*factor, null);
        g.dispose();
        return out;
    }

    private String runTesseract(BufferedImage image, String... options) throws IOException {
        Path png = Files.createTempFile("etiqueta-tabela-", ".png");
        Path txt = Files.createTempFile("etiqueta-tabela-", ".tsv");
        Path err = Files.createTempFile("etiqueta-tabela-", ".err");
        try {
            ImageIO.write(image, "PNG", png.toFile());
            List<String> cmd = new ArrayList<>(List.of(command, png.toString(), "stdout"));
            cmd.addAll(List.of(options));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // 1 thread por processo: com varios Tesseract em paralelo o OpenMP padrao disputa CPU.
            pb.environment().put("OMP_THREAD_LIMIT", "1");
            // Avisos do Tesseract (ex.: "Invalid resolution 0 dpi") vao para stderr e nao podem
            // se misturar ao resultado: na leitura de digitos virariam quantidades falsas.
            Process p = pb.redirectError(err.toFile()).redirectOutput(txt.toFile()).start();
            boolean finished;
            try { finished = p.waitFor(60, TimeUnit.SECONDS); }
            catch (InterruptedException e) {
                p.destroyForcibly(); Thread.currentThread().interrupt();
                throw new IOException("OCR interrompido", e);
            }
            if (!finished) { p.destroyForcibly(); throw new IOException("OCR excedeu 60 segundos"); }
            if (p.exitValue() != 0) throw new IOException("Tesseract falhou: " + Files.readString(err));
            return Files.readString(txt, StandardCharsets.UTF_8);
        } finally {
            Files.deleteIfExists(png);
            Files.deleteIfExists(txt);
            Files.deleteIfExists(err);
        }
    }
}
