package br.com.cristorei.etiquetas;

import java.util.ArrayList;
import java.util.List;

/** Preserva o Z64 e adiciona ^FO/^A0N/^FD antes de ^PQ na etiqueta original. */
public final class ZplWriter {
    private static final String END_OF_PRINT = "^PQ1,0,0,N^XZ";
    private static final int MAX_COLUMNS = 42;
    private ZplWriter() {}

    public static boolean fits(String text) {
        try { return lines(text).size() <= 2; }
        catch (Exception e) { return false; }
    }

    public static String append(String original, String description) {
        if (!original.contains(END_OF_PRINT) || original.indexOf(END_OF_PRINT) != original.lastIndexOf(END_OF_PRINT))
            throw new IllegalArgumentException("Etiqueta sem bloco de impressao esperado");
        List<String> texts = lines(description);
        if (texts.isEmpty() || texts.size() > 2)
            throw new IllegalArgumentException("Descricao nao cabe no espaco de duas linhas");
        StringBuilder overlay = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            int y = 1150 + 24*i;
            overlay.append("^FO16,").append(y).append("^A0N,19,18^FD")
                   .append(texts.get(i)).append("^FS");
        }
        return original.replace(END_OF_PRINT, overlay + END_OF_PRINT);
    }

    public static List<String> lines(String text) {
        // Mantem virgulas entre itens e converte acentos para ASCII, sem comandos ZPL.
        String safe = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").toUpperCase(java.util.Locale.ROOT)
            .replaceAll("[^A-Z0-9, ]", " ").replaceAll(" +", " ").trim();
        if (safe.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        if (safe.length() <= MAX_COLUMNS) return List.of(safe);
        String[] parts = safe.split(",\\s*");
        if (parts.length > 1) {
            for (String p : parts) {
                if (p.length() > MAX_COLUMNS) out.addAll(wrap(p));
                else out.add(p);
            }
            return out;
        }
        return wrap(safe);
    }

    private static List<String> wrap(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" +")) {
            if (word.length() > MAX_COLUMNS) return List.of("INVALIDO", "INVALIDO", "INVALIDO");
            if (!line.isEmpty() && line.length() + 1 + word.length() > MAX_COLUMNS) {
                out.add(line.toString()); line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }
}
