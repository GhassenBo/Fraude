package com.frauddetect.twoddoc;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.datamatrix.DataMatrixReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Localise et decode les DataMatrix d'un PDF.
 *
 * Le code occupe une fraction de la page : soumettre l'image entiere a ZXing
 * echoue, le detecteur ne parvenant pas a isoler le motif parmi le texte. La
 * page est donc balayee par tuiles chevauchantes, de plusieurs tailles, ce qui
 * garantit qu'au moins une tuile contient le code entier avec une marge
 * suffisante.
 */
@Component
public class TwoDDocDecoder {

    private static final int RENDER_DPI = 300;

    /** Tailles de tuiles essayees, en pixels a 300 DPI. */
    private static final int[] TILE_SIZES = {700, 1000, 1400};

    /** Au-dela, le cout de balayage ne se justifie pas : le code est en tete. */
    private static final int MAX_PAGES = 3;

    /**
     * @return contenus bruts des codes trouves, dans l'ordre de decouverte
     */
    public List<String> decodeAll(InputStream pdf) throws Exception {
        List<String> found = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdf.readAllBytes())) {
            PDFRenderer renderer = new PDFRenderer(document);
            int pages = Math.min(document.getNumberOfPages(), MAX_PAGES);

            for (int page = 0; page < pages; page++) {
                BufferedImage image = renderer.renderImageWithDPI(page, RENDER_DPI);
                String raw = scanTiles(image);
                if (raw != null && !found.contains(raw)) {
                    found.add(raw);
                    // Un 2D-DOC par document en pratique : inutile de poursuivre.
                    break;
                }
            }
        }
        return found;
    }

    private String scanTiles(BufferedImage page) {
        for (int size : TILE_SIZES) {
            int step = size / 2; // chevauchement de moitie : le code n'est jamais coupe
            for (int y = 0; y < page.getHeight(); y += step) {
                for (int x = 0; x < page.getWidth(); x += step) {
                    int w = Math.min(size, page.getWidth() - x);
                    int h = Math.min(size, page.getHeight() - y);
                    if (w < 100 || h < 100) continue;

                    String raw = decodeRegion(page.getSubimage(x, y, w, h));
                    if (raw != null) return raw;
                }
            }
        }
        return null;
    }

    private String decodeRegion(BufferedImage region) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        try {
            BinaryBitmap bitmap = new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(region)));
            return new DataMatrixReader().decode(bitmap, hints).getText();
        } catch (Exception e) {
            // Aucun DataMatrix dans cette tuile : cas nominal du balayage.
            return null;
        }
    }
}
