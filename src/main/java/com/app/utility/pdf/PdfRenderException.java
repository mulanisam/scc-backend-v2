package com.app.utility.pdf;

/**
 * A PDF could not be drawn.
 *
 * Unchecked, because every PDFBox call declares IOException and none of them is
 * recoverable here: if a glyph cannot be written or a page cannot be opened, the
 * document is not going to be produced and the caller's only move is to report that.
 * Wrapping keeps the layout code readable as layout rather than as error handling.
 */
public class PdfRenderException extends RuntimeException {

    public PdfRenderException(String message, Throwable cause) {
        super(message, cause);
    }

    public PdfRenderException(String message) {
        super(message);
    }
}
