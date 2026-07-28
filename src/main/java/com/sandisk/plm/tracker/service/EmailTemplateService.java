package com.sandisk.plm.tracker.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * Shared email template service for PLM Toolkit.
 * Produces Outlook-safe HTML emails with table-based layouts and all inline CSS.
 */
@Service
public class EmailTemplateService {

    // ── Palette ──────────────────────────────────────────────────────────
    private static final String PAPER    = "#FAFAF7";
    private static final String CARD     = "#FFFFFF";
    private static final String INK      = "#0F1720";
    private static final String MUTED    = "#6B7280";
    private static final String HAIRLINE = "#E8E6DF";
    private static final String ACCENT   = "#2C6FBB";
    private static final String SUCCESS  = "#1F8A4C";
    private static final String WARN     = "#C7801B";
    private static final String RED      = "#B8342B";

    // ── Type stacks ──────────────────────────────────────────────────────
    private static final String FONT_BODY  = "'IBM Plex Sans','Segoe UI',Calibri,Arial,sans-serif";
    private static final String FONT_SERIF = "'IBM Plex Serif',Georgia,serif";
    private static final String FONT_MONO  = "'IBM Plex Mono',Consolas,monospace";

    private static final Random RANDOM = new Random();

    // =====================================================================
    //  1. wrap — complete email envelope
    // =====================================================================
    /**
     * Returns the complete email HTML string with all inline CSS.
     *
     * @param section      navigation label after "PLM Toolkit / " (e.g. "Field Compare")
     * @param tag          optional tag badge next to the section (e.g. "BETA"), null to omit
     * @param eyebrow      small uppercase text above h1, null to omit
     * @param heroTitle    main h1 heading
     * @param heroSub      subtitle text under h1, null to omit
     * @param kpiHtml      output of kpiRow(), null to omit
     * @param bodyHtml     main body HTML content
     * @param ctaText      call-to-action button label, null to omit CTA
     * @param ctaHref      call-to-action URL
     * @param ctaHint      muted hint text below button, null to omit
     * @param attachHtml   output of attachmentStrip(), null to omit
     * @param metaStripHtml additional meta strip content (request ID, etc.), null to auto-generate
     * @param footerLine   custom footer delivery line, null for default
     */
    public String wrap(String section, String tag, String eyebrow, String heroTitle,
                       String heroSub, String kpiHtml, String bodyHtml,
                       String ctaText, String ctaHref, String ctaHint,
                       String attachHtml, String metaStripHtml, String footerLine) {

        StringBuilder sb = new StringBuilder();

        // ── DOCTYPE + head ───────────────────────────────────────────────
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"en\" xmlns=\"http://www.w3.org/1999/xhtml\">\n");
        sb.append("<head>\n");
        sb.append("  <meta charset=\"utf-8\">\n");
        sb.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("  <meta name=\"color-scheme\" content=\"light dark\">\n");
        sb.append("  <meta name=\"supported-color-schemes\" content=\"light dark\">\n");
        sb.append("  <title>").append(esc(heroTitle)).append("</title>\n");
        sb.append("  <style>\n");
        sb.append("    @media (prefers-color-scheme: dark) {\n");
        sb.append("      .email-body { background: #1a1a1a !important; }\n");
        sb.append("      .email-card { background: #2a2a2a !important; border-color: #444 !important; }\n");
        sb.append("      .email-ink  { color: #e0e0e0 !important; }\n");
        sb.append("      .email-muted { color: #999 !important; }\n");
        sb.append("      .email-hairline { border-color: #444 !important; }\n");
        sb.append("      .kpi-tile { background: #333 !important; border-color: #444 !important; }\n");
        sb.append("      .callout-default { background: #2d2b24 !important; }\n");
        sb.append("      .callout-success { background: #1a2e22 !important; }\n");
        sb.append("      .callout-warn { background: #2e2718 !important; }\n");
        sb.append("      .callout-bad { background: #2e1a18 !important; }\n");
        sb.append("      .preview-th { background: #333 !important; color: #e0e0e0 !important; }\n");
        sb.append("      .preview-td { background: #2a2a2a !important; color: #ccc !important; border-color: #444 !important; }\n");
        sb.append("      .preview-tr-alt { background: #303030 !important; }\n");
        sb.append("      .footer-pill { border-color: #555 !important; }\n");
        sb.append("    }\n");
        sb.append("  </style>\n");
        sb.append("</head>\n");

        // ── Body ─────────────────────────────────────────────────────────
        sb.append("<body class=\"email-body\" style=\"margin:0;padding:0;background:").append(PAPER)
          .append(";font-family:").append(FONT_BODY).append(";-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%;\">\n");

        // preheader
        sb.append(preheader(heroTitle + (heroSub != null ? " — " + heroSub : "")));

        // outer wrapper
        sb.append("<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" role=\"presentation\" ")
          .append("style=\"background:").append(PAPER).append(";\">\n");
        sb.append("<tr><td align=\"center\" style=\"padding:24px 20px;\">\n");

        // card
        sb.append("<table class=\"email-card\" cellpadding=\"0\" cellspacing=\"0\" width=\"600\" ")
          .append("style=\"max-width:600px;width:100%;background:").append(CARD)
          .append(";border:1px solid ").append(HAIRLINE)
          .append(";border-radius:8px;overflow:hidden;\">\n");

        // ── HEADER row ───────────────────────────────────────────────────
        sb.append("<tr><td style=\"padding:16px 24px;border-bottom:1px solid ").append(HAIRLINE).append(";\">\n");
        sb.append("  <table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" role=\"presentation\">\n");
        sb.append("  <tr>\n");
        // PT mark
        sb.append("    <td style=\"width:24px;vertical-align:middle;\">\n");
        sb.append("      <table cellpadding=\"0\" cellspacing=\"0\" role=\"presentation\">\n");
        sb.append("      <tr><td style=\"width:24px;height:24px;background:").append(INK)
          .append(";color:").append(PAPER).append(";font-size:10px;font-weight:bold;font-family:")
          .append(FONT_BODY).append(";text-align:center;line-height:24px;border-radius:5px;\">PT</td></tr>\n");
        sb.append("      </table>\n");
        sb.append("    </td>\n");
        // section label
        sb.append("    <td style=\"padding-left:10px;vertical-align:middle;font-size:13px;color:")
          .append(MUTED).append(";font-family:").append(FONT_BODY).append(";\">\n");
        sb.append("      PLM Toolkit");
        if (section != null && !section.isEmpty()) {
            sb.append(" <span style=\"color:").append(HAIRLINE).append(";\">/</span> ");
            sb.append("<span class=\"email-ink\" style=\"color:").append(INK).append(";font-weight:600;\">")
              .append(esc(section)).append("</span>");
        }
        if (tag != null && !tag.isEmpty()) {
            sb.append(" <span style=\"display:inline-block;margin-left:8px;font-size:10px;font-weight:700;")
              .append("letter-spacing:0.5px;padding:2px 7px;border-radius:4px;background:")
              .append(HAIRLINE).append(";color:").append(MUTED).append(";\">")
              .append(esc(tag)).append("</span>");
        }
        sb.append("\n    </td>\n");
        sb.append("  </tr>\n");
        sb.append("  </table>\n");
        sb.append("</td></tr>\n");

        // ── HERO ─────────────────────────────────────────────────────────
        sb.append("<tr><td style=\"padding:28px 24px 20px 24px;\">\n");
        if (eyebrow != null && !eyebrow.isEmpty()) {
            sb.append("  <p style=\"margin:0 0 6px 0;font-size:11px;font-weight:700;letter-spacing:1px;")
              .append("text-transform:uppercase;color:").append(MUTED).append(";font-family:")
              .append(FONT_BODY).append(";\">").append(esc(eyebrow)).append("</p>\n");
        }
        sb.append("  <h1 class=\"email-ink\" style=\"margin:0 0 6px 0;font-size:22px;font-weight:700;")
          .append("line-height:1.3;color:").append(INK).append(";font-family:")
          .append(FONT_SERIF).append(";\">").append(esc(heroTitle)).append("</h1>\n");
        if (heroSub != null && !heroSub.isEmpty()) {
            sb.append("  <p class=\"email-muted\" style=\"margin:0;font-size:14px;color:").append(MUTED)
              .append(";line-height:1.5;\">").append(esc(heroSub)).append("</p>\n");
        }
        sb.append("</td></tr>\n");

        // ── KPI TILES ────────────────────────────────────────────────────
        if (kpiHtml != null && !kpiHtml.isEmpty()) {
            sb.append("<tr><td style=\"padding:0 24px 20px 24px;\">\n");
            sb.append(kpiHtml);
            sb.append("\n</td></tr>\n");
        }

        // ── BODY ─────────────────────────────────────────────────────────
        if (bodyHtml != null && !bodyHtml.isEmpty()) {
            sb.append("<tr><td class=\"email-ink\" style=\"padding:0 24px 20px 24px;font-size:14px;")
              .append("line-height:1.6;color:").append(INK).append(";\">\n");
            sb.append(bodyHtml);
            sb.append("\n</td></tr>\n");
        }

        // ── ATTACHMENT strip ─────────────────────────────────────────────
        if (attachHtml != null && !attachHtml.isEmpty()) {
            sb.append("<tr><td style=\"padding:0 24px 16px 24px;\">\n");
            sb.append(attachHtml);
            sb.append("\n</td></tr>\n");
        }

        // ── CTA button ──────────────────────────────────────────────────
        if (ctaText != null && !ctaText.isEmpty()) {
            sb.append("<tr><td style=\"padding:4px 24px 8px 24px;\">\n");
            sb.append("  <table cellpadding=\"0\" cellspacing=\"0\" role=\"presentation\">\n");
            sb.append("  <tr><td style=\"border-radius:6px;background:").append(INK).append(";\">\n");
            sb.append("    <a href=\"").append(esc(ctaHref != null ? ctaHref : "#"))
              .append("\" target=\"_blank\" style=\"display:inline-block;padding:10px 24px;font-size:14px;")
              .append("font-weight:600;color:").append(CARD).append(";font-family:").append(FONT_BODY)
              .append(";text-decoration:none;border-radius:6px;\">").append(esc(ctaText)).append("</a>\n");
            sb.append("  </td></tr>\n");
            sb.append("  </table>\n");
            if (ctaHint != null && !ctaHint.isEmpty()) {
                sb.append("  <p class=\"email-muted\" style=\"margin:8px 0 0 0;font-size:12px;color:")
                  .append(MUTED).append(";\">").append(esc(ctaHint)).append("</p>\n");
            }
            sb.append("</td></tr>\n");
        }

        // ── META STRIP ──────────────────────────────────────────────────
        sb.append("<tr><td style=\"padding:16px 24px;border-top:1px solid ").append(HAIRLINE).append(";\">\n");
        if (metaStripHtml != null && !metaStripHtml.isEmpty()) {
            sb.append(metaStripHtml);
        } else {
            String reqId = generateRequestId("PT");
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            sb.append("  <table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" role=\"presentation\">\n");
            sb.append("  <tr>\n");
            sb.append("    <td class=\"email-muted\" style=\"font-size:11px;color:").append(MUTED)
              .append(";font-family:").append(FONT_MONO).append(";\">\n");
            sb.append("      ").append(reqId).append(" &middot; ").append(ts).append("\n");
            sb.append("    </td>\n");
            sb.append("  </tr>\n");
            sb.append("  </table>\n");
        }
        sb.append("</td></tr>\n");

        // ── FOOTER ──────────────────────────────────────────────────────
        sb.append("<tr><td style=\"padding:20px 24px 24px 24px;background:").append(PAPER)
          .append(";border-top:1px solid ").append(HAIRLINE).append(";text-align:center;\">\n");
        // SanDisk wordmark pill
        sb.append("  <table cellpadding=\"0\" cellspacing=\"0\" role=\"presentation\" align=\"center\">\n");
        sb.append("  <tr><td class=\"footer-pill\" style=\"border:1px solid #ececec;border-radius:20px;")
          .append("padding:6px 12px;font-size:12px;font-weight:700;color:").append(MUTED)
          .append(";font-family:").append(FONT_BODY).append(";text-transform:lowercase;\">sandisk</td></tr>\n");
        sb.append("  </table>\n");
        // version tag
        sb.append("  <p class=\"email-muted\" style=\"margin:8px 0 4px 0;font-size:11px;color:")
          .append(MUTED).append(";font-family:").append(FONT_BODY).append(";\">PLM Toolkit &middot; v1.0.1</p>\n");
        // delivery line
        String foot = (footerLine != null && !footerLine.isEmpty()) ? footerLine
                : "This is an automated notification from PLM Toolkit. Please do not reply to this email.";
        sb.append("  <p class=\"email-muted\" style=\"margin:0;font-size:11px;color:").append(MUTED)
          .append(";line-height:1.5;\">").append(esc(foot)).append("</p>\n");
        sb.append("</td></tr>\n");

        // close card + outer wrapper
        sb.append("</table>\n");
        sb.append("</td></tr>\n");
        sb.append("</table>\n");
        sb.append("</body>\n");
        sb.append("</html>\n");

        return sb.toString();
    }

    // =====================================================================
    //  2. kpiTile — single KPI tile
    // =====================================================================
    /**
     * Returns one KPI tile HTML. Use 3 tiles in a row via kpiRow().
     *
     * @param label short label text
     * @param value large display value
     * @param trend trend indicator text (e.g. "+12%", "stable"), null to omit
     */
    public String kpiTile(String label, String value, String trend) {
        StringBuilder sb = new StringBuilder();
        sb.append("<td class=\"kpi-tile\" style=\"width:33%;padding:12px 14px;background:")
          .append(PAPER).append(";border:1px solid ").append(HAIRLINE)
          .append(";border-radius:6px;vertical-align:top;\">\n");
        sb.append("  <p class=\"email-muted\" style=\"margin:0 0 4px 0;font-size:11px;font-weight:600;")
          .append("text-transform:uppercase;letter-spacing:0.5px;color:").append(MUTED)
          .append(";font-family:").append(FONT_BODY).append(";\">").append(esc(label)).append("</p>\n");
        sb.append("  <p class=\"email-ink\" style=\"margin:0;font-size:22px;font-weight:700;color:")
          .append(INK).append(";font-family:").append(FONT_BODY).append(";line-height:1.2;\">")
          .append(esc(value)).append("</p>\n");
        if (trend != null && !trend.isEmpty()) {
            sb.append("  <p class=\"email-muted\" style=\"margin:4px 0 0 0;font-size:11px;color:")
              .append(MUTED).append(";\">").append(esc(trend)).append("</p>\n");
        }
        sb.append("</td>\n");
        return sb.toString();
    }

    // =====================================================================
    //  3. kpiRow — wrap tiles in a row
    // =====================================================================
    /**
     * Wraps KPI tiles in a table row container.
     *
     * @param tiles output of kpiTile() calls
     */
    public String kpiRow(String... tiles) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" role=\"presentation\" ")
          .append("style=\"border-collapse:separate;border-spacing:8px 0;\">\n");
        sb.append("<tr>\n");
        for (String tile : tiles) {
            sb.append(tile);
        }
        sb.append("</tr>\n");
        sb.append("</table>\n");
        return sb.toString();
    }

    // =====================================================================
    //  4. callout — colored callout block
    // =====================================================================
    /**
     * Returns a callout block with an optional left rail color.
     *
     * @param label   bold label text
     * @param content body text (may contain HTML)
     * @param variant null = default paper, "success", "warn", "bad"
     */
    public String callout(String label, String content, String variant) {
        String bg;
        String rail;
        String cssClass;

        if ("success".equals(variant)) {
            bg = "#eef6f1";
            rail = SUCCESS;
            cssClass = "callout-success";
        } else if ("warn".equals(variant)) {
            bg = "#faf3e5";
            rail = WARN;
            cssClass = "callout-warn";
        } else if ("bad".equals(variant)) {
            bg = "#faeae9";
            rail = RED;
            cssClass = "callout-bad";
        } else {
            bg = "#f5f2e9";
            rail = null;
            cssClass = "callout-default";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" role=\"presentation\" ")
          .append("style=\"margin:12px 0;\">\n");
        sb.append("<tr>\n");

        if (rail != null) {
            sb.append("  <td style=\"width:4px;background:").append(rail)
              .append(";border-radius:4px 0 0 4px;\"></td>\n");
        }

        sb.append("  <td class=\"").append(cssClass).append("\" style=\"padding:12px 16px;background:")
          .append(bg).append(";border-radius:").append(rail != null ? "0 6px 6px 0" : "6px")
          .append(";\">\n");

        if (label != null && !label.isEmpty()) {
            sb.append("    <p class=\"email-ink\" style=\"margin:0 0 4px 0;font-size:13px;font-weight:700;color:")
              .append(INK).append(";\">").append(esc(label)).append("</p>\n");
        }
        sb.append("    <p class=\"email-ink\" style=\"margin:0;font-size:13px;line-height:1.5;color:")
          .append(INK).append(";\">").append(content != null ? content : "").append("</p>\n");
        sb.append("  </td>\n");
        sb.append("</tr>\n");
        sb.append("</table>\n");
        return sb.toString();
    }

    // =====================================================================
    //  5. kvRow — single key-value row
    // =====================================================================
    /**
     * Returns a single key-value row for use inside kvBlock().
     */
    public String kvRow(String key, String value) {
        StringBuilder sb = new StringBuilder();
        sb.append("<tr>\n");
        sb.append("  <td class=\"email-muted\" style=\"padding:6px 12px 6px 0;font-size:13px;font-weight:600;")
          .append("color:").append(MUTED).append(";white-space:nowrap;vertical-align:top;border-bottom:1px solid ")
          .append(HAIRLINE).append(";\">").append(esc(key)).append("</td>\n");
        sb.append("  <td class=\"email-ink\" style=\"padding:6px 0;font-size:13px;color:").append(INK)
          .append(";border-bottom:1px solid ").append(HAIRLINE).append(";word-break:break-word;\">")
          .append(esc(value)).append("</td>\n");
        sb.append("</tr>\n");
        return sb.toString();
    }

    // =====================================================================
    //  6. kvBlock — wrap kvRows in a grid
    // =====================================================================
    /**
     * Wraps kvRow() outputs in a table container.
     */
    public String kvBlock(String... rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" role=\"presentation\" ")
          .append("style=\"margin:12px 0;\">\n");
        for (String row : rows) {
            sb.append(row);
        }
        sb.append("</table>\n");
        return sb.toString();
    }

    // =====================================================================
    //  7. previewTable — inline data table (up to 5 rows)
    // =====================================================================
    /**
     * Renders an inline preview table with up to 5 data rows.
     *
     * @param headers  column header labels
     * @param rows     data rows (each row is an array of cell values)
     * @param moreText optional "and N more..." text below the table, null to omit
     */
    public String previewTable(String[] headers, String[][] rows, String moreText) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" role=\"presentation\" ")
          .append("style=\"margin:12px 0;border:1px solid ").append(HAIRLINE)
          .append(";border-radius:6px;border-collapse:separate;overflow:hidden;\">\n");

        // header row
        sb.append("<tr>\n");
        for (int i = 0; i < headers.length; i++) {
            String radius = "";
            if (i == 0) radius = "border-radius:6px 0 0 0;";
            if (i == headers.length - 1) radius = "border-radius:0 6px 0 0;";
            if (headers.length == 1) radius = "border-radius:6px 6px 0 0;";

            sb.append("  <th class=\"preview-th\" style=\"padding:8px 12px;font-size:11px;font-weight:700;")
              .append("text-transform:uppercase;letter-spacing:0.5px;text-align:left;color:")
              .append(CARD).append(";background:").append(INK).append(";font-family:")
              .append(FONT_BODY).append(";").append(radius).append("\">")
              .append(esc(headers[i])).append("</th>\n");
        }
        sb.append("</tr>\n");

        // data rows (cap at 5)
        int limit = Math.min(rows.length, 5);
        for (int r = 0; r < limit; r++) {
            String rowBg = (r % 2 == 1) ? PAPER : CARD;
            String altClass = (r % 2 == 1) ? " preview-tr-alt" : "";
            sb.append("<tr class=\"").append(altClass.trim()).append("\">\n");
            for (int c = 0; c < rows[r].length; c++) {
                sb.append("  <td class=\"preview-td\" style=\"padding:8px 12px;font-size:13px;color:")
                  .append(INK).append(";background:").append(rowBg)
                  .append(";border-top:1px solid ").append(HAIRLINE)
                  .append(";font-family:").append(FONT_BODY).append(";\">")
                  .append(esc(rows[r][c])).append("</td>\n");
            }
            sb.append("</tr>\n");
        }

        sb.append("</table>\n");

        if (moreText != null && !moreText.isEmpty()) {
            sb.append("<p class=\"email-muted\" style=\"margin:6px 0 0 0;font-size:12px;color:")
              .append(MUTED).append(";font-style:italic;\">").append(esc(moreText)).append("</p>\n");
        }

        return sb.toString();
    }

    // =====================================================================
    //  8. attachmentStrip — XLSX attachment indicator
    // =====================================================================
    /**
     * Renders an attachment indicator strip.
     *
     * @param filename file name to display
     * @param sizeInfo size info string (e.g. "245 KB")
     */
    public String attachmentStrip(String filename, String sizeInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" role=\"presentation\" ")
          .append("style=\"background:").append(PAPER).append(";border:1px solid ").append(HAIRLINE)
          .append(";border-radius:6px;\">\n");
        sb.append("<tr>\n");
        // icon cell
        sb.append("  <td style=\"width:36px;padding:10px 0 10px 12px;vertical-align:middle;\">\n");
        sb.append("    <table cellpadding=\"0\" cellspacing=\"0\" role=\"presentation\">\n");
        sb.append("    <tr><td style=\"width:28px;height:28px;background:").append(SUCCESS)
          .append(";border-radius:4px;text-align:center;line-height:28px;font-size:11px;")
          .append("font-weight:700;color:").append(CARD).append(";font-family:").append(FONT_MONO)
          .append(";\">XLS</td></tr>\n");
        sb.append("    </table>\n");
        sb.append("  </td>\n");
        // filename + size
        sb.append("  <td style=\"padding:10px 12px;vertical-align:middle;\">\n");
        sb.append("    <p class=\"email-ink\" style=\"margin:0;font-size:13px;font-weight:600;color:")
          .append(INK).append(";font-family:").append(FONT_MONO).append(";\">")
          .append(esc(filename)).append("</p>\n");
        if (sizeInfo != null && !sizeInfo.isEmpty()) {
            sb.append("    <p class=\"email-muted\" style=\"margin:2px 0 0 0;font-size:11px;color:")
              .append(MUTED).append(";\">").append(esc(sizeInfo)).append("</p>\n");
        }
        sb.append("  </td>\n");
        // attachment label
        sb.append("  <td style=\"padding:10px 12px 10px 0;text-align:right;vertical-align:middle;\">\n");
        sb.append("    <span class=\"email-muted\" style=\"font-size:11px;font-weight:600;")
          .append("text-transform:uppercase;letter-spacing:0.5px;color:").append(MUTED)
          .append(";\">Attached</span>\n");
        sb.append("  </td>\n");
        sb.append("</tr>\n");
        sb.append("</table>\n");
        return sb.toString();
    }

    // =====================================================================
    //  9. generateRequestId — prefix + 4 hex chars
    // =====================================================================
    /**
     * Generates a request ID in the form prefix + "-" + 4 uppercase hex characters.
     *
     * @param prefix short prefix (e.g. "FC", "PT")
     * @return e.g. "FC-1A7C"
     */
    public String generateRequestId(String prefix) {
        int hex = RANDOM.nextInt(0xFFFF + 1);
        return prefix + "-" + String.format("%04X", hex);
    }

    // =====================================================================
    // 10. preheader — hidden preview text
    // =====================================================================
    /**
     * Returns a hidden div with preview text for email clients.
     */
    public String preheader(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // Hidden preview text + zero-width spaces to prevent email clients from pulling body text
        return "<div style=\"display:none;font-size:1px;color:" + PAPER
                + ";line-height:1px;max-height:0;overflow:hidden;\">"
                + esc(text)
                + "&#8199;&#65279;&#847; &zwnj; &nbsp;"
                + "</div>\n";
    }

    // =====================================================================
    //  Private helpers
    // =====================================================================

    /**
     * HTML-escapes a string for safe embedding in HTML attributes and text content.
     */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
