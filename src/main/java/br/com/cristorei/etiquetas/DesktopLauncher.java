package br.com.cristorei.etiquetas;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Abre a interface numa janela de app (Edge/Chrome em modo --app) e encerra o servidor ao fechar a janela. */
@QuarkusMain
public class DesktopLauncher implements QuarkusApplication {
    private static final List<String> BROWSERS = List.of(
        "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
        "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
        "C:/Program Files/Google/Chrome/Application/chrome.exe",
        "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe");
    private static final List<String> TESSERACTS = List.of(
        "C:/Program Files/Tesseract-OCR/tesseract.exe",
        "C:/Program Files (x86)/Tesseract-OCR/tesseract.exe");

    public static void main(String[] args) {
        if (System.getenv("OCR_COMMAND") == null && System.getProperty("ocr.command") == null) {
            for (String t : TESSERACTS) {
                if (new File(t).isFile()) { System.setProperty("ocr.command", t); break; }
            }
        }
        Quarkus.run(DesktopLauncher.class, args);
    }

    @ConfigProperty(name = "quarkus.http.port")
    int port;

    @Override
    public int run(String... args) throws Exception {
        String browser = BROWSERS.stream().filter(b -> new File(b).isFile()).findFirst().orElse(null);
        if (browser == null || List.of(args).contains("--no-window")) {
            Quarkus.waitForExit();
            return 0;
        }
        Path profile = Files.createTempDirectory("etiquetas-janela-");
        Process window = new ProcessBuilder(browser, "--app=http://127.0.0.1:" + port + "/",
                "--user-data-dir=" + profile, "--window-size=1200,850", "--no-first-run")
            .inheritIO().start();
        window.waitFor();
        return 0;
    }
}
