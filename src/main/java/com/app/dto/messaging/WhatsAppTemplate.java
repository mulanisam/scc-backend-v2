package com.app.dto.messaging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.Data;

/**
 * A WhatsApp template as it actually exists on the Fast2SMS account.
 *
 * Read from GET /dev/dlt_manager/whatsapp?type=template rather than assumed, because
 * assuming was wrong in a way that mattered: the outbox stored a preview of what the
 * code thought the message said, while the customer received whatever the approved
 * template says. For 12082 those were entirely different sentences, so the audit
 * trail was describing a message nobody had been sent.
 *
 * Two identifiers come back. messageId is what the send API takes; templateId is
 * Meta's own id and is only useful for looking a template up in the Meta console.
 */
@Data
public class WhatsAppTemplate {

    /** {{1}}, {{2}} ... in the approved body. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*(\\d+)\\s*}}");

    /** The id the send endpoint expects as message_id. */
    private Integer messageId;
    /** Meta's template id. */
    private String templateId;
    private String templateName;
    private String category;
    private String status;
    private String language;
    /** How many variables the approved body takes. */
    private Integer varCount;
    /** The approved body, placeholders included. */
    private String bodyText;
    /** Whether the template carries a call button or similar. */
    private boolean hasButtons;

    public boolean isApproved() {
        return "Approved".equalsIgnoreCase(status);
    }

    public boolean isUtility() {
        return "UTILITY".equalsIgnoreCase(category);
    }

    /**
     * The approved body with the values substituted - what the customer will
     * actually read.
     *
     * This is what belongs on the outbox row. Rendering the application's own idea
     * of the wording instead produced a preview that did not match the delivered
     * message at all.
     *
     * @param variables pipe-separated, in template order
     */
    public String render(String variables) {
        if (bodyText == null) {
            return null;
        }
        String[] values = variables == null ? new String[0] : variables.split("\\|", -1);

        StringBuilder rendered = new StringBuilder();
        Matcher matcher = PLACEHOLDER.matcher(bodyText);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            // A placeholder with no value is left as it is, so a mismatch is visible
            // in the preview rather than silently rendering as an empty space.
            String value = index >= 1 && index <= values.length ? values[index - 1] : matcher.group();
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    /** How many distinct placeholders the body actually contains. */
    public int placeholderCount() {
        if (bodyText == null) {
            return 0;
        }
        int highest = 0;
        Matcher matcher = PLACEHOLDER.matcher(bodyText);
        while (matcher.find()) {
            highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
        }
        return highest;
    }
}
