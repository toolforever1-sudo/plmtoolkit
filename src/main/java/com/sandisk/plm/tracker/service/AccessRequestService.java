package com.sandisk.plm.tracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class AccessRequestService {

    private static final Logger logger = Logger.getLogger(AccessRequestService.class.getName());
    private static final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.access-requests.file:./data/access-requests.json}")
    private String filePath;

    @Value("${ldap.url}")
    private String ldapUrl;

    @Value("${ldap.service.username}")
    private String serviceUsername;

    @Value("${ldap.service.password}")
    private String servicePassword;

    @Value("${ldap.search.base}")
    private String searchBase;

    @Value("${ldap.domain}")
    private String domain;

    @Value("${ldap.required.groups:}")
    private String requiredGroups;

    @Value("${mail.from}")
    private String mailFrom;

    @Value("${mail.smtp.host}")
    private String smtpHost;

    @Value("${mail.smtp.port:25}")
    private int smtpPort;

    @Autowired
    private EmailService emailService;

    @Autowired
    private EmailTemplateService emailTemplate;

    // Pending access requests: username -> request details
    private final ConcurrentHashMap<String, AccessRequest> pendingRequests = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        loadFromDisk();
        logger.info("[ACCESS] Loaded " + pendingRequests.size() + " pending access requests");
    }

    public void submitRequest(String username, String displayName, String email, String justification) throws Exception {
        AccessRequest req = new AccessRequest();
        req.username = username;
        req.displayName = displayName;
        req.email = email;
        req.justification = justification;
        req.requestedAt = System.currentTimeMillis();

        pendingRequests.put(username.toLowerCase(), req);
        saveToDisk();

        logger.info("[ACCESS] New request from " + displayName + " (" + username + ")");

        // Email admin
        sendAdminNotification(req);
    }

    private void sendAdminNotification(AccessRequest req) throws Exception {
        try {
            String missingGroup = requiredGroups.split(",")[0].trim();
            String timestamp = new java.text.SimpleDateFormat("MMM d, yyyy h:mm a").format(new Date(req.requestedAt));
            String firstName = req.displayName.split(",")[0].trim();

            String bodyHtml = emailTemplate.callout("Justification from " + firstName, escHtml(req.justification), null)
                    + emailTemplate.kvBlock(
                        emailTemplate.kvRow("Name", req.displayName),
                        emailTemplate.kvRow("Username", req.username),
                        emailTemplate.kvRow("Email", req.email),
                        emailTemplate.kvRow("Group needed", missingGroup)
                    );

            String body = emailTemplate.wrap(
                    "Access",                                          // section
                    "Request",                                         // tag
                    "Access request \u00b7 " + timestamp,              // eyebrow
                    req.displayName + " wants access.",                // heroTitle
                    "Not yet a member of " + missingGroup + ". Justification below.", // heroSub
                    null,                                              // kpiHtml
                    bodyHtml,                                          // bodyHtml
                    "Add to " + missingGroup,                          // ctaText
                    "https://anywhere.sandisk.com/ad-group-info/" + missingGroup, // ctaHref
                    "Opens anywhere.sandisk.com",                      // ctaHint
                    null,                                              // attachHtml
                    null,                                              // metaStripHtml
                    "Access loop polls AD every 10 minutes"            // footerLine
            );

            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));

            javax.mail.Session session = javax.mail.Session.getInstance(props);
            javax.mail.internet.MimeMessage message = new javax.mail.internet.MimeMessage(session);
            message.setFrom(new javax.mail.internet.InternetAddress(mailFrom));
            message.setRecipient(javax.mail.Message.RecipientType.TO,
                    new javax.mail.internet.InternetAddress("pdl-plm-admin@sandisk.com"));
            message.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag("Access request \u00b7 " + req.displayName + " \u00b7 " + missingGroup));

            message.setContent(body, "text/html; charset=utf-8");
            javax.mail.Transport.send(message);

            logger.info("[ACCESS] Admin notification sent for " + req.username);

        } catch (Exception e) {
            logger.log(Level.WARNING, "[ACCESS] Failed to send admin notification: " + e.getMessage(), e);
            throw e;
        }
    }

    // =========================================================================
    // Polling job — every 10 minutes, check if pending users are now in the group
    // =========================================================================

    @Scheduled(fixedDelay = 600000)
    public void checkPendingRequests() {
        if (pendingRequests.isEmpty()) return;

        String groupName = "";
        if (requiredGroups != null && !requiredGroups.trim().isEmpty()) {
            groupName = requiredGroups.split(",")[0].trim();
        }
        if (groupName.isEmpty()) return;

        logger.info("[ACCESS] Checking " + pendingRequests.size() + " pending requests against group " + groupName);

        List<String> granted = new ArrayList<>();

        for (Map.Entry<String, AccessRequest> entry : pendingRequests.entrySet()) {
            String username = entry.getKey();
            try {
                if (isUserInGroup(username, groupName)) {
                    granted.add(username);
                }
            } catch (Exception e) {
                logger.warning("[ACCESS] Failed to check group membership for " + username + ": " + e.getMessage());
            }
        }

        for (String username : granted) {
            AccessRequest req = pendingRequests.remove(username);
            if (req != null) {
                logger.info("[ACCESS] Access granted for " + req.displayName + " (" + username + ")");
                sendGrantedNotification(req);
            }
        }

        if (!granted.isEmpty()) {
            saveToDisk();
        }
    }

    private boolean isUserInGroup(String username, String groupName) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, ldapUrl);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, serviceUsername + "@" + domain);
        env.put(Context.SECURITY_CREDENTIALS, servicePassword);
        env.put("java.naming.ldap.factory.socket", "com.sandisk.plm.tracker.service.TrustAllSSLSocketFactory");

        DirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);
            SearchControls controls = new SearchControls();
            controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            controls.setReturningAttributes(new String[]{"memberOf"});

            String filter = "(sAMAccountName=" + username.replace("\\", "\\5c")
                    .replace("*", "\\2a").replace("(", "\\28").replace(")", "\\29") + ")";
            NamingEnumeration<SearchResult> results = ctx.search(searchBase, filter, controls);

            if (!results.hasMore()) return false;

            SearchResult sr = results.next();
            Attribute memberOf = sr.getAttributes().get("memberOf");
            if (memberOf == null) return false;

            for (int i = 0; i < memberOf.size(); i++) {
                String groupDn = (String) memberOf.get(i);
                if (groupDn.toUpperCase().startsWith("CN=")) {
                    String cn = groupDn.substring(3);
                    int comma = cn.indexOf(',');
                    if (comma > 0) cn = cn.substring(0, comma);
                    if (cn.equalsIgnoreCase(groupName)) return true;
                }
            }
            return false;

        } catch (NamingException e) {
            logger.warning("[ACCESS] LDAP error checking " + username + ": " + e.getMessage());
            return false;
        } finally {
            if (ctx != null) {
                try { ctx.close(); } catch (NamingException ignored) {}
            }
        }
    }

    private void sendGrantedNotification(AccessRequest req) {
        try {
            String firstName = req.displayName.split(",")[0].trim();
            String appUrl = "http://uls-ed-aglidss.corp.sandisk.com:8090/";

            String tips = "1. Run a Field Change query. Open the <strong>Items</strong> tab, stay on the <em>Field Changes</em> sub-tab (default), type a part number in the filter.<br>"
                    + "2. Explode a BOM. <strong>BOM</strong> tab &rarr; <em>Explorer</em> sub-tab, paste an assembly number, click Explode.<br>"
                    + "3. Schedule a morning digest. From any query, click the clock icon to set up a recurring email.";

            String bodyHtml = emailTemplate.callout("3 things to try first", tips, "success")
                    + "<p style=\"margin:12px 0 0 0;font-size:13px;line-height:1.5;\">You have read access to all "
                    + "PLM Toolkit views. If you need elevated permissions, contact a PLM admin.</p>";

            String body = emailTemplate.wrap(
                    "Welcome",                                         // section
                    "Granted",                                         // tag
                    null,                                              // eyebrow
                    "You\u2019re in, " + firstName + ".",              // heroTitle
                    "Your access to PLM Toolkit has been approved.",    // heroSub
                    null,                                              // kpiHtml
                    bodyHtml,                                          // bodyHtml
                    "Open PLM Toolkit",                                // ctaText
                    appUrl,                                            // ctaHref
                    "Single sign-on \u00b7 no separate password",      // ctaHint
                    null,                                              // attachHtml
                    null,                                              // metaStripHtml
                    null                                               // footerLine
            );

            Properties props = new Properties();
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPort));

            javax.mail.Session session = javax.mail.Session.getInstance(props);
            javax.mail.internet.MimeMessage message = new javax.mail.internet.MimeMessage(session);
            message.setFrom(new javax.mail.internet.InternetAddress(mailFrom));
            message.setRecipient(javax.mail.Message.RecipientType.TO,
                    new javax.mail.internet.InternetAddress(req.email));
            message.setSubject(com.sandisk.plm.tracker.service.EmailEnvTag.tag("You\u2019re in \u2014 PLM Toolkit access granted"));

            message.setContent(body, "text/html; charset=utf-8");
            javax.mail.Transport.send(message);

            logger.info("[ACCESS] Granted notification sent to " + req.email);

        } catch (Exception e) {
            logger.log(Level.WARNING, "[ACCESS] Failed to send granted notification to " + req.email, e);
        }
    }

    // =========================================================================
    // File I/O
    // =========================================================================

    private void loadFromDisk() {
        File f = new File(filePath);
        if (!f.exists()) return;
        try {
            Map<String, AccessRequest> data = mapper.readValue(f,
                    new TypeReference<Map<String, AccessRequest>>() {});
            pendingRequests.putAll(data);
        } catch (Exception e) {
            logger.warning("[ACCESS] Failed to load requests: " + e.getMessage());
        }
    }

    private synchronized void saveToDisk() {
        try {
            File f = new File(filePath);
            f.getParentFile().mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(f, pendingRequests);
        } catch (Exception e) {
            logger.warning("[ACCESS] Failed to save requests: " + e.getMessage());
        }
    }

    private String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // =========================================================================
    // Model
    // =========================================================================

    public static class AccessRequest {
        public String username;
        public String displayName;
        public String email;
        public String justification;
        public long requestedAt;

        public AccessRequest() {}
    }
}
