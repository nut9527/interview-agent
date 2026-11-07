package cn.edu.whu.zhizhida.resume;

import cn.edu.whu.zhizhida.common.StructuredOutput;
import cn.edu.whu.zhizhida.resume.api.ResumeDetails;
import cn.edu.whu.zhizhida.resume.api.ResumeProfile.ResumeProfileRequest;
import tools.jackson.databind.JsonNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 模型只负责从文档摘录和分类；模板、用户指定岗位、校验及缺失项由应用决定。 */
final class ResumeAiImportOutput {
    private static final Pattern MONTH = Pattern.compile("(?<![0-9])((?:19|20)[0-9]{2})\\h*[年./-]\\h*(0?[1-9]|1[0-2])月?(?![0-9])");
    private static final Pattern SPACING = Pattern.compile("[\\s\\p{Z}\\u00ad\\u200b]+");
    // 数字中的逗号和冒号、英文句点、斜杠、加号及井号具有实际含义。
    private static final Pattern LAYOUT = Pattern.compile("[|、。•▪▣]|(?<![0-9])[,;:]|[,;:](?![0-9])");
    private static final Pattern PLACEHOLDER = Pattern.compile("(?i)x{2,}|[_＊*＿—-]{2,}|待补充(?:姓名|目标岗位)?|未提供|未知|不详|无|n/a");
    private final String original;
    private final String source;
    private final String sourceWithoutLayout;

    private ResumeAiImportOutput(String original) {
        this.original = Normalizer.normalize(original, Normalizer.Form.NFKC);
        source = comparable(this.original);
        sourceWithoutLayout = LAYOUT.matcher(source).replaceAll("");
    }

    static ResumeProfileRequest parse(JsonNode payload, String source, ResumeProfileRequest local, String targetRoleOverride) {
        if (payload == null || !payload.isObject()) throw StructuredOutput.invalid("$");
        return new ResumeAiImportOutput(source).read(payload, local, targetRoleOverride);
    }

    private ResumeProfileRequest read(JsonNode root, ResumeProfileRequest local, String override) {
        String owner = excerpt(root, "ownerName", 80);
        String role = excerpt(root, "targetRole", 120);
        String summary = excerpt(root, "summary", 4000);
        List<String> skills = excerpts(root, "skills", 30, 80);
        var educationText = new LinkedHashSet<>(excerpts(root, "educationExperiences", 100, 3000));
        List<String> projectText = excerpts(root, "projectExperiences", 100, 3000);
        List<String> internships = excerpts(root, "internshipExperiences", 100, 3000);
        List<String> certificates = excerpts(root, "certificates", 30, 80);
        JsonNode detail = object(root, "details");
        List<ResumeDetails.Education> education = new ArrayList<>();
        for (JsonNode entry : objects(detail, "education", 10)) {
            String school = excerpt(entry, "school", 160);
            String major = excerpt(entry, "major", 160);
            String degree = excerpt(entry, "degree", 80);
            String start = excerpt(entry, "startDate", 120);
            String end = excerpt(entry, "endDate", 120);
            var row = new ResumeDetails.Education(school, major, degree, start, end);
            if (school != null && major != null && degree != null && validMonth(start)
                    && (validMonth(end) || "至今".equals(end)) && row.isPeriodValid()) {
                education.add(row);
            } else {
                // 旧文本栏可以完整保留缺少入学年月等必填项的履历。
                String raw = String.join(" ", Stream.of(school, major, degree, start, end).filter(Objects::nonNull).toList());
                if (!raw.isEmpty()) educationText.add(raw);
            }
        }
        List<ResumeDetails.Project> projects = new ArrayList<>();
        for (JsonNode entry : objects(detail, "projects", 20)) {
            String name = excerpt(entry, "name", 120);
            if (name == null) throw StructuredOutput.invalid("name");
            projects.add(new ResumeDetails.Project(name, excerpt(entry, "role", 120), excerpt(entry, "period", 120),
                    excerpt(entry, "background", 2000), excerpts(entry, "contributions", 30, 3000),
                    excerpts(entry, "techStack", 30, 80)));
        }

        String graduation = excerpt(detail, "graduationDate", 120);
        String availability = excerpt(detail, "availability", 200);
        ResumeDetails.Contact contact = readContact(object(detail, "contact"));
        boolean body = summary != null || !skills.isEmpty() || !educationText.isEmpty() || !education.isEmpty()
                || !projectText.isEmpty() || !projects.isEmpty() || !internships.isEmpty() || !certificates.isEmpty();
        if (!body && ((owner == null && role == null && contact == null && availability == null && graduation == null)
                || hasLocalSections(local))) throw StructuredOutput.invalid("$", StructuredOutput.RejectionReason.EMPTY_VALUE);

        ResumeDetails localDetails = local.details() == null ? ResumeDetails.empty() : local.details();
        var details = new ResumeDetails(first(graduation, localDetails.graduationDate()), availability, projects,
                mergeContact(contact, localDetails.contact()), education);
        String target = override == null || override.isBlank() ? first(role, "待补充目标岗位") : override.strip();
        return new ResumeProfileRequest(first(owner, "待补充姓名"), target.substring(0, Math.min(120, target.length())),
                summary, skills, List.copyOf(educationText), projectText, internships, certificates, "classic", null, details);
    }

    private ResumeDetails.Contact readContact(JsonNode node) {
        if (node == null) return null;
        var checked = new ResumeImportContact();
        for (String key : List.of("phone", "email", "location")) {
            int limit = switch (key) { case "phone" -> 40; case "email" -> 254; default -> 120; };
            String value = excerpt(node, key, limit);
            if (value != null && (Normalizer.normalize(value, Normalizer.Form.NFKC).matches("(?s).*[|;\\t].*")
                    || !checked.consume(key + ":" + value))) throw StructuredOutput.invalid(key);
        }
        List<ResumeDetails.Link> links = new ArrayList<>();
        for (JsonNode entry : objects(node, "links", 8)) {
            String url = excerpt(entry, "url", 1000);
            if (url == null || !url.matches("(?i)https?://[^\\s/]+(?:/[^\\s]*)?")) throw StructuredOutput.invalid("url");
            String label = optional(entry, "label", 80);
            links.add(new ResumeDetails.Link(label != null && source.contains(comparable(label)) ? label : "个人链接", url));
        }
        var values = checked.result();
        if (values == null && links.isEmpty()) return null;
        return values == null ? new ResumeDetails.Contact(null, null, null, links)
                : new ResumeDetails.Contact(values.phone(), values.email(), values.location(), links);
    }

    private static ResumeDetails.Contact mergeContact(ResumeDetails.Contact model, ResumeDetails.Contact local) {
        if (model == null) return local;
        if (local == null) return model;
        return new ResumeDetails.Contact(first(model.phone(), local.phone()), first(model.email(), local.email()),
                first(model.location(), local.location()), model.links().isEmpty() ? local.links() : model.links());
    }

    private static boolean hasLocalSections(ResumeProfileRequest local) {
        return Stream.of(local.educationExperiences(), local.projectExperiences(), local.internshipExperiences(),
                        local.skills(), local.certificates()).anyMatch(list -> list != null && !list.isEmpty())
                || local.details() != null && (!local.details().projects().isEmpty() || !local.details().education().isEmpty());
    }

    private static boolean validMonth(String value) {
        return value != null && value.matches("[0-9]{4}-(?:0[1-9]|1[0-2])");
    }

    private static String first(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String comparable(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String dates = MONTH.matcher(normalized.replace('–', '-').replace('—', '-')).replaceAll(match ->
                match.group(1) + "-" + (match.group(2).length() == 1 ? "0" : "") + match.group(2));
        return SPACING.matcher(dates).replaceAll("");
    }

    private static JsonNode object(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        JsonNode value = node.get(field);
        if (!value.isObject()) throw StructuredOutput.invalid(field);
        return value;
    }

    private static List<JsonNode> objects(JsonNode node, String field, int limit) {
        if (node == null || !node.hasNonNull(field)) return List.of();
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode value : StructuredOutput.array(node, field, limit)) {
            if (!value.isObject()) throw StructuredOutput.invalid(field);
            rows.add(value);
        }
        return rows;
    }

    private static String optional(JsonNode node, String field, int limit) {
        if (node == null || !node.hasNonNull(field)) return null;
        JsonNode value = node.get(field);
        if (value.isTextual() && value.textValue().isBlank()) return null;
        String text = StructuredOutput.text(node, field, limit);
        return PLACEHOLDER.matcher(text).matches() ? null : text;
    }

    private String excerpt(JsonNode node, String field, int limit) {
        String value = optional(node, field, limit);
        if (value != null) verifyExcerpt(field, value);
        return value;
    }

    private List<String> excerpts(JsonNode node, String field, int count, int limit) {
        if (node == null || !node.hasNonNull(field)) return List.of();
        var values = new LinkedHashSet<>(StructuredOutput.texts(node, field, count, limit));
        for (String value : values) verifyExcerpt(field, value);
        return List.copyOf(values);
    }

    private void verifyExcerpt(String field, String value) {
        String expected = comparable(value);
        boolean present = switch (field) {
            // 地址与大小写敏感路径直接核对原文，避免宽松标点匹配改变其含义。
            case "url" -> Pattern.compile(Pattern.quote(value) + "(?=$|[\\s\\p{Z}<>\\p{IsHan}、。|]|[.,;)\\]](?:\\s|\\p{IsHan}|$))")
                    .matcher(original).find();
            case "email" -> Pattern.compile("(?<![\\w.+%'-])" + Pattern.quote(value) + "(?![\\w@-]|\\.[\\w])")
                    .matcher(original).find();
            case "techStack", "skills" -> matchesSkill(value, expected);
            case "phone" -> Pattern.compile("(?<![0-9])" + Pattern.quote(expected) + "(?![0-9])").matcher(source).find();
            case "ownerName", "location", "startDate", "endDate", "graduationDate" -> source.contains(expected);
            default -> matchesText(expected);
        };
        if (expected.isEmpty() || !present) throw StructuredOutput.invalid(field, StructuredOutput.RejectionReason.SEMANTIC_MISMATCH);
    }

    private boolean matchesSkill(String value, String expected) {
        if (!value.matches("[A-Za-z0-9+#./_-]+(?:\\h+[A-Za-z0-9+#./_-]+)*")) {
            return value.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                    ? matchesText(expected) : source.contains(expected);
        }
        String[] words = value.strip().split("\\h+");
        String term = String.join("[\\s\\p{Z}]*", Stream.of(words).map(Pattern::quote).toList());
        return Pattern.compile("(?<![a-z0-9#+_])" + term + "(?![a-z0-9#+_])", Pattern.CASE_INSENSITIVE)
                .matcher(original).find();
    }

    private boolean matchesText(String expected) {
        String layoutFree = LAYOUT.matcher(expected).replaceAll("").replaceFirst("\\.$", "");
        return source.contains(expected) || !layoutFree.isEmpty() && sourceWithoutLayout.contains(layoutFree);
    }
}
