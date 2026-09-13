package com.app.utility.pdf;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/**
 * Millimetre-based drawing on top of PDFBox.
 *
 * It exists so the statement layout can be written the way it was already worked out
 * in the browser. The original is 600 lines of jsPDF calls in millimetres, measured
 * against A4 and tuned column by column; PDFBox works in points from the bottom-left
 * corner. Porting the arithmetic as well as the layout would have meant re-deriving
 * every coordinate and getting some of them wrong.
 *
 * So this converts: millimetres in, points out, y measured down from the top edge the
 * way every coordinate in that layout already is. The layout code then reads as the
 * same document, and a change to it can be checked against the browser version line
 * for line.
 *
 * Not thread-safe, and not meant to be: one instance renders one document.
 */
public class PdfCanvas implements AutoCloseable {

    /** 72 points to the inch, 25.4 millimetres to the inch. */
    private static final float MM = 72f / 25.4f;

    public static final float A4_WIDTH_MM = 210f;
    public static final float A4_HEIGHT_MM = 297f;

    private final PDDocument document = new PDDocument();
    private final List<PDPage> pages = new ArrayList<>();
    private final float pageWidthMm;
    private final float pageHeightMm;

    private PDPageContentStream stream;
    private int currentPage = -1;

    private PDFont font = PDType1Font.HELVETICA;
    private float fontSize = 10f;
    private Color color = Color.BLACK;

    public PdfCanvas() {
        this(A4_WIDTH_MM, A4_HEIGHT_MM);
    }

    public PdfCanvas(float pageWidthMm, float pageHeightMm) {
        this.pageWidthMm = pageWidthMm;
        this.pageHeightMm = pageHeightMm;
        addPage();
    }

    // ---- document and pages -----------------------------------------------

    public PDDocument document() {
        return document;
    }

    public int pageCount() {
        return pages.size();
    }

    public float widthMm() {
        return pageWidthMm;
    }

    public float heightMm() {
        return pageHeightMm;
    }

    /** Adds a page and makes it current. */
    public void addPage() {
        PDPage page = new PDPage(new PDRectangle(pageWidthMm * MM, pageHeightMm * MM));
        document.addPage(page);
        pages.add(page);
        setPage(pages.size() - 1);
    }

    /**
     * Switches which page drawing lands on, by zero-based index.
     *
     * Needed because the statement draws things it cannot know at the time: the
     * carried-forward balance at the foot of each page but the last, and "Page 3 of 7"
     * in the footer. Both are written on a second pass once the page count is settled.
     *
     * PDFBox will not keep two content streams open on one document, so the current
     * stream is closed and a new one appended to the target page.
     */
    public void setPage(int index) {
        if (index == currentPage) {
            return;
        }
        if (index < 0 || index >= pages.size()) {
            throw new IndexOutOfBoundsException("No page " + index + "; the document has " + pages.size());
        }
        closeStream();
        try {
            // APPEND, and do not reset the graphics context: the page already has
            // content and this adds to it rather than starting it.
            stream = new PDPageContentStream(document, pages.get(index),
                    PDPageContentStream.AppendMode.APPEND, true, true);
            currentPage = index;
        } catch (IOException e) {
            throw new PdfRenderException("Could not draw on page " + (index + 1), e);
        }
    }

    /** Zero-based index of the page being drawn on. */
    public int currentPageIndex() {
        return currentPage;
    }

    // ---- state ------------------------------------------------------------

    public void font(PDFont newFont, float size) {
        this.font = newFont;
        this.fontSize = size;
    }

    public void fontSize(float size) {
        this.fontSize = size;
    }

    public void color(Color newColor) {
        this.color = newColor;
    }

    // ---- text -------------------------------------------------------------

    /** Draws text with its left edge at x and its baseline at y. */
    public void text(String value, float xMm, float yMm) {
        draw(value, xMm, yMm);
    }

    /** Draws text ending at x - for a column of figures that line up on the right. */
    public void textRight(String value, float xMm, float yMm) {
        draw(value, xMm - widthOf(value), yMm);
    }

    public void textCentre(String value, float centreXMm, float yMm) {
        draw(value, centreXMm - widthOf(value) / 2f, yMm);
    }

    private void draw(String value, float xMm, float yMm) {
        String safe = sanitise(value);
        if (safe.isEmpty()) {
            return;
        }
        try {
            stream.beginText();
            stream.setFont(font, fontSize);
            stream.setNonStrokingColor(color);
            stream.newLineAtOffset(xMm * MM, (pageHeightMm - yMm) * MM);
            stream.showText(safe);
            stream.endText();
        } catch (IOException e) {
            throw new PdfRenderException("Could not draw text \"" + safe + "\"", e);
        }
    }

    /** The width this string would occupy at the current font and size, in mm. */
    public float widthOf(String value) {
        String safe = sanitise(value);
        if (safe.isEmpty()) {
            return 0f;
        }
        try {
            return font.getStringWidth(safe) / 1000f * fontSize / MM;
        } catch (IOException e) {
            throw new PdfRenderException("Could not measure \"" + safe + "\"", e);
        }
    }

    /**
     * Breaks text to fit a column, at spaces where it can and mid-word when a single
     * word is itself too long.
     *
     * The mid-word case is not hypothetical: the Particulars column is 45 mm and a
     * route name entered without spaces would otherwise print straight through the
     * Voucher column and out the other side, which is the class of bug this whole
     * layout was rewritten to remove.
     */
    public List<String> wrap(String value, float widthMm) {
        List<String> lines = new ArrayList<>();
        String safe = sanitise(value);
        if (safe.isBlank()) {
            lines.add("");
            return lines;
        }

        StringBuilder line = new StringBuilder();
        for (String word : safe.trim().split("\\s+")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (widthOf(candidate) <= widthMm) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (line.length() > 0) {
                lines.add(line.toString());
                line.setLength(0);
            }
            // The word alone still does not fit: split it by measurement.
            while (widthOf(word) > widthMm && word.length() > 1) {
                int cut = word.length();
                while (cut > 1 && widthOf(word.substring(0, cut)) > widthMm) {
                    cut--;
                }
                lines.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    // ---- shapes -----------------------------------------------------------

    public void fillRect(float xMm, float yMm, float widthMm, float heightMm, Color fill) {
        try {
            stream.setNonStrokingColor(fill);
            stream.addRect(xMm * MM, (pageHeightMm - yMm - heightMm) * MM, widthMm * MM, heightMm * MM);
            stream.fill();
        } catch (IOException e) {
            throw new PdfRenderException("Could not fill a rectangle", e);
        }
    }

    public void line(float x1Mm, float y1Mm, float x2Mm, float y2Mm, Color stroke, float widthMm) {
        try {
            stream.setStrokingColor(stroke);
            stream.setLineWidth(widthMm * MM);
            stream.moveTo(x1Mm * MM, (pageHeightMm - y1Mm) * MM);
            stream.lineTo(x2Mm * MM, (pageHeightMm - y2Mm) * MM);
            stream.stroke();
        } catch (IOException e) {
            throw new PdfRenderException("Could not draw a line", e);
        }
    }

    // ---- output -----------------------------------------------------------

    public byte[] toByteArray() {
        closeStream();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new PdfRenderException("Could not write the PDF", e);
        }
    }

    @Override
    public void close() {
        closeStream();
        try {
            document.close();
        } catch (IOException e) {
            throw new PdfRenderException("Could not close the document", e);
        }
    }

    private void closeStream() {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException e) {
            throw new PdfRenderException("Could not close the page content stream", e);
        }
        stream = null;
        currentPage = -1;
    }

    /**
     * Drops what the font cannot draw.
     *
     * Helvetica here is WinAnsi-encoded, so U+20B9 has no glyph and showText throws on
     * it. The layout already writes "Rs." for that reason; this is the backstop for
     * anything arriving from the database - a customer name pasted with a non-breaking
     * space, a stray control character - because a statement that fails to render is
     * worse than one with a character missing.
     */
    static String sanitise(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(code -> {
            if (code == '₹') {
                out.append("Rs.");
            } else if (code == ' ') {
                out.append(' ');
            } else if (code >= 32 && code < 127) {
                out.appendCodePoint(code);
            } else if (code >= 0x00A1 && code <= 0x00FF) {
                // WinAnsi covers Latin-1: accented names in the customer list survive.
                out.appendCodePoint(code);
            }
        });
        return out.toString();
    }
}
